package com.emie.designpm.service;

import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只读数据完整性扫描；不修复、不删除任何业务数据。 */
@Service
public class DataIntegrityService {
    private static final int SCAN_BATCH_SIZE = 500;

    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final DesignRequirementRepository requirementRepository;
    private final FileRecordRepository fileRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataIntegrityService(ProjectRepository projectRepository,
                                SubTaskRepository subTaskRepository,
                                DesignRequirementRepository requirementRepository,
                                FileRecordRepository fileRecordRepository) {
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.requirementRepository = requirementRepository;
        this.fileRecordRepository = fileRecordRepository;
    }

    public Map<String, Object> scan() {
        Set<String> knownFiles = new HashSet<>();
        scanBatches(fileRecordRepository::findIntegrityFilesAfter,
                record -> knownFiles.add(record.getStoredName()), FileRecordRepository.IntegrityFileProjection::getId);
        Set<String> referenced = new HashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> invalidJson = new LinkedHashSet<>();
        Map<String, Integer> referenceCounts = new HashMap<>();

        scanBatches(projectRepository::findIntegrityProjectsAfter, project -> {
            scanJson("project#" + project.getId() + ".referenceImagesJson", project.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("project#" + project.getId() + ".attachmentsJson", project.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
        }, ProjectRepository.IntegrityProjectProjection::getId);
        scanBatches(subTaskRepository::findIntegritySubTasksAfter, task -> {
            scanJson("sub_task#" + task.getId() + ".referenceImagesJson", task.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("sub_task#" + task.getId() + ".attachmentsJson", task.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
        }, SubTaskRepository.IntegritySubTaskProjection::getId);
        scanBatches(requirementRepository::findIntegrityRequirementsAfter, requirement -> {
            scanJson("design_requirement#" + requirement.getId() + ".referenceImagesJson", requirement.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".attachmentsJson", requirement.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".deliveryReferenceImagesJson", requirement.getDeliveryReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".deliveryAttachmentsJson", requirement.getDeliveryAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
        }, DesignRequirementRepository.IntegrityRequirementProjection::getId);

        List<String> duplicates = referenceCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .sorted(Map.Entry.comparingByKey())
                .limit(100)
                .map(entry -> entry.getKey() + " (" + entry.getValue() + "次引用)")
                .toList();
        return Map.of(
                "scannedFiles", knownFiles.size(),
                "referencedFiles", referenced.size(),
                "missingFiles", List.copyOf(missing),
                "invalidJson", List.copyOf(invalidJson),
                "duplicateReferences", duplicates,
                "healthy", missing.isEmpty() && invalidJson.isEmpty()
        );
    }

    private <T> void scanBatches(BatchLoader<T> loader, java.util.function.Consumer<T> scanner,
                                 java.util.function.ToLongFunction<T> idReader) {
        long afterId = 0L;
        while (true) {
            List<T> batch = loader.load(afterId, PageRequest.of(0, SCAN_BATCH_SIZE));
            batch.forEach(scanner);
            if (batch.size() < SCAN_BATCH_SIZE) return;
            afterId = idReader.applyAsLong(batch.getLast());
        }
    }

    @FunctionalInterface
    private interface BatchLoader<T> {
        List<T> load(Long afterId, org.springframework.data.domain.Pageable pageable);
    }

    private void scanJson(String source, String json, Set<String> knownFiles, Set<String> referenced,
                           Set<String> missing, Set<String> invalidJson, Map<String, Integer> counts) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return;
        try {
            List<Map<String, Object>> files = objectMapper.readValue(json, new TypeReference<>() {});
            for (Map<String, Object> file : files) {
                Object stored = file.get("storedName");
                if (!(stored instanceof String name) || name.isBlank()) continue;
                referenced.add(name);
                counts.merge(name, 1, Integer::sum);
                if (!knownFiles.contains(name)) missing.add(source + " -> " + name);
            }
        } catch (Exception e) {
            invalidJson.add(source);
        }
    }
}
