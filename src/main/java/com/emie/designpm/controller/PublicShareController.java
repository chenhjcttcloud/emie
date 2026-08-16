package com.emie.designpm.controller;

import com.emie.designpm.service.ShareLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公开分享页面（无需登录）
 * 访问 /share/{token} 直接看到渲染好的 HTML 页面
 */
@RestController
public class PublicShareController {

    private static final Logger log = LoggerFactory.getLogger(PublicShareController.class);

    private final ShareLinkService shareLinkService;

    public PublicShareController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @GetMapping(value = "/share/{token}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String viewShare(@PathVariable String token,
                            @RequestParam(value = "password", required = false) String password,
                            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        // 分享页为自包含 HTML（无外链脚本），禁止内联脚本与外部资源，阻止反射 XSS 落地。
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        try {
            Map<String, Object> data = shareLinkService.resolveShare(token, password);

            // 如果需要密码
            if (data.containsKey("needPassword") && Boolean.TRUE.equals(data.get("needPassword"))) {
                return renderPasswordPage(token, "");
            }

            return renderSharePage(data);
        } catch (IllegalArgumentException e) {
            if ("密码错误".equals(e.getMessage())) {
                return renderPasswordPage(token, e.getMessage());
            }
            return renderErrorPage("分享链接无效或已失效");
        } catch (Exception e) {
            log.error("分享页渲染失败 reason={}", e.getClass().getSimpleName());
            return renderErrorPage("页面加载失败，请重试");
        }
    }

    @PostMapping(value = "/share/{token}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String viewShareWithPassword(@PathVariable String token,
                                        @RequestParam("password") String password,
                                        HttpServletResponse response) {
        return viewShare(token, password, response);
    }

    // ==================== HTML 渲染 ====================

    private String renderPasswordPage(String token, String errorMessage) {
        // 错误提示仅在存在时渲染（避免空错误块占位）；隐藏逻辑由 /js/share.js 事件委托处理。
        String errorHtml = errorMessage != null && !errorMessage.isBlank()
                ? "<div class=\"error\" id=\"pwErr\">" + esc(errorMessage) + "</div>" : "";
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>请输入访问密码 - EMIE</title>
            <link rel="stylesheet" href="/css/share.css?v=1">
            <script src="/js/share.js?v=1" defer></script>
            </head>
            <body class="centered">
            <div class="pwd-card">
                <div class="pwd-emoji">🔒</div>
                <h1>此分享需要密码</h1>
                <p>请输入分享者提供的访问密码</p>
                <form method="POST" action="/share/%s" data-pw-form>
                    <input type="password" name="password" placeholder="请输入密码" required autofocus>
                    <button type="submit">验证</button>
                    %s
                </form>
            </div>
            </body>
            </html>
            """.formatted(esc(token), errorHtml);
    }

    private String renderSharePage(Map<String, Object> data) {
        String type = (String) data.get("type");

        if ("project".equals(type)) {
            return renderProjectPage(data);
        } else if ("sub_task".equals(type)) {
            return renderSubTaskPage(data);
        }
        return renderErrorPage("不支持的分享类型");
    }

    @SuppressWarnings("unchecked")
    private String renderProjectPage(Map<String, Object> data) {
        String title = "项目详情 - EMIE";
        String projectTitle = safe(data, "projectTitle");
        String typeLabel = safe(data, "typeLabel");
        String statusLabel = safe(data, "statusLabel");
        String salesName = safe(data, "salesName");
        String plannerName = safe(data, "plannerName");
        String deadline = safe(data, "deadline");
        String productRequirements = safe(data, "productRequirements");
        String description = safe(data, "description");
        String productCategory = safe(data, "productCategory");
        String priceRange = safe(data, "priceRange");
        String ipName = safe(data, "ipName");
        String createdAt = safe(data, "createdAt");

        Object metaRaw = data.get("_shareMeta");
        String viewCount = "";
        if (metaRaw instanceof Map) {
            viewCount = String.valueOf(((Map<String, Object>) metaRaw).get("viewCount"));
        }

        // 子任务表格
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.getOrDefault("tasks", List.of());
        String taskRows;
        if (tasks.isEmpty()) {
            taskRows = "<tr><td colspan='4' class='empty-row'>暂无子任务</td></tr>";
        } else {
            taskRows = tasks.stream().map(t -> {
                String name = safe(t, "name");
                String tStatus = safe(t, "statusLabel");
                String designer = safe(t, "designerName");
                String planned = safe(t, "plannedDate");
                return "<tr><td>" + esc(name) + "</td><td><span class=\"tag tag-" + esc(String.valueOf(t.getOrDefault("status", "")))
                        + "\">" + esc(tStatus) + "</span></td><td>" + esc(designer) + "</td><td>" + esc(planned) + "</td></tr>";
            }).collect(Collectors.joining());
        }

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>%s</title>
            <link rel="stylesheet" href="/css/share.css?v=1">
            </head>
            <body>
            <div class="top-bar">
                <div class="logo">🎨 EMIE 产品管理系统</div>
                <div class="meta">已访问 %s 次</div>
            </div>
            <div class="container">
                <div class="card">
                    <h2 class="card-title">%s</h2>
                    <p class="card-subtitle">
                        <span class="tag tag-%s">%s</span>
                        <span class="created-at">创建于 %s</span>
                    </p>
                    <div class="info-grid">
                        <div class="info-item"><label>项目类型</label><div class="value">%s</div></div>
                        <div class="info-item"><label>截止日期</label><div class="value">%s</div></div>
                        <div class="info-item"><label>销售</label><div class="value">%s</div></div>
                        <div class="info-item"><label>产品企划</label><div class="value">%s</div></div>
                        <div class="info-item"><label>产品类目</label><div class="value">%s</div></div>
                        <div class="info-item"><label>IP</label><div class="value">%s</div></div>
                        <div class="info-item"><label>参考价格</label><div class="value">%s</div></div>
                    </div>
                </div>
                <div class="card">
                    <h2>产品需求描述</h2>
                    <div class="desc-box">%s</div>
                </div>
                <div class="card">
                    <h2>项目描述</h2>
                    <div class="desc-box">%s</div>
                </div>
                <div class="card">
                    <h2>子任务进度</h2>
                    <table><thead><tr><th>任务名称</th><th>状态</th><th>负责人</th><th>计划时间</th></tr></thead>
                    <tbody>%s</tbody></table>
                </div>
            </div>
            <div class="footer">由 EMIE 产品管理系统生成 · 仅供查看，不可操作</div>
            </body>
            </html>
            """.formatted(
                    esc(title), esc(viewCount),
                    esc(projectTitle != null && !projectTitle.isBlank() ? projectTitle : "项目详情"),
                    esc(safe(data, "status")), esc(statusLabel), esc(createdAt.substring(0, Math.min(10, createdAt.length()))),
                    esc(typeLabel), esc(deadline), esc(salesName), esc(plannerName),
                    esc(productCategory), esc(ipName == null || ipName.isBlank() ? "无IP" : ipName), esc(priceRange),
                    esc(productRequirements), esc(description),
                    taskRows
            );
    }

