import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Isolated browser/component regression: actual modules and CSS, simulated API only.
// It starts its own loopback static server, and cannot read or change business data.
const repository = fileURLToPath(new URL('../../', import.meta.url));
const staticRoot = path.join(repository, 'src/main/resources/static');
const artifacts = process.env.EMIE_GROWTH_ARTIFACTS || path.join(repository, 'outputs', `growth-scores-${Date.now()}`);
await fs.mkdir(artifacts, { recursive: true });
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="/css/app.css"></head><body><main id="testHost" style="padding:20px;max-width:1200px;margin:auto"></main><script type="module">
window.EMIE = { actions: {}, modules: {}, state: {}, adminState: {}, registerActions(actions) { Object.assign(this.actions, actions); }, registerModule(name, module) { this.modules[name] = module; } };
EMIE.actions.escHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
EMIE.actions.closeM = id => document.getElementById(id)?.remove();
await import('/js/event-runtime.js'); await import('/js/designer-evaluation.js'); await import('/js/admin-scoring.js');
document.documentElement.dataset.ready = 'true';
</script></body></html>`;
const allowed = new Set(['/css/app.css', '/js/event-runtime.js', '/js/designer-evaluation.js', '/js/admin-scoring.js']);
const requests = [];
const server = http.createServer(async (request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  requests.push(pathname);
  if (pathname === '/') { response.writeHead(200, { 'content-type': 'text/html' }); return response.end(html); }
  if (!allowed.has(pathname)) { response.writeHead(404); return response.end('Isolated test: unknown resource'); }
  try { const contents = await fs.readFile(path.join(staticRoot, pathname)); response.writeHead(200, { 'content-type': pathname.endsWith('.js') ? 'text/javascript' : 'text/css' }); response.end(contents); }
  catch { response.writeHead(500); response.end('Static source unavailable'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'; try { await fs.access(chrome); executablePath = chrome; } catch { /* Playwright-managed Chromium */ } }
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', route => route.abort('blockedbyclient'));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true');
  await page.evaluate(() => {
    const e = window.EMIE;
    window.fixture = { gets: [], writes: [], saved: [], mode: 'published', month: '2026-10', completion: 100 };
    const f = window.fixture;
    const score = (value, status = 'published', previousScore = null) => ({ score: value, isFinal: status === 'published', status, previousScore, source: 'automatic_report', updatedAt: '2026-11-05T10:00:00Z', revision: 'a'.repeat(64) });
    window.currentGrowth = () => {
      if (f.mode === 'not_configured') return { userId: 'designer', matchStatus: 'not_configured' };
      const matchStatus = f.mode === 'name_mismatch' ? 'name_mismatch' : f.mode === 'unavailable' ? 'unavailable' : 'matched';
      return { userId: 'designer', feishuUserId: 'simulation-feishu-id', name: '接口模拟设计师', matchStatus,
        collaboration360: f.mode === 'published' ? { ...score(0), source: 'weighted_30_70' } : { ...score(null, 'not_published'), source: 'weighted_30_70' },
        weeklyQuality: f.mode === 'published' ? score(90) : score(null, 'rescoring', 88) };
    };
    e.actions.apiGet = async url => {
      f.gets.push(url);
      const params = new URL(url, location.origin).searchParams;
      if (url.startsWith('/performance/designer-targets')) return [];
      if (url.startsWith('/performance/designer-monthly-report')) return { month: params.get('month'), designers: [{ userId: 'designer', designer: '接口模拟设计师', score: 100, completedCount: 0, tasks: [] }] };
      if (url.startsWith('/performance/designer-evaluations/completion')) return { completionScore: f.completion, earnedPoints: 100, targetPoints: f.completion == null ? 0 : 100, completionReason: '月度所得积分 100 ÷ 目标积分 100 × 100 = 100 分' };
      if (url.startsWith('/performance/designer-evaluations')) return f.saved.filter(record => record.monthKey.startsWith(params.get('period')));
      if (url.startsWith('/performance/growth-scores')) {
        if (f.mode === 'legacy_empty') return [];
        if (f.mode === 'legacy_404') throw new Error('GET simulated failed: 404');
        if (f.mode === 'network_failure') throw new Error('Network connection unavailable');
        if (url.includes('?month=')) {
          if (f.defer) return new Promise(resolve => { f.resolveGrowth = resolve; });
          return { configured: f.mode !== 'not_configured', status: f.mode === 'unavailable' ? 'unavailable' : f.mode === 'not_configured' ? 'not_configured' : 'ok', month: params.get('month'), users: [window.currentGrowth()] };
        }
        return window.currentGrowth();
      }
      throw new Error('Unexpected simulated API ' + url);
    };
    e.actions.apiPut = async (url, body) => {
      f.writes.push({ url, body });
      const row = { ...body, userId: 'designer', monthKey: url.split('/').at(-2), version: 1, updatedAt: '2026-11-05T10:00:00', totalScore: body.completionScore * .7 + (body.collaborationScore + body.attendanceScore + body.weeklyReportScore) * .1 - body.weeklyReportDeduction };
      f.saved = [row]; return row;
    };
    window.renderScenario = async ({ mode, saved, period = '2026-10' }) => {
      e.actions.closeM('designerEvaluationModal');
      Object.assign(f, { mode, saved: saved || [], month: period, defer: false, resolveGrowth: null });
      await e.actions.renderAdminDesignerPerformance(document.getElementById('testHost'));
      document.getElementById('designerPerformanceMonth').type = period.length === 4 ? 'number' : 'month';
      document.getElementById('designerPerformanceMonth').value = period;
      await e.actions.reloadDesignerMonthlyReport();
    };
  });
  const host = page.locator('#testHost');
  const modal = page.locator('#designerEvaluationModal');
  const input = key => modal.locator(`[name="${key}"]`);
  const save = modal.locator('[data-emie-action="click:designer-evaluation-save"]');
  const open = async () => { await host.locator('[data-emie-action="click:designer-evaluation-edit"]').first().click(); await modal.locator('#designerEvaluationForm').waitFor(); };
  const render = scenario => page.evaluate(value => window.renderScenario(value), scenario);
  const saved = { userId: 'designer', monthKey: '2026-10', completionScore: 100, collaborationScore: 87, attendanceScore: 100, weeklyReportScore: 78, weeklyReportDeduction: 0, totalScore: 96.5, version: 2,
    collaborationReason: '成长周报 OS 来源记录：weighted_30_70', weeklyReportReason: '成长周报 OS 来源记录：<img src=x onerror=alert(1)>' };

  await render({ mode: 'published', saved: [saved] });
  assert.match(await host.innerText(), /综合绩效：96\.50 分/, 'reading source must preserve saved monthly performance');
  assert.match(await host.locator('.designer-growth-scores').innerText(), /0\.00.*90\.00/s, 'published zero must remain a real score');
  await host.locator('.designer-evaluation-basis summary').click();
  assert.match(await host.locator('.designer-evaluation-basis').innerText(), /读取成长周报 OS 已发布的360 协同分/);
  assert.match(await host.locator('.designer-evaluation-basis').innerText(), /weighted_30_70/);
  assert.doesNotMatch(await host.locator('.designer-evaluation-basis').innerText(), /依据周报提交与完成情况/);
  assert.equal(await host.locator('.designer-evaluation-basis img').count(), 0, 'saved source reason must be escaped');
  await open();
  assert.equal(await input('collaborationScore').inputValue(), '0');
  assert.equal(await input('weeklyReportScore').inputValue(), '90');
  assert.equal(await input('collaborationScore').getAttribute('readonly'), '');
  assert.equal(await input('weeklyReportScore').getAttribute('readonly'), '');
  assert.equal(await input('attendanceScore').getAttribute('readonly'), null);
  assert.match(await modal.innerText(), /本月原保存 87\.00 分/);
  assert.match(await modal.locator('#designerEvaluationTotal').innerText(), /89\.00/);
  assert.equal(await page.evaluate(() => fixture.writes.length), 0, 'opening modal must not write');
  await save.click(); await modal.waitFor({ state: 'detached' });
  assert.deepEqual(await page.evaluate(() => { const { collaborationScore, weeklyReportScore, attendanceScore, completionScore } = fixture.writes[0].body; return [collaborationScore, weeklyReportScore, attendanceScore, completionScore]; }), [0, 90, 100, 100]);
  assert.match(await host.innerText(), /综合绩效：89\.00 分/);

  for (const mode of ['pending', 'name_mismatch', 'unavailable']) {
    await render({ mode });
    await open();
    assert.equal(await input('collaborationScore').inputValue(), '', `${mode} must not use previousScore`);
    assert.equal(await input('weeklyReportScore').inputValue(), '');
    assert.equal(await save.isDisabled(), true, `${mode} must block a new monthly evaluation`);
    if (mode === 'name_mismatch') assert.match(await modal.innerText(), /双方姓名核对不符/);
    if (mode === 'pending') assert.match(await host.locator('.designer-growth-scores').innerText(), /旧分参考：88\.00 分/);
    else assert.doesNotMatch(await host.locator('.designer-growth-scores').innerText(), /旧分参考/);
    await render({ mode, saved: [saved] }); await open();
    assert.equal(await input('collaborationScore').inputValue(), '87');
    assert.equal(await input('weeklyReportScore').inputValue(), '78');
    assert.match(await modal.innerText(), /沿用本月已保存分/);
    assert.equal(await save.isDisabled(), false, 'same-month saved scores remain usable without a new upstream final');
    await page.screenshot({ path: path.join(artifacts, `retained-${mode}.png`), fullPage: true, animations: 'disabled' });
  }

  for (const mode of ['not_configured', 'legacy_empty', 'legacy_404']) {
    await render({ mode }); await open();
    assert.equal(await input('collaborationScore').getAttribute('readonly'), null, `${mode}: original manual behavior`);
    await input('collaborationScore').fill('100'); await input('weeklyReportScore').fill('100'); await input('attendanceScore').fill('100');
    assert.match(await modal.locator('#designerEvaluationTotal').innerText(), /100\.00/);
    await input('weeklyReportDeduction').fill('4');
    assert.match(await modal.locator('#designerEvaluationTotal').innerText(), /填完全部评分/);
    assert.equal(await input('weeklyReportDeduction').evaluate(el => el.checkValidity()), false);
  }

  // A known configured integration must stay locked during a later network failure.
  await render({ mode: 'published', saved: [saved] });
  await page.evaluate(() => { fixture.mode = 'network_failure'; }); await open();
  assert.equal(await input('collaborationScore').getAttribute('readonly'), '');
  assert.equal(await input('collaborationScore').inputValue(), '87');
  assert.match(await modal.innerText(), /沿用本月已保存分.*暂不可用/s);

  // Annual overview must not multiply the upstream request by twelve.
  await render({ mode: 'published', period: '2026' });
  await page.evaluate(() => { fixture.gets = []; });
  await page.evaluate(() => EMIE.actions.reloadDesignerMonthlyReport());
  assert.deepEqual(await page.evaluate(() => fixture.gets.filter(url => url.includes('/growth-scores'))), []);
  assert.match(await host.innerText(), /年度平均绩效/);
  await host.locator('.designer-evaluation-summary summary').click();
  assert.equal(await host.locator('.designer-evaluation-months > div').count(), 12);
  await open();
  assert.equal(await page.evaluate(() => fixture.gets.filter(url => url.includes('/growth-scores')).length), 1, 'one annual-month edit reads only that month');

  await render({ mode: 'published' });
  await page.evaluate(async () => { fixture.defer = true; await EMIE.actions.reloadDesignerMonthlyReport(); });
  assert.match(await host.innerText(), /接口模拟设计师/, 'core report must render before optional upstream resolves');
  assert.equal(await host.locator('.designer-growth-scores').count(), 0);
  await page.evaluate(() => { fixture.resolveGrowth({ configured: true, status: 'ok', users: [currentGrowth()] }); fixture.defer = false; });
  await host.locator('.designer-growth-scores').waitFor();

  // A delayed old-month response cannot replace the current month selection.
  await page.evaluate(async () => { fixture.defer = true; await EMIE.actions.reloadDesignerMonthlyReport(); fixture.oldResolve = fixture.resolveGrowth; fixture.defer = false; document.getElementById('designerPerformanceMonth').value = '2026-11'; await EMIE.actions.reloadDesignerMonthlyReport(); fixture.oldResolve({ configured: true, status: 'unavailable', users: [] }); });
  assert.equal(await host.locator('#designerPerformanceMonth').inputValue(), '2026-11');
  assert.equal(await host.locator('[data-growth-integration-status]').count(), 0);

  await render({ mode: 'pending' }); await open();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => { const box = document.querySelector('#designerEvaluationModal .modal').getBoundingClientRect(); return box.left >= -1 && box.right <= innerWidth + 1; });
  assert.equal(await modal.locator('.modal').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, 'mobile modal must not overflow horizontally');
  await page.screenshot({ path: path.join(artifacts, 'pending-mobile.png'), fullPage: true, animations: 'disabled' });
  assert.deepEqual(errors, []);
  assert.deepEqual(requests.filter(url => url.startsWith('/api/')), [], 'no real API traffic permitted');
  console.log(`Growth scores browser checks passed: final/zero, read-only, saved fallback, identity mismatch, unavailable, manual compatibility, weighted totals, annual requests, deferred/stale response, mobile. Artifacts: ${artifacts}`);
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
