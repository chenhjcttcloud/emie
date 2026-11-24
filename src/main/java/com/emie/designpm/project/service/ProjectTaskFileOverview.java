package com.emie.designpm.project.service;

import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectTaskFileOverview {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOWNLOAD_PREFIX = "/api/files/download/";
    private final FileRecordRepository files;

    public ProjectTaskFileOverview(FileRecordRepository files) {
        this.files = files;
    }

    /** Enrich file metadata only after the project detail has been authorized. */
    @Transactional(readOnly = true)
    public void addUploads(ProjectDetailDTO detail) {
        addProjectFileTimes(detail);
        List<TaskDetailDTO> tasks = detail.getTasks();
        if (tasks.isEmpty()) return;

        List<Long> taskIds = tasks.stream().map(TaskDetailDTO::getId).toList();
        String plannerId = detail.getPlannerId();
        Map<Long, List<TaskDetailDTO.UploadedFile>> uploadsByTask = new HashMap<>();
        for (var upload : files.findSubTaskUploads(taskIds)) {
            boolean plannerUpload =
                    plannerId != null && !plannerId.isBlank() && plannerId.equals(upload.getOwnerUserId());
            uploadsByTask
                    .computeIfAbsent(upload.getTargetId(), id -> new ArrayList<>())
                    .add(new TaskDetailDTO.UploadedFile(
                            upload.getStoredName(),
                            upload.getOriginalName(),
                            upload.getFileSize(),
                            plannerUpload,
                            upload.getCreatedAt() == null
                                    ? null
                                    : upload.getCreatedAt().toString()));
        }
        tasks.forEach(task -> task.setUploadedFiles(uploadsByTask.getOrDefault(task.getId(), List.of())));
    }

    private void addProjectFileTimes(ProjectDetailDTO detail) {
        detail.setProjectFileUploadTimes(Map.of());
        if (detail.getId() == null || detail.getId() <= 0) return;
        Set<String> references = new HashSet<>();
        collectProjectReferences(detail.getReferenceImagesJson(), references);
        collectProjectReferences(detail.getAttachmentsJson(), references);
        if (references.isEmpty()) return;
        Map<String, String> times = new HashMap<>();
        for (var file : files.findByTargetTypeAndTargetId("project", detail.getId())) {
            if (references.contains(file.getStoredName()) && file.getCreatedAt() != null) {
                times.put(file.getStoredName(), file.getCreatedAt().toString());
            }
        }
        detail.setProjectFileUploadTimes(times);
    }

    private void collectProjectReferences(String json, Set<String> references) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode entries = JSON.readTree(json);
            if (!entries.isArray()) return;
            for (JsonNode entry : entries) {
                String name = projectStoredName(entry);
                if (name != null && !name.isBlank() && !name.contains("/") && !name.contains("\\")) {
                    references.add(name);
                }
            }
        } catch (Exception ignored) {
            // A malformed legacy list must not hide metadata from the other current project list.
        }
    }

    private String projectStoredName(JsonNode file) {
        String storedName = file.path("storedName").asText("").trim();
        if (!storedName.isBlank()) return storedName;
        String url = file.isTextual() ? file.asText() : "";
        for (String key : List.of("url", "fileUrl", "path")) {
            if (url.isBlank()) url = file.path(key).asText("").trim();
        }
        if (!url.startsWith(DOWNLOAD_PREFIX)) return null;
        try {
            return URI.create(url).getPath().substring(DOWNLOAD_PREFIX.length());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
