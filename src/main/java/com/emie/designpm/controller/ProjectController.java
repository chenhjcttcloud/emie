package com.emie.designpm.controller;

import com.emie.designpm.dto.ProjectDetailDTO;
import com.emie.designpm.dto.ProjectSummaryDTO;
import com.emie.designpm.dto.TaskDetailDTO;
import com.emie.designpm.entity.*;
import com.emie.designpm.repository.ActivityLogRepository;
import com.emie.designpm.repository.ScoringRepository;
import com.emie.designpm.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ScoringRepository scoringRepository;
    private final ActivityLogRepository activityLogRepository;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public ProjectController(ProjectService projectService,
                             ScoringRepository scoringRepository,
                             ActivityLogRepository activityLogRepository) {
        this.projectService = projectService;
        this.scoringRepository = scoringRepository;
        this.activityLogRepository = activityLogRepository;
    }

    /** 获取所有项目列表 */
    @GetMapping
    public ResponseEntity<List<ProjectSummaryDTO>> getProjects(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean participating) {

        List<Project> projects;
        if (role != null && userId != null) {
            // 设计师查看渠道/常规品页面时，只显示已参与的项目
            if (participating && "designer".equals(role)) {
                projects = projectService.getDesignerParticipatingProjects(userId);
            } else {
                projects = projectService.getProjectsByRoleAndUser(role, userId);
            }
        } else {
            projects = projectService.getAllProjects();
        }

        if (type != null) {
            projects = projects.stream()
                    .filter(p -> type.equals(p.getType()))
                    .collect(Collectors.toList());
        }

        List<ProjectSummaryDTO> result = projects.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /** 获取项目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailDTO> getProjectDetail(@PathVariable Long id, HttpServletRequest request) {
        // 记录查询日志
        String token = request.getHeader("X-Auth-Token");
        if (token != null) {
            AuthController.AuthSession session = AuthController.validateToken(token);
            if (session != null) {
                activityLogRepository.save(new ActivityLog(
                    "查询项目 #" + id, session.name(), session.role()));
            }
        }
        return projectService.getProjectById(id)
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 新建项目 */
    @PostMapping
    public ResponseEntity<ProjectDetailDTO> createProject(@RequestBody Map<String, Object> body) {
        Project p = projectService.createProject(body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 企划接单 */
    @PostMapping("/{id}/accept")
    public ResponseEntity<ProjectDetailDTO> plannerAccept(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Project p = projectService.plannerAccept(id,
                body.getOrDefault("currentUser", ""),
                body.getOrDefault("currentRole", ""),
                body.getOrDefault("userId", ""));
        return ResponseEntity.ok(toDetail(p));
    }

    /** 添加子任务 */
    @PostMapping("/{id}/tasks")
    public ResponseEntity<ProjectDetailDTO> addTask(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.addSubTask(id, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 编辑子任务 */
    @PutMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<ProjectDetailDTO> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.updateSubTask(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 设计师接单 */
    @PostMapping("/{projectId}/tasks/{taskId}/accept")
    public ResponseEntity<?> taskAccept(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        try {
            Project p = projectService.taskAccept(projectId, taskId, body);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/deliver")
    public ResponseEntity<ProjectDetailDTO> taskDeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.taskDeliver(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 设计师重新交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/redeliver")
    public ResponseEntity<ProjectDetailDTO> taskRedeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.taskRedeliver(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 验收通过 */
    @PostMapping("/{projectId}/tasks/{taskId}/approve")
    public ResponseEntity<ProjectDetailDTO> taskApprove(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.taskApprove(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 驳回 */
    @PostMapping("/{projectId}/tasks/{taskId}/reject")
    public ResponseEntity<ProjectDetailDTO> taskReject(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.taskReject(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 提交评分 */
    @PostMapping("/{projectId}/tasks/{taskId}/score")
    public ResponseEntity<ProjectDetailDTO> submitScore(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Project p = projectService.submitScoring(projectId, taskId, body);
        return ResponseEntity.ok(toDetail(p));
    }

    /** 设计师状态看板 */
    @GetMapping("/designer-status")
    public ResponseEntity<Map<String, Object>> designerStatus() {
        return ResponseEntity.ok(projectService.getDesignerStatus());
    }

    /** 终止项目 */
    @PostMapping("/{id}/terminate")
    public ResponseEntity<?> terminateProject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Project p = projectService.terminateProject(id, body);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 暂停项目 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseProject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Project p = projectService.pauseProject(id, body);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 取消终止 */
    @PostMapping("/{id}/cancel-terminate")
    public ResponseEntity<?> cancelTerminate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Project p = projectService.cancelTerminate(id, body);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 继续项目 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumeProject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Project p = projectService.resumeProject(id, body);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除子任务 */
    @DeleteMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> deleteTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        try {
            Project p = projectService.deleteSubTask(projectId, taskId);
            return ResponseEntity.ok(toDetail(p));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== DTO Mappers ====================

    private ProjectSummaryDTO toSummary(Project p) {
        ProjectSummaryDTO dto = new ProjectSummaryDTO();
        dto.setId(p.getId());
        dto.setType(p.getType());
        String computedStatus = projectService.computeProjectStatus(p);
        dto.setStatus(computedStatus);
        Map<String, String> statusInfo = ProjectService.getProjectStatusInfo(computedStatus);
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setSalesName(p.getSalesName());
        dto.setPlannerName(p.getPlannerName());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());

        int taskCount = p.getTasks().size();
        long doneCount = p.getTasks().stream().filter(t -> projectService.isTaskFullyCompleted(t)).count();
        dto.setTaskCount(taskCount);
        dto.setApprovedTaskCount((int) doneCount);
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);
        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));
        return dto;
    }

    private ProjectDetailDTO toDetail(Project p) {
        ProjectDetailDTO dto = new ProjectDetailDTO();
        dto.setId(p.getId());
        dto.setType(p.getType());
        String computedStatus = projectService.computeProjectStatus(p);
        dto.setStatus(computedStatus);
        Map<String, String> statusInfo = ProjectService.getProjectStatusInfo(computedStatus);
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setSalesName(p.getSalesName());
        dto.setSalesId(p.getSalesId());
        dto.setPlannerName(p.getPlannerName());
        dto.setPlannerId(p.getPlannerId());
        dto.setDeadline(p.getDeadline());
        dto.setProductRequirements(p.getProductRequirements());
        dto.setDescription(p.getDescription());
        dto.setReferenceImagesJson(p.getReferenceImagesJson());
        dto.setAttachmentsJson(p.getAttachmentsJson());

        // Logs
        dto.setLogs(p.getLogs().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", l.getTime().format(DTF));
            m.put("action", l.getAction());
            m.put("user", l.getUsername());
            m.put("role", l.getRole());
            return m;
        }).collect(Collectors.toList()));

        // Tasks with batch-loaded scoring records
        List<SubTask> taskList = p.getTasks();
        // 批量加载评分记录
        List<Long> taskIds = taskList.stream().map(SubTask::getId).collect(Collectors.toList());
        Map<Long, List<Map<String, Object>>> scoringMap = new HashMap<>();
        if (!taskIds.isEmpty()) {
            scoringRepository.findBySubTaskIds(taskIds).forEach(sr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", sr.getRole());
                m.put("aesthetics", sr.getAesthetics());
                m.put("innovation", sr.getInnovation());
                m.put("weight", sr.getWeight());
                scoringMap.computeIfAbsent(sr.getSubTask().getId(), k -> new java.util.ArrayList<>()).add(m);
            });
        }
        dto.setTasks(taskList.stream().map(t -> {
            TaskDetailDTO tDto = toTaskDetail(t);
            tDto.setScoringRecords(scoringMap.getOrDefault(t.getId(), List.of()));
            return tDto;
        }).collect(Collectors.toList()));

        // Progress
        int taskCount = p.getTasks().size();
        long doneCount = p.getTasks().stream().filter(t -> projectService.isTaskFullyCompleted(t)).count();
        dto.setProgressPercent(taskCount > 0 ? (int) (doneCount * 100 / taskCount) : 0);

        dto.setCreatedAt(p.getCreatedAt().format(DTF));
        dto.setUpdatedAt(p.getUpdatedAt().format(DTF));

        return dto;
    }

    private TaskDetailDTO toTaskDetail(SubTask t) {
        TaskDetailDTO dto = new TaskDetailDTO();
        dto.setId(t.getId());
        dto.setName(t.getName());
        dto.setStatus(t.getStatus());
        Map<String, String> statusInfo = ProjectService.getTaskStatusInfo(t.getStatus());
        dto.setStatusLabel(statusInfo.get("label"));
        dto.setStatusCls(statusInfo.get("cls"));
        dto.setStatusIcon(statusInfo.get("icon"));
        dto.setPlannedDate(t.getPlannedDate());
        dto.setActualDate(t.getActualDate());
        dto.setDesignerId(t.getDesignerId());
        dto.setDesignerName(t.getDesignerName());
        dto.setDetails(t.getDetails());
        dto.setDeliverables(t.getDeliverables());
        dto.setAttachmentsJson(t.getAttachmentsJson());
        dto.setReferenceImagesJson(t.getReferenceImagesJson());
        dto.setReviewComments(t.getReviewComments());
        dto.setCreatedAt(t.getCreatedAt().format(DTF));

        // Scoring records
        List<ScoringRecord> records = scoringRepository.findBySubTaskId(t.getId());
        dto.setScoringRecords(records.stream().map(sr -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", sr.getRole());
            m.put("aesthetics", sr.getAesthetics());
            m.put("innovation", sr.getInnovation());
            m.put("weight", sr.getWeight());
            return m;
        }).collect(Collectors.toList()));

        return dto;
    }
}
