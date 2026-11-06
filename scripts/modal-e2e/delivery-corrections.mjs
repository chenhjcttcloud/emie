import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '交付更正模拟回归仅允许本机测试环境');
const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const artifactDirectory = fileURLToPath(new URL('test-results/modal-e2e/', import.meta.url));
await fs.mkdir(artifactDirectory, { recursive: true });
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [], corrections = [], approvals = [], uploads = [], unexpectedMutations = [];
const imageBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5xkAAAAASUVORK5CYII=', 'base64');
page.on('pageerror', error => errors.push(error.message));

const designerId = 'correction-ui-designer';
const deepLinkSession = 'notification-deep-link-session';
const feishuTicket = 'notification-deep-link-ticket';
function image(name) {
  return { name, storedName: name, url: '/api/files/download/' + name, size: imageBytes.length };
}
function version(id, fields = {}) {
  return {
    id, versionNo: id, submissionType: 'initial', submissionOrigin: 'initial', submittedAt: '2026-11-04T10:00:00',
    submittedByName: '模拟设计师', changeSummary: '本轮原交付', deliverables: 'UI模拟 · 本轮原成果',
    referenceImagesJson: JSON.stringify([image('ui-mistaken-initial.png')]), attachmentsJson: '[]',
    pointRuleCode: 'A1', pointRuleDescription: '包装设计', expectedPoints: 10, awardedPoints: null,
    pointStatus: 'pending', confirmedAt: null, confirmedBy: null, confirmedByName: null, awardedAt: null,
    pointRequestId: null, revisionNo: null, changeReason: null, reviewComments: null, ...fields,
  };
}
const originalInitial = version(1);
let task = {
  id: 93101, projectId: 94101, name: 'UI模拟 · 每轮确认前更正成果', projectName: 'UI模拟 · 交付更正项目',
  projectType: 'regular', status: 'delivered', workflowStage: 'design', assigneeRole: 'designer',
  designerId, designerName: '模拟设计师', plannerId: 'correction-ui-planner', plannerName: '模拟企划', publisherName: '模拟企划',
  plannedDate: '2026-11-20', createdAt: '2026-11-01T09:00:00', details: '核对更正当前成果和历史版本。',
  deliverables: originalInitial.deliverables, referenceImagesJson: originalInitial.referenceImagesJson, attachmentsJson: '[]',
  reviewComments: '', rejectionRecords: [], deliveryVersions: [originalInitial], pointRuleCode: 'A1', basePointSnapshot: 10,
  pointSummary: { awardedPoints: 0, basePoints: 0, changePoints: 0, otherPoints: 0, untrackedPoints: 0, hasUntrackedAwards: false },
};
const project = () => ({
  id: task.projectId, name: task.projectName, productName: 'UI模拟包装', type: 'regular', status: 'in_progress',
  plannerId: task.plannerId, plannerName: task.plannerName, statusLabel: '进行中', statusCls: 'badge-progress',
  createdAt: task.createdAt, deadline: task.plannedDate, tasks: [task], logs: [],
});

