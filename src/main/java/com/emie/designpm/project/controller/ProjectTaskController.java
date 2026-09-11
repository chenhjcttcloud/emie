package com.emie.designpm.project.controller;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Project;
import com.emie.designpm.project.service.ProjectService;
import com.emie.designpm.project.service.ProjectViewSupport;
import com.emie.designpm.project.service.SubTaskCommandService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectTaskController {

    private final ProjectService projectService;
    private final SubTaskCommandService subTaskCommandService;
    private final ProjectViewSupport view;
    private final ProjectRequestSupport req;

    @Autowired
    public ProjectTaskController(
            ProjectService projectService,
            SubTaskCommandService subTaskCommandService,
            ProjectViewSupport view,
            ProjectRequestSupport req) {
        this.projectService = projectService;
        this.subTaskCommandService = subTaskCommandService;
        this.view = view;
        this.req = req;
    }

    /** 保留给轻量 Controller 单元测试；生产运行始终使用完整依赖构造器。 */
    ProjectTaskController(ProjectService projectService, ProjectViewSupport view, ProjectRequestSupport req) {
        this(projectService, unsupportedSubTaskCommands(), view, req);
    }

    static SubTaskCommandService unsupportedSubTaskCommands() {
        return new SubTaskCommandService() {
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException("轻量测试未注入子任务命令服务");
            }

            public Project addSubTask(Long id, Map<String, Object> body) {
                throw unsupported();
            }

            public Project updateSubTask(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskAccept(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project withdrawMarketTask(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project withdrawAcceptedTask(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project cancelAcceptedTask(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project deleteSubTask(Long id, Long taskId) {
                throw unsupported();
            }

            public Project taskDeliver(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskSubmitReview(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskRedeliver(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskConfirmRevision(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskCorrectDelivery(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskApprove(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskReject(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project taskCancelReject(Long id, Long taskId, Long cycleId, Map<String, Object> body) {
                throw unsupported();
            }

            public Project submitScoring(Long id, Long taskId, Map<String, Object> body) {
                throw unsupported();
            }

            public List<Map<String, Object>> getDeliveryVersions(Long taskId) {
                return List.of();
            }

            public double currentScoringWeight(String type, String role) {
                throw unsupported();
            }
        };
    }

    /** 独立的“我的子任务”查询：只返回当前用户作为负责人或发布人关联的任务。 */
    @GetMapping("/my-subtasks")
    public ResponseEntity<List<Map<String, Object>>> getMySubTasks(HttpServletRequest request) {
        AuthSession session = req.getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(view.getMySubTasks(session));
    }

    /** 部门负责人/管理员只读查看部门成员关联任务。 */
    @GetMapping("/department-subtasks")
    public ResponseEntity<List<Map<String, Object>>> getDepartmentSubTasks(HttpServletRequest request) {
        AuthSession session = req.getSession(request);
        if (session == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(view.getDepartmentSubTasks(session));
    }

    /** 编辑子任务 */
    @PutMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.edit");
            if (denied != null) return denied;
            Project project =
                    subTaskCommandService.updateSubTask(projectId, taskId, req.withSessionContext(body, request));
            return ResponseEntity.ok(view.toDetail(project));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师接单 */
    @PostMapping("/{projectId}/tasks/{taskId}/accept")
    public ResponseEntity<?> taskAccept(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.accept");
            if (denied != null) return denied;
            Project project =
                    subTaskCommandService.taskAccept(projectId, taskId, req.withSessionContext(body, request));
            return ResponseEntity.ok(view.toDetail(project));
        } catch (RuntimeException e) {
            if (e.getMessage() != null
                    && (e.getMessage().contains("已被接单") || e.getMessage().contains("已处理"))) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 企划在无人接单时将任务撤出接单市场。 */
    @PostMapping("/{projectId}/tasks/{taskId}/withdraw-market")
    public ResponseEntity<?> withdrawMarketTask(
            @PathVariable Long projectId, @PathVariable Long taskId, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.edit");
            if (denied != null) return denied;
            return ResponseEntity.ok(view.toDetail(subTaskCommandService.withdrawMarketTask(
                    projectId, taskId, req.withSessionContext(new LinkedHashMap<>(), request))));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师退单（接单后一小时内免罚，超时按比例扣分）。 */
    @PostMapping("/{projectId}/tasks/{taskId}/withdraw")
    public ResponseEntity<?> withdrawAcceptedTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.accept");
            if (denied != null) return denied;
            return ResponseEntity.ok(view.toDetail(subTaskCommandService.withdrawAcceptedTask(
                    projectId, taskId, req.withSessionContext(body, request))));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 企划取消设计师已接单但尚未交付的子任务。 */
    @PostMapping("/{projectId}/tasks/{taskId}/cancel-accept")
    public ResponseEntity<?> cancelAcceptedTask(
            @PathVariable Long projectId, @PathVariable Long taskId, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.edit");
            if (denied != null) return denied;
            return ResponseEntity.ok(view.toDetail(subTaskCommandService.cancelAcceptedTask(
                    projectId, taskId, req.withSessionContext(new LinkedHashMap<>(), request))));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设计师交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/deliver")
    public ResponseEntity<?> taskDeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "subtask.deliver",
                () -> subTaskCommandService.taskDeliver(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 设计师/供应链提交已交付成果进入企划送审。 */
    @PostMapping("/{projectId}/tasks/{taskId}/submit-review")
    public ResponseEntity<?> taskSubmitReview(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "subtask.review.first.submit",
                () -> subTaskCommandService.taskSubmitReview(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 设计师重新交付 */
    @PostMapping("/{projectId}/tasks/{taskId}/redeliver")
    public ResponseEntity<?> taskRedeliver(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "subtask.redeliver",
                () -> subTaskCommandService.taskRedeliver(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 被驳回负责人确认开始修改。 */
    @PostMapping("/{projectId}/tasks/{taskId}/confirm-revision")
    public ResponseEntity<?> taskConfirmRevision(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "subtask.redeliver",
                () -> subTaskCommandService.taskConfirmRevision(
                        projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 审核完成前，负责人主动修正漏交或错交文件；生成新版本并使旧审核失效。 */
    @PostMapping("/{projectId}/tasks/{taskId}/correct-delivery")
    public ResponseEntity<?> taskCorrectDelivery(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "subtask.redeliver",
                () -> subTaskCommandService.taskCorrectDelivery(
                        projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 验收通过 */
    @PostMapping("/{projectId}/tasks/{taskId}/approve")
    public ResponseEntity<?> taskApprove(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return reviewCommand(
                request,
                true,
                () -> subTaskCommandService.taskApprove(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 驳回 */
    @PostMapping("/{projectId}/tasks/{taskId}/reject")
    public ResponseEntity<?> taskReject(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return reviewCommand(
                request,
                false,
                () -> subTaskCommandService.taskReject(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 取消当前最新一次误驳回，并精确恢复驳回前状态。 */
    @PostMapping("/{projectId}/tasks/{taskId}/rejections/{cycleId}/cancel")
    public ResponseEntity<?> taskCancelReject(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable Long cycleId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return reviewCommand(
                request,
                false,
                () -> subTaskCommandService.taskCancelReject(
                        projectId, taskId, cycleId, req.withSessionContext(body, request)));
    }

    /** 提交评分 */
    @PostMapping("/{projectId}/tasks/{taskId}/score")
    public ResponseEntity<?> submitScore(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return command(
                request,
                "scoring.submit",
                () -> subTaskCommandService.submitScoring(projectId, taskId, req.withSessionContext(body, request)));
    }

    /** 删除子任务 */
    @DeleteMapping("/{projectId}/tasks/{taskId}")
    public ResponseEntity<?> deleteTask(
            @PathVariable Long projectId, @PathVariable Long taskId, HttpServletRequest request) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, "subtask.delete");
            if (denied != null) return denied;
            AuthSession session = req.getSession(request);
            Project project = projectService.getProjectById(projectId).orElseThrow(() -> new RuntimeException("项目不存在"));
            if (session == null
                    || !("admin".equals(session.role())
                            || ("planner".equals(session.role())
                                    && Objects.equals(session.userId(), project.getPlannerId())))) {
                return ResponseEntity.status(403).body(Map.of("error", "仅项目企划或管理员可删除子任务"));
            }
            return ResponseEntity.ok(view.toDetail(subTaskCommandService.deleteSubTask(projectId, taskId)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> command(
            HttpServletRequest request, String permission, java.util.function.Supplier<Project> action) {
        try {
            ResponseEntity<?> denied = req.denyUnless(request, permission);
            if (denied != null) return denied;
            return ResponseEntity.ok(view.toDetail(action.get()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> reviewCommand(
            HttpServletRequest request, boolean approve, java.util.function.Supplier<Project> action) {
        try {
            return command(request, reviewPermission(req.getSession(request), approve), action);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String reviewPermission(AuthSession session, boolean approve) {
        String action = approve ? "approve" : "reject";
        return switch (session.role()) {
            case "planner" -> "subtask.review.first." + action;
            case "sales" -> "subtask.review.channel." + action;
            case "admin" -> "subtask.review.regular." + action;
            default -> null;
        };
    }
}
