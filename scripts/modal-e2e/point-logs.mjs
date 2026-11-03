import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '积分日志模拟检查仅允许本机测试环境');
const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
const errors = [], requests = [], mutations = [];
let failLogs = false;
page.on('pageerror', error => errors.push(error.message));

const logs = Array.from({ length: 23 }, (_, index) => ({
  id: `L-${index + 1}`, userId: index < 22 ? 'feishu_2d4404c6e36daa76' : 'designer_zhang',
  userName: index < 22 ? '黄海岚' : '张明', source: 'TASK_BASE', businessId: index + 1,
  businessType: 'sub_task', businessName: `模拟设计任务 ${index + 1}`, ruleCode: 'D30_29:BASE',
  ruleDescription: '送审前 3D 建模与调整', points: index === 0 ? -3 : index < 22 ? 2 : 5,
  createdAt: '2026-10-20T10:00:00', submittedAt: '2026-10-19T10:00:00',
  confirmedAt: '2026-10-20T09:00:00', confirmedByName: '模拟确认人', createdByName: '模拟操作者',
  reason: '积分日志界面模拟数据', deliveryVersionNo: 1, tracking: 'tracked',
}));
const audits = logs.map((item, index) => ({ ...item, businessVersionId: index + 1, versionNo: 1, issue: 'MISSING_LEDGER', expectedPoints: 2 }));
function filtered(items, params, isAudit) {
  const person = (params.get('personKeyword') || '').toLowerCase();
  return items.filter(item => (!person || `${item.userName}\n${item.userId}`.toLowerCase().includes(person))
    && (!params.get('from') || item[isAudit ? 'submittedAt' : 'createdAt'].slice(0, 10) >= params.get('from'))
    && (!params.get('to') || item[isAudit ? 'submittedAt' : 'createdAt'].slice(0, 10) <= params.get('to'))
    && (isAudit || !params.get('source') || item.source === params.get('source'))
    && (isAudit || !params.get('keyword') || `${item.ruleCode} ${item.ruleDescription} ${item.businessName}`.includes(params.get('keyword')))
    && (isAudit || !params.get('sign') || (params.get('sign') === 'positive' ? item.points > 0 : item.points < 0)));
}

