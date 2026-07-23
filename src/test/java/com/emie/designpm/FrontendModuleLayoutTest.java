package com.emie.designpm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendModuleLayoutTest {

    private static final List<String> MODULES = List.of(
            "core-runtime.js",
            "event-runtime.js",
            "core-auth.js",
            "core-identity.js",
            "core-shell.js",
            "core-ui.js",
            "core.js",
            "dashboard-projects.js",
            "dashboard-home.js",
            "dashboard-lists.js",
            "dashboard-scoring.js",
            "dashboard-designer.js",
            "dashboard.js",
            "project-uploads.js",
            "project-form.js",
            "project-detail.js",
            "project-tasks.js",
            "project-sharing.js",
            "projects.js",
            "admin-shell.js",
            "admin-users.js",
            "admin-roles.js",
            "admin-catalog.js",
            "admin-org.js",
            "admin-scoring.js",
            "admin-audit.js",
            "admin-storage.js",
            "admin-workload.js",
            "admin.js",
            "files.js",
            "bootstrap.js");

    private static final List<String> REGISTERED_MODULES = List.of(
            "coreRuntime",
            "eventRuntime",
            "coreAuth",
            "coreIdentity",
            "coreShell",
            "coreUi",
            "core",
            "dashboardProjects",
            "dashboardHome",
            "dashboardLists",
            "dashboardScoring",
            "dashboardDesigner",
            "dashboard",
            "projectUploads",
            "projectForm",
            "projectDetail",
            "projectTasks",
            "projectSharing",
            "projects",
            "adminShell",
            "adminUsers",
            "adminRoles",
            "adminCatalog",
            "adminOrg",
            "adminScoring",
            "adminAudit",
            "adminStorage",
            "adminWorkload",
            "admin",
            "files");

    @Test
    void frontendModulesExistAndLoadInDependencyOrder() throws IOException {
        String html = readResource("/static/index.html");
        String bootstrap = readResource("/static/js/bootstrap.js");
        int previousIndex = -1;

        for (String module : MODULES) {
            assertNotNull(getClass().getResourceAsStream("/static/js/" + module), module + " 必须存在");
            if (module.equals("bootstrap.js")) continue;
            int currentIndex = bootstrap.indexOf("./" + module + "?v=147");
            if (module.equals("dashboard-designer.js")) {
                currentIndex = bootstrap.indexOf("./" + module + "?v=174");
            }
            if (module.equals("dashboard-lists.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=179");
            if (module.equals("core-shell.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=172");
            if (module.equals("core-runtime.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=152");
            if (module.equals("dashboard-projects.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=157");
            if (module.equals("dashboard-home.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=160");
            if (module.equals("admin-shell.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=150");
            if (module.equals("dashboard-scoring.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=160");
            if (module.equals("admin-users.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=166");
            if (module.equals("admin-workload.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=148");
            if (module.equals("admin-org.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=165");
            if (module.equals("project-uploads.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=148");
            if (module.equals("project-detail.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=171");
            if (module.equals("project-form.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=179");
            if (module.equals("project-tasks.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=177");
            if (module.equals("projects.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=148");
            assertTrue(currentIndex > previousIndex, module + " 的 ES Module 加载顺序不正确");
            previousIndex = currentIndex;
        }

        assertFalse(html.contains("/js/app.js"), "页面不应继续加载已拆分的 app.js");
        assertTrue(html.contains("<script type=\"module\" src=\"/js/bootstrap.js?v=200\"></script>"),
                "页面应只通过当前版本 ES Module 启动入口加载前端");
        assertFalse(html.matches("(?s).*<script(?![^>]*type=\"module\")[^>]+src=\"/js/.*"),
                "页面不应继续加载经典业务脚本");
        assertTrue(bootstrap.indexOf("./core.js") > bootstrap.indexOf("./core-ui.js"),
                "core.js 核心域门面必须在子模块之后加载");
        assertTrue(bootstrap.indexOf("./dashboard.js") > bootstrap.indexOf("./dashboard-designer.js"),
                "dashboard.js 工作台域门面必须在子模块之后加载");
        assertTrue(bootstrap.indexOf("./projects.js") > bootstrap.indexOf("./project-sharing.js"),
                "projects.js 项目域门面必须在子模块之后加载");
        assertTrue(bootstrap.indexOf("./admin.js") > bootstrap.indexOf("./admin-workload.js"),
                "admin.js 管理域门面必须在子模块之后加载");
        assertTrue(readResource("/static/js/admin-shell.js").contains("系统更新通知"),
                "管理后台应提供明确的系统更新通知入口");
        assertTrue(readResource("/static/js/admin-shell.js").contains("确认发送给全部系统用户"),
                "全员飞书通知发送前必须二次确认");
        assertTrue(readResource("/static/js/admin-shell.js").contains("waitForTemporaryBroadcast(job.jobId)"),
                "全员飞书通知应提交后台任务并轮询最终结果");
    }

    @Test
    void adminUserRoleBadgeClassIsCaseInsensitive() throws IOException {
        String adminUsers = readResource("/static/js/admin-users.js");

        assertTrue(adminUsers.contains("function adminUserRoleClass(role)"));
        assertTrue(adminUsers.contains(".trim().toLowerCase()"),
                "角色徽章 CSS 类名应忽略角色键大小写");
        assertFalse(adminUsers.matches("(?s).*role-\\$\\{(u\\.role|userData\\.role|existingRole)\\}.*"),
                "角色徽章不应直接使用未规范化的角色键");
    }

    @Test
    void adminOrganizationUsesDynamicRoles() throws IOException {
        String adminOrg = readResource("/static/js/admin-org.js");

        assertTrue(adminOrg.contains("apiGet('/admin/roles')"),
                "组织架构应从角色管理动态读取关联角色");
        assertTrue(adminOrg.contains("r.displayName || r.name"),
                "组织架构应显示角色管理中的角色名称");
        assertFalse(adminOrg.contains("const roles = ['sales','planner','designer','supplychain','admin']"),
                "部门新建和编辑不应继续使用固定角色列表");
    }

    @Test
    void listPagesExposeKeywordAndConditionFilters() throws IOException {
        String lists = readResource("/static/js/dashboard-lists.js");
        String designer = readResource("/static/js/dashboard-designer.js");
        String scoring = readResource("/static/js/dashboard-scoring.js");

        assertTrue(lists.contains("id=\"projectCategoryFilter\""));
        assertTrue(lists.contains("id=\"projectMarketFilter\""));
        assertTrue(lists.contains("id=\"taskProjectTypeFilter\""));
        assertTrue(lists.contains("function projectMatchesKeyword(project, query)"));
        assertTrue(lists.contains("/projects/page?${params}"),
                "项目列表默认应使用服务端分页接口，避免进入页面时读取全部项目");
        assertTrue(lists.contains("size: '15'"),
                "全部项目、渠道定制单和公司常规品列表应固定每页 15 条");
        assertTrue(lists.contains("function changeProjectListPage(page)"),
                "项目列表应提供翻页入口");
        assertFalse(lists.contains("state.allOrders = await apiGet(`/projects?${params}`)"),
                "项目筛选不得回退到全量项目接口，应复用服务端分页筛选");
        assertTrue(designer.contains("id=\"designerTaskStatusFilter\""));
        assertTrue(designer.contains("id=\"designerTaskTypeFilter\""));
        assertTrue(designer.contains("function resetDesignerTaskFilters()"));
        assertTrue(designer.contains("class=\"subtask-project-inline\""),
                "子任务标题后应内联展示所属项目名称");
        assertTrue(designer.contains("（所属项目：${escHtml(t.projectName || '未命名项目')}）"),
                "所属项目名称应紧跟在子任务名称之后");
        assertTrue(scoring.contains("id=\"scoringTypeFilter\""));
        assertTrue(scoring.contains("id=\"scoringStageFilter\""));
        assertTrue(scoring.contains("id=\"scoringPlannerFilter\""),
                "评分页面应提供产品企划筛选卡");
        assertTrue(scoring.contains("EMIE.state.users?.planner || []"),
                "产品企划筛选应列出系统内全部产品企划，不应只列当前结果中的人员");
        assertTrue(scoring.contains("t.plannerId === plannerId"),
                "产品企划筛选应按稳定的用户 ID 匹配");
        assertTrue(scoring.contains("if (a.isPending !== b.isPending) return a.isPending ? -1 : 1;"),
                "评分列表应始终把待评分任务排在已评分任务之前");
        assertTrue(scoring.indexOf("a.isPending !== b.isPending")
                        < scoring.indexOf("const dateCompare"),
                "评分列表必须先按待评分状态分组，再在组内按时间排序");
        assertTrue(scoring.contains("reviewStage === stage"));
    }

    @Test
    void addSubTaskButtonRequiresProjectOwnershipOrAdminRole() throws IOException {
        String detail = readResource("/static/js/project-detail.js");

        assertTrue(detail.contains("EMIE.state.currentRole === 'admin'"),
                "管理员应可以管理项目子任务");
        assertTrue(detail.contains("detail.plannerId === getCurrentUserId()"),
                "企划只有作为当前项目负责人时才应看到添加子任务按钮");
        assertTrue(detail.contains("canManageSubTasks &&"),
                "添加子任务按钮必须复用负责人权限判断");
    }

    @Test
    void navigationBadgesUseOneAuthoritativeRefreshPath() throws IOException {
        String projects = readResource("/static/js/dashboard-projects.js");
        String home = readResource("/static/js/dashboard-home.js");

        assertTrue(projects.contains("async function refreshNavigationBadges()"));
        assertTrue(projects.contains("apiGet('/projects/badge-stats')"));
        assertFalse(projects.contains("badgeStats.totalCount || 0"),
                "缺失字段不能被静默写成 0 覆盖导航统计");
        assertTrue(home.contains("return refreshNavigationBadges();"),
                "历史工作台徽章入口必须转发到统一统计源");
    }

    @Test
    void mutableStateAndEventsUseModuleNamespaces() throws IOException {
        String coreRuntime = readResource("/static/js/core-runtime.js");
        String allModules = String.join("\n", MODULES.stream().map(module -> {
            try {
                return readResource("/static/js/" + module);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).toList());

        assertTrue(coreRuntime.contains("window.EMIE"));
        assertTrue(coreRuntime.contains("EMIE.state"));
        assertTrue(coreRuntime.contains("EMIE.projectState"));
        assertTrue(coreRuntime.contains("EMIE.adminState"));
        assertTrue(coreRuntime.contains("EMIE.fileState"));
        assertFalse(allModules.matches("(?s).*(let|var)\\s+(AUTH_USER|ORIGINAL_USER|USERS|APP_CACHE|currentAdminTab|_filePreviewSequence)\\b.*"),
                "跨模块可变状态不应重新声明为散落的全局变量");
        assertFalse(allModules.matches("(?s).*window\\._[A-Za-z0-9_$]+.*"),
                "业务状态不应挂载为 window._xxx 全局变量");
        assertFalse(allModules.matches("(?s).*\\s(onclick|onchange|oninput|onsubmit)=.*"),
                "前端模板不应保留 HTML 内联事件属性");
        assertTrue(allModules.contains("data-emie-onclick"), "动态交互应使用统一声明式事件入口");
        for (String module : REGISTERED_MODULES) {
            assertTrue(allModules.contains("EMIE.registerModule('" + module + "'"),
                    module + " 必须显式注册公共接口");
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path + " 必须存在");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