    private String renderSubTaskPage(Map<String, Object> data) {
        String name = safe(data, "name");
        String statusLabel = safe(data, "statusLabel");
        String status = safe(data, "status");
        String designerName = safe(data, "designerName");
        String plannedDate = safe(data, "plannedDate");
        String actualDate = safe(data, "actualDate");
        String deliverables = safe(data, "deliverables");

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>任务详情 - EMIE</title>
            <link rel="stylesheet" href="/css/share.css?v=1">
            </head>
            <body>
            <div class="top-bar">
                <div class="logo">🎨 EMIE 产品管理系统</div>
            </div>
            <div class="container narrow">
                <div class="card">
                    <h2 class="card-title">%s</h2>
                    <p class="card-subtitle"><span class="tag tag-%s">%s</span></p>
                    <div class="info-grid">
                        <div class="info-item"><label>负责人</label><div class="value">%s</div></div>
                        <div class="info-item"><label>计划日期</label><div class="value">%s</div></div>
                        <div class="info-item"><label>实际完成</label><div class="value">%s</div></div>
                    </div>
                </div>
                <div class="card">
                    <h2>交付成果</h2>
                    <div class="desc-box">%s</div>
                </div>
            </div>
            <div class="footer">由 EMIE 产品管理系统生成 · 仅供查看，不可操作</div>
            </body>
            </html>
            """.formatted(
                    esc(name), esc(status), esc(statusLabel),
                    esc(designerName), esc(plannedDate),
                    esc(actualDate != null && !actualDate.isBlank() ? actualDate : "尚未完成"),
                    esc(deliverables != null && !deliverables.isBlank() ? deliverables : "暂无交付成果")
            );
    }

    private String renderErrorPage(String message) {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "<title>分享链接 - EMIE</title>\n" +
            "<link rel=\"stylesheet\" href=\"/css/share.css?v=1\">\n" +
            "</head>\n<body class=\"centered\">\n" +
            "<div class=\"err-card\">\n" +
            "  <div class=\"err-emoji\">😕</div>\n" +
            "  <h1>" + esc(message) + "</h1>\n" +
            "  <p>请联系分享者获取最新的链接</p>\n" +
            "</div>\n</body>\n</html>";
    }

    private static String safe(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
