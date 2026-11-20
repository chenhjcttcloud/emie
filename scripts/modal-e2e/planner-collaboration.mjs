import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '企划协作模拟检查仅允许本机地址');
const repository = fileURLToPath(new URL('../../', import.meta.url));
const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const browser = await chromium.launch({ headless: true, executablePath: chrome });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [], mutations = [], dashboardRequests = [];
let scopeFailure = false, profileFailure = false, collaborationFailure = false;
let deferPlannerTasks = false, pendingPlannerTasks;
const planner = (id, userId, name) => ({ id, userId, name, role: 'planner', status: 'active', projectDataScope: 'inherit' });
const owner = planner(1, 'planner-owner', '负责人企划');
const collaborator = planner(2, 'planner-collaborator', '协作企划甲');
const another = planner(3, 'planner-another', '协作企划乙');
const planners = [owner, collaborator, another];
const users = { planner: planners, designer: [], sales: [], supplychain: [], promotion: [], admin: [] };
const task = (id, publisherId, status = 'pending') => ({
  id, name: '协作任务 ' + id, publisherId, publisherName: publisherId, status,
  assigneeRole: 'designer', designerId: 'designer-1', designerName: '模拟设计师',
  workflowStage: 'design_review', plannedDate: '2026-12-20', deliveryVersions: [], rejectionRecords: [],
  referenceImagesJson: '[]', attachmentsJson: '[]',
});
let project = {
  id: 901, type: 'regular', status: 'in_progress', productName: '协作权限模拟项目',
  plannerId: owner.userId, plannerName: owner.name, collaborators: [], deadline: '2026-12-20',
  statusLabel: '进行中', statusCls: 'badge-progress', logs: [],
  tasks: [task(11, collaborator.userId), task(12, owner.userId), task(13, collaborator.userId, 'accepted'),
    task(14, collaborator.userId, 'delivered'), task(15, owner.userId, 'completed'),
    task(16, owner.userId, 'delivered'), task(17, collaborator.userId, 'submitted_for_review'),
    task(18, collaborator.userId, 'planner_approved'),
    { ...task(19, collaborator.userId, 'rejected'), activeRejectionCycleId: 191, activeRejectionRole: 'planner' },
    { ...task(20, owner.userId, 'rejected'), activeRejectionCycleId: 201, activeRejectionRole: 'planner' },
    task(21, collaborator.userId, 'completed'), task(22, another.userId, 'delivered'), task(23, '', 'delivered')],
};
const permissions = ['page.dashboard.view', 'page.projects.view', 'page.admin.view', 'admin.user.manage',
  'project.view', 'project.detail.view', 'subtask.create', 'subtask.edit', 'subtask.delete',
  'subtask.review.first.approve', 'subtask.review.first.reject'];
page.on('pageerror', error => errors.push(error.message));

