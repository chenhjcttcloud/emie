package com.emie.designpm.service;

import com.emie.designpm.entity.ActivityLog;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ProjectWorkflowAttempt;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ProjectWorkflowAttemptRepository;
import com.emie.designpm.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 项目级子任务总流程：五阶段推进与三类审核阶段的多轮留痕。 */
@Service
public class ProjectWorkflowService {

    public static final List<String> STAGES =
            List.of("design", "design_review", "three_d_review", "sample_review", "promotion", "bulk");
    private static final List<String> REVIEW_STAGES =
            List.of("design_review", "three_d_review", "sample_review", "promotion");
    private static final Map<String, String> LABELS = Map.of(
            "design", "设计",
            "design_review", "设计送审",
            "three_d_review", "3D送审",
            "sample_review", "打样送审",
            "promotion", "产品宣发",
            "bulk", "大货");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ProjectRepository projects;
    private final ProjectWorkflowAttemptRepository attempts;

    public ProjectWorkflowService(ProjectRepository projects, ProjectWorkflowAttemptRepository attempts) {
        this.projects = projects;
        this.attempts = attempts;
    }

    @Transactional
    public Project completeExecution(Long projectId, String userId, String userName, String role) {
        Project project = loadActive(projectId);
        requirePlanner(project, userId, role);
        String stage = currentStage(project);
        if (!List.of("design", "bulk").contains(stage) || !"current".equals(currentStatus(project))) {
            throw new IllegalArgumentException("当前阶段不能执行完成操作");
        }
        if ("design".equals(stage)) {
            project.setWorkflowStage("design_review");
            project.setWorkflowStatus("current");
            addLog(project, "子任务总流程：设计阶段完成，进入设计送审", userName, role);
        } else {
            project.setWorkflowStatus("completed");
            addLog(project, "子任务总流程：大货阶段完成，全部流程完结", userName, role);
        }
        return projects.save(project);
    }

    @Transactional
    public Map<String, Object> submitReview(Long projectId, String userId, String userName, String role) {
        Project project = loadActive(projectId);
        requirePlanner(project, userId, role);
        String stage = currentStage(project);
        String status = currentStatus(project);
        if (!REVIEW_STAGES.contains(stage) || !List.of("current", "rejected").contains(status)) {
            throw new IllegalArgumentException("当前阶段不能提交送审");
        }

        int attemptNo = Math.toIntExact(attempts.countByProjectIdAndStageKey(projectId, stage) + 1);
        ProjectWorkflowAttempt attempt = new ProjectWorkflowAttempt();
        attempt.setProject(project);
        attempt.setStageKey(stage);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus("pending");
        attempt.setSubmittedBy(userId);
        attempt.setSubmittedByName(userName);
        attempt.setSubmittedAt(LocalDateTime.now());
        attempts.save(attempt);

        project.setWorkflowStatus("under_review");
        addLog(project, "子任务总流程：" + LABELS.get(stage) + "第 " + attemptNo + " 轮已提交", userName, role);
        projects.save(project);
        return build(project);
    }

    @Transactional
    public Map<String, Object> review(Long projectId, String decision, String comment,
                                      String userId, String userName, String role) {
        Project project = loadActive(projectId);
        requireReviewer(project, userId, role);
        String stage = currentStage(project);
        if (!REVIEW_STAGES.contains(stage) || !"under_review".equals(currentStatus(project))) {
            throw new IllegalArgumentException("当前没有待审核的流程阶段");
        }
        if (!List.of("approved", "rejected").contains(decision)) {
            throw new IllegalArgumentException("审核结果无效");
        }
        String safeComment = SecurityUtil.sanitizeText(comment, 1000);
        if ("rejected".equals(decision) && (safeComment == null || safeComment.isBlank())) {
            throw new IllegalArgumentException("驳回时必须填写原因");
        }

        ProjectWorkflowAttempt attempt = attempts
                .findFirstByProjectIdAndStageKeyOrderByAttemptNoDesc(projectId, stage)
                .filter(item -> "pending".equals(item.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("未找到待审核轮次"));
        attempt.setStatus(decision);
        attempt.setReviewerId(userId);
        attempt.setReviewerName(userName);
        attempt.setReviewedAt(LocalDateTime.now());
        attempt.setComment(safeComment);
        attempts.save(attempt);

        if ("rejected".equals(decision)) {
            project.setWorkflowStatus("rejected");
            addLog(project, "子任务总流程：" + LABELS.get(stage) + "第 " + attempt.getAttemptNo()
                    + " 轮未通过", userName, role);
        } else {
            int nextIndex = STAGES.indexOf(stage) + 1;
            project.setWorkflowStage(STAGES.get(nextIndex));
            project.setWorkflowStatus("current");
            addLog(project, "子任务总流程：" + LABELS.get(stage) + "第 " + attempt.getAttemptNo()
                    + " 轮通过，进入" + LABELS.get(project.getWorkflowStage()), userName, role);
        }
        projects.save(project);
        return build(project);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> build(Project project) {
        String stage = currentStage(project);
        String status = currentStatus(project);
        List<Map<String, Object>> history = new ArrayList<>();
        if (project.getId() != null) {
            attempts.findByProjectIdOrderByIdAsc(project.getId()).forEach(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("stageKey", item.getStageKey());
                row.put("stageLabel", LABELS.getOrDefault(item.getStageKey(), item.getStageKey()));
                row.put("attemptNo", item.getAttemptNo());
                row.put("status", item.getStatus());
                row.put("submittedByName", item.getSubmittedByName());
                row.put("submittedAt", item.getSubmittedAt().format(DTF));
                row.put("reviewerName", item.getReviewerName());
                row.put("reviewedAt", item.getReviewedAt() == null ? null : item.getReviewedAt().format(DTF));
                row.put("comment", item.getComment());
                history.add(row);
            });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentStage", stage);
        result.put("status", status);
        result.put("stages", STAGES.stream().map(key -> Map.of("key", key, "label", LABELS.get(key))).toList());
        result.put("attempts", history);
        return result;
    }

    private Project loadActive(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        if (List.of("terminated", "paused", "pending_terminate").contains(project.getStatus())) {
            throw new IllegalArgumentException("项目当前状态不能推进子任务总流程");
        }
        return project;
    }

    private void requirePlanner(Project project, String userId, String role) {
        if (!"planner".equals(role) || userId == null || !userId.equals(project.getPlannerId())) {
            throw new IllegalArgumentException("仅当前项目负责人产品企划可推进流程");
        }
    }

    private void requireReviewer(Project project, String userId, String role) {
        boolean allowed = "channel_custom".equals(project.getType())
                ? "sales".equals(role) && userId != null && userId.equals(project.getSalesId())
                : "admin".equals(role);
        if (!allowed) throw new IllegalArgumentException("当前用户无权审核该流程阶段");
    }

    private String currentStage(Project project) {
        String stage = project.getWorkflowStage();
        if (stage != null && STAGES.contains(stage)) return stage;
        return "completed".equals(project.getStatus()) ? "bulk" : "design";
    }

    private String currentStatus(Project project) {
        String status = project.getWorkflowStatus();
        if ("completed".equals(status)) return "completed";
        if (status != null && List.of("current", "under_review", "rejected").contains(status)) return status;
        return "completed".equals(project.getStatus()) ? "completed" : "current";
    }

    private void addLog(Project project, String action, String userName, String role) {
        project.getLogs().add(new ActivityLog(action, userName, role, project));
    }
}
