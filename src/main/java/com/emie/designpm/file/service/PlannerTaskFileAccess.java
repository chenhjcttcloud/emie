package com.emie.designpm.file.service;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;

/** 企划执行自己承担的子任务时，只放行该任务绑定或明确引用的文件。 */
public final class PlannerTaskFileAccess {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SubTaskRepository tasks;

    public PlannerTaskFileAccess(SubTaskRepository tasks) {
        this.tasks = tasks;
    }

    public boolean canAccessTarget(AuthSession session, String targetType, Long targetId) {
        return isPlanner(session)
                && "sub_task".equals(targetType)
                && targetId != null
                && tasks.existsByIdAndDesignerIdAndAssigneeRole(targetId, session.userId(), "planner");
    }

    public boolean hasReference(AuthSession session, String storedName, String relativePath) {
        if (!isPlanner(session)) return false;
        return tasks.findAssignedPlannerFileReferences(session.userId(), storedName).stream()
                .anyMatch(task -> contains(task.getReferenceImagesJson(), storedName, relativePath)
                        || contains(task.getAttachmentsJson(), storedName, relativePath));
    }

    private boolean isPlanner(AuthSession session) {
        return session != null && "planner".equals(session.role()) && session.userId() != null;
    }

    private boolean contains(String json, String storedName, String relativePath) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode files = MAPPER.readTree(json);
            if (!files.isArray()) return false;
            for (JsonNode file : files) {
                if (storedName.equals(file.path("storedName").asText())) return true;
                try {
                    String path = URI.create(file.path("url").asText()).getPath();
                    if (("/api/files/download/" + storedName).equals(path)
                            || ("/api/files/download/" + relativePath).equals(path)) return true;
                } catch (IllegalArgumentException ignored) {
                    // 旧数组中单个无效地址不影响后续明确的文件引用。
                }
            }
        } catch (Exception ignored) {
            // 无法解析的历史元数据不作为授权依据。
        }
        return false;
    }
}
