import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Real navigation, points and adjustment modules; all API reads/writes use in-memory fixtures.
const repository = fileURLToPath(new URL('../../', import.meta.url));
const staticRoot = path.join(repository, 'src/main/resources/static');
const artifacts = path.join(repository, 'scripts/modal-e2e/test-results/planner-points');
await fs.mkdir(artifacts, { recursive: true });
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/css/app.css"></head><body><main id="mainContent" style="padding:20px;max-width:1200px;margin:auto"></main><script type="module">
window.EMIE = { actions: { doneOpenModal(){}, isModalOpen(){return false;} }, modules:{}, state:{renderId:1,permissions:['page.points.view'],departments:[],users:{}}, adminState:{}, registerActions(values){Object.assign(this.actions,values);}, registerModule(name,value){this.modules[name]=value;} };
EMIE.actions.hasPermission = code => EMIE.state.permissions.includes(code);
await import('/js/event-runtime.js'); await import('/js/core-ui.js'); await import('/js/core-shell.js'); await import('/js/dashboard-points.js'); await import('/js/admin-scoring.js');
document.documentElement.dataset.ready='true';
</script></body></html>`;
const allowed = new Set(['/css/app.css', '/js/event-runtime.js', '/js/core-ui.js', '/js/core-shell.js', '/js/dashboard-points.js', '/js/admin-scoring.js']);
const realApiRequests = [];
const server = http.createServer(async (request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  if (pathname.startsWith('/api/')) realApiRequests.push(pathname);
  if (pathname === '/') { response.writeHead(200, { 'content-type':'text/html' }); return response.end(html); }
  if (!allowed.has(pathname)) { response.writeHead(404); return response.end(); }
  try {
    const body = await fs.readFile(path.join(staticRoot, pathname));
    response.writeHead(200, { 'content-type':pathname.endsWith('.js') ? 'text/javascript' : 'text/css' }); response.end(body);
  } catch { response.writeHead(500); response.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let executablePath = process.env.CHROME_BIN;
if (!executablePath) {
  const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  try { await fs.access(chrome); executablePath = chrome; } catch { /* Playwright-managed Chromium */ }
}
let browser;
try {
  browser = await chromium.launch({ headless:true, executablePath });
  const page = await browser.newPage({ viewport:{width:1440,height:1100}, reducedMotion:'reduce' });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', route => { realApiRequests.push(route.request().url()); return route.abort('blockedbyclient'); });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => document.documentElement.dataset.ready === 'true');
  await page.evaluate(() => {
    const e = EMIE;
    const f = window.plannerPointsFixture = { reads:[], writes:[], loads:[], toasts:[], mode:'normal', pending:[], deferMonth:null, deferRules:false, deferUsers:false, deferLoad:false };
    window.actualAdjustmentOpen = e.actions.openManualAdjustmentModal;
    window.designerRows = month => [
      { userId:'d1', name:'设计师甲', points:month === '2026-10' ? 25 : 8 },
      { userId:'d2', name:'其他企划的设计师乙', points:month === '2026-10' ? 12.5 : 4 },
      { userId:'d-zero', name:'零分设计师', points:0 },
    ];
    const users = [{ userId:'d1', name:'设计师甲', role:'designer', status:'active' }];
    e.actions.apiGet = async url => {
      f.reads.push({url,role:e.state.currentRole,userId:e.state.currentUserId});
      const parsed = new URL(url, location.origin), route = parsed.pathname;
      if (route === '/performance/designer-leaderboard') {
        if (!['admin','planner'].includes(e.state.currentRole)) throw new Error('403: designer overview');
        if (f.deferMonth && parsed.searchParams.get('month') === f.deferMonth) return new Promise((resolve,reject) => { f.pending.push({url,resolve,reject}); });
        if (f.mode === 'error') throw new Error('模拟积分读取失败');
        if (f.mode === 'empty') return [];
        return window.designerRows(parsed.searchParams.get('month'));
      }
      if (route === '/performance/leaderboard') return [{userId:'designer',name:'本人设计师',points:9}];
      if (route === '/performance/preview') return {points:9,targetPoints:100,attainmentRate:.09};
      if (route === '/points/me') return {balance:9,ledger:[],ledgerPages:0,ledgerTotal:0,adjustmentLedger:[]};
      if (route === '/points/rules') {
        if (f.deferRules) return new Promise(resolve => { f.resolveRules = resolve; });
        return [{ruleCode:'A1',category:'A',description:'模拟设计规则',points:3,enabled:true}];
      }
      if (route === '/point-governance/po/me') return {projects:[],progress:[]};
      if (route === '/admin/configs') return {business:[]};
      if (route === '/admin/users') {
        if (f.deferUsers) return new Promise(resolve => { f.resolveUsers = () => resolve(users); });
        return users;
      }
      return [];
    };
    e.actions.apiPost = async (url,body) => { f.writes.push({url,body,role:e.state.currentRole}); return {}; };
    e.actions.showAdminToast = message => f.toasts.push(message);
    e.loadViewModules = async view => {
      f.loads.push({view,role:e.state.currentRole});
      if (f.deferLoad) return new Promise(resolve => { f.resolveLoad = () => { e.actions.openManualAdjustmentModal=window.actualAdjustmentOpen; resolve(); }; });
      e.actions.openManualAdjustmentModal=window.actualAdjustmentOpen;
    };
    window.renderPointsRole = async role => {
      document.dispatchEvent(new CustomEvent('emie:identity-changing'));
      e.state.currentRole=role; e.state.currentUserId=role; e.state.authUser={userId:role,role}; e.state.renderId++;
      e.state.permissions=role==='admin' ? ['page.points.view','page.admin.view'] : ['page.points.view'];
      localStorage.setItem('design_pm_token',`isolated-${role}`);
      await e.modules.dashboardPoints.renderPointsView(document.getElementById('mainContent'));
    };
  });
  const host = page.locator('#mainContent');
  const ranks = host.locator('.points-rank-row');
  const render = role => page.evaluate(value => window.renderPointsRole(value), role);
  const month = async value => {
    await host.locator('#pointsMonthFilter').evaluate((input,selected) => {
      input.value=selected;
      input.dispatchEvent(new Event('change',{bubbles:true}));
    },value);
  };
  const settled = () => page.waitForFunction(() => !document.querySelector('#pointsLeaderboardBody .loading'));
  const assertPlannerControls = async () => {
    assert.match(await host.locator('h2').innerText(), /设计师积分总览/);
    assert.equal(await host.locator('[data-emie-action="click:points-manual-adjust"], .points-admin-governance-card, .points-admin-program-grid, .points-ledger-card, #pointsPerformancePreview').count(), 0);
    assert.equal(await host.locator('[data-emie-action="click:points-eligibility"], [data-emie-action="click:points-save-governance"], [data-emie-action="click:points-create-po"], [data-emie-action="click:points-review-po"]').count(), 0);
  };
  await render('planner');
  assert.equal(await page.evaluate(() => EMIE.actions.canNavigateTo('points')), true);
  assert.equal(await page.evaluate(() => EMIE.actions.canNavigateTo('admin') || EMIE.actions.canNavigateTo('designer-performance')), false);
  await assertPlannerControls();
  await month('2026-10'); await settled();
  assert.equal(await ranks.count(), 3);
  assert.deepEqual(await ranks.locator('.points-rank-person strong').allTextContents(), ['设计师甲','其他企划的设计师乙','零分设计师']);
  assert.deepEqual((await ranks.locator('.points-rank-score').allTextContents()).map(value=>value.trim()), ['25分','12.5分','0分']);
  const plannerReads = await page.evaluate(() => plannerPointsFixture.reads.filter(item=>item.role==='planner').map(item=>item.url));
  assert(plannerReads.includes('/performance/designer-leaderboard?month=2026-10'));
  assert.equal(plannerReads.some(url => /^\/admin\//.test(url) || url.startsWith('/projects/my-subtasks')
    || url.startsWith('/performance/preview') || url.startsWith('/performance/leaderboard') || url.startsWith('/points/me')
    || url.startsWith('/point-governance/po/progress')), false, '企划总览不读取管理接口、本人任务/积分或绩效预览');
  await page.evaluate(async () => {
    await EMIE.actions.openManualAdjustmentModal();
    const forged = document.createElement('div'); forged.id='manualAdjustmentModal';
    forged.innerHTML='<input id="manualAdjUser" value="d1"><input id="manualAdjPoints" value="2"><textarea id="manualAdjReason">伪造调整</textarea><button>提交</button>';
    document.body.append(forged); await EMIE.actions.submitManualAdjustment(forged.querySelector('button'));
    for (const action of ['points-manual-adjust','points-eligibility','points-save-governance','points-create-po','points-review-po']) {
      const button=document.createElement('button'); button.dataset.emieAction='click:'+action; document.body.append(button); button.click(); button.remove();
    }
  });
  assert.equal(await page.locator('#manualAdjustmentModal').count(), 0);
  assert.equal(await page.evaluate(() => plannerPointsFixture.writes.length), 0);
  assert.equal(await page.evaluate(() => plannerPointsFixture.loads.length), 0, '企划不加载管理模块');
  await host.screenshot({path:path.join(artifacts,'planner-desktop.png'),animations:'disabled'});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await host.evaluate(element=>element.scrollWidth<=element.clientWidth+1), true, '手机总览不横向溢出');
  assert.equal(await host.locator('.points-admin-leaderboard-panel h3').evaluate(element=>element.getBoundingClientRect().height<=45),true,
    '窄屏标题不被月份控件挤成竖排');
  await host.screenshot({path:path.join(artifacts,'planner-mobile.png'),animations:'disabled'});
  await page.setViewportSize({width:1440,height:1100});

  await page.evaluate(()=>{plannerPointsFixture.mode='error';});
  await month('2026-09'); await settled();
  assert.match(await host.locator('#pointsLeaderboardBody').innerText(), /模拟积分读取失败/);
  await page.evaluate(()=>{plannerPointsFixture.mode='empty';});
  await month('2026-08'); await settled();
  assert.match(await host.locator('#pointsLeaderboardBody').innerText(), /暂无在职设计师/);
  await page.evaluate(()=>{plannerPointsFixture.mode='normal';plannerPointsFixture.deferMonth='2026-09';});
  await month('2026-09');
  await page.waitForFunction(()=>plannerPointsFixture.pending.length===1);
  await month('2026-10'); await settled();
  await page.evaluate(async()=>{plannerPointsFixture.pending.shift().resolve([{userId:'old',name:'过期月份',points:999}]);await new Promise(resolve=>requestAnimationFrame(resolve));});
  assert.equal(await ranks.count(),3);
  assert.doesNotMatch(await host.locator('#pointsLeaderboardBody').innerText(),/999|过期月份/);
  await page.evaluate(()=>{plannerPointsFixture.deferMonth='2026-08';});
  await month('2026-08'); await page.waitForFunction(()=>plannerPointsFixture.pending.length===1);
  await month('2026-10'); await settled();
  await page.evaluate(async()=>{plannerPointsFixture.pending.shift().reject(new Error('过期错误'));await new Promise(resolve=>requestAnimationFrame(resolve));});
  assert.doesNotMatch(await host.locator('#pointsLeaderboardBody').innerText(),/过期错误/);
  await page.evaluate(()=>{plannerPointsFixture.deferMonth=null;});
  await page.evaluate(async()=>{await EMIE.actions.refreshPointsMonth('total');});
  assert.equal(await page.evaluate(()=>plannerPointsFixture.reads.at(-1).url), '/performance/designer-leaderboard');

  await render('planner');
  await page.evaluate(()=>{plannerPointsFixture.deferMonth='2026-09';});
  await month('2026-09'); await page.waitForFunction(()=>plannerPointsFixture.pending.length===1);
  await render('designer');
  await page.evaluate(async()=>{plannerPointsFixture.pending.shift().resolve([{userId:'old',name:'旧企划私有内容',points:999}]);await new Promise(resolve=>requestAnimationFrame(resolve));});
  assert.equal(await host.locator('#pointsPerformancePreview').count(),1);
  assert.equal(await host.locator('.points-ledger-card').count(),1);
  assert.deepEqual(await ranks.locator('.points-rank-person strong').allTextContents(),['本人设计师']);
  assert.doesNotMatch(await host.innerText(),/旧企划私有内容|999/);
  const designerReads=await page.evaluate(()=>plannerPointsFixture.reads.filter(item=>item.role==='designer').map(item=>item.url));
  assert(designerReads.some(url=>url.startsWith('/performance/leaderboard?')));
  assert(designerReads.some(url=>url.startsWith('/performance/preview?')));
  assert.equal(designerReads.some(url=>url.startsWith('/performance/designer-leaderboard')),false,'设计师仍使用原排行榜，不扩大接口');
  await page.evaluate(()=>{plannerPointsFixture.deferMonth=null;plannerPointsFixture.deferRules=true;window.oldPointsRender=EMIE.modules.dashboardPoints.renderPointsView(document.getElementById('mainContent'));});
  await page.waitForFunction(()=>typeof plannerPointsFixture.resolveRules==='function');
  await page.evaluate(()=>{plannerPointsFixture.deferRules=false;});
  await render('planner');
  await page.evaluate(async()=>{plannerPointsFixture.resolveRules([]);await window.oldPointsRender;});
  await assertPlannerControls();
  assert.equal(await ranks.count(),3,'旧个人页初始请求不能覆盖新企划页面');

  await render('admin'); await month('2026-10'); await settled();
  assert.equal(await host.locator('[data-emie-action="click:points-manual-adjust"]').count(),1);
  assert.deepEqual((await ranks.locator('.points-rank-score').allTextContents()).map(value=>value.trim()),['25分','12.5分','0分'],'管理员与企划使用同一设计师数据');
  await page.evaluate(()=>{delete EMIE.actions.openManualAdjustmentModal;});
  await host.locator('[data-emie-action="click:points-manual-adjust"]').click();
  const adjustment=page.locator('#manualAdjustmentModal');
  await adjustment.waitFor({state:'visible'});
  assert.deepEqual(await page.evaluate(()=>plannerPointsFixture.loads),[{view:'admin',role:'admin'}],'首次直接打开积分可按需加载调账');
  await adjustment.locator('#manualAdjUser').selectOption('d1');
  await adjustment.locator('#manualAdjPoints').fill('2.5');
  await adjustment.locator('#manualAdjReason').fill('仅模拟管理员调整');
  const rankingReadsBeforeAdjustment=await page.evaluate(()=>plannerPointsFixture.reads.filter(item=>item.url==='/performance/designer-leaderboard?month=2026-10').length);
  await adjustment.locator('[data-emie-action="click:scoring-submit-adjustment"]').click();
  await adjustment.waitFor({state:'hidden'});
  await page.waitForFunction(before=>plannerPointsFixture.reads.filter(item=>item.url==='/performance/designer-leaderboard?month=2026-10').length>before,rankingReadsBeforeAdjustment);
  await settled();
  assert.equal(await host.locator('#pointsMonthFilter').inputValue(),'2026-10','管理员调账后保留所选月份并刷新排行榜');
  assert.deepEqual(await page.evaluate(()=>plannerPointsFixture.writes),[{url:'/point-governance/manual-adjustment',body:{userId:'d1',points:2.5,reason:'仅模拟管理员调整'},role:'admin'}]);

  await host.locator('[data-emie-action="click:points-manual-adjust"]').click(); await adjustment.waitFor({state:'visible'});
  await adjustment.locator('#manualAdjUser').selectOption('d1'); await adjustment.locator('#manualAdjPoints').fill('2');
  await adjustment.locator('#manualAdjReason').fill('旧管理员窗口');
  await page.evaluate(async()=>{EMIE.state.currentRole='planner';EMIE.state.currentUserId='planner';EMIE.state.authUser={userId:'planner',role:'planner'};await EMIE.actions.submitManualAdjustment(document.querySelector('#manualAdjustmentModal button'));});
  assert.equal(await adjustment.count(),0);
  assert.equal(await page.evaluate(()=>plannerPointsFixture.writes.length),1,'旧管理员窗口在企划身份不能提交');
  await render('admin');
  await page.evaluate(()=>{plannerPointsFixture.deferUsers=true;window.oldAdjustmentOpen=EMIE.actions.openManualAdjustmentModal();});
  await page.waitForFunction(()=>typeof plannerPointsFixture.resolveUsers==='function');
  await render('planner');
  await page.evaluate(async()=>{plannerPointsFixture.resolveUsers();await window.oldAdjustmentOpen;plannerPointsFixture.deferUsers=false;});
  assert.equal(await adjustment.count(),0,'成员列表迟到响应不能给企划打开管理弹窗');
  await render('admin');
  await page.evaluate(()=>{plannerPointsFixture.deferLoad=true;delete EMIE.actions.openManualAdjustmentModal;});
  await host.locator('[data-emie-action="click:points-manual-adjust"]').click();
  await page.waitForFunction(()=>typeof plannerPointsFixture.resolveLoad==='function');
  const usersBeforeSwitch=await page.evaluate(()=>plannerPointsFixture.reads.filter(item=>item.url==='/admin/users').length);
  await render('planner');
  await page.evaluate(async()=>{plannerPointsFixture.resolveLoad();await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
  assert.equal(await adjustment.count(),0,'模块加载中切企划不能打开旧调账弹窗');
  assert.equal(await page.evaluate(()=>plannerPointsFixture.reads.filter(item=>item.url==='/admin/users').length),usersBeforeSwitch);
  await assertPlannerControls();
  assert.equal(await page.evaluate(()=>plannerPointsFixture.writes.filter(item=>item.role!=='admin').length),0);
  assert.deepEqual(realApiRequests,[],'不访问真实业务接口');
  assert.deepEqual(errors,[]);
  console.log(`Planner points checks passed: all designers including zero, shared admin data, monthly/total queries, read-only controls, forced calls blocked, lazy admin adjustment, errors/empty/concurrent/stale identity responses, designer scope and mobile. Artifacts: ${artifacts}`);
} finally {
  if (browser) await browser.close();
  await new Promise(resolve=>server.close(resolve));
}
