package com.emie.designpm.util;

import java.util.Arrays;
import java.util.List;

/**
 * 安全工具类：输入过滤、文件校验等
 */
public class SecurityUtil {

    // 允许上传的图片扩展名
    private static final List<String> ALLOWED_IMAGE_EXTS = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");

    // 允许上传的附件扩展名（办公文档 + 图片 + PDF）
    private static final List<String> ALLOWED_ATTACHMENT_EXTS = Arrays.asList(
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "zip", "rar", "7z"
    );

    // 禁止上传的扩展名（高危）
    private static final List<String> BLOCKED_EXTS = Arrays.asList(
        "sql", "sh", "bat", "cmd", "exe", "dll", "so", "jar",
        "war", "php", "asp", "aspx", "jsp", "py", "rb", "pl",
        "vbs", "ps1", "msi", "reg", "scr"
    );

    /**
     * 截断并清理文本（防止 XSS 和超长文本）
     */
    public static String sanitizeText(String input, int maxLen) {
        if (input == null) return null;
        String s = input.trim();
        // 截断长度
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        // 移除 HTML 标签
        s = s.replaceAll("<[^>]*>", "");
        // 移除可能导致 XSS 的危险字符
        s = s.replaceAll("javascript:", "x-javascript:")
             .replaceAll("on\\w+\\s*=", "x-event=");
        return s;
    }

    /**
     * 验证文件名是否安全
     */
    public static boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        // 防止路径穿越
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) return false;
        // 获取扩展名
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        return !BLOCKED_EXTS.contains(ext);
    }

    /**
     * 验证参考图片文件名是否允许
     */
    public static boolean isValidImageFile(String fileName) {
        if (fileName == null || !isValidFileName(fileName)) return false;
        int dot = fileName.lastIndexOf('.');
        String ext = fileName.substring(dot + 1).toLowerCase();
        return ALLOWED_IMAGE_EXTS.contains(ext);
    }

    /**
     * 验证附件文件名是否允许
     */
    public static boolean isValidAttachmentFile(String fileName) {
        if (fileName == null || !isValidFileName(fileName)) return false;
        int dot = fileName.lastIndexOf('.');
        String ext = fileName.substring(dot + 1).toLowerCase();
        return ALLOWED_ATTACHMENT_EXTS.contains(ext);
    }
}