// Every API is simulated, including successful writes and rejected changes.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  const respond = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) });
  if (request.method() !== 'GET') mutations.push({ path: url.pathname, body: request.postDataJSON() });
  if (url.pathname === '/api/users') return respond(users);
  if (url.pathname === '/api/admin/users/page') return respond({ items: planners, page: 0, size: 30, total: planners.length, totalPages: 1 });
  if (url.pathname === '/api/admin/roles') return respond([{ name: 'planner', displayName: '产品企划' }]);
  if (url.pathname === '/api/auth/permissions') return respond({ permissions, scopes: { 'project.view': ['own'] }, permissionVersion: '1:1' });
  if (url.pathname === '/api/admin/users/2/project-scope') {
    if (scopeFailure) return respond({ error: '模拟范围保存被拒绝' }, 403);
    const body = request.postDataJSON();
    collaborator.projectDataScope = body.scope;
    return respond({ userId: collaborator.userId, projectDataScope: body.scope, permissionVersion: '1:2' });
  }
  if (url.pathname === '/api/admin/users/2') {
    if (profileFailure) return respond({ error: '模拟用户资料保存失败' }, 400);
    Object.assign(collaborator, request.postDataJSON());
    return respond(collaborator);
  }
  if (url.pathname === '/api/projects/901/collaborators') {
    if (collaborationFailure) return respond({ error: '模拟协作保存被拒绝' }, 403);
    project.collaborators = request.postDataJSON().userIds.map(id => planners.find(user => user.userId === id))
      .map(user => ({ userId: user.userId, name: user.name }));
    return respond(project);
  }
  if (url.pathname === '/api/projects/901') return respond(project);
  if (url.pathname === '/api/dashboard/full') {
    dashboardRequests.push(url.searchParams.get('scope'));
    return respond({ orders: [], stats: { totalProjects: 0, channelProjects: 0, regularProjects: 0, inProgress: 0, allTasks: 0, pendingTasks: 0, approvedTasks: 0 }, departments: [] });
  }
  if (url.pathname === '/api/dashboard/role-status') return respond({});
  if (url.pathname === '/api/projects/department-subtasks' && deferPlannerTasks) {
    pendingPlannerTasks = route;
    return;
  }
  if (['/api/projects/my-subtasks', '/api/projects/department-subtasks', '/api/design-requirements/dashboard', '/api/points/rules'].includes(url.pathname)) return respond([]);
  return respond({});
});
for (const [pattern, directory, contentType] of [['**/js/*.js*', 'js', 'text/javascript'], ['**/css/*.css*', 'css', 'text/css']]) {
  await page.route(pattern, route => route.fulfill({ contentType, path: path.join(repository, 'src/main/resources/static', directory, path.basename(new URL(route.request().url()).pathname)) }));
}
await page.route(baseURL + '/', route => route.fulfill({ contentType: 'text/html', path: path.join(repository, 'src/main/resources/static/index.html') }));

