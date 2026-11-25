import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Actual frontend modules and modal infrastructure, with isolated in-memory API fixtures.
const repository = fileURLToPath(new URL('../../', import.meta.url));
const staticRoot = path.join(repository, 'src/main/resources/static');
const artifacts = path.join(repository, 'scripts/modal-e2e/test-results/monthly-point-reconciliation');
await fs.mkdir(artifacts, { recursive: true });
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="/css/app.css"></head><body><main id="testHost" style="padding:20px;max-width:1200px;margin:auto"></main><script type="module">
window.EMIE = { actions: { doneOpenModal() {}, isModalOpen() { return false; }, renderDesignerEvaluationSummary() { return ''; } }, modules: {}, state: { currentRole: 'admin', currentUserId: 'admin', authUser: { userId: 'admin' } }, adminState: {}, registerActions(actions) { Object.assign(this.actions, actions); }, registerModule(name, module) { this.modules[name] = module; } };
await import('/js/event-runtime.js'); await import('/js/core-ui.js'); await import('/js/admin-scoring.js'); await import('/js/designer-performance.js');
document.documentElement.dataset.ready = 'true';
</script></body></html>`;
const allowed = new Set(['/css/app.css', '/js/event-runtime.js', '/js/core-ui.js', '/js/admin-scoring.js', '/js/designer-performance.js']);
const realApiRequests = [];
const server = http.createServer(async (request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  if (pathname.startsWith('/api/')) realApiRequests.push(pathname);
  if (pathname === '/') { response.writeHead(200, { 'content-type': 'text/html' }); return response.end(html); }
  if (!allowed.has(pathname)) { response.writeHead(404); return response.end('Unknown isolated resource'); }
  try {
    const contents = await fs.readFile(path.join(staticRoot, pathname));
    response.writeHead(200, { 'content-type': pathname.endsWith('.js') ? 'text/javascript' : 'text/css' });
    response.end(contents);
  } catch { response.writeHead(500); response.end('Static source unavailable'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let executablePath = process.env.CHROME_BIN;
if (!executablePath) {
  const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  try { await fs.access(chrome); executablePath = chrome; } catch { /* Playwright-managed Chromium */ }
}
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, reducedMotion: 'reduce' });
  const errors = [], downloads = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('download', download => downloads.push(download));
  await page.route('**/api/**', route => { realApiRequests.push(route.request().url()); return route.abort('blockedbyclient'); });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true');
  await page.evaluate(() => {
    const e = window.EMIE;
    const tasks = Array.from({ length: 10 }, (_, index) => ({
      id: index + 1, kind: index < 7 ? 'task' : 'requirement',
      ruleVersion: index < 7 ? 'legacy' : '3.0', ruleCode: index < 7 ? 'A1' : 'VIS_V3_05',
      name: `${index < 7 ? '子任务' : '设计需求'} ${index + 1}`, project: index < 7 ? '核对项目' : '设计/送审需求',
      completedAt: '2026-10-20T10:00:00', category: '包装设计',
      periodPoints: index === 0 ? 4 : index < 7 ? 2 : 3,
      otherPeriodPoints: index === 0 ? 2 : 0, score: index === 0 ? 6 : index < 7 ? 2 : 3,
    }));
    const items = tasks.map((task, index) => ({
      id: `${index < 7 ? 'L' : 'A'}-${task.id}`, accountingMonth: '2026-10',
      createdAt: index === 0 ? '2026-11-01T10:00:00' : '2026-10-21T10:00:00',
      source: index < 7 ? 'TASK_BASE' : 'DESIGN_REQUIREMENT',
      taskId: index < 7 ? task.id : null, requirementId: index < 7 ? null : task.id,
      taskName: index < 7 ? task.name : null, requirementName: index < 7 ? null : task.name,
      project: task.project, completedAt: task.completedAt,
      ruleCode: task.ruleCode, ruleDescription: '模拟规则', points: task.periodPoints, reason: '模拟入账说明',
    }));
    items.push({ id: 'A-11', source: 'MANUAL', points: -2, accountingMonth: '2026-10', reason: '管理员扣减', createdAt: '2026-10-25T10:00:00' },
      { id: 'A-12', source: 'MATERIAL_MARKET', points: 2, accountingMonth: '2026-10', reason: '素材采纳奖励', createdAt: '2026-10-26T10:00:00' });
    const f = window.reconciliationFixture = { tasks, items, reads: [], exports: [], mode: 'normal', period: '2026-10' };
    window.reconciliationReport = period => ({ month: period, designers: [{
      userId: 'designer', designer: '核对设计师', score: 25, completedCount: 10,
      categoryCounts: { 包装设计: 10 }, tasks: f.tasks,
    }] });
    window.reconciliationDetails = month => ({ month, userId: 'designer', designer: '核对设计师', score: 25, items: f.items });
    localStorage.setItem('design_pm_token', 'isolated-admin');
    e.actions.apiGet = async (url, options) => {
      f.reads.push({ url, options });
      const params = new URL(url, location.origin).searchParams;
      if (/\/designer\/points\?/.test(url)) {
        if (f.mode === 'error') throw new Error('模拟读取失败');
        if (f.mode === 'deferred') return new Promise(resolve => { f.resolveDetails = () => resolve(window.reconciliationDetails(params.get('month'))); });
        return window.reconciliationDetails(params.get('month'));
      }
      if (url.startsWith('/performance/designer-monthly-report')) return window.reconciliationReport(params.get('month'));
      if (url.startsWith('/performance/my-report')) return window.reconciliationReport(params.get('period'));
      if (url.startsWith('/performance/growth-scores')) return { configured: false, status: 'not_configured', users: [] };
      if (url.startsWith('/points/me')) return { balance: 27 };
      return [];
    };
    e.actions.authHeaders = token => ({ 'X-Auth-Token': token });
    e.actions.submitGuard = async (button, action) => { button.disabled = true; try { return await action(); } finally { button.disabled = false; } };
    e.actions.fetchWithTimeout = async (url, options) => {
      f.exports.push({ url, options });
      const result = () => new Response('isolated workbook', { headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' } });
      return f.deferExport ? new Promise(resolve => { f.resolveExport = () => resolve(result()); }) : result();
    };
    window.renderReconciliation = async period => {
      e.state.currentRole = 'admin'; e.state.currentUserId = 'admin'; e.state.authUser = { userId: 'admin' };
      f.mode = 'normal'; f.period = period;
      await e.actions.renderAdminDesignerPerformance(document.getElementById('testHost'));
      const input = document.getElementById('designerPerformanceMonth');
      input.type = period.length === 4 ? 'number' : 'month'; input.value = period;
      await e.actions.reloadDesignerMonthlyReport();
    };
  });
  const host = page.locator('#testHost');
  const scoreButton = () => host.locator('[data-emie-action="click:scoring-period-points"]');
  const modal = page.locator('#designerPointDetailsModal');
  const open = async () => { await scoreButton().click(); await modal.locator('[data-point-total]').waitFor(); };
  const close = async () => { await modal.locator('[data-emie-action="click:scoring-close-period-points"]').first().click(); await modal.waitFor({ state: 'hidden' }); };
  const render = period => page.evaluate(value => window.renderReconciliation(value), period);
  await render('2026-10');
  assert.match(await scoreButton().innerText(), /25\.00/);
  assert.equal(await host.locator('.designer-performance-task:visible').count(), 10);
  assert.match(await host.locator('.designer-performance-task').first().innerText(), /当月归属 4\.00 积分/);
  assert.match(await host.locator('.designer-performance-task').first().innerText(), /累计 6\.00 积分；其他月份 2\.00 积分/);
  await host.locator('#designerPerformanceRuleVersion').selectOption('3.0');
  assert.equal(await host.locator('.designer-performance-task:visible').count(), 3, '需求参与规则版本筛选');
  assert.match(await scoreButton().innerText(), /25\.00/, '完成事项筛选不改变归属总分');
  assert.match(await host.innerText(), /规则版本仅筛选完成事项/);
  await open();
  assert.equal(await modal.locator('tbody tr').count(), 12, '完整列出所有来源，包含完成事项以外调账');
  assert.equal(await modal.locator('[data-point-total]').innerText(), '25.00');
  assert.match(await modal.innerText(), /管理员调账/);
  assert.match(await modal.innerText(), /设计\/送审需求/);
  assert.match(await modal.locator('[data-point-id="A-8"]').innerText(), /设计需求 8/, '需求显示具体名称');
  assert.match(await modal.innerText(), /2026-11-01 10:00:00/);
  const detailRequest = await page.evaluate(() => reconciliationFixture.reads.find(item => /\/designer\/points\?/.test(item.url)));
  assert.equal(detailRequest.url, '/performance/designer-monthly-report/designer/points?month=2026-10');
  assert.deepEqual(detailRequest.options, { fresh: true });
  await page.screenshot({ path: path.join(artifacts, 'points-desktop.png'), animations: 'disabled' });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await modal.locator('.modal').evaluate(element => {
    const box = element.getBoundingClientRect(); return box.left >= -1 && box.right <= innerWidth + 1;
  }), true, '手机弹窗不超出屏幕');
  assert.equal(await modal.locator('.modal-body').evaluate(element => element.scrollWidth <= element.clientWidth + 1), true);
  assert.equal(await modal.locator('.table-wrap').evaluate(element => element.scrollWidth > element.clientWidth), true,
    '手机表格独立横向滚动，保留完整列宽');
  await page.screenshot({ path: path.join(artifacts, 'points-mobile.png'), animations: 'disabled' });
  await page.keyboard.press('Escape');
  await modal.waitFor({ state: 'hidden' });
  await page.setViewportSize({ width: 1440, height: 1100 });

  const exportButton = host.getByRole('button', { name: '导出月度积分与完成事项', exact: true });
  const downloadPromise = page.waitForEvent('download');
  await exportButton.click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), '设计师月度积分与完成事项-2026-10.xlsx');
  const exported = await page.evaluate(() => reconciliationFixture.exports.at(-1));
  assert.deepEqual(Object.fromEntries(new URL(exported.url, 'http://localhost').searchParams), { month: '2026-10', ruleVersion: '3.0' });
  assert.equal(exported.options.headers['X-Auth-Token'], 'isolated-admin');

  await page.evaluate(() => { reconciliationFixture.mode = 'deferred'; });
  await scoreButton().click();
  await page.waitForFunction(() => typeof reconciliationFixture.resolveDetails === 'function');
  await close();
  await page.evaluate(async () => { reconciliationFixture.resolveDetails(); await new Promise(resolve => requestAnimationFrame(resolve)); });
  assert.equal(await modal.count(), 0, '关闭后迟到请求不重建弹窗');
  await scoreButton().click();
  await page.evaluate(() => {
    document.dispatchEvent(new CustomEvent('emie:identity-changing'));
    EMIE.state.currentRole = 'designer';
    reconciliationFixture.resolveDetails();
  });
  assert.equal(await modal.count(), 0, '切换身份立即关闭积分明细');
  await render('2026-10');
  await page.evaluate(() => { reconciliationFixture.mode = 'error'; });
  await scoreButton().click();
  await modal.getByText('积分明细加载失败：模拟读取失败').waitFor();
  await close();

  await page.evaluate(() => {
    reconciliationFixture.mode = 'normal';
    reconciliationFixture.items[0].reason = '<img src=x onerror=alert(1)>';
    reconciliationFixture.items[0].taskName = '<script>unsafe()</script>';
  });
  await open();
  assert.equal(await modal.locator('img,script').count(), 0);
  assert.match(await modal.innerText(), /<img src=x onerror=alert\(1\)>/);
  await close();

  await render('2026');
  await open();
  assert.match(await modal.innerText(), /全年归属积分/);
  assert.equal(await page.evaluate(() => reconciliationFixture.reads.at(-1).url), '/performance/designer-monthly-report/designer/points?month=2026');
  await close();
  assert.equal(await host.locator('[data-emie-action="click:scoring-export-subtasks"]').count(), 0);

  await render('2026-10');
  await page.evaluate(() => { reconciliationFixture.deferExport = true; });
  await host.getByRole('button', { name: '导出月度积分与完成事项', exact: true }).click();
  await page.waitForFunction(() => typeof reconciliationFixture.resolveExport === 'function');
  await page.evaluate(async () => {
    document.dispatchEvent(new CustomEvent('emie:identity-changing'));
    EMIE.state.currentRole = 'designer';
    reconciliationFixture.resolveExport();
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  assert.equal(downloads.length, 1, '切换身份后不下载迟到导出');

  await page.evaluate(async () => {
    EMIE.state.currentRole = 'designer'; EMIE.state.currentUserId = 'designer';
    await EMIE.actions.renderDesignerPersonalPerformance(document.getElementById('testHost'));
    document.getElementById('personalPerformancePeriod').value = '2026-10';
    document.getElementById('personalPerformancePeriod').dispatchEvent(new Event('change', { bubbles: true }));
  });
  await host.locator('.designer-performance-task').first().waitFor();
  assert.match(await host.locator('.designer-performance-task').first().innerText(), /当月归属 4\.00 积分/);
  assert.match(await host.locator('.designer-performance-task').first().innerText(), /累计 6\.00 积分；其他月份 2\.00 积分/);
  assert.equal(await host.locator('[data-emie-action="click:scoring-period-points"]').count(), 0);
  assert.deepEqual(realApiRequests, [], '无真实业务接口请求');
  assert.deepEqual(errors, []);
  console.log(`Monthly point reconciliation checks passed: complete sources and totals, requirement filters, period/lifetime split, XLSX parameters, mobile, errors, escaping, closed/stale identity responses, personal view. Artifacts: ${artifacts}`);
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
