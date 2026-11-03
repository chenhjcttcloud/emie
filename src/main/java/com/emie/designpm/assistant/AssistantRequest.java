package com.emie.designpm.assistant;

import java.util.List;

public record AssistantRequest(String message, List<History> history, Context context, String attachmentStoredName) {
    public record History(String role, String content) {}

    public record Context(String view, Long projectId, Long taskId) {}

    void validate() {
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new IllegalArgumentException("请输入2000字以内的问题");
        if (history != null
                && (history.size() > 8
                        || history.stream()
                                .anyMatch(item -> item == null
                                        || item.role() == null
                                        || !List.of("user", "assistant").contains(item.role())
                                        || item.content() == null
                                        || item.content().length() > 2000)))
            throw new IllegalArgumentException("对话记录过长或格式无效，请清空对话后重试");
        if (context != null
                && ((context.view() != null && context.view().length() > 60)
                        || context.projectId() != null && context.projectId() <= 0
                        || context.taskId() != null && context.taskId() <= 0))
            throw new IllegalArgumentException("页面上下文无效");
        if (attachmentStoredName != null
                && !attachmentStoredName.isBlank()
                && (!attachmentStoredName.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}")
                        || attachmentStoredName.contains(".."))) throw new IllegalArgumentException("素材文件标识无效");
    }
}
