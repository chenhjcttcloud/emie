package com.emie.designpm.recommendation.service;

import com.emie.designpm.admin.repository.ActivityLogRepository;
import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.*;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.imagelibrary.repository.ImageLibraryItemRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.recommendation.analysis.AnalysisView;
import com.emie.designpm.recommendation.analysis.VisualProfile;
import com.emie.designpm.recommendation.dto.ImageRecommendationItem;
import com.emie.designpm.recommendation.repository.TaskImageLibraryLinkRepository;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImageRecommendationService {
    private static final Set<String> VIEW_ROLES = Set.of("admin", "planner", "designer");
    private static final Set<String> EDITABLE = Set.of("pending", "accepted", "rejected", "revision_requested");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CACHE_SIZE = 128;
    private static final Duration CACHE_TTL = Duration.ofHours(2);
    private final ImageLibraryItemRepository library;
    private final FileRecordRepository files;
    private final SubTaskRepository tasks;
    private final ProjectAccessService projectAccess;
    private final PermissionService permissions;
    private final TaskImageLibraryLinkRepository links;
    private final ActivityLogRepository logs;
    private final VisualAnalysisService analysis;
    private final ImageRecommendationMatcher matcher;
    private final Map<Long, Snapshot> cache = new LinkedHashMap<>(16, .75f, true);

    public ImageRecommendationService(
            ImageLibraryItemRepository library,
            FileRecordRepository files,
            SubTaskRepository tasks,
            ProjectAccessService projectAccess,
            PermissionService permissions,
            TaskImageLibraryLinkRepository links,
            ActivityLogRepository logs,
            VisualAnalysisService analysis,
            ImageRecommendationMatcher matcher) {
        this.library = library;
        this.files = files;
        this.tasks = tasks;
        this.projectAccess = projectAccess;
        this.permissions = permissions;
        this.links = links;
        this.logs = logs;
        this.analysis = analysis;
        this.matcher = matcher;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recommendations(
            Long projectId, Long taskId, int requestedLimit, boolean refresh, AuthSession session) {
        SubTask task = visibleTask(projectId, taskId, session, false);
        int limit = Math.max(1, Math.min(8, requestedLimit));
        List<Candidate> candidates = catalog();
        List<String> referenceNames = referenceNames(task);
        Set<String> names = new LinkedHashSet<>(referenceNames);
        candidates.forEach(c ->
                c.images().forEach(image -> names.add(image.get("storedName").toString())));
        Map<String, AnalysisView> profiles = new HashMap<>();
        List<String> orderedNames = List.copyOf(names);
        for (int start = 0; start < orderedNames.size(); start += 1000)
            profiles.putAll(analysis.peekAll(orderedNames.subList(start, Math.min(start + 1000, orderedNames.size()))));
        Set<String> requestedNames = new HashSet<>();
        referenceNames.stream().limit(9).forEach(name -> {
            profiles.put(name, analysis.profile(name));
            requestedNames.add(name);
        });
        ImageRecommendationMatcher.Input input = input(task, profileList(referenceNames, profiles));
        // At most 24 assets are queued by a view. Cached successes and failures are reused by analysis.
        Map<Long, Double> preliminaryScores = new HashMap<>();
        candidates.forEach(candidate -> preliminaryScores.put(
                candidate.item().getId(), score(input, candidate, profiles).score()));
        candidates.stream()
                .sorted(Comparator.comparingDouble(
                                (Candidate c) -> preliminaryScores.get(c.item().getId()))
                        .reversed())
                .limit(24)
                .forEach(c -> {
                    String name = c.images().getFirst().get("storedName").toString();
                    profiles.put(name, analysis.profile(name));
                    requestedNames.add(name);
                });
        boolean pending = requestedNames.stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .anyMatch(v -> "PENDING".equals(v.status()));
        String digest = digest(task, candidates, profiles, analysis.configured());
        Snapshot snapshot;
        boolean cached;
        synchronized (cache) {
            snapshot = cache.get(taskId);
            cached = !refresh
                    && snapshot != null
                    && snapshot.digest().equals(digest)
                    && snapshot.createdAt().plus(CACHE_TTL).isAfter(Instant.now());
        }
        if (!cached) {
            List<ImageRecommendationItem> ranked = candidates.stream()
                    .map(c -> item(c, score(input, c, profiles), profiles))
                    .filter(item -> item.score() > 0)
                    .sorted(Comparator.comparingDouble(ImageRecommendationItem::score)
                            .reversed()
                            .thenComparing(ImageRecommendationItem::id, Comparator.reverseOrder()))
                    .limit(8)
                    .toList();
            snapshot = new Snapshot(digest, ranked, Instant.now());
            synchronized (cache) {
                cache.put(taskId, snapshot);
                while (cache.size() > CACHE_SIZE)
                    cache.remove(cache.keySet().iterator().next());
            }
        }
        List<ImageRecommendationItem> recommended =
                snapshot.items().stream().limit(limit).toList();
        List<Long> associated = links.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(TaskImageLibraryLink::getLibraryItemId)
                .distinct()
                .toList();
        Set<Long> shown = new HashSet<>(
                recommended.stream().map(ImageRecommendationItem::id).toList());
        List<ImageRecommendationItem> linkedItems = candidates.stream()
                .filter(c -> associated.contains(c.item().getId())
                        && !shown.contains(c.item().getId()))
                .map(c -> item(c, new ImageRecommendationMatcher.Match(0, List.of("已关联至此任务")), profiles))
                .toList();
        boolean visual = recommended.stream().anyMatch(i -> "READY".equals(i.analysisStatus()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", recommended);
        result.put("linkedItems", linkedItems);
        result.put("associatedLibraryItemIds", associated);
        result.put("canAssociate", canAssociate(task, session));
        result.put("aiConfigured", analysis.configured());
        result.put("analysisPending", pending);
        result.put("cached", cached);
        result.put("generatedAt", snapshot.createdAt().toString());
        result.put("status", pending ? "ANALYZING" : visual ? "READY" : recommended.isEmpty() ? "EMPTY" : "METADATA");
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void associate(Long projectId, Long taskId, Long libraryItemId, AuthSession session) {
        SubTask task = visibleTask(projectId, taskId, session, true);
        if (!canAssociate(task, session)) throw status(HttpStatus.FORBIDDEN, "仅任务负责人、项目企划和管理员可关联执行中的任务");
        ImageLibraryItem item =
                library.findById(libraryItemId).orElseThrow(() -> status(HttpStatus.NOT_FOUND, "图档不存在"));
        boolean valid = files.findByTargetTypeAndTargetId("image_library", libraryItemId).stream()
                .anyMatch(record -> validStoredName(record.getStoredName())
                        && mapList(item.getImagesJson()).stream()
                                .anyMatch(image -> record.getStoredName().equals(image.get("storedName"))));
        if (!valid) throw status(HttpStatus.NOT_FOUND, "该图档暂无可用图片");
        if (links.existsByTaskIdAndLibraryItemId(taskId, libraryItemId)) return;
        if (links.countByTaskId(taskId) >= 6) throw status(HttpStatus.CONFLICT, "每个任务最多关联 6 个图档");
        TaskImageLibraryLink link = new TaskImageLibraryLink();
        link.setTaskId(taskId);
        link.setLibraryItemId(libraryItemId);
        link.setActorUserId(session.userId());
        link.setCreatedAt(LocalDateTime.now());
        links.saveAndFlush(link);
        logs.save(new ActivityLog(
                "为任务「" + task.getName() + "」关联图档「" + item.getName() + "」",
                session.name(),
                session.role(),
                task.getProject(),
                "sub_task",
                taskId,
                "{}",
                json(Map.of("libraryItemId", libraryItemId, "libraryName", item.getName())),
                "[\"图档关联\"]"));
    }

    private SubTask visibleTask(Long projectId, Long taskId, AuthSession session, boolean locked) {
        if (session == null) throw status(HttpStatus.UNAUTHORIZED, "请先登录");
        String role = PermissionCatalog.normalizeRole(session.role());
        if (!VIEW_ROLES.contains(role) || !permissions.has(role, "page.image_library.view"))
            throw status(HttpStatus.FORBIDDEN, "没有权限查看图档库");
        SubTask task = (locked ? tasks.findByIdForUpdate(taskId) : tasks.findByIdWithProject(taskId))
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "任务不存在"));
        if (task.getProject() == null || !Objects.equals(task.getProject().getId(), projectId))
            throw status(HttpStatus.NOT_FOUND, "任务不存在");
        if (!projectAccess.canView(task.getProject(), session)) throw status(HttpStatus.FORBIDDEN, "没有权限查看此任务");
        return task;
    }

    private boolean canAssociate(SubTask task, AuthSession session) {
        if (!EDITABLE.contains(task.getStatus())
                || Set.of("terminated", "paused", "pending_terminate", "completed")
                        .contains(Objects.toString(task.getProject().getStatus(), ""))) return false;
        return "admin".equals(PermissionCatalog.normalizeRole(session.role()))
                || Objects.equals(task.getProject().getPlannerId(), session.userId())
                || Objects.equals(task.getDesignerId(), session.userId());
    }

    private List<Candidate> catalog() {
        Map<String, FileRecord> fileIndex = new HashMap<>();
        files.findByTargetTypeOrderByCreatedAtDesc("image_library")
                .forEach(record -> fileIndex.putIfAbsent(record.getStoredName(), record));
        List<Candidate> result = new ArrayList<>();
        for (ImageLibraryItem item : library.findAllByOrderByCreatedAtDesc()) {
            List<Map<String, Object>> images = new ArrayList<>();
            for (Map<String, Object> raw : mapList(item.getImagesJson())) {
                String storedName = Objects.toString(raw.get("storedName"), "");
                FileRecord record = fileIndex.get(storedName);
                if (!validStoredName(storedName)
                        || record == null
                        || !Objects.equals(item.getId(), record.getTargetId())) continue;
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("storedName", storedName);
                image.put("name", Objects.toString(record.getOriginalName(), storedName));
                image.put("size", record.getFileSize() == null ? 0 : record.getFileSize());
                image.put("url", "/api/files/download/" + storedName);
                images.add(image);
            }
            if (!images.isEmpty()) result.add(new Candidate(item, List.copyOf(images)));
        }
        return result;
    }

    private List<String> referenceNames(SubTask task) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : List.of(
                Objects.toString(task.getPendingChangeReferenceImagesJson(), "[]"),
                Objects.toString(task.getReferenceImagesJson(), "[]"),
                Objects.toString(task.getProject().getReferenceImagesJson(), "[]"))) {
            for (Map<String, Object> file : mapList(raw)) {
                String name = Objects.toString(file.get("storedName"), "");
                if (!validStoredName(name)) continue;
                FileRecord record = files.findByStoredName(name).orElse(null);
                if (record == null) continue;
                if ("image_library".equals(record.getTargetType())
                        || "project".equals(record.getTargetType())
                                && Objects.equals(
                                        record.getTargetId(), task.getProject().getId())
                        || "sub_task".equals(record.getTargetType())
                                && Objects.equals(record.getTargetId(), task.getId())) names.add(name);
                if (names.size() == 9) return List.copyOf(names);
            }
        }
        return List.copyOf(names);
    }

    private ImageRecommendationMatcher.Input input(SubTask task, List<VisualProfile> profiles) {
        Project project = task.getProject();
        return new ImageRecommendationMatcher.Input(
                project.getIpName(),
                stringList(project.getIpSubOptions()),
                String.join(
                        " ",
                        Objects.toString(task.getName(), ""),
                        Objects.toString(task.getDetails(), ""),
                        Objects.toString(project.getProductName(), ""),
                        Objects.toString(project.getProductRequirements(), ""),
                        Objects.toString(project.getDescription(), "")),
                profiles);
    }

    private ImageRecommendationMatcher.Match score(
            ImageRecommendationMatcher.Input input, Candidate candidate, Map<String, AnalysisView> profiles) {
        return matcher.match(
                input,
                candidate.item().getIpName(),
                stringList(candidate.item().getSubOptionsJson()),
                Objects.toString(candidate.item().getName(), "") + " "
                        + Objects.toString(candidate.item().getNotes(), ""),
                profileList(candidate.names(), profiles));
    }

    private ImageRecommendationItem item(
            Candidate candidate, ImageRecommendationMatcher.Match score, Map<String, AnalysisView> profiles) {
        String status = candidate.names().stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .map(AnalysisView::status)
                .filter("READY"::equals)
                .findFirst()
                .orElse("METADATA");
        return new ImageRecommendationItem(
                candidate.item().getId(),
                candidate.item().getId(),
                candidate.item().getName(),
                candidate.item().getIpName(),
                stringList(candidate.item().getSubOptionsJson()),
                candidate.images(),
                score.reasons(),
                score.score(),
                status);
    }

    private static List<VisualProfile> profileList(List<String> names, Map<String, AnalysisView> profiles) {
        return names.stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .filter(v -> "READY".equals(v.status()))
                .map(AnalysisView::profile)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String digest(
            SubTask task, List<Candidate> candidates, Map<String, AnalysisView> profiles, boolean configured) {
        Project project = task.getProject();
        List<Object> parts = new ArrayList<>(Arrays.asList(
                task.getName(),
                task.getDetails(),
                task.getReferenceImagesJson(),
                task.getPendingChangeReferenceImagesJson(),
                project.getProductName(),
                project.getProductRequirements(),
                project.getDescription(),
                project.getIpName(),
                project.getIpSubOptions(),
                project.getReferenceImagesJson(),
                configured));
        for (Candidate c : candidates)
            parts.add(Arrays.asList(
                    c.item().getId(),
                    c.item().getName(),
                    c.item().getNotes(),
                    c.item().getIpName(),
                    c.item().getSubOptionsJson(),
                    c.images()));
        parts.add(new TreeMap<>(profiles));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(json(parts).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean validStoredName(String name) {
        return SecurityUtil.isValidFileName(name) && name.matches("(?i).+\\.(png|jpe?g|gif|webp|bmp|ai|pdf)$");
    }

    private static List<Map<String, Object>> mapList(String value) {
        try {
            return value == null ? List.of() : JSON.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> stringList(String value) {
        try {
            return value == null
                    ? List.of()
                    : JSON.readValue(value, new TypeReference<List<String>>() {}).stream()
                            .filter(Objects::nonNull)
                            .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("推荐数据无法编码", e);
        }
    }

    private static ResponseStatusException status(HttpStatus code, String message) {
        return new ResponseStatusException(code, message);
    }

    private record Candidate(ImageLibraryItem item, List<Map<String, Object>> images) {
        List<String> names() {
            return images.stream()
                    .map(image -> image.get("storedName").toString())
                    .toList();
        }
    }

    private record Snapshot(String digest, List<ImageRecommendationItem> items, Instant createdAt) {}
}
