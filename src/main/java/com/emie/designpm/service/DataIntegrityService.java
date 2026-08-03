package com.emie.designpm.service;

import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.FileRecordRepository;
import com.emie.designpm.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只读数据完整性扫描；不修复、不删除任何业务数据。 */
@Service
public class DataIntegrityService {
    private final ProjectRepository projectRepository;
    private final DesignRequirementRepository requirementRepository;
    private final FileRecordRepository fileRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataIntegrityService(ProjectRepository projectRepository,
                                DesignRequirementRepository requirementRepository,
                                FileRecordRepository fileRecordRepository) {
        this.projectRepository = projectRepository;
        this.requirementRepository = requirementRepository;
        this.fileRecordRepository = fileRecordRepository;
    }

    public Map<String, Object> scan() {
        Set<String> knownFiles = new HashSet<>();
        fileRecordRepository.findAll().forEach(record -> knownFiles.add(record.getStoredName()));
        Set<String> referenced = new HashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> invalidJson = new LinkedHashSet<>();
        Map<String, Integer> referenceCounts = new HashMap<>();

        for (Project project : projectRepository.findAll()) {
            scanJson("project#" + project.getId() + ".referenceImagesJson", project.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("project#" + project.getId() + ".attachmentsJson", project.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            for (SubTask task : Optional.ofNullable(project.getTasks()).orElseGet(List::of)) {
                scanJson("sub_task#" + task.getId() + ".referenceImagesJson", task.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
                scanJson("sub_task#" + task.getId() + ".attachmentsJson", task.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            }
        }
        for (DesignRequirement requirement : requirementRepository.findAll()) {
            scanJson("design_requirement#" + requirement.getId() + ".referenceImagesJson", requirement.getReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".attachmentsJson", requirement.getAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".deliveryReferenceImagesJson", requirement.getDeliveryReferenceImagesJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
            scanJson("design_requirement#" + requirement.getId() + ".deliveryAttachmentsJson", requirement.getDeliveryAttachmentsJson(), knownFiles, referenced, missing, invalidJson, referenceCounts);
        }

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
