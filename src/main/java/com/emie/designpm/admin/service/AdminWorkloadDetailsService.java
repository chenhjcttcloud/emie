package com.emie.designpm.admin.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AdminWorkloadDetailsService {
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminWorkloadDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getWorkloadDetails(String userId, String bucket) {
        User user = userRepository.findByUserId(userId).orElse(null);
        if (user == null
                || !Set.of("promotion", "planner", "designer", "supplychain").contains(user.getRole())) {
            return List.of();
        }
        boolean own = "own".equals(bucket);
        List<Map<String, Object>> result = new ArrayList<>();
        String taskSql =
                "SELECT s.id, s.designer_id, s.publisher_id, s.name, s.status, s.workflow_stage, p.id, p.project_code, p.product_name "
                        + "FROM sub_tasks s JOIN projects p ON p.id = s.project_id "
                        + "WHERE s.completed_at IS NULL AND p.status <> 'terminated' "
                        + "AND (s.designer_id = ?1 OR s.publisher_id = ?1) ORDER BY s.updated_at DESC";
        for (Object[] row : (List<Object[]>)
                entityManager.createNativeQuery(taskSql).setParameter(1, userId).getResultList()) {
            String status = row[4] == null ? "" : String.valueOf(row[4]);
            boolean assignee = userId.equals(String.valueOf(row[1]));
            boolean publisher = userId.equals(String.valueOf(row[2]));
            WorkloadStatusBoundary.Bucket taskBucket =
                    assignee ? WorkloadStatusBoundary.forAssignee(status) : WorkloadStatusBoundary.forPublisher(status);
            if (assignee
                    && publisher
                    && WorkloadStatusBoundary.forPublisher(status) == WorkloadStatusBoundary.Bucket.OWN) {
                taskBucket = WorkloadStatusBoundary.Bucket.OWN;
            }
            if (own != (taskBucket == WorkloadStatusBoundary.Bucket.OWN)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "task");
            item.put("id", row[0]);
            item.put("name", row[3] == null ? "未命名任务" : row[3]);
            item.put("projectId", row[6]);
            item.put("project", row[8] == null ? row[7] : row[8]);
            item.put("projectCode", row[7]);
            item.put("stage", stageLabel(row[5]));
            item.put("status", status);
            item.put("statusLabel", taskStatusLabel(status));
            result.add(item);
        }
        if ("planner".equals(user.getRole())) {
            String projectSql = "SELECT id, project_code, product_name, status, workflow_stage FROM projects "
                    + "WHERE planner_id = ?1 AND status NOT IN ('completed','terminated') ORDER BY updated_at DESC";
            for (Object[] row : (List<Object[]>) entityManager
                    .createNativeQuery(projectSql)
                    .setParameter(1, userId)
                    .getResultList()) {
                String status = row[3] == null ? "" : String.valueOf(row[3]);
                boolean projectOwn =
                        WorkloadStatusBoundary.forProjectPlanner(status) == WorkloadStatusBoundary.Bucket.OWN;
                if (own != projectOwn) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "project");
                item.put("id", row[0]);
                item.put("projectId", row[0]);
                item.put("name", row[2] == null ? row[1] : row[2]);
                item.put("projectCode", row[1]);
                item.put("stage", stageLabel(row[4]));
                item.put("status", status);
                item.put("statusLabel", projectStatusLabel(status));
                result.add(item);
            }
        }
        return result;
    }

    private String taskStatusLabel(String status) {
        return switch (status) {
            case "pending" -> "待接单";
            case "accepted" -> "处理中";
            case "rejected" -> "待返工";
            case "delivered" -> "待验收";
            case "submitted_for_review" -> "待审核";
            case "planner_approved", "sales_approved", "admin_approved" -> "待评分";
            default -> status.isBlank() ? "未设置状态" : status;
        };
    }

    private String projectStatusLabel(String status) {
        return switch (status) {
            case "pending_planner" -> "待企划接单";
            case "planner_accepted", "in_progress" -> "处理中";
            case "draft" -> "草稿";
            default -> status.isBlank() ? "未设置状态" : status;
        };
    }

    private String stageLabel(Object rawStage) {
        String stage = rawStage == null ? "" : String.valueOf(rawStage);
        return switch (stage) {
            case "design" -> "设计阶段";
            case "design_review" -> "设计审核";
            case "three_d_review" -> "三维审核";
            case "sample_review" -> "样品审核";
            case "promotion" -> "推广阶段";
            case "bulk" -> "量产阶段";
            default -> stage.isBlank() ? "未设置阶段" : stage;
        };
    }
}
