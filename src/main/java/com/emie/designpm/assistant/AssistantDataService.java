package com.emie.designpm.assistant;

import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.admin.service.PermissionService;
import com.emie.designpm.assistant.repository.AssistantTaskRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.*;
import com.emie.designpm.file.repository.FileRecordRepository;
import com.emie.designpm.imagelibrary.repository.ImageLibraryItemRepository;
import com.emie.designpm.project.repository.ProjectRepository;
import com.emie.designpm.project.repository.SubTaskRepository;
import com.emie.designpm.project.service.ProjectAccessService;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssistantDataService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PermissionService permissions;
    private final ProjectAccessService access;
    private final ProjectRepository projects;
    private final SubTaskRepository tasks;
    private final AssistantTaskRepository ownTasks;
    private final ImageLibraryItemRepository library;
    private final FileRecordRepository files;
    private final AssistantImageColorSearch colorSearch;

    public AssistantDataService(
            PermissionService permissions,
            ProjectAccessService access,
            ProjectRepository projects,
            SubTaskRepository tasks,
            AssistantTaskRepository ownTasks,
            ImageLibraryItemRepository library,
            FileRecordRepository files,
            AssistantImageColorSearch colorSearch) {
        this.permissions = permissions;
        this.access = access;
        this.projects = projects;
        this.tasks = tasks;
        this.ownTasks = ownTasks;
        this.library = library;
        this.files = files;
        this.colorSearch = colorSearch;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> context(AssistantRequest.Context context, AuthSession session) {
        if (context == null || context.projectId() == null && context.taskId() == null) return Map.of();
        if (!permissions.has(session.role(), "project.detail.view")) throw forbidden();
        Project project;
        SubTask task = null;
        if (context.taskId() != null) {
            task = tasks.findByIdWithProject(context.taskId()).orElseThrow(AssistantDataService::forbidden);
            project = task.getProject();
            if (context.projectId() != null && !Objects.equals(context.projectId(), project.getId())) throw forbidden();
        } else project = projects.findById(context.projectId()).orElseThrow(AssistantDataService::forbidden);
        if (!access.canView(project, session)) throw forbidden();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", project.getId());
        value.put("name", safe(project.getProductName(), 200));
        value.put("type", safe(project.getType(), 40));
        value.put("status", safe(project.getStatus(), 40));
        value.put("stage", safe(project.getWorkflowStage(), 40));
        value.put("stageStatus", safe(project.getWorkflowStatus(), 40));
        value.put("deadline", safe(project.getDeadline(), 50));
        value.put("planner", safe(project.getPlannerName(), 60));
        value.put("sales", safe(project.getSalesName(), 60));
        value.put("requirements", safe(project.getProductRequirements(), 1000));
        value.put("ipName", safe(project.getIpName(), 100));
        value.put("ipSubOptions", safe(project.getIpSubOptions(), 1000));
        if (task != null) {
            value.put("taskId", task.getId());
            value.put("taskName", safe(task.getName(), 200));
            value.put("taskStatus", safe(task.getStatus(), 40));
            value.put("taskDeadline", safe(task.getPlannedDate(), 50));
            value.put("assignee", safe(task.getDesignerName(), 60));
            value.put("taskDetails", safe(task.getDetails(), 1000));
            value.put("reviewComments", safe(task.getReviewComments(), 500));
        }
        return value;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myTasks(AuthSession session) {
        if (!permissions.has(session.role(), "page.subtasks.mine.view")) return List.of();
        return ownTasks.findOwnOpenTasks(session.userId(), PageRequest.of(0, 50)).stream()
                .filter(task -> Objects.equals(session.userId(), task.getDesignerId())
                        || Objects.equals(session.userId(), task.getPublisherId()))
                .filter(task -> access.canView(task.getProject(), session))
                .limit(8)
                .map(task -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", task.getId());
                    item.put("projectId", task.getProject().getId());
                    item.put("name", safe(task.getName(), 200));
                    item.put("status", safe(task.getStatus(), 40));
                    item.put("deadline", safe(task.getPlannedDate(), 50));
                    return item;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public FileRecord ownedAttachment(String storedName, AuthSession session) {
        requireLibrary(session);
        if (!permissions.has(session.role(), "file.upload")) throw forbidden();
        FileRecord file = files.findByStoredName(storedName).orElseThrow(AssistantDataService::forbidden);
        if (!Objects.equals(session.userId(), file.getOwnerUserId())
                || file.getTargetId() != null
                || file.getTargetType() != null && !file.getTargetType().isBlank()) throw forbidden();
        return file;
    }

    public record ImageSearchResult(
            List<Map<String, Object>> images,
            int libraryCount,
            List<String> colors,
            int missingThumbnails,
            boolean partial) {}

    public boolean isColorSearch(String query) {
        return !colorSearch.parseColors(query).isEmpty()
                && terms(colorSearch.textWithoutColors(query)).isEmpty();
    }

    @Transactional(readOnly = true)
    public ImageSearchResult searchImages(String query, AuthSession session) {
        requireLibrary(session);
        var colors = colorSearch.parseColors(query);
        List<String> terms = terms(colors.isEmpty() ? query : colorSearch.textWithoutColors(query));
        if (terms.isEmpty() && colors.isEmpty()) return new ImageSearchResult(List.of(), -1, List.of(), 0, false);
        List<ImageLibraryItem> items = library.findAllByOrderByCreatedAtDesc();
        List<String> colorNames = colors.stream()
                .map(AssistantImageColorSearch.ColorFamily::label)
                .toList();
        if (items.isEmpty()) return new ImageSearchResult(List.of(), 0, colorNames, 0, false);
        if (!colors.isEmpty()) return searchByColor(items, terms, colors, colorNames);
        List<Map<String, Object>> results = items.stream()
                .map(item -> new Ranked(item, matches(item, terms)))
                .filter(rank -> !rank.reasons().isEmpty())
                .sorted(Comparator.comparingInt((Ranked rank) -> rank.reasons().size())
                        .reversed())
                .map(rank -> new ImageMatch(rank, imageFiles(rank.item())))
                .filter(match -> !match.images().isEmpty())
                .limit(6)
                .map(match -> imageView(match.rank().item(), match.rank().reasons(), match.images()))
                .toList();
        return new ImageSearchResult(results, items.size(), List.of(), 0, false);
    }

    private ImageSearchResult searchByColor(
            List<ImageLibraryItem> items,
            List<String> terms,
            List<AssistantImageColorSearch.ColorFamily> colors,
            List<String> colorNames) {
        // Resolve shared-library file bindings once, before touching thumbnail caches.
        Map<String, FileRecord> records = new HashMap<>();
        files.findByTargetTypeOrderByCreatedAtDesc("image_library")
                .forEach(file -> records.putIfAbsent(file.getStoredName(), file));
        List<ColorRanked> results = new ArrayList<>();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        int inspected = 0;
        int unavailable = 0;
        boolean partial = false;
        for (ImageLibraryItem item : items) {
            List<String> textReasons = matches(item, terms);
            if (!terms.isEmpty() && textReasons.isEmpty()) continue;
            List<Map<String, Object>> matchedFiles = new ArrayList<>();
            List<String> reasons = new ArrayList<>(textReasons);
            double best = 0;
            for (Map<String, Object> file : imageFiles(item, name -> Optional.ofNullable(records.get(name)))) {
                // Bound cold-cache work and retain any matches already found.
                if (inspected >= 800 || System.nanoTime() >= deadline) {
                    partial = true;
                    break;
                }
                inspected++;
                var match = colorSearch.match(file.get("storedName").toString(), colors);
                if (!match.available()) unavailable++;
                if (match.score() <= 0) continue;
                matchedFiles.add(file);
                if (match.score() > best) {
                    best = match.score();
                    reasons = new ArrayList<>(textReasons);
                    reasons.addAll(match.reasons());
                    // Preview the file that explains this result before its other variants.
                    matchedFiles.remove(file);
                    matchedFiles.add(0, file);
                }
            }
            if (best > 0) results.add(new ColorRanked(item, best, reasons, matchedFiles));
            if (partial) break;
        }
        List<Map<String, Object>> views = results.stream()
                .sorted(Comparator.comparingDouble(ColorRanked::score).reversed())
                .limit(6)
                .map(match -> imageView(match.item(), match.reasons(), match.images()))
                .toList();
        return new ImageSearchResult(views, items.size(), colorNames, unavailable, partial);
    }

    private Map<String, Object> imageView(
            ImageLibraryItem item, List<String> reasons, List<Map<String, Object>> images) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("name", safe(item.getName(), 160));
        view.put("ipName", safe(item.getIpName(), 100));
        view.put("reasons", reasons);
        view.put("images", images);
        return view;
    }

    private record ColorRanked(
            ImageLibraryItem item, double score, List<String> reasons, List<Map<String, Object>> images) {}

    public boolean canSearchImages(AuthSession session) {
        return session != null
                && Set.of("admin", "planner", "designer").contains(PermissionCatalog.normalizeRole(session.role()))
                && permissions.has(session.role(), "page.image_library.view");
    }

    private void requireLibrary(AuthSession session) {
        if (!canSearchImages(session)) throw forbidden();
    }

    private record Ranked(ImageLibraryItem item, List<String> reasons) {}

    private record ImageMatch(Ranked rank, List<Map<String, Object>> images) {}

    private List<String> matches(ImageLibraryItem item, List<String> terms) {
        String text = String.join(
                        " ",
                        safe(item.getName(), 160),
                        safe(item.getIpName(), 100),
                        safe(item.getSubOptionsJson(), 1000),
                        safe(item.getNotes(), 1000))
                .toLowerCase(Locale.ROOT);
        return terms.stream()
                .filter(text::contains)
                .limit(3)
                .map(term -> "名称/IP/标签含“" + term + "”")
                .toList();
    }

    static List<String> terms(String query) {
        String value = safe(query, 2000)
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "我想要|我需要|我想找|想要|想找|需要|能不能|可不可以|有没有|是否有|给我|我要|请给|帮忙|帮我|请帮|找一些|找几张|找一张|找几个|找一个|一些|几张|一张|几款|看看|看一下|提供|挑选|选择|色调|配色|搭配|图案|根据这张|按这张|这张|找一下|找相似|相似|参考图|图档库|图库|图档|图片|素材|推荐|查找|找图|在我的|我的|里面|筛选|展示|出来|类型|相关",
                        " ")
                .replaceAll("\\b(?:please|find|show|me|some|artwork|images?|pictures?|assets?)\\b", " ")
                .replaceAll("(?<=\\p{IsHan})(?=[a-z0-9])|(?<=[a-z0-9])(?=\\p{IsHan})", " ")
                .replaceAll("\\bhello\\s+kitty\\b", "hellokitty");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : value.split("[^\\p{L}\\p{N}]+")) {
            if (term.length() < 2
                    || Set.of("帮我", "图档", "找图", "相似", "推荐", "素材", "图片", "查找", "找一下")
                            .contains(term)) continue;
            if (term.matches("[\\p{IsHan}]+") && term.length() > 2) {
                for (int i = 0; i + 1 < term.length() && terms.size() < 60; i++) terms.add(term.substring(i, i + 2));
            } else terms.add(term);
            if (terms.size() >= 60) break;
        }
        if (terms.contains("kt") || terms.contains("hellokitty")) {
            terms.add("kt");
            terms.add("hellokitty");
            terms.add("hello kitty");
        }
        return terms.stream()
                .filter(term -> !Set.of(
                                "帮我", "我找", "找图", "图档", "相似", "似图", "参考", "图片", "素材", "推荐", "查找", "找一", "一下", "按这",
                                "张图", "这个", "当前", "风格", "类型", "色系", "系的", "相关")
                        .contains(term))
                .limit(60)
                .toList();
    }

    private List<Map<String, Object>> imageFiles(ImageLibraryItem item) {
        return imageFiles(item, files::findByStoredName);
    }

    private List<Map<String, Object>> imageFiles(
            ImageLibraryItem item, java.util.function.Function<String, Optional<FileRecord>> lookup) {
        String raw = item.getImagesJson();
        try {
            List<Map<String, Object>> values = JSON.readValue(raw == null ? "[]" : raw, new TypeReference<>() {});
            return values.stream()
                    .filter(Objects::nonNull)
                    .limit(3)
                    .filter(file -> {
                        String name = String.valueOf(file.getOrDefault("storedName", ""));
                        return name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}")
                                && !name.contains("..")
                                && lookup.apply(name)
                                        .filter(record -> "image_library".equals(record.getTargetType())
                                                && (record.getTargetId() == null
                                                        || Objects.equals(item.getId(), record.getTargetId())))
                                        .isPresent();
                    })
                    .map(file -> Map.<String, Object>of(
                            "storedName",
                            file.get("storedName"),
                            "name",
                            safe(String.valueOf(file.getOrDefault("name", "图档文件")), 200)))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static String safe(String value, int max) {
        return value == null ? "" : SecurityUtil.sanitizeText(value, max);
    }

    static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法查看该业务内容");
    }
}
