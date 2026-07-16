package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通知文案和飞书卡片的唯一入口。业务服务只提供事件与上下文，不能自行拼接渠道消息。
 * 第二阶段编排器接入后，所有事件都会复用本服务，确保站内与飞书字段一致。
 */
@Service
public class NotificationTemplateService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SystemConfigRepository configRepository;

    public NotificationTemplateService(SystemConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public record Template(String title, String content, String priority, boolean mandatory,
                           String deepLink, String feishuCardJson) {}

    public Template render(String eventType, Map<String, String> context) {
        String project = value(context, "projectName", "未命名项目");
        String task = value(context, "taskName", "未命名子任务");
        String actor = value(context, "actorName", "系统");
        String deadline = value(context, "deadline", "未设置");
        String reason = value(context, "reason", "无");
        String deliveryCount = value(context, "deliveryCount", "");
        String reviewRole = value(context, "reviewRole", "当前审核人");
        String target = value(context, "targetName", "相关负责人");

        return switch (eventType) {
            case "PROJECT_ASSIGNED" -> create("有新的项目待接单", "“" + project + "”已由" + actor + "指定给你，请及时接单并安排任务。", "high", true, projectLink(context), project, "项目待接单", deadline, actor);
            case "TASK_ASSIGNED", "TASK_REASSIGNED" -> create("有新的子任务待处理", "子任务“" + task + "”已指派给你，所属项目：" + project + "；计划完成：" + deadline + "。", "high", true, taskLink(context), project, "子任务待处理：" + task, deadline, actor);
            case "TASK_ACCEPTED" -> create("子任务已接单", actor + "已接单子任务“" + task + "”。", "normal", false, taskLink(context), project, "子任务已接单：" + task, deadline, actor);
            case "TASK_DELIVERED" -> create("子任务待审核", actor + "已交付子任务“" + task + "”，请查看成果并完成审核。", "high", true, taskLink(context), project, "待审核：" + task, deadline, actor);
            case "TASK_REJECTED" -> create("子任务已驳回", "子任务“" + task + "”被驳回，原因：" + reason + "。请修改后重新交付。", "high", true, taskLink(context), project, "已驳回：" + task, deadline, actor);
            case "TASK_REDELIVERED" -> create("子任务再次交付待审核", actor + "已第" + deliveryCount + "次交付“" + task + "”。上次驳回原因：" + reason + "。", "high", true, taskLink(context), project, "再次交付待审核：" + task, deadline, actor);
            case "REVIEW_PENDING" -> create("有审核待办", "项目“" + project + "”的子任务“" + task + "”等待" + reviewRole + "审核。", "high", true, reviewLink(context), project, "审核待办：" + task, deadline, actor);
            case "REVIEW_APPROVED" -> create("审核已通过", "“" + task + "”已由" + actor + "审核通过。", "normal", false, reviewLink(context), project, "审核通过：" + task, deadline, actor);
            case "REVIEW_REJECTED" -> create("审核已驳回", "“" + task + "”审核未通过，原因：" + reason + "。", "high", true, reviewLink(context), project, "审核驳回：" + task, deadline, actor);
            case "PROJECT_REMINDER", "TASK_REMINDER" -> create("待办催办提醒", actor + "提醒你处理“" + ("PROJECT_REMINDER".equals(eventType) ? project : task) + "”。当前负责人：" + target + "。", "high", true, "PROJECT_REMINDER".equals(eventType) ? projectLink(context) : taskLink(context), project, "催办提醒", deadline, actor);
            case "TASK_DUE_SOON" -> create("子任务即将到期", "子任务“" + task + "”将于" + deadline + "到期，请及时处理。", "high", true, taskLink(context), project, "即将到期：" + task, deadline, actor);
            case "TASK_OVERDUE" -> create("子任务已逾期", "子任务“" + task + "”已超过计划完成时间" + deadline + "，请尽快处理。", "urgent", true, taskLink(context), project, "已逾期：" + task, deadline, actor);
            case "SYSTEM_ALERT" -> create("系统通知告警", value(context, "message", "系统检测到需要处理的异常。"), "urgent", true, "/admin?tab=logs", project, "系统告警", deadline, actor);
            default -> throw new IllegalArgumentException("未定义的通知事件模板: " + eventType);
        };
    }

    private Template create(String title, String content, String priority, boolean mandatory, String deepLink,
                            String project, String subject, String deadline, String actor) {
        return new Template(title, content, priority, mandatory, deepLink,
                card(title, project, subject, deadline, actor, content, deepLink));
    }

    private String card(String title, String project, String subject, String deadline, String actor, String content, String deepLink) {
        ObjectNode card = JSON.createObjectNode();
        card.put("schema", "2.0");
        ObjectNode header = card.putObject("header");
        header.put("template", "blue");
        header.putObject("title").put("tag", "plain_text").put("content", title);
        ArrayNode elements = card.putObject("body").putArray("elements");
        elements.addObject().put("tag", "markdown").put("content", content);
        elements.addObject().put("tag", "markdown").put("content", "**项目**：" + project + "\n**事项**：" + subject + "\n**截止时间**：" + deadline + "\n**触发人**：" + actor);
        String cardLink = toPublicUrl(deepLink);
        if (!cardLink.isBlank()) {
            ObjectNode button = elements.addObject();
            button.put("tag", "button").put("type", "primary");
            button.put("element_id", "view_and_process");
            button.putObject("text").put("tag", "plain_text").put("content", "查看并处理");
            button.putArray("behaviors").addObject().put("type", "open_url").put("default_url", cardLink);
        }
        try {
            return JSON.writeValueAsString(card);
        } catch (Exception e) {
            throw new IllegalStateException("构建飞书通知卡片失败", e);
        }
    }

    private String projectLink(Map<String, String> context) { return value(context, "projectLink", "/"); }
    private String taskLink(Map<String, String> context) { return value(context, "taskLink", projectLink(context)); }
    private String reviewLink(Map<String, String> context) { return value(context, "reviewLink", taskLink(context)); }
    private String value(Map<String, String> context, String key, String fallback) {
        String value = context.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 飞书卡片只接受含 scheme 的绝对 HTTP(S) 链接，内部相对路径不能直接作为按钮目标。 */
    private String toPublicUrl(String deepLink) {
        if (deepLink == null || deepLink.isBlank()) return "";
        if (deepLink.startsWith("https://") || deepLink.startsWith("http://")) return deepLink;
        String baseUrl = configRepository.findByConfigKey("notification.publicBaseUrl")
                .map(SystemConfig::getConfigValue)
                .map(String::trim)
                .orElse("");
        if (!baseUrl.matches("https?://.+")) return "";
        return baseUrl.replaceAll("/+$", "") + (deepLink.startsWith("/") ? deepLink : "/" + deepLink);
    }
}
