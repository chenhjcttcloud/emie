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
let scopeFailure = false, collaborationFailure = false;
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
    task(14, collaborator.userId, 'delivered'), task(15, owner.userId, 'completed')],
};
const permissions = ['page.dashboard.view', 'page.projects.view', 'page.admin.view', 'admin.user.manage',
  'project.view', 'project.detail.view', 'subtask.create', 'subtask.edit', 'subtask.delete'];
page.on('pageerror', error => errors.push(error.message));

// Every API is simulated, including successful writes and rejected changes.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  const respond = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) });
  if (request.method() !== 'GET') mutations.push({ path: url.pathname, body: request.postDataJSON() });
  if (url.pathname === '/api/users') return respond(users);
  if (url.pathname === '/api/auth/permissions') return respond({ permissions, scopes: { 'project.view': ['own'] }, permissionVersion: '1:1' });
  if (url.pathname === '/api/admin/users/2/project-scope') {
    if (scopeFailure) return respond({ error: '模拟范围保存被拒绝' }, 403);
    const body = request.postDataJSON();
    collaborator.projectDataScope = body.scope;
    return respond({ userId: collaborator.userId, projectDataScope: body.scope, permissionVersion: '1:2' });
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
  assert.equal(mutations[0].path, '/api/admin/users/2/project-scope');
  await action('users-close-edit').first().click();
  await page.locator('#editUserModal').waitFor({ state: 'detached' });
  await page.evaluate(() => window.EMIE.actions.openEditUserModal(window.EMIE.state.adminUsers[0]));
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'own');
  scopeFailure = true;
  await page.locator('#editUserProjectScope').selectOption('role_team');
  await page.locator('#editUserProjectScopeReason').fill('测试错误保留');
  await action('users-save-project-scope').click();
  await page.waitForFunction(() => document.getElementById('editUserProjectScopeStatus').textContent.includes('模拟范围保存被拒绝'));
  assert.equal(await page.locator('#editUserProjectScope').inputValue(), 'role_team');
  assert.equal(await page.locator('#editUserProjectScopeReason').inputValue(), '测试错误保留');
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
  for (const name of ['detail-task-approve', 'detail-task-reject', 'detail-task-change-bonus', 'detail-collaborators-edit', 'detail-project-pause', 'detail-project-terminate']) {
    assert.equal(await action(name).count(), 0, name + ' must stay with owner');
  }
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
  assert.equal(await action('detail-task-approve').count(), 1);
  assert.equal(await action('detail-task-change-bonus').count(), 1);
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
  console.log('planner-collaboration: personal scope independent save/reopen/errors, collaborator selection/errors/stale identity, publisher controls and owner review, dashboard scope narrowing/cache/stale team responses, department label and mobile passed; external writes=0');
} finally {
  await browser.close();
}
