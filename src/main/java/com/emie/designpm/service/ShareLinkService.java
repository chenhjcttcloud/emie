package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.Project;
import com.emie.designpm.entity.ShareLink;
import com.emie.designpm.entity.SubTask;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.ProjectRepository;
import com.emie.designpm.repository.ShareLinkRepository;
import com.emie.designpm.repository.SubTaskRepository;
import com.emie.designpm.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Service
public class ShareLinkService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final int MAX_PASSWORD_ATTEMPTS = 10;
    private static final long PASSWORD_WINDOW_MS = 60_000L;
    private final Map<String, AttemptWindow> passwordAttempts = new ConcurrentHashMap<>();

    private final ShareLinkRepository shareLinkRepository;
    private final ProjectRepository projectRepository;
    private final SubTaskRepository subTaskRepository;
    private final UserRepository userRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    public ShareLinkService(ShareLinkRepository shareLinkRepository,
                            ProjectRepository projectRepository,
                            SubTaskRepository subTaskRepository,
                            UserRepository userRepository) {
        this.shareLinkRepository = shareLinkRepository;
        this.projectRepository = projectRepository;
        this.subTaskRepository = subTaskRepository;
        this.userRepository = userRepository;
    }

    /** 生成分享链接 */
    @Transactional
    public Map<String, Object> createShareLink(String targetType, Long targetId,
                                               String createdBy, Long expiresInSec, String rawPassword) {
        User creator = userRepository.findByUserId(createdBy)
                .orElseThrow(() -> new IllegalArgumentException("创建分享链接的用户不存在"));
        validateTarget(targetType, targetId, creator);

        String token = generateToken();
        LocalDateTime expiresAt = expiresInSec != null && expiresInSec > 0
                ? LocalDateTime.now().plusSeconds(expiresInSec)
                : null;
        String passwordHash = rawPassword != null && !rawPassword.isBlank()
                ? PASSWORD_ENCODER.encode(rawPassword)
                : null;

        ShareLink link = ShareLink.builder()
                .token(token)
                .targetType(targetType)
                .targetId(targetId)
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .password(passwordHash)
                .maxViews(0)
                .viewCount(0)
                .status("active")
                .build();
        shareLinkRepository.save(link);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", link.getId());
        result.put("token", link.getToken());
        result.put("url", "/share/" + link.getToken());
        result.put("targetType", link.getTargetType());
        result.put("targetId", link.getTargetId());
        result.put("expiresAt", link.getExpiresAt());
        result.put("hasPassword", link.getPassword() != null);
        result.put("status", link.getStatus());
        return result;
    }

    /** 验证并渲染分享内容（公开调用，无鉴权） */
    @Transactional
    public Map<String, Object> resolveShare(String token, String password) {
        ShareLink link = shareLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("分享链接不存在"));

        // 校验状态
        if ("revoked".equals(link.getStatus())) {
            throw new IllegalArgumentException("分享链接已被收回");
        }
        if ("expired".equals(link.getStatus())) {
            throw new IllegalArgumentException("分享链接已过期");
        }
        if (link.getExpiresAt() != null && LocalDateTime.now().isAfter(link.getExpiresAt())) {
            link.setStatus("expired");
            shareLinkRepository.save(link);
            throw new IllegalArgumentException("分享链接已过期");
        }

        // 校验密码
        if (link.getPassword() != null) {
            if (password == null || password.isBlank()) {
                return Map.of("needPassword", true, "token", token);
            }
            checkPasswordRateLimit(token);
            String storedPassword = link.getPassword();
            boolean matches = storedPassword.startsWith("$2")
                    ? PASSWORD_ENCODER.matches(password, storedPassword)
                    : AuthController.sha256(password).equals(storedPassword);
            if (!matches) {
                registerPasswordFailure(token);
                throw new IllegalArgumentException("密码错误");
            }
            passwordAttempts.remove(token);
            if (!storedPassword.startsWith("$2")) {
                link.setPassword(PASSWORD_ENCODER.encode(password));
                shareLinkRepository.save(link);
            }
        }

        // 校验查看次数
        if (link.getMaxViews() > 0 && link.getViewCount() >= link.getMaxViews()) {
            throw new IllegalArgumentException("分享链接已达到查看次数上限");
        }

        // 增加查看次数
        link.setViewCount(link.getViewCount() + 1);
        shareLinkRepository.save(link);

        // 获取并脱敏数据
        Map<String, Object> data = fetchTargetData(link.getTargetType(), link.getTargetId());
        data.put("_shareMeta", Map.of(
                "token", token,
                "targetType", link.getTargetType(),
                "createdAt", link.getCreatedAt() != null ? link.getCreatedAt().toString() : "",
                "viewCount", link.getViewCount()
        ));
        return data;
    }

    private void checkPasswordRateLimit(String token) {
        AttemptWindow window = passwordAttempts.get(token);
        if (window != null && window.isActive() && window.attempts >= MAX_PASSWORD_ATTEMPTS) {
            throw new IllegalArgumentException("尝试过于频繁，请稍后再试");
        }
    }

    private void registerPasswordFailure(String token) {
        passwordAttempts.compute(token, (key, current) -> {
            if (current == null || !current.isActive()) return new AttemptWindow(1, System.currentTimeMillis());
            return new AttemptWindow(current.attempts + 1, current.startedAt);
        });
    }

    private record AttemptWindow(int attempts, long startedAt) {
        boolean isActive() {
            return System.currentTimeMillis() - startedAt < PASSWORD_WINDOW_MS;
        }
    }

    /** 获取当前用户创建的所有分享链接 */
    public List<Map<String, Object>> getUserShares(String userId) {
        return shareLinkRepository.findByCreatedByOrderByCreatedAtDesc(userId)
                .stream().map(this::toShareInfo).collect(Collectors.toList());
    }

    /** 管理员获取全部分享链接 */
    public List<Map<String, Object>> getAllShares() {
        return shareLinkRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toShareInfo).collect(Collectors.toList());
    }

    /** 收回分享链接 */
    @Transactional
    public void revokeShare(Long id, String userId) {
        ShareLink link = shareLinkRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分享链接不存在"));
        if (!link.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("只能收回自己创建的分享链接");
        }
        if ("revoked".equals(link.getStatus())) {
            throw new IllegalArgumentException("该链接已被收回");
        }
        link.setStatus("revoked");
        shareLinkRepository.save(link);
    }

    /** 管理员收回任意分享链接 */
    @Transactional
    public void adminRevokeShare(Long id) {
        ShareLink link = shareLinkRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分享链接不存在"));
        if ("revoked".equals(link.getStatus())) {
            throw new IllegalArgumentException("该链接已被收回");
        }
        link.setStatus("revoked");
        shareLinkRepository.save(link);
    }

    /** 管理员更新分享链接（过期时间、密码） */
    @Transactional
    public void adminUpdateShare(Long id, Long expiresInSec, String rawPassword) {
        ShareLink link = shareLinkRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分享链接不存在"));
        if ("revoked".equals(link.getStatus())) {
            throw new IllegalArgumentException("该链接已被收回，无法修改");
        }
        if (expiresInSec != null && expiresInSec > 0) {
            link.setExpiresAt(LocalDateTime.now().plusSeconds(expiresInSec));
        } else if (expiresInSec != null && expiresInSec <= 0) {
            link.setExpiresAt(null); // 永不过期
        }
        // 密码：null 表示不改，空字符串表示清除密码
        if (rawPassword != null) {
            if (rawPassword.isBlank()) {
                link.setPassword(null);
            } else {
                link.setPassword(PASSWORD_ENCODER.encode(rawPassword));
            }
        }
        shareLinkRepository.save(link);
    }

    // ==================== 内部方法 ====================

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        // 确保唯一性
        String token = sb.toString();
        while (shareLinkRepository.findByToken(token).isPresent()) {
            RANDOM.nextBytes(bytes);
            sb = new StringBuilder(40);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            token = sb.toString();
        }
        return token;
    }

    private void validateTarget(String targetType, Long targetId, User creator) {
        switch (targetType) {
            case "project" -> {
                Project project = projectRepository.findById(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
                if (!canAccessProject(creator, project)) {
                    throw new IllegalArgumentException("无权分享该项目");
                }
            }
            case "sub_task" -> {
                SubTask task = subTaskRepository.findById(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("子任务不存在"));
                if (!canAccessSubTask(creator, task)) {
                    throw new IllegalArgumentException("无权分享该子任务");
                }
            }
            default -> throw new IllegalArgumentException("不支持的分享类型: " + targetType);
        }
    }

    /** 获取展示数据并脱敏 */
    private Map<String, Object> fetchTargetData(String targetType, Long targetId) {
        switch (targetType) {
            case "project":
                return fetchProjectData(targetId);
            case "sub_task":
                return fetchSubTaskData(targetId);
            default:
                throw new IllegalArgumentException("不支持的分享类型");
        }
    }

    /** 获取项目详情（脱敏后） */
    private Map<String, Object> fetchProjectData(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "project");
        data.put("projectId", project.getId());
        data.put("projectTitle", safe(project.getProductRequirements()));
        data.put("typeLabel", "channel_custom".equals(project.getType()) ? "渠道定制单" : "公司常规品");
        data.put("status", project.getStatus());
        data.put("statusLabel", statusLabel(project.getStatus()));
        data.put("salesName", safe(project.getSalesName()));
        data.put("plannerName", safe(project.getPlannerName()));
        data.put("deadline", safe(project.getDeadline()));
        data.put("productRequirements", safe(project.getProductRequirements()));
        data.put("description", safe(project.getDescription()));
        data.put("createdAt", project.getCreatedAt() != null ? project.getCreatedAt().toString() : "");
        data.put("updatedAt", project.getUpdatedAt() != null ? project.getUpdatedAt().toString() : "");

        // 类目
        if (project.getProductCategory() != null) {
            data.put("productCategory", project.getProductCategory().getName());
        }
        data.put("productCategoryNote", safe(project.getProductCategoryNote()));
        data.put("priceRange", safe(project.getPriceRange()));
        data.put("ipName", safe(project.getIpName()));

        // 子任务（脱敏：只显示名称、状态、交付物图片，隐藏内部备注/评分）
        List<Map<String, Object>> taskList = new ArrayList<>();
        if (project.getTasks() != null) {
            for (SubTask task : project.getTasks()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", task.getId());
                t.put("name", safe(task.getName()));
                t.put("status", safe(task.getStatus()));
                t.put("statusLabel", taskStatusLabel(task.getStatus()));
                t.put("plannedDate", safe(task.getPlannedDate()));
                t.put("actualDate", safe(task.getActualDate()));
                t.put("designerName", safe(task.getDesignerName()));
                t.put("deliverables", safe(task.getDeliverables()));
                taskList.add(t);
            }
        }
        data.put("tasks", taskList);

        return data;
    }

    /** 获取子任务详情（脱敏后） */
    private Map<String, Object> fetchSubTaskData(Long subTaskId) {
        SubTask task = subTaskRepository.findById(subTaskId)
                .orElseThrow(() -> new IllegalArgumentException("子任务不存在"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "sub_task");
        data.put("taskId", task.getId());
        data.put("name", safe(task.getName()));
        data.put("status", safe(task.getStatus()));
        data.put("statusLabel", taskStatusLabel(task.getStatus()));
        data.put("plannedDate", safe(task.getPlannedDate()));
        data.put("actualDate", safe(task.getActualDate()));
        data.put("designerName", safe(task.getDesignerName()));
        data.put("deliverables", safe(task.getDeliverables()));

        // 关联项目信息
        if (task.getProject() != null) {
            data.put("projectId", task.getProject().getId());
            data.put("projectTypeLabel", "channel_custom".equals(task.getProject().getType()) ? "渠道定制单" : "公司常规品");
            data.put("projectStatus", task.getProject().getStatus());
            data.put("projectDeadline", safe(task.getProject().getDeadline()));
            data.put("projectPlannerName", safe(task.getProject().getPlannerName()));
            data.put("projectSalesName", safe(task.getProject().getSalesName()));
        }

        return data;
    }

    private Map<String, Object> toShareInfo(ShareLink link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", link.getId());
        m.put("token", link.getToken());
        m.put("url", "/share/" + link.getToken());
        m.put("targetType", link.getTargetType());
        m.put("targetId", link.getTargetId());
        m.put("createdBy", link.getCreatedBy());
        // 获取创建人显示名称
        String createdByName = userRepository.findByUserId(link.getCreatedBy())
                .map(u -> u.getName()).orElse(link.getCreatedBy());
        m.put("createdByName", createdByName);
        m.put("createdAt", link.getCreatedAt() != null ? link.getCreatedAt().toString() : "");
        m.put("expiresAt", link.getExpiresAt() != null ? link.getExpiresAt().toString() : "");
        m.put("hasPassword", link.getPassword() != null);
        m.put("viewCount", link.getViewCount());
        m.put("maxViews", link.getMaxViews());
        m.put("status", link.getStatus());
        return m;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "draft" -> "草稿";
            case "pending_planner" -> "待企划接单";
            case "planner_accepted" -> "企划已接单";
            case "in_progress" -> "进行中";
            case "completed" -> "已完成";
            case "paused" -> "已暂停";
            case "pending_terminate" -> "终止确认中";
            case "terminated" -> "已终止";
            default -> status;
        };
    }

    private static String taskStatusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "pending" -> "待认领";
            case "accepted" -> "进行中";
            case "delivered" -> "已交付";
            case "planner_approved" -> "企划已验收";
            case "scoring_planner" -> "待二次验收";
            case "sales_approved" -> "销售已验收";
            case "admin_approved" -> "管理已验收";
            case "approved" -> "已通过";
            case "completed" -> "已完成";
            case "rejected" -> "已驳回";
            default -> status;
        };
    }

    private boolean canAccessProject(User user, Project project) {
        if (user == null || project == null) {
            return false;
        }
        return switch (user.getRole()) {
            case "admin" -> true;
            case "sales" -> Objects.equals(user.getUserId(), project.getSalesId());
            case "planner" -> Objects.equals(user.getUserId(), project.getPlannerId());
            case "designer", "supplychain" -> project.getTasks() != null && project.getTasks().stream()
                    .anyMatch(task -> Objects.equals(user.getUserId(), task.getDesignerId()));
            default -> false;
        };
    }

    private boolean canAccessSubTask(User user, SubTask task) {
        if (user == null || task == null) {
            return false;
        }
        if ("designer".equals(user.getRole()) || "supplychain".equals(user.getRole())) {
            return Objects.equals(user.getUserId(), task.getDesignerId());
        }
        return task.getProject() != null && canAccessProject(user, task.getProject());
    }
}
