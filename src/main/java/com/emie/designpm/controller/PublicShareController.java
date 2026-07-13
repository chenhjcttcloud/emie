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
            log.error("分享页渲染失败 token={}", token, e);
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
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>请输入访问密码 - EMIE</title>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f3f4f6; display: flex; align-items: center; justify-content: center;
                       min-height: 100vh; }
                .pwd-card { background: #fff; border-radius: 16px; padding: 40px; width: 360px;
                            box-shadow: 0 4px 24px rgba(0,0,0,0.08); text-align: center; }
                .pwd-card h1 { font-size: 18px; color: #1f2937; margin-bottom: 8px; }
                .pwd-card p { font-size: 13px; color: #6b7280; margin-bottom: 24px; }
                .pwd-card input { width: 100%%; padding: 10px 16px; border: 1px solid #d1d5db;
                                  border-radius: 8px; font-size: 14px; text-align: center;
                                  outline: none; margin-bottom: 16px; }
                .pwd-card input:focus { border-color: #3370FF; box-shadow: 0 0 0 3px rgba(51,112,255,0.1); }
                .pwd-card button { width: 100%%; padding: 10px; background: #3370FF; color: #fff;
                                   border: none; border-radius: 8px; font-size: 14px; cursor: pointer; }
                .pwd-card button:hover { background: #2860E0; }
                .pwd-card .error { color: #dc2626; font-size: 13px; margin-top: 12px; display: none; }
            </style>
            </head>
            <body>
            <div class="pwd-card">
                <div style="font-size: 36px; margin-bottom: 16px;">🔒</div>
                <h1>此分享需要密码</h1>
                <p>请输入分享者提供的访问密码</p>
                <form method="POST" action="/share/%s" onsubmit="document.getElementById('pwErr').style.display='none'">
                    <input type="password" name="password" placeholder="请输入密码" required autofocus>
                    <button type="submit">验证</button>
                    <div class="error" id="pwErr" style="%s">%s</div>
                </form>
            </div>
            </body>
            </html>
            """.formatted(token, errorMessage != null && !errorMessage.isBlank() ? "display:block;" : "", esc(errorMessage));
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
            taskRows = "<tr><td colspan='4' style='text-align:center;color:#9ca3af;padding:24px;'>暂无子任务</td></tr>";
        } else {
            taskRows = tasks.stream().map(t -> {
                String name = safe(t, "name");
                String tStatus = safe(t, "statusLabel");
                String designer = safe(t, "designerName");
                String planned = safe(t, "plannedDate");
                return "<tr><td>" + esc(name) + "</td><td><span class=\"tag tag-" + t.getOrDefault("status", "")
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
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f3f4f6; color: #1f2937; line-height: 1.6; }
                .top-bar { background: #fff; border-bottom: 1px solid #e5e7eb; padding: 12px 24px;
                           display: flex; justify-content: space-between; align-items: center; }
                .top-bar .logo { font-size: 14px; font-weight: 600; color: #374151; }
                .top-bar .meta { font-size: 12px; color: #9ca3af; }
                .container { max-width: 800px; margin: 24px auto; padding: 0 16px; }
                .card { background: #fff; border-radius: 12px; padding: 24px; margin-bottom: 16px;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
                .card h2 { font-size: 16px; font-weight: 600; margin-bottom: 16px; color: #111827; }
                .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .info-item label { font-size: 12px; color: #9ca3af; display: block; margin-bottom: 2px; }
                .info-item .value { font-size: 14px; color: #1f2937; }
                .tag { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; }
                .tag-draft { background: #f3f4f6; color: #6b7280; }
                .tag-pending_planner, .tag-pending { background: #fef3c7; color: #92400e; }
                .tag-planner_accepted, .tag-accepted { background: #dbeafe; color: #1e40af; }
                .tag-in_progress { background: #dbeafe; color: #1e40af; }
                .tag-planner_approved, .tag-sales_approved, .tag-admin_approved, .tag-completed, .tag-approved { background: #d1fae5; color: #065f46; }
                .tag-scoring_planner { background: #fef3c7; color: #92400e; }
                .tag-delivered { background: #fef3c7; color: #92400e; }
                .tag-rejected { background: #fee2e2; color: #991b1b; }
                .tag-pending_terminate { background: #fee2e2; color: #991b1b; }
                .tag-paused { background: #f3f4f6; color: #6b7280; }
                .tag-terminated { background: #fee2e2; color: #991b1b; }
                table { width: 100%%; border-collapse: collapse; }
                th { text-align: left; padding: 8px 12px; font-size: 12px; color: #6b7280;
                     border-bottom: 1px solid #e5e7eb; font-weight: 500; }
                td { padding: 10px 12px; font-size: 13px; border-bottom: 1px solid #f3f4f6; }
                .desc-box { background: #f9fafb; border-radius: 8px; padding: 16px;
                            font-size: 13px; color: #374151; line-height: 1.7; white-space: pre-wrap; }
                .footer { text-align: center; padding: 24px; font-size: 12px; color: #9ca3af; }
                @media (max-width: 640px) {
                    .info-grid { grid-template-columns: 1fr; }
                    .container { padding: 0 8px; }
                    .card { padding: 16px; }
                }
            </style>
            </head>
            <body>
            <div class="top-bar">
                <div class="logo">🎨 EMIE 产品管理系统</div>
                <div class="meta">已访问 %s 次</div>
            </div>
            <div class="container">
                <div class="card">
                    <h2 style="margin-bottom:4px;">%s</h2>
                    <p style="color:#6b7280;font-size:13px;margin-bottom:16px;">
                        <span class="tag tag-%s">%s</span>
                        <span style="margin-left:8px;">创建于 %s</span>
                    </p>
                    <div class="info-grid">
                        <div class="info-item"><label>项目类型</label><div class="value">%s</div></div>
                        <div class="info-item"><label>截止日期</label><div class="value">%s</div></div>
                        <div class="info-item"><label>销售</label><div class="value">%s</div></div>
                        <div class="info-item"><label>产品企划</label><div class="value">%s</div></div>
                        <div class="info-item"><label>产品类目</label><div class="value">%s</div></div>
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
                    title, viewCount,
                    esc(projectTitle != null && !projectTitle.isBlank() ? projectTitle : "项目详情"),
                    safe(data, "status"), statusLabel, createdAt.substring(0, Math.min(10, createdAt.length())),
                    typeLabel, deadline, salesName, plannerName,
                    productCategory, priceRange,
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
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f3f4f6; color: #1f2937; }
                .top-bar { background: #fff; border-bottom: 1px solid #e5e7eb; padding: 12px 24px;
                           display: flex; justify-content: space-between; align-items: center; }
                .top-bar .logo { font-size: 14px; font-weight: 600; color: #374151; }
                .container { max-width: 700px; margin: 24px auto; padding: 0 16px; }
                .card { background: #fff; border-radius: 12px; padding: 24px; margin-bottom: 16px;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
                .card h2 { font-size: 16px; font-weight: 600; margin-bottom: 16px; color: #111827; }
                .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .info-item label { font-size: 12px; color: #9ca3af; display: block; margin-bottom: 2px; }
                .info-item .value { font-size: 14px; color: #1f2937; }
                .tag { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; }
                .tag-pending { background: #fef3c7; color: #92400e; }
                .tag-accepted { background: #dbeafe; color: #1e40af; }
                .tag-in_progress { background: #dbeafe; color: #1e40af; }
                .tag-delivered { background: #fef3c7; color: #92400e; }
                .tag-planner_approved, .tag-sales_approved, .tag-admin_approved, .tag-approved, .tag-completed { background: #d1fae5; color: #065f46; }
                .tag-scoring_planner { background: #fef3c7; color: #92400e; }
                .tag-rejected { background: #fee2e2; color: #991b1b; }
                .desc-box { background: #f9fafb; border-radius: 8px; padding: 16px;
                            font-size: 13px; color: #374151; white-space: pre-wrap; }
                .footer { text-align: center; padding: 24px; font-size: 12px; color: #9ca3af; }
                .back-link { display: inline-block; margin-bottom: 16px; color: #3370FF;
                             text-decoration: none; font-size: 13px; }
            </style>
            </head>
            <body>
            <div class="top-bar">
                <div class="logo">🎨 EMIE 产品管理系统</div>
            </div>
            <div class="container">
                <div class="card">
                    <h2 style="margin-bottom:4px;">%s</h2>
                    <p style="margin-bottom:16px;"><span class="tag tag-%s">%s</span></p>
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
                    esc(name), status, statusLabel,
                    esc(designerName), plannedDate,
                    actualDate != null && !actualDate.isBlank() ? actualDate : "尚未完成",
                    esc(deliverables != null && !deliverables.isBlank() ? deliverables : "暂无交付成果")
            );
    }

    private String renderErrorPage(String message) {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "<title>分享链接 - EMIE</title>\n" +
            "<style>\n" +
            "* { margin:0;padding:0;box-sizing:border-box; }\n" +
            "body { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f3f4f6;display:flex;align-items:center;justify-content:center;min-height:100vh; }\n" +
            ".err-card { background:#fff;border-radius:16px;padding:40px;width:400px;box-shadow:0 4px 24px rgba(0,0,0,0.08);text-align:center; }\n" +
            ".err-card h1 { font-size:18px;color:#1f2937;margin-bottom:8px; }\n" +
            ".err-card p { font-size:14px;color:#6b7280; }\n" +
            "</style>\n</head>\n<body>\n" +
            "<div class=\"err-card\">\n" +
            "  <div style=\"font-size:48px;margin-bottom:16px;\">😕</div>\n" +
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
