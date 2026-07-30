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
                currentIndex = bootstrap.indexOf("./" + module + "?v=177");
            }
            if (module.equals("dashboard-lists.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=185");
            if (module.equals("core-shell.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=173");
            if (module.equals("core-runtime.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=155");
            if (module.equals("core-auth.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=151");
            if (module.equals("core-identity.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=151");
            if (module.equals("dashboard-projects.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=159");
            if (module.equals("dashboard-home.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=161");
            if (module.equals("admin-shell.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=151");
            if (module.equals("dashboard-scoring.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=161");
            if (module.equals("admin-users.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=166");
            if (module.equals("admin-roles.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=151");
            if (module.equals("admin-workload.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=148");
            if (module.equals("admin-org.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=165");
            if (module.equals("project-uploads.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=157");
            if (module.equals("project-detail.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=188");
            if (module.equals("project-form.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=183");
            if (module.equals("project-tasks.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=185");
            if (module.equals("projects.js")) currentIndex = bootstrap.indexOf("./" + module + "?v=148");
            assertTrue(currentIndex > previousIndex, module + " 的 ES Module 加载顺序不正确");
            previousIndex = currentIndex;
        }

        assertFalse(html.contains("/js/app.js"), "页面不应继续加载已拆分的 app.js");
        assertTrue(html.contains("<script type=\"module\" src=\"/js/bootstrap.js?v=235\"></script>"),
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
        String coreAuth = readResource("/static/js/core-auth.js");
        String coreShell = readResource("/static/js/core-shell.js");
        String dashboardLists = readResource("/static/js/dashboard-lists.js");
        assertTrue(coreAuth.contains("apiGet('/auth/permissions')"),
                "登录后应加载后端统一能力清单");
        assertTrue(readResource("/static/js/core-identity.js").contains("apiGet('/auth/permissions')"),
                "管理员切换身份后应立即刷新目标角色能力清单");
        assertTrue(coreShell.contains("page.design_requirements.view")
                        && coreShell.contains("canNavigateTo(view)"),
                "导航显示和页面进入应统一读取页面权限");
        assertTrue(dashboardLists.contains("hasPermission('project.channel.create')")
                        && dashboardLists.contains("hasPermission('design_requirement.create')"),
                "新建入口应使用能力清单而不是角色名称硬编码");
        String projectUploads = readResource("/static/js/project-uploads.js");
        assertTrue(projectUploads.contains("const originalBlob = payload.blob")
                        && projectUploads.contains("return { type: originalType, blob: originalBlob }"),
                "快捷键复制 PNG 时应直接写入原图字节，保留原图 DPI");
        assertFalse(projectUploads.contains("const maxPayloadSide = 2400"),
                "快捷键复制不得再缩小并重编码原图");
        assertFalse(projectUploads.contains("document.execCommand('copy')"),
                "快捷键复制失败不得污染随后浏览器右键复制的剪贴板格式");
        assertTrue(projectUploads.contains("new File([payload.blob], payload.fileName")
                        && projectUploads.contains("e.dataTransfer.items?.add(file)")
                        && projectUploads.contains("e.dataTransfer.setData('DownloadURL'"),
                "拖入 PowerPoint/WPS 时应优先提供原始图片文件并保留浏览器降级格式");
        assertTrue(projectUploads.contains("document.addEventListener('pointerover'")
                        && projectUploads.contains("preparePresentationImageDrag(image)"),
                "原图必须在 dragstart 前预取，避免异步操作错过可写拖拽数据窗口");
        assertFalse(projectUploads.contains("const maxSide = 1200"),
                "跨应用拖拽不得再用缩放后的 Canvas 载荷替代原图文件");
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
    void identitySwitcherUsesCurrentRolesAndUsers() throws IOException {
        String identity = readResource("/static/js/core-identity.js");
        String adminRoles = readResource("/static/js/admin-roles.js");
        String appCss = readResource("/static/css/app.css");

        assertTrue(identity.contains("apiGet('/users/roles')"),
                "管理员身份切换器应实时读取角色管理数据");
        assertTrue(identity.contains("apiGet('/users')"),
                "管理员身份切换器应实时读取最新用户数据");
        assertTrue(identity.contains("promotion: '产品推广'"),
                "身份切换器应内置显示产品推广角色");
        assertTrue(identity.contains("configuredRoles"),
                "身份切换器应纳入后续新增的自定义角色");
        assertTrue(identity.contains("seenUserIds"),
                "兼容角色别名时应按用户 ID 去重");
        assertTrue(appCss.contains(".identity-user-role.r-promotion"),
                "产品推广在身份切换器中应显示独立角色徽章");
        assertTrue(adminRoles.contains("await renderRoleSwitcher()"),
                "新增、编辑或删除角色后应立即刷新身份切换器");
        assertTrue(adminRoles.contains("roleChangeReasonInput")
                        && adminRoles.contains("refreshCurrentCapabilities()"),
                "角色权限变更应要求审计原因并刷新当前能力版本");
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
    void addSubTaskButtonRequiresProjectPlannerOwnership() throws IOException {
        String detail = readResource("/static/js/project-detail.js");

        assertFalse(detail.contains("const canManageSubTasks = EMIE.state.currentRole === 'admin'"),
                "管理员不能代替产品企划创建子任务");
        assertTrue(detail.contains("const canManageSubTasks = EMIE.state.currentRole === 'planner'"),
                "添加子任务入口必须限定为产品企划角色");
        assertTrue(detail.contains("detail.plannerId === getCurrentUserId()"),
                "企划只有作为当前项目负责人时才应看到添加子任务按钮");
        assertTrue(detail.contains("canManageSubTasks &&"),
                "添加子任务按钮必须复用负责人权限判断");
        assertTrue(detail.contains("📌 子任务进度"),
                "项目详情应在项目总进度上方展示独立子任务进度");
        assertTrue(detail.contains("design_review") && detail.contains("three_d_review")
                        && detail.contains("sample_review")
                        && detail.contains("promotion") && detail.contains("bulk"),
                "子任务总流程应完整展示六个固定阶段");
        assertTrue(detail.contains("completedCount === stageTasks.length")
                        && detail.contains("(task.workflowStage || 'design') === stage.key"),
                "每个阶段必须根据所属子任务是否全部完成自动计算节点状态");
        assertTrue(detail.contains("bulkStageCompleted && allTasksCompleted"),
                "只有大货节点与全部子任务完成后才可进入项目完结");
        String tasks = readResource("/static/js/project-tasks.js");
        assertTrue(tasks.contains("switchAssigneeType('add', 'promotion'") && tasks.contains("📣 产品推广"),
                "新建子任务负责人类型应包含产品推广");
        assertTrue(detail.contains("第 ${record.attemptNo} 次驳回")
                        && detail.contains("openTaskRejectionRecord")
                        && detail.contains("本次提交内容"),
                "子任务卡片应逐行展示驳回记录，并可查看当轮提交快照");
        assertTrue(detail.contains("openProjectSubTaskDetail(event,${task.id})")
                        && detail.contains("projectSubTaskDetailModal")
                        && detail.contains("修改要求次数"),
                "项目详情中的整张子任务卡片应可打开对应子任务详情");
        assertTrue(detail.contains("detail.tasks.map((t, i) => renderSubTaskCard(detail, t, i))"),
                "项目参与者应在项目详情中看到该项目的完整子任务链");
        assertFalse(detail.contains("EMIE.state.currentRole === 'designer') return !t.assigneeRole")
                        || detail.contains("EMIE.state.currentRole === 'supplychain') return t.assigneeRole"),
                "项目详情不得再按当前执行角色过滤其他角色的子任务");
        String designerTasks = readResource("/static/js/dashboard-designer.js");
        assertTrue(designerTasks.contains("openPublishedSubTaskDetail")
                        && designerTasks.contains("修改要求记录")
                        && designerTasks.contains("当轮提交内容")
                        && designerTasks.contains("record.rejectionAttachmentsJson"),
                "已发布子任务应可从独立详情查看每一轮修改要求、提交快照和附件");
        String projects = readResource("/static/js/dashboard-projects.js");
        String lists = readResource("/static/js/dashboard-lists.js");
        assertTrue(projects.contains("data-emie-onclick=\"${rowOpen}\"")
                        && projects.contains("tabindex=\"0\""),
                "项目列表整行应支持鼠标和键盘打开详情");
        assertTrue(projects.contains("o.statusLabel || fallbackStatus.label")
                        && projects.contains("o.statusCls || fallbackStatus.cls"),
                "项目列表应优先展示接口返回的业务状态，避免设计需求显示内部状态码");
        assertTrue(lists.contains("openDesignRequirementDetail")
                        && lists.contains("/design-requirements/${id}"),
                "设计/送审需求应使用独立详情接口和弹窗");
        assertTrue(lists.contains("deliveryReferenceImagesJson")
                        && lists.contains("deliveryAttachmentsJson")
                        && lists.contains("handleDeliverImages")
                        && lists.contains("handleDeliverAttachments"),
                "设计/送审需求交付应支持参考图和附件");
        assertTrue(detail.contains("🔵 项目总进度"),
                "项目生命周期进度应明确命名为项目总进度");
        assertTrue(detail.indexOf("renderSubTaskProgress(detail)")
                        < detail.indexOf("renderProjectPipeline(detail)"),
                "子任务进度应排列在项目总进度上方");
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
