package com.emie.designpm.assistant;

import com.emie.designpm.admin.service.PermissionCatalog;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.FileRecord;
import com.emie.designpm.recommendation.service.VisualAiSettings;
import com.emie.designpm.recommendation.service.VisualInputReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssistantService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROLES =
            Set.of("admin", "sales", "planner", "designer", "supplychain", "promotion");
    private final AssistantDataService data;
    private final AssistantModelClient client;
    private final AssistantModelSettings settings;
    private final VisualInputReader input;
    private final Semaphore modelSlots = new Semaphore(2);
    private final Map<String, Window> usage = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {}

    public AssistantService(
            AssistantDataService data,
            AssistantModelClient client,
            AssistantModelSettings settings,
            VisualInputReader input) {
        this.data = data;
        this.client = client;
        this.settings = settings;
        this.input = input;
    }

    public Map<String, Object> chat(AssistantRequest request, AuthSession session) {
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!ROLES.contains(PermissionCatalog.normalizeRole(session.role()))) throw AssistantDataService.forbidden();
        request.validate();
        limit(session);
        String message = AssistantDataService.safe(request.message(), 2000);
        Map<String, Object> context = data.context(request.context(), session);
        boolean attachment = request.attachmentStoredName() != null
                && !request.attachmentStoredName().isBlank();
        boolean search = attachment || message.matches("(?s).*(找图|图档|相似|参考图|素材推荐|图片推荐).*$");
        boolean todo = message.matches("(?s).*(待办|待处理|我的任务|我的子任务|今天|本周|截止|优先|先做).*$");
        List<Map<String, Object>> tasks = todo ? data.myTasks(session) : List.of();
        List<Map<String, Object>> sources = new ArrayList<>(AssistantKnowledge.baseSources());
        if (!context.isEmpty()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("title", "当前项目与任务记录");
            source.put("view", "orders");
            source.put("projectId", context.get("projectId"));
            if (context.containsKey("taskId")) source.put("taskId", context.get("taskId"));
            sources.add(source);
        }
        if (todo) sources.add(Map.of("title", "我的子任务 · 当前账号", "view", "tasks"));
        String fallback = AssistantKnowledge.guide(session.role(), message);
        if (todo)
            fallback = "已按当前账号查询本人负责或发布的待处理任务，本次展示最多8项。"
                    + (tasks.isEmpty() ? "当前没有查到可展示的待处理记录；可在我的子任务页进一步核对。" : "可点击任务卡片查看需求和截止日期，优先处理临近截止、待接单或被要求修改的任务。")
                    + "\n\n" + fallback;
        if (!context.isEmpty())
            fallback = "当前项目：" + context.get("name") + "；状态：" + AssistantKnowledge.status(context.get("status"))
                    + "；总流程：" + AssistantKnowledge.stage(context.get("stage")) + "（"
                    + AssistantKnowledge.workflowStatus(context.get("stageStatus")) + "）。"
                    + (context.containsKey("taskStatus")
                            ? "当前子任务状态：" + AssistantKnowledge.status(context.get("taskStatus")) + "。"
                            : "")
                    + "\n"
                    + AssistantKnowledge.nextStep(context) + "\n\n" + fallback;
        String serviceMessage = "";
        String mode = "guide";
        String answer = fallback;
        List<Map<String, Object>> images = List.of();
        VisualAiSettings.RuntimeConfig config = null;
        try {
            config = settings.current();
        } catch (Exception ignored) {
            serviceMessage = "AI配置暂不可用，当前使用系统指引或文字检索";
        }
        if (search) {
            // Access/ownership checks must happen before any optional external model call.
            FileRecord file = attachment ? data.ownedAttachment(request.attachmentStoredName(), session) : null;
            boolean contextualSearch = !attachment && !context.isEmpty() && message.matches("(?s).*(当前|这个|本任务|本项目).*$");
            String query = contextualSearch
                    ? String.join(
                            " ",
                            String.valueOf(context.getOrDefault("ipName", "")),
                            String.valueOf(context.getOrDefault("ipSubOptions", "")),
                            String.valueOf(context.getOrDefault("taskName", "")),
                            String.valueOf(context.getOrDefault("requirements", "")),
                            String.valueOf(context.getOrDefault("taskDetails", "")),
                            message)
                    : message;
            boolean described = false;
            if (file != null && config != null && !config.visionSupported) {
                serviceMessage = "当前模型仅支持文字，素材内容未分析；可切换支持图片的模型，当前仅按你输入的文字查找";
            } else if (file != null && config != null && config.configured()) {
                if (modelSlots.tryAcquire()) {
                    try {
                        query += " " + client.describe(config, input.imageData(file));
                        described = true;
                    } catch (Exception failure) {
                        serviceMessage = publicFailure(failure) + "；素材内容未完成理解，当前仅按你输入的文字查找";
                    } finally {
                        modelSlots.release();
                    }
                } else serviceMessage = "小觅当前繁忙，素材内容未分析，当前仅按你输入的文字查找";
            } else if (file != null) serviceMessage = "智能对话未启用或未配置密钥，素材内容未分析，当前仅按你输入的文字查找";
            images = data.searchImages(query, session);
            mode = "search";
            answer = (described
                            ? "已从本次素材提取关键词，并按图库名称、IP和标签检索。"
                            : contextualSearch ? "已按当前任务的IP和需求文字检索图库。" : "当前按你输入的名称、IP和风格文字检索图库。")
                    + "这不是整库视觉相似度比较。"
                    + (images.isEmpty()
                            ? "没有找到足够相关的真实图档，请补充明确的IP、风格或元素关键词。"
                            : "下面展示最多6个真实图档，推荐理由来自实际名称、IP或标签匹配，请预览确认是否适用。");
            sources.add(Map.of("title", "图档库 · 名称/IP/标签检索", "view", "image-library"));
        } else if (config != null && config.configured()) {
            if (modelSlots.tryAcquire()) {
                try {
                    String prompt = "当前用户角色：" + AssistantKnowledge.roleName(session.role()) + "。\n用户问题：" + message
                            + "\n服务器当前授权查询（仅此范围；时间" + Instant.now() + "）："
                            + JSON.writeValueAsString(Map.of("context", context, "myTasks", tasks))
                            + "\n可依据的系统操作指引：" + fallback;
                    answer = client.reply(config, prompt, request.history());
                    mode = "ai";
                } catch (Exception failure) {
                    serviceMessage = publicFailure(failure);
                } finally {
                    modelSlots.release();
                }
            } else serviceMessage = "小觅当前繁忙，先展示系统操作指引，请稍后重试";
        } else if (serviceMessage.isBlank()) serviceMessage = "智能对话未启用或未配置密钥，当前展示已核对的系统操作指引";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("mode", mode);
        result.put("sources", sources);
        result.put("tasks", tasks);
        result.put("images", images);
        result.put("suggestions", List.of("我是新员工，从哪里开始？", "我今天应该先做什么？", "解释当前项目下一步", "按IP或风格查找图档"));
        result.put("serviceMessage", serviceMessage);
        result.put("modelName", config == null ? "模型配置暂不可用" : config.displayName);
        result.put("modelId", config == null ? "" : config.profileId);
        result.put("provider", config == null ? "" : config.provider);
        result.put("visionSupported", config != null && config.visionSupported);
        return result;
    }

    private void limit(AuthSession session) {
        long minute = Instant.now().getEpochSecond() / 60;
        synchronized (usage) {
            if (usage.size() >= 512)
                usage.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
            String key = session.originalUserId() == null ? session.userId() : session.originalUserId();
            if (!usage.containsKey(key) && usage.size() >= 512)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "小觅当前繁忙，请稍后再试");
            Window window = usage.compute(
                    key,
                    (ignored, old) ->
                            old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
            if (window.count().incrementAndGet() > 15)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "提问较频繁，请稍后再试");
        }
    }

    private String publicFailure(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return failure instanceof AssistantModelClient.ModelFailure known
                ? known.publicMessage()
                : "AI服务或素材处理暂不可用，当前展示系统指引或文字检索结果";
    }
}
