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
const correction = delivery(4, {
  ...secondChange, id: 4, versionNo: 4, submissionType: 'correction', changeSummary: '补充当前轮附件',
  deliverables: '已补充另一款包装附件', submittedAt: '2026-10-25T12:00:00',
});
const summary = { awardedPoints: 13, basePoints: 10, changePoints: 3, otherPoints: 0, untrackedPoints: 0, hasUntrackedAwards: false };
const task = {
  id: 93001, projectId: 94001, name: 'UI模拟 · 多轮交付与积分', projectName: 'UI模拟 · 验收积分项目',
  projectType: 'regular', status: 'delivered', workflowStage: 'design', assigneeRole: 'designer',
  designerId: 'delivery-ui-designer', designerName: '模拟设计师', plannerId: 'delivery-ui-planner',
  plannerName: '模拟企划', publisherName: '模拟企划', plannedDate: '2026-11-20', createdAt: '2026-10-01T10:00:00',
  details: '核对首次验收及后续修改给分。', deliverables: correction.deliverables, reviewComments: '',
  pointRuleCode: 'A1', basePointSnapshot: 10, pointSummary: summary, pendingChangeBonusRequestId: 'revision-2',
  pendingChangeRuleCode: 'A3', pendingChangeBonusPoints: 2, pendingChangeBonusReason: '补充另一款包装',
  rejectionRecords: [], deliveryVersions: [correction, secondChange, firstChange, initial],
};
const project = {
  id: task.projectId, name: task.projectName, productName: 'UI模拟包装', type: 'regular', status: 'in_progress',
  plannerId: task.plannerId, plannerName: task.plannerName, statusLabel: '进行中', statusCls: 'badge-progress',
  createdAt: task.createdAt, updatedAt: correction.submittedAt, deadline: task.plannedDate, tasks: [task], logs: [],
};

// Fulfill every API request locally; this check never reads or writes real business data.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  if (request.method() !== 'GET') mutations.push(`${request.method()} ${url.pathname}`);
  let payload = {};
  if (url.pathname === '/api/projects/my-subtasks') payload = [task];
  if (url.pathname === '/api/projects/task-market') payload = [];
  if (url.pathname === `/api/projects/${project.id}`) payload = project;
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
async function showFixture(value) {
  await page.evaluate(value => {
    const e = window.EMIE;
    const f = window.deliveryPointsFixture;
    f.task = value;
    e.dashboardState.designerTaskCache = [value];
    e.projectState.currentProjectDetail = { ...f.project, tasks: [value] };
    document.getElementById('mainContent').innerHTML = e.actions.renderDesignerTaskCards([value]);
  }, value);
}
function textNumbers(text) { return text.replace(/\s+/g, ' '); }
async function assertSummary(container, expected = 13) {
  const totals = container.locator('.task-point-summary');
  assert.ok(await totals.count() > 0, '子任务应直接展示积分汇总');
  assert.equal(await totals.first().getAttribute('data-awarded-points'), String(expected), '累计积分只采用实际入账');
  assert.match(textNumbers(await totals.first().innerText()), new RegExp(`累计已入账\\s*${expected}\\s*分`));
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready]').waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.EMIE?.modules?.dashboardDesigner && window.EMIE?.modules?.projectDetail);
  await page.evaluate(({ task, project }) => {
    const e = window.EMIE;
    e.state.currentRole = 'designer'; e.state.currentUserId = task.designerId;
    e.state.authUser = { userId: task.designerId, name: task.designerName, role: 'designer' };
    e.state.permissions = ['page.dashboard.view', 'project.view', 'subtask.view'];
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
  await showFixture(secondChangeCompleted); await openHistory();
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

  assert.deepEqual(mutations, [], '模拟回归不得发送业务写请求');
  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  process.stdout.write('delivery-points: actual totals, revision/correction grouping, per-round confirmation, responsive history, no-points, rejection, missing-ledger and historical states passed; business writes=0\n');
} catch (error) {
  await page.screenshot({ path: path.join(artifactDirectory, 'delivery-points-failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
