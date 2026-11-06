import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '交付积分模拟回归仅允许本机测试环境');
const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const artifactDirectory = fileURLToPath(new URL('test-results/modal-e2e/', import.meta.url));
await fs.mkdir(artifactDirectory, { recursive: true });
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [], mutations = [];
page.on('pageerror', error => errors.push(error.message));

function delivery(id, fields = {}) {
  return {
    id, versionNo: id, submissionType: 'initial', submissionOrigin: 'initial', submittedAt: '2026-10-20T10:00:00',
    submittedByName: '模拟设计师', changeSummary: 'UI 模拟交付记录', deliverables: 'UI 模拟初版成果',
    pointRuleCode: 'A1', pointRuleDescription: '包装整套设计', expectedPoints: 10, awardedPoints: null,
    pointStatus: 'pending', confirmedAt: null, confirmedBy: null, confirmedByName: null,
    awardedAt: null, pointRequestId: null, revisionNo: null, changeReason: null, reviewComments: null,
    referenceImagesJson: '[]', attachmentsJson: '[]', ...fields,
  };
}
const initial = delivery(1, {
  confirmedAt: '2026-10-21T09:30:00', confirmedBy: 'delivery-ui-planner', confirmedByName: '模拟企划',
  awardedAt: '2026-10-21T09:30:00', awardedPoints: 10, pointStatus: 'awarded', reviewComments: '初版验收通过',
});
const firstChange = delivery(2, {
  submissionType: 'revision', submissionOrigin: 'revision', pointRequestId: 'revision-1', revisionNo: 1, changeReason: '调整配色和文案',
  deliverables: '已调整配色和文案', pointRuleCode: 'A3', pointRuleDescription: '包装修改', expectedPoints: 3,
  submittedAt: '2026-10-23T10:00:00', confirmedAt: '2026-10-24T11:00:00',
  confirmedBy: 'delivery-ui-planner', confirmedByName: '模拟企划', awardedAt: '2026-10-24T11:00:00',
  awardedPoints: 3, pointStatus: 'awarded', reviewComments: '配色和文案已验收',
});
const secondChange = delivery(3, {
  submissionType: 'revision', submissionOrigin: 'revision', pointRequestId: 'revision-2', revisionNo: 2, changeReason: '补充另一款包装',
  deliverables: '另一款包装初次提交', pointRuleCode: 'A3', pointRuleDescription: '包装修改', expectedPoints: 2,
  submittedAt: '2026-10-25T10:00:00',
});
const currentAttachmentName = '包装交付更新版-2026-10-25-final-production-artwork-with-a-long-filename-for-mobile-layout.zip';
const currentAttachments = JSON.stringify([{ name: currentAttachmentName, url: '/api/files/download/current-packaging.zip', size: 1024 }]);
const correction = delivery(4, {
  ...secondChange, id: 4, versionNo: 4, submissionType: 'correction', changeSummary: '补充当前轮附件',
  deliverables: '已补充另一款包装附件', submittedAt: '2026-10-25T12:00:00', attachmentsJson: currentAttachments,
});
const summary = { awardedPoints: 13, basePoints: 10, changePoints: 3, otherPoints: 0, untrackedPoints: 0, hasUntrackedAwards: false };
const task = {
  id: 93001, projectId: 94001, name: 'UI模拟 · 多轮交付与积分', projectName: 'UI模拟 · 验收积分项目',
  projectType: 'regular', status: 'delivered', workflowStage: 'design', assigneeRole: 'designer',
  designerId: 'delivery-ui-designer', designerName: '模拟设计师', plannerId: 'delivery-ui-planner',
  plannerName: '模拟企划', publisherName: '模拟企划', plannedDate: '2026-11-20', createdAt: '2026-10-01T10:00:00',
  details: '核对首次验收及后续修改给分。', deliverables: correction.deliverables, attachmentsJson: currentAttachments, reviewComments: '',
  pointRuleCode: 'A1', basePointSnapshot: 10, pointSummary: summary, pendingChangeBonusRequestId: 'revision-2',
  pendingChangeRuleCode: 'A3', pendingChangeBonusPoints: 2, pendingChangeBonusReason: '补充另一款包装',
  rejectionRecords: [], deliveryVersions: [correction, secondChange, firstChange, initial],
};
const project = {
  id: task.projectId, name: task.projectName, productName: 'UI模拟包装', type: 'regular', status: 'in_progress',
  plannerId: task.plannerId, plannerName: task.plannerName, statusLabel: '进行中', statusCls: 'badge-progress',
  createdAt: task.createdAt, updatedAt: correction.submittedAt, deadline: task.plannedDate, tasks: [task], logs: [],
};
let projectResponse = project;
const taskListResponses = new Map([
  ['/api/projects/my-subtasks', [task]],
  ['/api/projects/department-subtasks', []],
  ['/api/projects/task-market', []],
]);