// Every API request is fulfilled locally; no business data is read or changed.
await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  if (request.method() !== 'GET') mutations.push(url.pathname);
  const fulfill = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
  const isAudit = url.pathname === '/api/points/audit';
  if (url.pathname === '/api/points/logs' || isAudit) {
    requests.push({ path: url.pathname, params: Object.fromEntries(url.searchParams) });
    if (failLogs && !isAudit) return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"模拟日志服务不可用"}' });
    const items = filtered(isAudit ? audits : logs, url.searchParams, isAudit);
    const current = Number(url.searchParams.get('page') || 0), size = Number(url.searchParams.get('size') || 20);
    return fulfill({ items: items.slice(current * size, (current + 1) * size), total: items.length,
      page: current, size, totalPages: Math.ceil(items.length / size), historicalCount: items.length ? 3 : 0,
      summary: { positive: items.reduce((sum, item) => sum + Math.max(0, item.points), 0),
        negative: items.reduce((sum, item) => sum + Math.min(0, item.points), 0), net: items.reduce((sum, item) => sum + item.points, 0) } });
  }
  return fulfill({});
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  for (const [pattern, directory, contentType] of [['**/js/*.js*', 'js', 'text/javascript'], ['**/css/*.css*', 'css', 'text/css']]) {
    await page.route(pattern, async route => route.fulfill({ contentType, body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static', directory, path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  }
  await page.route(baseURL + '/', async route => route.fulfill({ contentType: 'text/html', body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static/index.html'), 'utf8') }));
}

const action = name => page.locator(`[data-emie-action="click:${name}"]`);
const pager = kind => page.locator(`[data-emie-action="click:point-logs-page"][data-kind="${kind}"]`);
async function settled() {
  await page.waitForFunction(() => ['pointLogResults', 'pointAuditResults'].every(id => {
    const node = document.getElementById(id);
    return node?.textContent.trim() && !node.querySelector('.loading');
  }));
}
async function query(person, viaEnter = false) {
  const before = requests.length;
  await page.locator('#pointLog-personKeyword').fill(person);
  if (viaEnter) await page.locator('#pointLog-personKeyword').press('Enter');
  else await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('#pointLogResults .loading'));
  await settled();
  assert.equal(requests.length, before + 2, '一次查询同时刷新日志和核对');
  const latest = requests.slice(-2);
  for (const request of latest) {
    assert.equal(request.params.personKeyword, person.trim());
    assert.equal(request.params.page, '0', '重新查询重置两个列表页码');
    assert.equal(request.params.userId, undefined, '界面使用名称与账号统一查询参数');
  }
}
async function checkPaginationLayout() {
  const groups = page.locator('.point-log-pagination-controls');
  assert.equal(await groups.count(), 2);
  for (let index = 0; index < await groups.count(); index++) {
    const buttons = groups.nth(index).getByRole('button');
    const previous = await buttons.nth(0).boundingBox(), next = await buttons.nth(1).boundingBox();
    assert.ok(previous && next && next.x - previous.x - previous.width < 100, '分页按钮相邻显示');
  }
  const overflow = await page.locator('#adminContent').evaluate(node => node.scrollWidth - node.clientWidth);
  assert.ok(overflow <= 1, `积分日志内容不应造成页面横向溢出: ${overflow}`);
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.modules?.adminPointLogs || window.EMIE?.loadViewModules);
  await page.evaluate(async () => {
    const e = window.EMIE;
    await e.loadViewModules?.('admin');
    e.state.currentRole = 'admin'; e.state.currentUserId = 'point-log-ui-admin';
    e.state.authUser = { userId: 'point-log-ui-admin', name: '积分日志模拟管理员', role: 'admin' };
    e.state.permissions = ['page.admin.view', 'admin.config.view', 'admin.user.manage'];
    localStorage.setItem('design_pm_token', 'point-log-ui-simulation-only');
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    e.adminState.currentTab = 'pointLogs';
    await e.actions.renderAdmin(document.getElementById('mainContent'), 'admin', 'point-log-ui-admin');
  });
  await settled();
  assert.equal(await page.locator('#pointLogResults tbody tr').count(), 20);
  assert.equal(await pager('logs').first().isDisabled(), true);
  assert.equal(await pager('logs').last().isDisabled(), false);

  await query(' 黄海岚 ', true);
  assert.match(await page.locator('#pointLogResults').innerText(), /共 22 条/);
  assert.doesNotMatch(await page.locator('#pointLogResults tbody').innerText(), /张明/);
  assert.deepEqual(await page.locator('.point-log-summary strong').allTextContents(), ['42', '-3', '39'], '统计基于全部筛选结果');
  assert.match(await page.locator('#pointAuditResults').innerText(), /历史未跟踪版本 3 条/);
  await pager('logs').last().click(); await settled();
  assert.equal(await page.locator('#pointLogResults tbody tr').count(), 2);
  assert.equal(requests.at(-2).params.page, '1');
  assert.equal(requests.at(-1).params.page, '0', '日志分页不改变核对列表页码');
  assert.equal(requests.at(-2).params.personKeyword, '黄海岚');
  assert.deepEqual(await page.locator('.point-log-summary strong').allTextContents(), ['42', '-3', '39'], '翻页不改变筛选结果统计');
  await pager('audit').last().click(); await settled();
  assert.equal(requests.at(-2).params.page, '1');
  assert.equal(requests.at(-1).params.page, '1');
  assert.equal(await page.locator('#pointAuditResults tbody tr').count(), 2);

  await query('feishu_2d4404c6e36daa76');
  assert.match(await page.locator('#pointLogResults').innerText(), /共 22 条/);
  await query('海岚', true);
  assert.match(await page.locator('#pointLogResults').innerText(), /共 22 条/);
  await query('designer_zhang');
  assert.match(await page.locator('#pointLogResults tbody').innerText(), /张明/);
  assert.equal(await page.locator('#pointLogResults tbody tr').count(), 1);

  await page.locator('#pointLog-from').fill('2026-10-01');
  await page.locator('#pointLog-to').fill('2026-10-31');
  await page.locator('#pointLog-source').selectOption('TASK_BASE');
  await page.locator('#pointLog-keyword').fill('建模');
  await page.locator('#pointLog-sign').selectOption('positive');
  await query('黄');
  assert.match(await page.locator('#pointLogResults').innerText(), /共 21 条/);
  for (const request of requests.slice(-2)) {
    assert.equal(request.params.from, '2026-10-01'); assert.equal(request.params.to, '2026-10-31');
  }
  assert.equal(requests.at(-2).params.source, 'TASK_BASE');
  assert.equal(requests.at(-2).params.keyword, '建模');
  assert.equal(requests.at(-2).params.sign, 'positive');
  assert.equal(requests.at(-1).params.source, undefined);
  await checkPaginationLayout();
  await page.screenshot({ path: '/tmp/emie-point-logs-desktop.png', fullPage: true, animations: 'disabled' });
  await page.locator('#pointLogResults .point-log-pagination').screenshot({ path: '/tmp/emie-point-logs-pagination-desktop.png', animations: 'disabled' });

  await page.setViewportSize({ width: 390, height: 844 });
  await checkPaginationLayout();
  await page.locator('.point-log-filters').screenshot({ path: '/tmp/emie-point-logs-mobile.png', animations: 'disabled' });
  await page.locator('#pointLogResults .point-log-pagination').screenshot({ path: '/tmp/emie-point-logs-pagination-mobile.png', animations: 'disabled' });
  await page.setViewportSize({ width: 1600, height: 1100 });

  await action('point-logs-reset').click(); await settled();
  for (const id of ['personKeyword', 'from', 'to', 'source', 'keyword', 'sign']) assert.equal(await page.locator(`#pointLog-${id}`).inputValue(), '');
  for (const request of requests.slice(-2)) assert.deepEqual(request.params, { page: '0', size: '20' });
  assert.match(await page.locator('#pointLogResults').innerText(), /共 23 条/);

  await query('没有此人');
  assert.match(await page.locator('#pointLogResults').innerText(), /暂无积分记录/);
  assert.match(await page.locator('#pointAuditResults').innerText(), /未发现积分异常/);
  assert.equal(await pager('logs').last().isDisabled(), true);
  failLogs = true;
  await query('黄海岚');
  assert.match(await page.locator('#pointLogResults').innerText(), /积分日志加载失败/);
  assert.equal(await page.locator('#pointAuditResults tbody tr').count(), 20, '日志接口失败不阻断积分核对');
  failLogs = false;
  await query('黄海岚');
  assert.equal(await page.locator('#pointLogResults tbody tr').count(), 20, '失败后可重新查询恢复');
  assert.deepEqual(mutations, [], '界面回归不得发出写请求');
  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  process.stdout.write('point-logs: name/account/partial search, Enter, filters, independent paging, reset, responsive layout, empty/error recovery passed; external writes=0\n');
} catch (error) {
  await page.screenshot({ path: '/tmp/emie-point-logs-failure.png', fullPage: true }).catch(() => {});
  throw error;
} finally {
  await browser.close();
}
