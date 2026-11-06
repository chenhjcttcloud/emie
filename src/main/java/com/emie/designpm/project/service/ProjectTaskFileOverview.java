package com.emie.designpm.project.service;

import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.file.repository.FileRecordRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectTaskFileOverview {
    private final FileRecordRepository files;

    public ProjectTaskFileOverview(FileRecordRepository files) {
        this.files = files;
    }

    /** Enrich only task details from an already authorized project. */
    @Transactional(readOnly = true)
    public void addUploads(ProjectDetailDTO detail) {
        List<TaskDetailDTO> tasks = detail.getTasks();
        if (tasks.isEmpty()) return;

        List<Long> taskIds = tasks.stream().map(TaskDetailDTO::getId).toList();
        Map<Long, List<TaskDetailDTO.UploadedFile>> uploadsByTask = new HashMap<>();
        for (var upload : files.findSubTaskUploads(taskIds)) {
            uploadsByTask
                    .computeIfAbsent(upload.getTargetId(), id -> new ArrayList<>())
                    .add(new TaskDetailDTO.UploadedFile(
                            upload.getStoredName(), upload.getOriginalName(), upload.getFileSize()));
        }
        tasks.forEach(task -> task.setUploadedFiles(uploadsByTask.getOrDefault(task.getId(), List.of())));
    }
}
