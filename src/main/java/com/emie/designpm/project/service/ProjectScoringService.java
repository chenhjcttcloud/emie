package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ScoringRecord;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectScoringService {
    private final ScoringRepository scoringRepository;
    private final ProjectAccessService projectAccessService;
    private final ScoringWeightConfig scoringWeights;

    public ProjectScoringService(
            ScoringRepository scoringRepository,
            SystemConfigRepository systemConfigRepository,
            ProjectAccessService projectAccessService) {
        this.scoringRepository = scoringRepository;
        this.projectAccessService = projectAccessService;
        this.scoringWeights = new ScoringWeightConfig(systemConfigRepository);
    }

    /** 工作台首页、导航徽章和评分中心共用的待评分条目数量。 */
    public long countPendingScoresForUser(String role, String userId) {
        if (!List.of("admin", "planner", "sales").contains(role)) return 0L;
        // 工作台徽章必须和评分中心使用同一批聚合条目，避免旧的 SQL 口径
        // 只统计部分任务状态、漏掉设计需求或把评分记录重复计数。
        long projectPending = getPendingScoringTasks(role, userId).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("isPending")))
                .count();
        return projectPending;
    }

    /** 判断子任务是否真正完成（已验收 + 所有评分角色已评分）。 */
    public boolean isTaskFullyCompleted(SubTask task) {
        if (!List.of("completed", "approved").contains(task.getStatus())) {
            return false;
        }
        List<String> requiredRoles = getRequiredScoringRoles(task);
        if (requiredRoles.isEmpty()) {
            return true;
        }
        List<ScoringRecord> records = scoringRepository.findBySubTaskId(task.getId());
        return requiredRoles.stream().allMatch(role -> records.stream()
                .filter(sr -> role.equals(sr.getRole()))
                .anyMatch(this::isScoringRecordCompleted));
    }

    /**
     * 计算项目综合得分：取所有已完成子任务的加权平均分
     * 每个子任务得分 = Σ(角色平均分 × 角色权重) / Σ(权重)
     * 角色平均分 = (审美 + 创新) / 2
     */
    public Double computeProjectScore(Project project) {
        List<SubTask> tasks = project.getTasks();
        if (tasks == null || tasks.isEmpty()) return null;
        Map<String, Double> weights = scoringWeights.weightMap(project.getType());
        Map<Long, List<ScoringRecord>> recordsByTask =
                scoringRepository
                        .findBySubTaskIds(tasks.stream().map(SubTask::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(sr -> sr.getSubTask().getId()));
        double totalScore = 0;
        int scoredCount = 0;
        for (SubTask task : tasks) {
            if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
            List<ScoringRecord> records = recordsByTask.getOrDefault(task.getId(), List.of());
            if (records == null || records.isEmpty()) continue;
            double weightedSum = 0;
            double totalWeight = 0;
            for (ScoringRecord sr : records) {
                Double normalizedScore = toHundredPointScore(sr);
                if (normalizedScore != null) {
                    double weight = weights.getOrDefault(sr.getRole(), 0.25);
                    weightedSum += normalizedScore * weight;
                    totalWeight += weight;
                }
            }
            if (totalWeight > 0) {
                totalScore += (weightedSum / totalWeight);
                scoredCount++;
            }
        }
        return scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null;
    }

    /**
     * 批量计算项目综合得分（消除 N+1 查询）
     * 一次 SQL 拉取所有项目的评分记录，内存中聚合计算
     */
    public Map<Long, Double> computeProjectScoresBatch(List<Project> projects) {
        if (projects == null || projects.isEmpty()) return Collections.emptyMap();
        Map<String, Map<String, Double>> weightsByType = projects.stream()
                .map(Project::getType)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(type -> type, scoringWeights::weightMap));
        List<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toList());
        // 一次 SQL 查全部
        List<ScoringRecord> allRecords = scoringRepository.findByProjectIds(projectIds);
        // JOIN FETCH 子任务后按项目和子任务聚合，避免分页项目逐个懒加载 tasks 产生 N+1 查询。
        Map<Long, Map<Long, List<ScoringRecord>>> recordsByProjectAndTask = new HashMap<>();
        for (ScoringRecord sr : allRecords) {
            if (sr.getSubTask() != null && sr.getSubTask().getProject() != null) {
                Long pid = sr.getSubTask().getProject().getId();
                recordsByProjectAndTask
                        .computeIfAbsent(pid, k -> new HashMap<>())
                        .computeIfAbsent(sr.getSubTask().getId(), k -> new ArrayList<>())
                        .add(sr);
            }
        }
        // 逐项目计算：只遍历已有评分记录的子任务，未产生评分记录的项目分数为 null。
        Map<Long, Double> result = new HashMap<>();
        for (Project p : projects) {
            Map<Long, List<ScoringRecord>> recordsByTask =
                    recordsByProjectAndTask.getOrDefault(p.getId(), Collections.emptyMap());
            double totalScore = 0;
            int scoredCount = 0;
            for (List<ScoringRecord> taskRecords : recordsByTask.values()) {
                SubTask task = taskRecords.get(0).getSubTask();
                if (!"completed".equals(task.getStatus()) && !"approved".equals(task.getStatus())) continue;
                double weightedSum = 0;
                double totalWeight = 0;
                for (ScoringRecord sr : taskRecords) {
                    Double normalizedScore = toHundredPointScore(sr);
                    if (normalizedScore != null) {
                        Map<String, Double> weights =
                                weightsByType.get(task.getProject().getType());
                        if (weights == null) weights = scoringWeights.weightMap("regular");
                        double weight = weights.getOrDefault(sr.getRole(), 0.25);
                        weightedSum += normalizedScore * weight;
                        totalWeight += weight;
                    }
                }
                if (totalWeight > 0) {
                    totalScore += (weightedSum / totalWeight);
                    scoredCount++;
                }
            }
            result.put(p.getId(), scoredCount > 0 ? Math.round(totalScore / scoredCount * 10.0) / 10.0 : null);
        }
        return result;
    }

    // ==================== Pending Scoring (聚合查询) ====================

    /** 获取待评分任务列表（替代前端 N+1 次循环） */
    public List<Map<String, Object>> getPendingScoringTasks(String role, String userId) {
        List<Project> projects = getProjectsByRoleAndUser(role, userId);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Long> taskIds = projects.stream()
                .flatMap(p -> p.getTasks().stream())
                .map(SubTask::getId)
                .toList();
        Map<Long, List<ScoringRecord>> recordsByTask = taskIds.isEmpty()
                ? Map.of()
                : scoringRepository.findBySubTaskIds(taskIds).stream()
                        .collect(Collectors.groupingBy(sr -> sr.getSubTask().getId()));

        for (Project p : projects) {
            for (SubTask t : p.getTasks()) {
                // 驳回任务等待负责人修改并重新交付，不属于评分中心的待办或历史评分结果。
                if ("rejected".equals(t.getStatus())) {
                    continue;
                }
                List<ScoringRecord> records = recordsByTask.getOrDefault(t.getId(), List.of());
                Optional<ScoringRecord> myRecord =
                        records.stream().filter(sr -> role.equals(sr.getRole())).findFirst();
                if (myRecord.isEmpty() || "waiting".equals(myRecord.get().getReviewStatus())) {
                    continue;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", t.getId());
                item.put("taskName", t.getName());
                item.put("taskStatus", t.getStatus());
                item.put("projectId", p.getId());
                item.put("projectType", p.getType());
                item.put(
                        "projectName",
                        p.getProductName() != null && !p.getProductName().isBlank()
                                ? p.getProductName().trim()
                                : p.getProductRequirements());
                item.put("plannerId", p.getPlannerId());
                item.put("plannerName", p.getPlannerName());
                item.put("plannedDate", t.getPlannedDate());
                item.put(
                        "lastActivityAt",
                        records.stream()
                                .map(ScoringRecord::getReviewedAt)
                                .filter(Objects::nonNull)
                                .max(LocalDateTime::compareTo)
                                .orElse(null));
                item.put("designerId", t.getDesignerId());
                item.put("designerName", t.getDesignerName());
                item.put("selfScore", t.getSelfScore());
                item.put("selfAesthetics", t.getSelfAesthetics());
                item.put("selfInnovation", t.getSelfInnovation());
                item.put("isPending", isScoringRecordPending(myRecord.get()));
                item.put(
                        "scoringRecords",
                        records.stream()
                                .map(sr -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("role", sr.getRole());
                                    m.put("scoreType", sr.getScoreType());
                                    m.put("reviewStage", sr.getReviewStage());
                                    m.put("reviewStatus", sr.getReviewStatus());
                                    m.put("reviewerId", sr.getReviewerId());
                                    m.put("reviewerName", sr.getReviewerName());
                                    m.put("reviewedAt", sr.getReviewedAt());
                                    m.put("comment", sr.getComment());
                                    m.put("score", sr.getScore());
                                    m.put("aesthetics", sr.getAesthetics());
                                    m.put("innovation", sr.getInnovation());
                                    m.put("weight", scoringWeights.pct(p.getType(), sr.getRole()) / 100.0);
                                    return m;
                                })
                                .collect(Collectors.toList()));
                result.add(item);
            }
        }
        return result;
    }

    private List<Project> getProjectsByRoleAndUser(String role, String userId) {
        return projectAccessService.findVisibleProjectsLight(role, userId);
    }

    private boolean isProjectPlanner(Project project, Map<String, Object> body) {
        String userId = (String) body.getOrDefault("currentUserId", "");
        return userId != null && !userId.isBlank() && Objects.equals(userId, project.getPlannerId());
    }

    /** 验收和评分必须同时满足角色、项目归属，不能仅由请求体中的 role 决定。 */
    private boolean canReviewTask(Project project, SubTask task, String role, String userId) {
        if (project == null || task == null || userId == null || userId.isBlank()) return false;
        if ("admin".equals(role)) return true;
        if ("planner".equals(role)) {
            return Objects.equals(userId, project.getPlannerId());
        }
        return "sales".equals(role)
                && "channel_custom".equals(project.getType())
                && Objects.equals(userId, project.getSalesId());
    }

    private Integer parseOptionalScore(Object rawScore) {
        if (rawScore == null) {
            return null;
        }
        Integer score;
        if (rawScore instanceof Number number) {
            score = number.intValue();
        } else {
            try {
                score = Integer.parseInt(String.valueOf(rawScore));
            } catch (NumberFormatException e) {
                throw new RuntimeException("评分必须为数字");
            }
        }
        if (score < 1 || score > 100) {
            throw new RuntimeException("评分必须在 1-100 之间");
        }
        return score;
    }

    private void validateScoreRequired(Integer score, String actorLabel) {
        if (score == null) {
            throw new RuntimeException(actorLabel + "评分不能为空");
        }
    }

    private List<String> getRequiredScoringRoles(String projectType) {
        return "channel_custom".equals(projectType) ? List.of("planner", "sales") : List.of("planner", "admin");
    }

    private List<String> getRequiredScoringRoles(SubTask task) {
        List<String> roles = new ArrayList<>(getRequiredScoringRoles(
                task.getProject() != null ? task.getProject().getType() : "regular"));
        if ("designer".equalsIgnoreCase(task.getAssigneeRole()) && task.getSelfScore() != null) {
            roles.add("designer");
        }
        return roles;
    }

    private boolean isScoringRecordCompleted(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "approved".equals(record.getReviewStatus());
        }
        return record.getScore() != null || (record.getAesthetics() != null && record.getInnovation() != null);
    }

    private boolean isScoringRecordPending(ScoringRecord record) {
        if (record.getReviewStatus() != null) {
            return "pending".equals(record.getReviewStatus());
        }
        return !isScoringRecordCompleted(record);
    }

    private Double toHundredPointScore(ScoringRecord record) {
        if (record.getScore() != null) {
            return record.getScore().doubleValue();
        }
        if (record.getAesthetics() != null && record.getInnovation() != null) {
            return ((record.getAesthetics() + record.getInnovation()) / 2.0) * 10.0;
        }
        return null;
    }

    private String projectType(SubTask task) {
        return task.getProject() != null && task.getProject().getType() != null
                ? task.getProject().getType()
                : "regular";
    }

    /** 当前系统设置中的角色权重（小数形式），用于历史评分重新核算。 */
    public double currentScoringWeight(String projectType, String role) {
        return scoringWeights.pct(projectType, role) / 100.0;
    }
}