// Fulfill every API request locally; this check never reads or writes real business data.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  if (request.method() !== 'GET') mutations.push(`${request.method()} ${url.pathname}`);
  let payload = taskListResponses.get(url.pathname) || {};
  if (url.pathname === `/api/projects/${project.id}`) payload = projectResponse;
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(payload) });
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  for (const [pattern, directory, contentType] of [['**/js/*.js*', 'js', 'text/javascript'], ['**/css/*.css*', 'css', 'text/css']]) {
    await page.route(pattern, async route => route.fulfill({ contentType, body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static', directory, path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  }
  await page.route(baseURL + '/', async route => route.fulfill({ contentType: 'text/html', body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static/index.html'), 'utf8') }));
}

const history = () => page.locator('#deliveryHistoryModal');
const rounds = () => history().locator('details.delivery-round');
async function openHistory() {
  await page.evaluate(() => window.EMIE.actions.openDeliveryHistory(window.deliveryPointsFixture.task.id));
  await history().waitFor();
}
async function closeHistory() {
  await history().locator('[data-emie-action="click:designer-history-close"]').first().click();
  await history().waitFor({ state: 'detached' });
}
async function showFixture(value, readOnly = false) {
  await page.evaluate(({ value, readOnly }) => {
    const e = window.EMIE;
    const f = window.deliveryPointsFixture;
    f.task = value;
    e.dashboardState.designerTaskCache = [value];
    e.projectState.currentProjectDetail = { ...f.project, tasks: [value] };
    document.getElementById('mainContent').innerHTML = e.actions.renderDesignerTaskCards([value], readOnly);
  }, { value, readOnly });
}
function textNumbers(text) { return text.replace(/\s+/g, ' '); }
async function assertSummary(container, expected = 13) {
  const totals = container.locator('.task-point-summary');
  assert.ok(await totals.count() > 0, '子任务应直接展示积分汇总');
  assert.equal(await totals.first().getAttribute('data-awarded-points'), String(expected), '累计积分只采用实际入账');
  assert.match(textNumbers(await totals.first().innerText()), new RegExp(`累计已入账\\s*${expected}\\s*分`));
}

async function assertTaskDrawer(drawer, { status, deliverables, versionNo, canCorrect }) {
  const body = drawer.locator('.task-detail-body');
  const current = body.locator('.task-detail-current');
  assert.equal(await body.locator(':scope > :first-child').evaluate(node => node.matches('.task-detail-current')), true,
    '子任务详情先展示当前交付和验收状态');
  assert.match(await current.locator('.task-detail-state').innerText(), new RegExp(status));
  const output = current.locator('.task-detail-output');
  assert.equal(await output.getByRole('heading', { name: '当前交付成果' }).isVisible(), true);
  assert.equal(await output.locator('.task-detail-version').innerText(), `第 ${versionNo} 版`);
  assert.equal(await output.getByText(deliverables, { exact: true }).isVisible(), true);
  assert.equal(await output.getByText(currentAttachmentName, { exact: true }).isVisible(), true);
  assert.equal(await current.locator('[data-emie-action="click:designer-correct"]').count(), canCorrect ? 1 : 0,
    '更正入口仅向待验收成果的当前子任务负责人显示');
  const inlineRounds = body.locator('.task-detail-history .delivery-round');
  assert.ok(await inlineRounds.count() > 1, '多轮交付仍保留可展开的历史');
  assert.equal(await body.locator('.task-detail-history .delivery-round[open]').count(), 0,
    '抽屉内的历史仅默认展示每轮摘要');
  const latest = inlineRounds.first();
  await latest.locator(':scope > summary').click();
  assert.equal(await latest.getAttribute('open'), '', '可以展开当轮的验收和积分记录');
  assert.equal(await drawer.getByText(currentAttachmentName, { exact: true }).count(), 1,
    '展开当轮记录也只展示一份当前附件');
  await latest.locator(':scope > summary').click();
}

async function assertRejectionHistory(container, reasons) {
  const records = container.locator('.task-rejection-history > .task-rejection-record');
  assert.equal(await records.count(), reasons.length);
  for (const reason of reasons) {
    assert.equal(await records.locator('.task-rejection-reason').filter({ hasText: reason }).isVisible(), true,
      '多次重交后各轮驳回原因默认可见');
  }
  const cancelled = records.filter({ hasText: '此驳回已撤销' });
  assert.equal(await cancelled.locator('.task-rejection-heading > span').innerText(), '已撤销');
  assert.match(await records.first().locator('.task-rejection-meta').first().innerText(), /产品企划.*模拟企划.*(?:10[-/]22|10月22)/);
  assert.equal(await records.locator('.task-rejection-reason img').count(), 0, '驳回原因中的 HTML 作为文本显示');
}

async function assertFilteredTaskRows(tasks) {
  await page.waitForFunction(expectedNames => {
    const rows = [...document.querySelectorAll('#designerTaskContainer .subtask-name')];
    return rows.length === expectedNames.length
      && expectedNames.every(name => rows.some(row => row.textContent.includes(name)));
  }, tasks.map(item => item.name));
}

async function assertTaskDateFilters() {
  const from = '2026-10-25';
  const to = '2026-10-27';
  const latestCorrection = { ...task, id: 93101, name: '更正日期落在上界', submittedForReviewAt: initial.submittedAt,
    deliveryVersions: [{ ...correction, submittedAt: `${to}T23:59:59` }, initial] };
  const latestRedelivery = { ...task, id: 93102, name: '重新交付日期落在下界',
    deliveryVersions: [delivery(2, { submissionType: 'redelivery', submissionOrigin: 'rejection', submittedAt: `${from}T00:00:00` }), initial] };
  const legacySubmission = { ...task, id: 93103, name: '历史任务有提交时间',
    deliveryVersions: [], submittedForReviewAt: '2026-10-26T10:00:00' };
  const oldVersionInRange = { ...task, id: 93104, name: '仅旧版本日期落在区间',
    deliveryVersions: [{ ...correction, submittedAt: '2026-10-28T09:00:00' }, secondChange] };
  const noSubmission = { ...task, id: 93105, name: '未提交但计划日期符合', status: 'pending', plannedDate: '2026-10-26',
    deliveryVersions: [], deliverables: null, attachmentsJson: '[]', pendingChangeBonusRequestId: null };
  const all = [latestCorrection, latestRedelivery, legacySubmission, oldVersionInRange, noSubmission];
  taskListResponses.set('/api/projects/my-subtasks', all);
  taskListResponses.set('/api/projects/department-subtasks', all);

  const openList = action => page.evaluate(action => {
    const e = window.EMIE;
    return e.actions[action](document.getElementById('mainContent'), e.state.currentRole, e.state.currentUserId);
  }, action);
  const dateBasis = page.locator('#designerTaskDateBasis');
  const submittedChoice = dateBasis.getByText('最新提交', { exact: true });
  const plannedChoice = dateBasis.getByText('计划完成', { exact: true });
  const submittedRadio = dateBasis.locator('input[name="designerTaskDateBasis"][value="submitted"]');
  const plannedRadio = dateBasis.locator('input[name="designerTaskDateBasis"][value="planned"]');
  const startDate = page.locator('#designerTaskDateStart');
  const endDate = page.locator('#designerTaskDateEnd');
  for (const action of ['renderMyTasks', 'renderDepartmentTasks']) {
    await openList(action);
    assert.equal(await submittedChoice.isVisible(), true);
    assert.equal(await plannedChoice.isVisible(), true, '两种日期依据直接显示');
    assert.equal(await submittedRadio.isChecked(), true, '任务日期默认按最近成果提交');
    await assertFilteredTaskRows(all);
    await startDate.fill(from);
    await endDate.fill(to);
    await assertFilteredTaskRows([latestCorrection, latestRedelivery, legacySubmission]);
    await plannedChoice.click();
    assert.equal(await plannedRadio.isChecked(), true);
    assert.deepEqual([await startDate.inputValue(), await endDate.inputValue()], [from, to], '切换日期依据时保留日期范围');
    await assertFilteredTaskRows([noSubmission]);
    await submittedChoice.click();
    assert.equal(await submittedRadio.isChecked(), true);
    await assertFilteredTaskRows([latestCorrection, latestRedelivery, legacySubmission]);
    await plannedChoice.click();
    await assertFilteredTaskRows([noSubmission]);
    await page.locator('[data-emie-action="click:designer-reset"]').click();
    assert.equal(await submittedRadio.isChecked(), true, '重置回到成果提交日期');
    assert.equal(await startDate.inputValue(), '');
    assert.equal(await endDate.inputValue(), '');
    await assertFilteredTaskRows(all);
  }

  const matchingMarket = { ...noSubmission, id: 93106, name: '市场任务计划日期符合', allocationStatus: 'market_open', designerId: null };
  const outsideMarket = { ...matchingMarket, id: 93107, name: '市场任务计划日期不符', plannedDate: '2026-10-28' };
  taskListResponses.set('/api/projects/task-market', [matchingMarket, outsideMarket]);
  await openList('renderTaskMarket');
  assert.equal(await dateBasis.count(), 0, '接单市场仍按计划完成日期筛选');
  await startDate.fill(from);
  await endDate.fill(to);
  await assertFilteredTaskRows([matchingMarket]);
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready]').waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.EMIE?.modules?.dashboardDesigner && window.EMIE?.modules?.projectDetail);
  await page.evaluate(({ task, project }) => {
    const e = window.EMIE;
    e.state.currentRole = 'designer'; e.state.currentUserId = task.designerId;
    e.state.authUser = { userId: task.designerId, name: task.designerName, role: 'designer' };
    e.state.permissions = ['page.dashboard.view', 'project.view', 'subtask.view', 'subtask.redeliver'];
    localStorage.setItem('design_pm_token', 'delivery-points-ui-simulation-only');
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    // Recommendations have their own independent browser suite.
    e.actions.mountTaskRecommendations = () => {};
    window.deliveryPointsFixture = { task, project };
  }, { task, project });
  await showFixture(task);
  const main = page.locator('#mainContent');
  await assertSummary(main);
  assert.match(textNumbers(await main.locator('.task-point-breakdown').first().innerText()), /首次.*10.*修改.*3/);
  assert.match(textNumbers(await main.locator('.task-point-pending').first().innerText()), /预计.*\+?2\s*分/);
  assert.doesNotMatch(await main.locator('.task-point-summary').first().innerText(), /累计已入账\s*15/);
  await main.locator('[data-emie-action="click:designer-history"]').first().click();
  await history().waitFor();
  assert.match(await history().locator('.modal-title').innerText(), /交付与积分记录/);
  assert.equal(await rounds().count(), 3, '主动更正附件仍属于同一轮交付');
  const latest = rounds().first();
  assert.equal(await latest.getAttribute('open'), '', '最新轮默认展开');
  assert.equal(await rounds().nth(1).getAttribute('open'), null, '旧轮默认保留摘要');
  assert.match(textNumbers(await latest.locator('summary').first().innerText()), /第\s*2\s*次修改/);
  assert.match(textNumbers(await latest.locator('summary').first().innerText()), /待验收.*预计.*\+?2\s*分/);
  const firstChangeTitle = rounds().nth(1).locator('summary').first();
  assert.match(textNumbers(await firstChangeTitle.innerText()), /第\s*1\s*次修改/);
  assert.match(textNumbers(await firstChangeTitle.innerText()), /已入账.*\+3\s*分/);
  assert.match(await firstChangeTitle.innerText(), /模拟企划/);
  assert.match(await firstChangeTitle.innerText(), /10[-/]24|10月24/);
  assert.match(textNumbers(await rounds().last().locator('summary').first().innerText()), /首次交付.*已入账.*\+10\s*分/);
  await firstChangeTitle.click();
  const changeBody = rounds().nth(1).locator('.delivery-round-body');
  assert.match(await changeBody.innerText(), /调整配色和文案/);
  assert.match(await changeBody.innerText(), /已调整配色和文案/);
  assert.match(await changeBody.innerText(), /配色和文案已验收/);
  await firstChangeTitle.click();
  await page.screenshot({ path: path.join(artifactDirectory, 'delivery-points-desktop.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 390, height: 844 });
  const overflow = await history().locator('.modal').evaluate(node => node.scrollWidth - node.clientWidth);
  assert.ok(overflow <= 1, `交付记录弹窗不应横向溢出: ${overflow}`);
  for (const header of await rounds().locator(':scope > summary').all()) {
    const dimensions = await header.evaluate(node => ({ overflow: node.scrollWidth - node.clientWidth, left: node.getBoundingClientRect().left, right: node.getBoundingClientRect().right }));
    assert.ok(dimensions.overflow <= 1 && dimensions.left >= 0 && dimensions.right <= 390, '窄屏每轮摘要和积分状态应保持在可视范围');
  }
  const closeButton = history().locator('.modal-footer [data-emie-action="click:designer-history-close"]');
  await closeButton.scrollIntoViewIfNeeded();
  assert.equal(await closeButton.isVisible(), true, '窄屏关闭操作可见');
  await page.screenshot({ path: path.join(artifactDirectory, 'delivery-points-mobile.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await closeHistory();

  // The same accounting is visible from the project card and task detail.
  await page.evaluate(() => {
    const e = window.EMIE, f = window.deliveryPointsFixture;
    e.state.currentRole = 'planner'; e.state.currentUserId = f.task.plannerId;
    e.projectState.currentProjectDetail = f.project;
    document.getElementById('mainContent').innerHTML = e.actions.renderProjectDetailContent(f.project);
  });
  await assertSummary(main);
  await main.locator('.subtask-card').first().click();
  const detail = page.locator('#projectSubTaskDetailModal');
  await detail.waitFor();
  await assertSummary(detail);
  await assertTaskDrawer(detail, { status: '等待企划验收', deliverables: correction.deliverables, versionNo: 4, canCorrect: false });
  const olderRound = detail.locator('.task-detail-history .delivery-round').nth(1);
  await olderRound.locator(':scope > summary').click();
  assert.equal(await olderRound.getByText(firstChange.deliverables, { exact: true }).isVisible(), true,
    '展开历史轮次仍可查看当时的交付成果');
  await olderRound.locator(':scope > summary').click();
  await detail.locator('[data-emie-action="click:designer-history"]').first().click();
  await history().waitFor();
  assert.equal(await rounds().count(), 3);
  await closeHistory();
  await detail.locator('[data-emie-action="click:detail-subtask-close"]').first().click();
  await detail.waitFor({ state: 'detached' });

  // Once the latest modification is accepted, clearing its pending request must not hide the award.
  const secondChangeCompleted = { ...task, status: 'completed', pendingChangeBonusRequestId: null, pendingChangeBonusPoints: null,
    pointSummary: { ...summary, awardedPoints: 15, changePoints: 5 },
    deliveryVersions: [{ ...correction, awardedPoints: 2, pointStatus: 'awarded', confirmedAt: '2026-10-26T09:00:00',
      confirmedBy: 'delivery-ui-planner', confirmedByName: '模拟企划', awardedAt: '2026-10-26T09:00:00', reviewComments: '第二次修改验收通过' }, secondChange, firstChange, initial] };
  await page.evaluate(() => {
    const e = window.EMIE;
    e.state.currentRole = 'designer'; e.state.currentUserId = window.deliveryPointsFixture.task.designerId;
  });
  await showFixture(secondChangeCompleted);
  await page.evaluate(id => window.EMIE.actions.openPublishedSubTaskDetail(id), secondChangeCompleted.id);
  const published = page.locator('#publishedSubTaskDetailModal');
  await published.waitFor();
  await assertTaskDrawer(published, { status: '已完成', deliverables: correction.deliverables, versionNo: 4, canCorrect: false });
  await published.locator('.modal-body').evaluate(node => { node.scrollTop = 0; });
  await page.screenshot({ path: path.join(artifactDirectory, 'task-detail-completed.png'), fullPage: true, animations: 'disabled' });
  await published.locator('.modal-footer [data-emie-action="click:designer-detail-close"]').click();
  await published.waitFor({ state: 'detached' });
  await openHistory();
  await assertSummary(history(), 15);
  assert.equal(await rounds().count(), 3);
  assert.match(textNumbers(await rounds().first().locator('summary').first().innerText()), /第\s*2\s*次修改.*已入账.*\+2\s*分/);
  assert.match(textNumbers(await rounds().nth(1).locator('summary').first().innerText()), /第\s*1\s*次修改.*已入账.*\+3\s*分/);
  assert.equal(await history().locator('.task-point-pending').count(), 0, '验收完成后不再显示待发积分');
  await closeHistory();

  const correctedInitial = { ...task, status: 'completed', pendingChangeBonusRequestId: null,
    pointSummary: { ...summary, awardedPoints: 10, changePoints: 0 },
    deliveryVersions: [{ ...initial, id: 2, versionNo: 2, submissionType: 'correction', submissionOrigin: 'unknown',
      pointRequestId: null, revisionNo: null, changeSummary: '首轮更正附件', submittedAt: '2026-10-20T11:00:00' },
      { ...initial, awardedPoints: null, awardedAt: null, confirmedAt: null, confirmedBy: null, confirmedByName: null, pointStatus: 'pending' }] };
  await showFixture(correctedInitial); await openHistory();
  assert.equal(await rounds().count(), 1, '首次交付主动更正保留在首轮');
  const correctedInitialTitle = await rounds().first().locator('summary').first().innerText();
  assert.match(correctedInitialTitle, /首次交付/);
  assert.doesNotMatch(correctedInitialTitle, /历史交付|第\s*\d+\s*次修改/, '更正记录来源未知不能覆盖本轮首次交付来源');
  assert.match(correctedInitialTitle, /已入账.*\+10\s*分/);
  await closeHistory();

  const noPoints = { ...task, status: 'completed', pendingChangeBonusRequestId: null, pendingChangeBonusPoints: null,
    deliveryVersions: [{ ...correction, expectedPoints: 0, awardedPoints: 0, pointRuleCode: null, pointStatus: 'no_points',
      confirmedAt: '2026-10-26T09:00:00', confirmedByName: '模拟企划' }, { ...secondChange, expectedPoints: 0, pointRuleCode: null }, firstChange, initial] };
  await showFixture(noPoints); await openHistory();
  assert.equal(await rounds().first().getAttribute('data-point-status'), 'no_points');
  assert.match(await rounds().first().locator('summary').first().innerText(), /本次不计分|本次无积分/);
  assert.doesNotMatch(await rounds().first().locator('summary').first().innerText(), /已入账/);
  await closeHistory();

  const noPointsPending = { ...noPoints, status: 'delivered', pendingChangeBonusRequestId: 'revision-2',
    pendingChangeRuleCode: null, pendingChangeBonusPoints: 0,
    deliveryVersions: [{ ...correction, expectedPoints: 0, awardedPoints: 0, pointRuleCode: null, pointStatus: 'no_points' },
      { ...secondChange, expectedPoints: 0, awardedPoints: 0, pointRuleCode: null, pointStatus: 'no_points' }, firstChange, initial] };
  await showFixture(noPointsPending); await openHistory();
  assert.match(textNumbers(await rounds().first().locator('summary').first().innerText()), /待验收.*本次无积分|待验收.*本次不计分/);
  assert.match(await rounds().first().locator('.delivery-round-body').innerText(), /等待企划验收/);
  assert.match(textNumbers(await history().locator('.task-point-pending').innerText()), /待验收.*本次无积分|待验收.*本次不计分/);
  await closeHistory();

  const noPointsRejected = { ...noPointsPending, status: 'rejected',
    rejectionRecords: [{ cycleId: 2, attemptNo: 1, reviewedAt: '2026-10-25T13:00:00', reason: '请再次检查包装尺寸', withPoints: false }] };
  await showFixture(noPointsRejected); await openHistory();
  const noPointsRejectedTitle = await rounds().first().locator('summary').first().innerText();
  assert.match(noPointsRejectedTitle, /已驳回.*本次无积分|已驳回.*本次不计分/);
  assert.doesNotMatch(noPointsRejectedTitle, /待验收/, '已被驳回的无积分交付不能仍显示待验收');
  assert.doesNotMatch(await rounds().first().locator('.delivery-round-body').innerText(), /等待企划验收/);
  await closeHistory();

  const rejectedThenResubmitted = { ...task, status: 'completed', pendingChangeBonusRequestId: null,
    pointSummary: { ...summary, awardedPoints: 10, changePoints: 0 },
    rejectionRecords: [{ attemptNo: 1, reviewedAt: '2026-10-20T12:00:00', reason: '首稿细节需调整', withPoints: true, points: 2, pointRequestId: 'rejection-request' }],
    deliveryVersions: [delivery(2, { ...initial, id: 2, versionNo: 2, submissionType: 'revision', submissionOrigin: 'rejection', rejectionReason: '首稿细节需调整', rejectionCycleId: 1, pointRequestId: 'rejection-request', revisionNo: null, submittedAt: '2026-10-22T10:00:00' }), delivery(1)] };
  await showFixture(rejectedThenResubmitted); await openHistory();
  const rejectionTitles = await rounds().locator(':scope > summary').allTextContents();
  assert.ok(rejectionTitles.some(title => /驳回后重交|重新交付/.test(title)), '验收前驳回重交应有明确标签');
  assert.ok(rejectionTitles.every(title => !/第\s*\d+\s*次修改/.test(title)), '加分驳回不能被误算成验收后的新增修改');
  await closeHistory();

  const reasonOne = '第一轮驳回：调整包装比例 <img src=x onerror=alert(1)>';
  const reasonTwo = '第二轮驳回：修改盒侧文字';
  const cancelledReason = '此驳回已撤销，不是当前修改要求';
  const pointsRequest = 'shared-paid-rejection-request';
  const redeliveryContent = '按第二轮意见已更新盒侧文字';
  const rejection = (id, attemptNo, reviewedAt, reason, rejectedDeliveryVersionId, fields = {}) => ({
    id, cycleId: 100 + id, attemptNo, reviewedAt, reason, rejectedDeliveryVersionId, pointRequestId: pointsRequest,
    reviewerRole: 'planner', reviewerName: '模拟企划', requiredCompletionDate: '2026-10-30', ...fields,
  });
  const repeatedlyRedelivered = {
    ...task, status: 'submitted_for_review', reviewComments: null, pendingChangeBonusRequestId: pointsRequest,
    deliverables: redeliveryContent,
    rejectionRecords: [
      rejection(11, 1, '2026-10-20T12:00:00', reasonOne, 1, { deliverables: '第一次交付快照', withPoints: true, points: 2 }),
      rejection(12, 2, '2026-10-22T12:00:00', reasonTwo, 2, { deliverables: '第二次交付快照', withPoints: false,
        rejectionAttachmentsJson: JSON.stringify([{ name: '二次驳回说明.pdf', url: '/api/files/download/rejection-note.pdf', size: 1024 }]) }),
      rejection(13, 3, '2026-10-22T13:00:00', cancelledReason, 2, { cancelled: true }),
    ],
    deliveryVersions: [
      delivery(3, { submissionType: 'redelivery', submissionOrigin: 'rejection', pointRequestId: pointsRequest,
        changeReason: '旧积分请求不能覆盖本轮驳回', deliverables: redeliveryContent,
        attachmentsJson: currentAttachments, submittedAt: '2026-10-23T10:00:00' }),
      delivery(2, { submissionType: 'revision', submissionOrigin: 'rejection', rejectionCycleId: 111,
        pointRequestId: pointsRequest, submittedAt: '2026-10-22T10:00:00' }),
      delivery(1),
    ],
  };
  projectResponse = { ...project, tasks: [repeatedlyRedelivered] };
  await showFixture(repeatedlyRedelivered);
  const cardHistory = main.locator('.task-rejection-history');
  assert.equal(await cardHistory.getByText(reasonTwo, { exact: true }).isVisible(), true);
  assert.equal(await cardHistory.getByText(reasonOne, { exact: true }).isVisible(), false, '任务卡先显示最近一轮有效驳回');
  await cardHistory.locator('.task-rejection-previous > summary').click();
  assert.equal(await cardHistory.getByText(reasonOne, { exact: true }).isVisible(), true);

  await main.locator('[data-emie-action="click:designer-detail"]').click();
  await published.waitFor();
  const redeliveredDrawer = { status: '等待企划验收', deliverables: redeliveryContent, versionNo: 3, canCorrect: true };
  await assertTaskDrawer(published, redeliveredDrawer);
  await assertRejectionHistory(published, [reasonOne, reasonTwo, cancelledReason]);
  await page.screenshot({ path: path.join(artifactDirectory, 'task-detail-desktop.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 390, height: 844 });
  const drawerWidth = await published.locator('.modal').evaluate(node => ({
    overflow: node.scrollWidth - node.clientWidth, right: node.getBoundingClientRect().right,
  }));
  assert.ok(drawerWidth.overflow <= 1 && drawerWidth.right <= 390, '窄屏子任务详情及长附件名不横向溢出');
  await page.screenshot({ path: path.join(artifactDirectory, 'task-detail-mobile.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await published.locator('.task-detail-current [data-emie-action="click:designer-correct"]').click();
  const correctionModal = page.locator('#taskCorrectDeliveryModal');
  await correctionModal.waitFor();
  assert.equal(await correctionModal.locator('textarea[name="deliverables"]').inputValue(), redeliveryContent,
    '从详情更正时保留当前成果');
  await correctionModal.locator('.modal-footer [data-emie-action="click:task-correct-close"]').click();
  await correctionModal.waitFor({ state: 'detached' });
  assert.equal(await published.isVisible(), true, '取消更正回到子任务详情');
  const snapshot = published.locator('.task-rejection-snapshot').first();
  await snapshot.locator('summary').click();
  assert.equal(await published.getByText('第二次交付快照', { exact: true }).isVisible(), true);
  assert.equal(await snapshot.getByText('二次驳回说明.pdf', { exact: true }).isVisible(), true);
  const latestRound = published.locator('.delivery-round[data-delivery-round="3"]');
  await latestRound.locator(':scope > summary').click();
  const latestRequirement = latestRound.locator('.delivery-round-step').first();
  assert.match(await latestRequirement.innerText(), /第二轮驳回/);
  assert.doesNotMatch(await latestRequirement.innerText(), /第一轮驳回|此驳回已撤销|旧积分请求/, '同一积分请求的旧驳回不能套用到新交付');
  const previousRound = published.locator('.delivery-round[data-delivery-round="2"]');
  await previousRound.locator(':scope > summary').click();
  assert.match(await previousRound.locator('.delivery-rejection-reason').innerText(), /第二轮驳回/);
  await published.locator('.modal-footer [data-emie-action="click:designer-detail-close"]').click();
  await published.waitFor({ state: 'detached' });

  await showFixture(repeatedlyRedelivered, true);
  await main.locator('[data-emie-action="click:designer-detail"][data-read-only="true"]').click();
  await published.waitFor();
  assert.equal(await published.locator('[data-emie-action="click:designer-correct"]').count(), 0,
    '只读入口即使是子任务负责人也不显示更正操作');
  await published.locator('.modal-footer [data-emie-action="click:designer-detail-close"]').click();
  await published.waitFor({ state: 'detached' });

  await page.evaluate(pid => window.EMIE.actions.openProjectDetail(pid), project.id);
  const projectModal = page.locator('#projectDetailModal');
  await projectModal.waitFor();
  await projectModal.locator('[data-emie-action="click:detail-subtask-open"]').first().click();
  await detail.waitFor();
  await assertTaskDrawer(detail, redeliveredDrawer);
  await assertRejectionHistory(detail, [reasonOne, reasonTwo, cancelledReason]);
  await detail.locator('.task-rejection-history [data-emie-action="click:detail-rejection-record"][data-attempt-no="1"]').click();
  const recordModal = page.locator('#taskRejectionRecordModal');
  await recordModal.waitFor();
  assert.match(await recordModal.innerText(), /第一次交付快照/);
  await page.evaluate(() => window.EMIE.actions.closeM('taskRejectionRecordModal'));
  await recordModal.waitFor({ state: 'detached' });
  await detail.locator('[data-emie-action="click:detail-subtask-close"]').first().click();
  await detail.waitFor({ state: 'detached' });
  await page.evaluate(() => window.EMIE.actions.closeM('projectDetailModal'));
  await projectModal.waitFor({ state: 'detached' });

  const awaitingRedelivery = { ...repeatedlyRedelivered, status: 'accepted',
    reviewComments: '当前新增的修改要求', pendingChangeBonusRequestId: 'new-modification-request' };
  projectResponse = { ...project, tasks: [awaitingRedelivery] };
  await showFixture(awaitingRedelivery);
  await main.locator('[data-emie-action="click:designer-redeliver"]').click();
  const redelivery = page.locator('#taskRedeliverModal');
  await redelivery.waitFor();
  assert.match(await redelivery.locator('.review-box').innerText(), /当前修改意见.*当前新增的修改要求/);
  await assertRejectionHistory(redelivery, [reasonOne, reasonTwo, cancelledReason]);
  await redelivery.locator('.modal-footer [data-emie-action="click:task-redeliver-close"]').click();
  await redelivery.waitFor({ state: 'detached' });
  projectResponse = project;

  const unknownOrigin = { ...task, pendingChangeBonusRequestId: null,
    rejectionRecords: [{ attemptNo: 1, reviewedAt: '2026-10-22T12:00:00', reason: '未明确关联的旧驳回', pointRequestId: 'different-rejection' }],
    deliveryVersions: [{ ...secondChange, pointRequestId: 'unknown-request', revisionNo: null, submissionOrigin: 'unknown' }, initial] };
  await showFixture(unknownOrigin); await openHistory();
  const unknownTitle = await rounds().first().locator('summary').first().innerText();
  assert.match(unknownTitle, /历史交付/);
  assert.doesNotMatch(unknownTitle, /驳回后重交|第\s*\d+\s*次修改/, '缺少关联证据时不能按时间间隔推断来源或轮次');
  await closeHistory();

  const missing = { ...task, status: 'completed', pendingChangeBonusRequestId: null,
    pointSummary: { ...summary, awardedPoints: 0, basePoints: 0, changePoints: 0 },
    deliveryVersions: [{ ...initial, awardedPoints: null, awardedAt: null, pointStatus: 'missing' }] };
  await showFixture(missing); await openHistory();
  assert.equal(await rounds().first().getAttribute('data-point-status'), 'missing');
  const missingTitle = await rounds().first().locator('summary').first().innerText();
  assert.doesNotMatch(missingTitle, /已入账/, '确认通过不能代替实际积分入账证据');
  assert.match(missingTitle, /核对|未入账|待入账|未查到入账/);
  await closeHistory();

  const historical = { ...task, status: 'completed', pendingChangeBonusRequestId: null,
    pointSummary: { ...summary, awardedPoints: 7, basePoints: 7, changePoints: 0, untrackedPoints: 7, hasUntrackedAwards: true },
    deliveryVersions: [{ id: 1, versionNo: 1, submissionType: 'initial', submittedAt: initial.submittedAt, deliverables: '历史成果', confirmedAt: initial.confirmedAt }] };
  await showFixture(historical); await openHistory();
  const historicalTitle = await rounds().first().locator('summary').first().innerText();
  assert.match(historicalTitle, /未记录|未关联/);
  assert.doesNotMatch(historicalTitle, /已入账.*\+7/, '不能将未关联的历史积分反推到某次交付');
  await closeHistory();

  await assertTaskDateFilters();
  assert.deepEqual(mutations, [], '模拟回归不得发送业务写请求');
  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  process.stdout.write('delivery-points: drawer hierarchy and responsive layout, actual totals, revision/correction grouping, per-round confirmation, no-points, repeated rejection history, missing-ledger and historical states, latest submission date filters passed; business writes=0\n');
} catch (error) {
  await page.screenshot({ path: path.join(artifactDirectory, 'delivery-points-failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