// Every API call is fulfilled in this browser context. Mutations affect only these in-memory fixtures.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  const fulfill = (payload, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(payload) });
  if (url.pathname === '/api/auth/feishu/config') return fulfill({ enabled: 'true', appId: 'fixture', state: 'fixture-state' });
  if (url.pathname === '/api/auth/feishu/exchange' && request.method() === 'POST') {
    assert.deepEqual(request.postDataJSON(), { ticket: feishuTicket });
    return fulfill({ token: deepLinkSession });
  }
  if (url.pathname === '/api/auth/me' && request.headers()['x-auth-token'] === deepLinkSession) {
    return fulfill({ userId: designerId, name: '模拟设计师', role: 'designer' });
  }
  if (url.pathname === '/api/auth/permissions') {
    return fulfill({ permissions: ['page.dashboard.view', 'project.detail.view', 'subtask.view'] });
  }
  if (url.pathname === '/api/files/upload' && request.method() === 'POST') {
    const name = request.postDataBuffer()?.toString('latin1').match(/filename="([^"]+)"/)?.[1] || 'replacement.png';
    const result = { ...image(name), storedName: `ui-correction-upload-${uploads.length + 1}.png` };
    result.url = '/api/files/download/' + result.storedName;
    uploads.push(result);
    return fulfill(result);
  }
  if (url.pathname === `/api/projects/${task.projectId}/tasks/${task.id}/correct-delivery` && request.method() === 'POST') {
    const body = request.postDataJSON();
    corrections.push(structuredClone(body));
    if (!['delivered', 'submitted_for_review'].includes(task.status) || task.designerId !== designerId) {
      return fulfill({ error: '本轮交付已由企划确认，无法继续更正。' }, 403);
    }
    const current = task.deliveryVersions[0];
    const next = version(Math.max(...task.deliveryVersions.map(value => value.versionNo)) + 1, {
      ...current, id: current.versionNo + 1, versionNo: current.versionNo + 1, submissionType: 'correction',
      submittedAt: `2026-11-04T10:${String(current.versionNo + 1).padStart(2, '0')}:00`,
      changeSummary: body.changeSummary, deliverables: body.deliverables,
      referenceImagesJson: body.referenceImagesJson, attachmentsJson: body.attachmentsJson,
    });
    task = { ...task, deliverables: body.deliverables, referenceImagesJson: body.referenceImagesJson,
      attachmentsJson: body.attachmentsJson, deliveryVersions: [next, ...task.deliveryVersions] };
    return fulfill(project());
  }
  if (url.pathname === `/api/projects/${task.projectId}/tasks/${task.id}/approve` && request.method() === 'POST') {
    const body = request.postDataJSON();
    approvals.push(structuredClone(body));
    const latestId = task.deliveryVersions[0]?.id ?? null;
    if (body.expectedDeliveryVersionId !== latestId) {
      return fulfill({ error: '交付版本已更新，请重新打开确认窗口查看最新成果。' }, 409);
    }
    task = { ...task, status: 'completed', deliveryVersions: task.deliveryVersions.map((value, index) => index === 0
      ? { ...value, confirmedAt: '2026-11-04T12:00:00', confirmedBy: task.plannerId, confirmedByName: task.plannerName }
      : value) };
    return fulfill(project());
  }
  if (request.method() !== 'GET') {
    unexpectedMutations.push(`${request.method()} ${url.pathname}`);
    return fulfill({ error: 'UI模拟禁止未声明的写操作' }, 400);
  }
  if (url.pathname.startsWith('/api/files/')) return route.fulfill({ status: 200, contentType: 'image/png', body: imageBytes });
  if (url.pathname === `/api/projects/${task.projectId}`) {
    if (request.headers()['x-auth-token'] === deepLinkSession) {
      return fulfill({ ...project(), tasks: [{ ...task, id: task.id + 1, name: '非链接目标子任务' }, task] });
    }
    return fulfill(project());
  }
  if (url.pathname === '/api/projects/my-subtasks') return fulfill([task]);
  if (url.pathname === '/api/projects/task-market') return fulfill([]);
  return fulfill({});
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  for (const [pattern, directory, contentType] of [['**/js/*.js*', 'js', 'text/javascript'], ['**/css/*.css*', 'css', 'text/css']]) {
    await page.route(pattern, async route => route.fulfill({ contentType, body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static', directory, path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  }
  await page.route(url => url.origin === new URL(baseURL).origin && url.pathname === '/',
    async route => route.fulfill({ contentType: 'text/html', body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static/index.html'), 'utf8') }));
}

const form = () => page.locator('#taskCorrectDeliveryModal');
const buttonFor = view => page.locator(view === 'project' ? '[data-emie-action="click:detail-task-correct"]' : '[data-emie-action="click:designer-correct"]');
async function showFixture(view = 'dashboard') {
  await page.evaluate(async view => {
    const fixture = window.deliveryCorrectionsFixture;
    fixture.view = view;
    await fixture.refresh();
  }, view);
}
async function replaceImage(view, fileName, summary) {
  await buttonFor(view).click();
  await form().waitFor();
  assert.match(await form().innerText(), /企划确认前可多次更正/);
  await form().locator('[data-emie-action="click:upload-remove-file"][data-list-key="deliverImage"]').click();
  assert.equal(await form().locator('#deliverImageList img').count(), 0, '误传图已从本次成果移除');
  await form().locator('#correctDeliverImageInput').setInputFiles({ name: fileName, mimeType: 'image/png', buffer: imageBytes });
  await form().locator(`#deliverImageList img[alt="${fileName}"]`).waitFor();
  await page.waitForFunction(() => window.EMIE.projectState.uploadingCount === 0);
  await form().locator('[name="changeSummary"]').fill(summary);
  await form().locator('[name="deliverables"]').fill(summary + '后的成果');
  const response = page.waitForResponse(value => value.request().method() === 'POST' && new URL(value.url()).pathname.endsWith('/correct-delivery'));
  await form().getByRole('button', { name: '保存更正', exact: true }).click();
  assert.equal((await response).status(), 200);
  await form().waitFor({ state: 'detached' });
  await page.waitForFunction(summary => document.getElementById('mainContent').textContent.includes(summary + '后的成果'), summary);
  const submitted = corrections.at(-1);
  assert.equal(submitted.currentUserId, designerId);
  assert.equal(submitted.currentRole, 'designer');
  assert.equal(submitted.changeSummary, summary);
  const files = JSON.parse(submitted.referenceImagesJson);
  assert.equal(files.length, 1);
  assert.equal(files[0].name, fileName);
  assert.equal(files[0].storedName, uploads.at(-1).storedName);
}
async function assertHistory(roundCount, previousNames) {
  await page.locator('[data-emie-action="click:designer-history"]').first().click();
  const history = page.locator('#deliveryHistoryModal');
  await history.waitFor();
  assert.equal(await history.locator('details.delivery-round').count(), roundCount, '更正沿用本轮交付，不新建修改轮');
  const latest = history.locator('details.delivery-round').first();
  assert.match(await latest.locator('summary').first().innerText(), /含 2 次更正/);
  await latest.locator('.delivery-correction-history > summary').click();
  for (const name of previousNames) {
    assert.equal(await latest.locator(`.delivery-correction-history img[alt="${name}"]`).count(), 1, '旧图仍可在更正历史中查看');
  }
  await history.locator('[data-emie-action="click:designer-history-close"]').first().click();
  await history.waitFor({ state: 'detached' });
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready]').waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.EMIE?.modules?.dashboardDesigner && window.EMIE?.modules?.projectTasks);
  await page.evaluate(({ designerId, projectId }) => {
    const e = window.EMIE;
    e.state.currentRole = 'designer'; e.state.currentUserId = designerId;
    e.state.authUser = { userId: designerId, name: '模拟设计师', role: 'designer' };
    e.state.permissions = ['page.dashboard.view', 'page.subtasks.mine.view', 'project.detail.view', 'file.upload'];
    localStorage.setItem('design_pm_token', 'delivery-corrections-ui-simulation-only');
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    const fixture = window.deliveryCorrectionsFixture = { view: 'dashboard' };
    fixture.refresh = async () => {
      const detail = await e.actions.apiGet(`/projects/${projectId}`);
      e.projectState.currentProjectDetail = detail;
      e.dashboardState.designerTaskCache = detail.tasks;
      document.getElementById('mainContent').innerHTML = fixture.view === 'project'
        ? e.actions.renderProjectDetailContent(detail) : e.actions.renderDesignerTaskCards(detail.tasks);
    };
    // Keep fixture rendering stable while using the real mutation API, upload handler, and form actions.
    e.actions.refreshAfterMutation = fixture.refresh;
  }, { designerId, projectId: task.projectId });
  await showFixture();
  await replaceImage('dashboard', 'initial-replacement-1.png', '首轮移除误传图');
  await replaceImage('dashboard', 'initial-replacement-2.png', '首轮再次补正');
  assert.equal(task.deliveryVersions.length, 3);
  assert.equal(task.deliveryVersions.at(-1).referenceImagesJson, originalInitial.referenceImagesJson);
  await assertHistory(1, ['ui-mistaken-initial.png', 'initial-replacement-1.png']);

  const confirmed = task.deliveryVersions.map(value => ({ ...value, confirmedAt: value.versionNo === 3 ? '2026-11-04T11:00:00' : null,
    pointStatus: value.versionNo === 3 ? 'awarded' : 'pending', awardedPoints: value.versionNo === 3 ? 10 : null }));
  const revision = version(4, { submissionType: 'revision', submissionOrigin: 'revision', pointRequestId: 'ui-revision-1', revisionNo: 1,
    changeReason: '调整修改轮配色', referenceImagesJson: JSON.stringify([image('ui-mistaken-revision.png')]), expectedPoints: 2, pointRuleCode: 'A3' });
  task = { ...task, status: 'submitted_for_review', pendingChangeBonusRequestId: 'ui-revision-1', pendingChangeBonusPoints: 2,
    referenceImagesJson: revision.referenceImagesJson, deliverables: revision.deliverables,
    deliveryVersions: [revision, ...confirmed] };
  await showFixture('project');
  await replaceImage('project', 'revision-replacement-1.png', '修改轮移除误传图');
  await replaceImage('project', 'revision-replacement-2.png', '修改轮再次更正');
  assert.equal(task.deliveryVersions.length, 6);
  assert.equal(task.deliveryVersions[0].revisionNo, 1);
  assert.equal(task.deliveryVersions[0].pointRequestId, 'ui-revision-1');
  await assertHistory(2, ['ui-mistaken-revision.png', 'revision-replacement-1.png']);

  for (const status of ['completed', 'planner_approved', 'sales_approved', 'admin_approved']) {
    task = { ...task, status };
    for (const view of ['dashboard', 'project']) {
      await showFixture(view);
      assert.equal(await buttonFor(view).count(), 0, `${status} 已确认成果不显示更正入口`);
    }
  }
  task = { ...task, status: 'delivered', designerId: 'other-ui-designer' };
  for (const view of ['dashboard', 'project']) {
    await showFixture(view);
    assert.equal(await buttonFor(view).count(), 0, '非本人负责人不显示更正入口');
  }

  task = { ...task, status: 'submitted_for_review', designerId };
  await showFixture();
  await buttonFor('dashboard').click();
  await form().waitFor();
  await form().locator('[name="changeSummary"]').fill('保留我的待提交更正');
  await form().locator('[name="deliverables"]').fill('企划确认期间仍保留表单内容');
  const versionCount = task.deliveryVersions.length;
  task = { ...task, status: 'completed' };
  const denied = page.waitForResponse(value => new URL(value.url()).pathname.endsWith('/correct-delivery'));
  await form().getByRole('button', { name: '保存更正', exact: true }).click();
  assert.equal((await denied).status(), 403);
  const alert = page.locator('#systemAlertModal');
  await alert.getByText('修正交付失败: 本轮交付已由企划确认，无法继续更正。', { exact: true }).waitFor();
  await alert.getByRole('button', { name: '知道了', exact: true }).click();
  assert.equal(await form().locator('[name="changeSummary"]').inputValue(), '保留我的待提交更正');
  assert.equal(await form().locator('[name="deliverables"]').inputValue(), '企划确认期间仍保留表单内容');
  assert.equal(await form().locator('#deliverImageList img[alt="revision-replacement-2.png"]').count(), 1, '403 保留当前成果图片');
  assert.equal(task.deliveryVersions.length, versionCount, '403 不追加交付版本');
  await form().getByRole('button', { name: '取消', exact: true }).click();
  await form().waitFor({ state: 'detached' });
  await buttonFor('dashboard').click();
  await alert.getByText('当前成果已确认或任务状态已变化，请刷新后查看。', { exact: true }).waitFor();
  assert.equal(await form().count(), 0, '旧按钮打开时使用最新状态，不展示失效表单');
  await alert.getByRole('button', { name: '知道了', exact: true }).click();
  task = { ...task, status: 'delivered', designerId: 'other-ui-designer' };
  await buttonFor('dashboard').click();
  await alert.getByText('当前成果已确认或任务状态已变化，请刷新后查看。', { exact: true }).waitFor();
  assert.equal(await form().count(), 0, '旧按钮打开时核对最新负责人');
  await alert.getByRole('button', { name: '知道了', exact: true }).click();
  assert.equal(corrections.length, 5, '四次成功更正和一次受保护拒绝');
  assert.equal(uploads.length, 4);

  // The planner approves the version displayed when opening the window.
  const oldVersion = { ...task.deliveryVersions[0], id: 73006, attachmentsJson: JSON.stringify([image('approval-source-v6.ai')]) };
  task = { ...task, status: 'submitted_for_review', designerId, attachmentsJson: oldVersion.attachmentsJson,
    deliveryVersions: [oldVersion, ...task.deliveryVersions.slice(1)] };
  await page.evaluate(({ userId, name }) => {
    const e = window.EMIE;
    document.dispatchEvent(new Event('emie:identity-changing'));
    e.state.currentRole = 'planner'; e.state.currentUserId = userId;
    e.state.authUser = { userId, name, role: 'planner' };
  }, { userId: task.plannerId, name: task.plannerName });
  await showFixture('project');
  const approveButton = page.locator('[data-emie-action="click:detail-task-approve"]');
  const approval = page.locator('#taskApproveModal');
  await approveButton.click();
  await approval.waitFor();
  assert.equal(await approval.getAttribute('data-delivery-version-id'), String(oldVersion.id));
  assert.match(await approval.innerText(), new RegExp(`本次确认第\\s*${oldVersion.versionNo}\\s*版成果`));
  assert.ok((await approval.innerText()).includes(oldVersion.deliverables));
  assert.equal(await approval.locator('img[alt="revision-replacement-2.png"]').count(), 1);
  assert.equal(await approval.getByText('approval-source-v6.ai', { exact: true }).count(), 1);
  await approval.locator('#approveComments').fill('已核对打开窗口时的成果');
  const newVersion = version(oldVersion.versionNo + 1, { ...oldVersion, id: oldVersion.id + 1, versionNo: oldVersion.versionNo + 1,
    submissionType: 'correction', changeSummary: '确认窗口打开后更正', deliverables: '企划打开窗口后更正的新成果',
    referenceImagesJson: JSON.stringify([image('approval-replacement-v7.png')]), attachmentsJson: JSON.stringify([image('approval-source-v7.ai')]) });
  task = { ...task, deliverables: newVersion.deliverables, referenceImagesJson: newVersion.referenceImagesJson,
    attachmentsJson: newVersion.attachmentsJson, deliveryVersions: [newVersion, ...task.deliveryVersions] };
  const staleApproval = page.waitForResponse(value => new URL(value.url()).pathname.endsWith('/approve'));
  await approval.getByRole('button', { name: '确认', exact: true }).click();
  assert.equal((await staleApproval).status(), 409);
  await alert.getByText('操作失败: 交付版本已更新，请重新打开确认窗口查看最新成果。', { exact: true }).waitFor();
  assert.equal(approvals.at(-1).expectedDeliveryVersionId, oldVersion.id, '旧窗口绑定原版本，不自动确认新成果');
  assert.equal(task.status, 'submitted_for_review', '过时确认保持待验收状态');
  await alert.getByRole('button', { name: '知道了', exact: true }).click();
  assert.equal(await approval.locator('#approveComments').inputValue(), '已核对打开窗口时的成果');
  assert.equal(await approval.getAttribute('data-delivery-version-id'), String(oldVersion.id));
  await approval.getByRole('button', { name: '取消', exact: true }).click();
  await approval.waitFor({ state: 'detached' });
  await approveButton.click();
  await approval.waitFor();
  assert.equal(await approval.getAttribute('data-delivery-version-id'), String(newVersion.id));
  assert.match(await approval.innerText(), new RegExp(`本次确认第\\s*${newVersion.versionNo}\\s*版成果`));
  assert.ok((await approval.innerText()).includes(newVersion.deliverables));
  assert.equal(await approval.locator('img[alt="approval-replacement-v7.png"]').count(), 1);
  assert.equal(await approval.getByText('approval-source-v7.ai', { exact: true }).count(), 1);
  assert.equal(await approval.locator('img[alt="revision-replacement-2.png"]').count(), 0);
  assert.equal(await approval.getByText('approval-source-v6.ai', { exact: true }).count(), 0);
  await approval.locator('#approveComments').fill('已核对最新版本');
  const currentApproval = page.waitForResponse(value => new URL(value.url()).pathname.endsWith('/approve'));
  await approval.getByRole('button', { name: '确认', exact: true }).click();
  assert.equal((await currentApproval).status(), 200);
  await approval.waitFor({ state: 'detached' });
  assert.equal(approvals.at(-1).expectedDeliveryVersionId, newVersion.id);
  assert.equal(task.status, 'completed');
  await approveButton.waitFor({ state: 'detached' });

  task = { ...task, status: 'delivered', deliveryVersions: [] };
  await showFixture('project');
  await approveButton.click();
  await approval.waitFor();
  const legacyApproval = page.waitForResponse(value => new URL(value.url()).pathname.endsWith('/approve'));
  await approval.getByRole('button', { name: '确认', exact: true }).click();
  assert.equal((await legacyApproval).status(), 200);
  await approval.waitFor({ state: 'detached' });
  assert.equal(approvals.at(-1).expectedDeliveryVersionId, null, '无交付版本的历史任务显式提交空版本');
  assert.equal(approvals.length, 3);

  // Intercept the OAuth round trip while exercising the real URL, login and drawer code.
  const projectLink = new URL(`/?projectId=${task.projectId}`, baseURL);
  const taskLink = new URL(projectLink);
  taskLink.searchParams.set('taskId', task.id);
  const callbackLink = new URL(`/?sso_ticket=${feishuTicket}`, baseURL);
  await page.route('https://open.feishu.cn/open-apis/authen/v1/index?**',
    route => route.fulfill({ status: 302, headers: { location: callbackLink.href } }));
  await page.evaluate(() => localStorage.removeItem('design_pm_token'));
  await page.goto(taskLink.href, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready="login"]').waitFor();
  const projectDrawer = page.locator('#projectDetailModal');
  const taskDrawer = page.locator('#projectSubTaskDetailModal');
  assert.equal(await projectDrawer.count(), 0, '登录前不打开项目详情');
  await page.locator('#feishuLoginBtn').click();
  const assertLinkedTask = async () => {
    await page.locator('html[data-app-ready="authenticated"]').waitFor();
    await projectDrawer.waitFor();
    await taskDrawer.waitFor();
    assert.ok((await taskDrawer.locator('.modal-title').innerText()).includes(task.name), '精确打开通知所链接的子任务');
    assert.equal(new URL(page.url()).search, '', '登录票据和已处理的项目、子任务编号从地址栏移除');
  };
  await assertLinkedTask();
  await page.goto(taskLink.href, { waitUntil: 'domcontentloaded' });
  await assertLinkedTask();
  await page.goto(projectLink.href, { waitUntil: 'domcontentloaded' });
  await projectDrawer.waitFor();
  assert.equal(await taskDrawer.count(), 0, '仅项目编号的旧通知仍打开项目详情');

  assert.deepEqual(unexpectedMutations, []);
  assert.deepEqual(errors, []);
  console.log('delivery correction browser checks passed (local API simulation, both entry points, repeated corrections per round, replacement uploads, retained history, ownership, confirmed state, 403 form preservation, stale-state guard, stale approval version rejection and fresh approval, legacy null version, authenticated and OAuth notification deep links)');
} catch (error) {
  await page.screenshot({ path: path.join(artifactDirectory, 'delivery-corrections-failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally { await page.close(); await browser.close(); }