const action = name => page.locator(`[data-emie-action="click:${name}"]`);
const taskAction = (name, taskId) => page.locator(`[data-emie-action="click:${name}"][data-task-id="${taskId}"]`);
async function rerenderProject() {
  await page.evaluate(() => {
    const e = window.EMIE;
    document.querySelector('#projectDetailModal .modal-body').innerHTML = e.actions.renderProjectDetailContent(e.projectState.currentProjectDetail);
  });
}
async function identity(user, role = 'planner') {
  await page.evaluate(({ user, role, users, permissions }) => {
    const e = window.EMIE;
    document.querySelectorAll('.modal-overlay').forEach(modal => modal.remove());
    e.state.currentRole = role; e.state.currentUserId = user.userId;
    e.state.authUser = { ...user, role }; e.state.users = users; e.state.permissions = permissions;
    e.state.currentView = 'fixture'; e.state.cache = {};
    e.state.renderId = (e.state.renderId || 0) + 1;
    e.state.permissionScopes = { 'project.view': ['own'], 'project.detail.view': ['own'], 'subtask.view': ['own'] };
    e.actions.clearSWRCache();
  }, { user, role, users, permissions });
}
async function showProject(user) {
  await identity(user);
  await page.evaluate(() => window.EMIE.actions.openProjectDetail(901));
  await page.locator('#projectDetailModal').waitFor();
}
async function reopenUser() {
  await page.evaluate(async () => {
    const e = window.EMIE;
    const result = await e.actions.apiGet('/admin/users/page');
    e.state.adminUsers = result.items;
    e.actions.openEditUserModal(result.items.find(user => user.id === 2));
  });
}
const scopePath = '/api/admin/users/2/project-scope';
const profilePath = '/api/admin/users/2';

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.loadViewModules);
  await page.evaluate(async ({ users, collaborator }) => {
    const e = window.EMIE;
    await e.loadViewModules('admin');
    e.state.adminUsers = [collaborator];
    e.state.users = users;
    e.actions.showSystemAlert = async () => {};
    e.actions.showSystemConfirm = async () => true;
    e.actions.showAdminToast = () => {};
    e.actions.renderSidebar = () => {};
    e.actions.renderRoleSwitcher = async () => {};
    e.actions.renderAdminContent = async () => {};
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
  }, { users, collaborator });

  await identity({ userId: 'admin-ui', name: '模拟管理员' }, 'admin');
  await page.evaluate(user => window.EMIE.actions.openEditUserModal(user), collaborator);
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'inherit');
  await page.locator('#editUserName').fill('未保存的资料修改');
  await page.locator('#editUserProjectScope').selectOption('own');
  await action('users-save-project-scope').click();
  assert.match(await page.locator('#editUserProjectScopeStatus').innerText(), /填写.*原因/);
  assert.equal(mutations.length, 0);
  await page.locator('#editUserProjectScopeReason').fill('送审人员使用个人视角');
  await action('users-save-project-scope').click();
  await page.waitForFunction(() => document.getElementById('editUserProjectScopeStatus').textContent === '项目可见范围已保存');
  assert.equal(await page.locator('#editUserName').inputValue(), '未保存的资料修改');
  assert.equal(mutations.length, 1);
  assert.equal(mutations[0].path, scopePath);
  // The separate scope save clears its reason; footer save must still accept the confirmed scope.
  await page.locator('#editUserName').fill(collaborator.name);
  await action('users-submit-edit').click();
  await page.locator('#editUserModal').waitFor({ state: 'detached' });
  assert.deepEqual(mutations.map(write => write.path), [scopePath, profilePath], '已单独保存的范围不应重复提交或要求再次填写原因');
  await reopenUser();
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'own');
  scopeFailure = true;
  await page.locator('#editUserProjectScope').selectOption('role_team');
  await page.locator('#editUserProjectScopeReason').fill('测试错误保留');
  await action('users-save-project-scope').click();
  await page.waitForFunction(() => document.getElementById('editUserProjectScopeStatus').textContent.includes('模拟范围保存被拒绝'));
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'role_team');
  assert.equal(await page.locator('#editUserProjectScopeReason').inputValue(), '测试错误保留');

  // A rejected scope must prevent footer save from reporting profile success and closing the dialog.
  const beforeRejectedFooter = mutations.length;
  await action('users-submit-edit').click();
  await page.waitForFunction(() => !document.querySelector('[data-emie-action="click:users-submit-edit"]').disabled);
  assert.equal(await page.locator('#editUserModal').count(), 1);
  assert.deepEqual(mutations.slice(beforeRejectedFooter).map(write => write.path), [scopePath]);
  assert.match(await page.locator('#editUserModal').innerText(), /模拟范围保存被拒绝/);
  assert.equal(await page.locator('#editUserProjectScopeReason').inputValue(), '测试错误保留');

  // A dirty scope without its audit reason cannot be silently discarded by footer save.
  scopeFailure = false;
  await page.locator('#editUserProjectScopeReason').fill('');
  const beforeMissingReason = mutations.length;
  await action('users-submit-edit').click();
  await page.waitForFunction(() => !document.querySelector('[data-emie-action="click:users-submit-edit"]').disabled);
  assert.equal(await page.locator('#editUserModal').count(), 1);
  assert.match(await page.locator('#editUserModal').innerText(), /填写.*原因/);
  assert.equal(mutations.length, beforeMissingReason, '缺少范围变更原因时不得提交资料或范围');

  // The prominent footer button saves both changed scope and profile, and survives a fresh read.
  await page.locator('#editUserProjectScopeReason').fill('通过统一保存入口扩大项目范围');
  const beforeCombinedSave = mutations.length;
  await action('users-submit-edit').click();
  await page.locator('#editUserModal').waitFor({ state: 'detached' });
  assert.deepEqual(mutations.slice(beforeCombinedSave).map(write => write.path), [scopePath, profilePath]);
  assert.deepEqual(mutations[beforeCombinedSave].body, { scope: 'role_team', reason: '通过统一保存入口扩大项目范围' });
  assert.equal(collaborator.projectDataScope, 'role_team');
  await reopenUser();
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'role_team');

  // If profile persistence fails after a confirmed scope write, retry must not duplicate that audit.
  profileFailure = true;
  await page.locator('#editUserProjectScope').selectOption('own');
  await page.locator('#editUserProjectScopeReason').fill('资料重试时保留已保存的个人范围');
  const beforeProfileFailure = mutations.length;
  await action('users-submit-edit').click();
  await page.locator('#editUserError').waitFor({ state: 'visible' });
  assert.match(await page.locator('#editUserError').innerText(), /模拟用户资料保存失败/);
  assert.deepEqual(mutations.slice(beforeProfileFailure).map(write => write.path), [scopePath, profilePath]);
  assert.equal(await page.locator('#editUserProjectScope').getAttribute('data-saved-scope'), 'own');
  assert.equal(collaborator.projectDataScope, 'own');
  profileFailure = false;
  const beforeProfileRetry = mutations.length;
  await action('users-submit-edit').click();
  await page.locator('#editUserModal').waitFor({ state: 'detached' });
  assert.deepEqual(mutations.slice(beforeProfileRetry).map(write => write.path), [profilePath], '资料重试不得重复范围变更审计');
  await reopenUser();
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'own');
  await action('users-close-edit').first().click();
  await page.locator('#editUserModal').waitFor({ state: 'detached' });
  await page.evaluate(() => window.EMIE.actions.openEditUserModal({ id: 4, userId: 'designer', name: '设计师', role: 'designer', status: 'active' }));
  assert.equal(await page.locator('#editUserProjectScope').count(), 0);

  await showProject(owner);
  await action('detail-collaborators-edit').click();
  await page.locator('#projectCollaboratorsModal').waitFor();
  assert.equal(await page.locator('input[name="projectCollaborator"]').count(), 2);
  await page.locator(`input[name="projectCollaborator"][value="${collaborator.userId}"]`).check();
  await action('detail-collaborators-save').click();
  await page.locator('#projectCollaboratorsModal').waitFor({ state: 'detached' });
  assert.match(await page.locator('#projectDetailModal').innerText(), /协作企划甲/);
  assert.deepEqual(project.collaborators.map(user => user.userId), [collaborator.userId]);

  await showProject(collaborator);
  assert.equal(await action('detail-subtask-add').count(), 1);
  assert.equal(await action('detail-task-edit').count(), 2);
  assert.equal(await action('detail-task-delete').count(), 1);
  assert.equal(await action('detail-task-cancel-accept').count(), 1);
  assert.equal(await page.locator('[data-emie-action="click:detail-task-edit"][data-task-id="12"]').count(), 0);
  for (const name of ['detail-task-approve', 'detail-task-reject']) {
    assert.equal(await action(name).count(), 3, '协作企划仅可审核自己发布且等待审核的子任务');
    for (const taskId of [14, 17, 18]) assert.equal(await taskAction(name, taskId).count(), 1);
    for (const taskId of [11, 12, 13, 15, 16, 19, 20, 21, 22, 23]) assert.equal(await taskAction(name, taskId).count(), 0);
  }
  assert.equal(await taskAction('detail-task-cancel-reject', 19).count(), 1);
  assert.equal(await taskAction('detail-task-cancel-reject', 20).count(), 0);
  assert.match(await page.locator('#projectDetailModal').innerText(), /协作企划可创建、管理并审核自己发布的子任务/);
  for (const name of ['detail-task-change-bonus', 'detail-collaborators-edit', 'detail-project-pause', 'detail-project-terminate']) {
    assert.equal(await action(name).count(), 0, name + ' must stay with owner');
  }
  await taskAction('detail-task-approve', 14).click();
  await page.locator('#taskApproveModal').waitFor();
  assert.match(await page.locator('#taskApproveModal').innerText(), /企划确认.*协作任务 14/);
  await action('task-approve-close').click();
  await page.locator('#taskApproveModal').waitFor({ state: 'detached' });
  await taskAction('detail-task-reject', 14).click();
  await page.locator('#taskRejectModal').waitFor();
  await page.waitForFunction(() => document.getElementById('rejectDeadline').dataset.datePickerReady === '1');
  await action('task-reject-close').click();
  await page.locator('#taskRejectModal').waitFor({ state: 'detached' });
  // Each review permission is independent; rejection cancellation uses the reject permission.
  await page.evaluate(() => {
    window.EMIE.state.permissions = window.EMIE.state.permissions.filter(permission => permission !== 'subtask.review.first.approve');
  });
  await rerenderProject();
  assert.equal(await action('detail-task-approve').count(), 0);
  assert.equal(await action('detail-task-reject').count(), 3);
  await page.evaluate(permissions => {
    window.EMIE.state.permissions = permissions.filter(permission => permission !== 'subtask.review.first.reject');
  }, permissions);
  await rerenderProject();
  assert.equal(await action('detail-task-approve').count(), 3);
  assert.equal(await action('detail-task-reject').count(), 0);
  assert.equal(await action('detail-task-cancel-reject').count(), 0);
  await page.evaluate(permissions => { window.EMIE.state.permissions = permissions; }, permissions);
  for (const status of ['paused', 'terminated', 'pending_terminate']) {
    await page.evaluate(status => { window.EMIE.projectState.currentProjectDetail.status = status; }, status);
    await rerenderProject();
    for (const name of ['detail-task-approve', 'detail-task-reject', 'detail-task-cancel-reject']) {
      assert.equal(await action(name).count(), 0, status + ' 项目不可审核');
    }
  }
  await page.evaluate(() => {
    const detail = window.EMIE.projectState.currentProjectDetail;
    detail.status = 'in_progress';
    detail.collaborators = [];
  });
  await rerenderProject();
  for (const name of ['detail-task-approve', 'detail-task-reject', 'detail-task-cancel-reject', 'detail-subtask-add']) {
    assert.equal(await action(name).count(), 0, '移除协作关系后，即使仍是任务发布人也不可继续审核');
  }
  await showProject(collaborator);
  await page.evaluate(() => {
    const e = window.EMIE;
    e.state.permissions = e.state.permissions.filter(permission => permission !== 'subtask.edit');
    document.querySelector('#projectDetailModal .modal-body').innerHTML = e.actions.renderProjectDetailContent(e.projectState.currentProjectDetail);
  });
  assert.equal(await action('detail-task-edit').count(), 0, '角色禁止编辑时隐藏编辑入口');
  assert.equal(await action('detail-task-withdraw').count(), 0);
  assert.equal(await action('detail-task-cancel-accept').count(), 0);
  assert.equal(await action('detail-task-delete').count(), 1, '删除权限独立判断');
  await showProject(owner);
  assert.equal(await action('detail-task-approve').count(), 6, '负责人仍可审核所有发布人的待审核任务');
  assert.equal(await action('detail-task-reject').count(), 6);
  assert.equal(await action('detail-task-cancel-reject').count(), 2);
  assert.equal(await action('detail-task-change-bonus').count(), 2);
  assert.equal(await action('detail-task-edit').count(), 3);
  await action('detail-collaborators-edit').click();
  await page.locator('#projectCollaboratorsModal').waitFor();
  collaborationFailure = true;
  await page.locator(`input[name="projectCollaborator"][value="${another.userId}"]`).check();
  await action('detail-collaborators-save').click();
  await page.locator('#projectCollaboratorsError').waitFor({ state: 'visible' });
  assert.equal(await page.locator(`input[name="projectCollaborator"][value="${another.userId}"]`).isChecked(), true);
  assert.match(await page.locator('#projectCollaboratorsError').innerText(), /模拟协作保存被拒绝/);
  const writesBeforeIdentityChange = mutations.length;
  await page.evaluate(userId => { window.EMIE.state.currentUserId = userId; }, collaborator.userId);
  await action('detail-collaborators-save').click();
  assert.equal(mutations.length, writesBeforeIdentityChange, '身份变化后的旧协作弹窗不得发起保存');

  await showProject(another);
  assert.equal(await action('detail-subtask-add').count(), 0);
  assert.equal(await action('detail-task-edit').count(), 0);
  assert.equal(await action('detail-task-delete').count(), 0);
  for (const name of ['detail-task-approve', 'detail-task-reject', 'detail-task-cancel-reject', 'detail-task-change-bonus']) {
    assert.equal(await action(name).count(), 0, '仅有同角色项目可见权限不得审核，即使任务由本人发布');
  }
  await page.evaluate(async () => {
    const e = window.EMIE;
    e.state.permissionScopes = { 'project.view': ['role_team'] };
    e.state.permissionVersion = '1:1';
    document.querySelectorAll('.modal-overlay').forEach(modal => modal.remove());
    await e.actions.renderDashboard(document.getElementById('mainContent'), 'planner', e.state.currentUserId);
  });
  assert.equal(await page.locator('select[aria-label="工作台项目范围"] option[value="all"]').count(), 1);
  await page.evaluate(async () => {
    const e = window.EMIE;
    await e.actions.changePlannerBoardScope('all');
    await e.actions.renderDashboard(document.getElementById('mainContent'), 'planner', e.state.currentUserId);
  });
  assert.equal(dashboardRequests.at(-1), 'all');
  await page.evaluate(async () => {
    const e = window.EMIE;
    e.state.permissionScopes = { 'project.view': ['own'] };
    e.state.permissionVersion = '1:2';
    await e.actions.renderDashboard(document.getElementById('mainContent'), 'planner', e.state.currentUserId);
  });
  assert.equal(dashboardRequests.at(-1), 'mine');
  assert.equal(await page.locator('select[aria-label="工作台项目范围"]').count(), 0);
  await page.evaluate(async () => {
    const e = window.EMIE;
    e.state.permissionScopes = { 'project.view': ['department'] };
    e.state.permissionVersion = '1:3';
    await e.actions.renderDashboard(document.getElementById('mainContent'), 'planner', e.state.currentUserId);
  });
  assert.equal(await page.locator('select[aria-label="工作台项目范围"] option[value="all"]').innerText(), '本部门企划');

  deferPlannerTasks = true;
  const oldPlannerRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/api/projects/department-subtasks');
  await page.evaluate(async () => {
    const e = window.EMIE;
    e.state.permissionScopes = { 'project.view': ['role_team'] };
    e.state.permissionVersion = 'race:team';
    await e.actions.changePlannerBoardScope('all');
    document.getElementById('mainContent').innerHTML = '<div id="dashboardPlannerTasks"></div>';
    window.pendingPlannerTaskLoad = e.actions.loadDashboardPlannerTasks(e.state.currentUserId);
  });
  await oldPlannerRequest;
  await page.evaluate(() => {
    const e = window.EMIE;
    e.state.permissionScopes = { 'project.view': ['own'] };
    e.state.permissionVersion = 'race:own';
    e.state.renderId++;
    e.dashboardState.designerTaskCache = [{ id: 987, name: '当前个人任务' }];
    document.getElementById('mainContent').innerHTML = '<div id="dashboardPlannerTasks">当前个人任务</div>';
  });
  await pendingPlannerTasks.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 988, name: '过期团队任务', status: 'pending' }]) });
  await page.evaluate(() => window.pendingPlannerTaskLoad);
  assert.equal(await page.evaluate(() => window.EMIE.dashboardState.designerTaskCache[0].id), 987);
  assert.equal(await page.locator('#dashboardPlannerTasks').innerText(), '当前个人任务');
  deferPlannerTasks = false;

  await page.setViewportSize({ width: 390, height: 844 });
  await showProject(owner);
  await action('detail-collaborators-edit').click();
  await page.locator('#projectCollaboratorsModal').waitFor();
  await page.waitForFunction(() => {
    const box = document.querySelector('#projectCollaboratorsModal .modal').getBoundingClientRect();
    return box.x >= 0 && box.right <= innerWidth;
  });
  const dialog = await page.locator('#projectCollaboratorsModal .modal').boundingBox();
  assert.ok(dialog.x >= 0 && dialog.x + dialog.width <= 390);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.deepEqual(errors, []);
  console.log('planner-collaboration: scope independent and combined save/reopen/validation/errors/profile retry, collaborator selection/errors/stale identity, own-publisher review/permissions/status/removal and owner rights, dashboard scope narrowing/cache/stale team responses, department label and mobile passed; external writes=0');
} finally {
  await browser.close();
}
