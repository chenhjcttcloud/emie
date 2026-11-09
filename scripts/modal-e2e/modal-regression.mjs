import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { runDesignerPersonalPerformanceChecks } from './designer-performance.mjs';
import { runPointsLedgerLinkChecks } from './points-ledger-links.mjs';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
const repeat = Number.parseInt(process.env.MODAL_E2E_REPEAT || '1', 10);
const artifactRoot = path.resolve(process.env.MODAL_E2E_ARTIFACTS || 'test-results/modal-e2e');
const configuredChrome = process.env.CHROME_BIN;
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

async function executablePath() {
  if (configuredChrome) return configuredChrome;
  try {
    await fs.access(macChrome);
    return macChrome;
  } catch {
    return undefined;
  }
}

function modalMarkup({ id, title, close = true, backdropCloses = false, tall = false }) {
  return `
    <div class="modal-overlay" id="${id}" data-test-backdrop-closes="${backdropCloses}">
      <div class="modal"${close ? '' : ` aria-label="${title}"`}>
        ${close ? `<div class="modal-header">
          <div class="modal-header-left"><div class="modal-title">${title}</div></div>
          <button class="modal-close">✕</button>
        </div>` : ''}
        <div class="modal-body">
          <button type="button">第一个操作</button>
          <input aria-label="弹窗输入框">
          ${tall ? '<div style="height:900px">长内容</div>' : ''}
        </div>
        <div class="modal-footer"><button type="button">最后一个操作</button></div>
      </div>
    </div>`;
}

async function addModal(page, options) {
  const markup = modalMarkup(options);
  await page.evaluate(({ markup, id, backdropCloses }) => {
    const host = document.createElement('div');
    host.innerHTML = markup.trim();
    const overlay = host.firstElementChild;
    if (backdropCloses) {
      overlay.addEventListener('click', event => {
        if (event.target === overlay) overlay.remove();
      });
    }
    const close = overlay.querySelector('.modal-close');
    close?.addEventListener('click', () => overlay.remove());
    document.body.appendChild(overlay);
  }, { markup, id: options.id, backdropCloses: options.backdropCloses });
  const dialog = page.getByRole('dialog', { name: options.title });
  await dialog.waitFor({ state: 'visible' });
  return dialog;
}

async function removeTestModals(page) {
  await page.evaluate(async () => {
    document.querySelectorAll('.modal-overlay[id^="e2e-"]').forEach(node => node.remove());
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
}

async function pressEscape(page) {
  await page.evaluate(() => {
    document.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape',
      code: 'Escape',
      bubbles: true,
      cancelable: true,
    }));
  });
}

async function runChecks(page) {
  await page.setViewportSize({ width: 1280, height: 800 });

  // Legacy templates receive the same accessible header close affordance.
  await page.evaluate(() => {
    const overlay = document.createElement('div');
    overlay.id = 'e2e-normalized';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = '<button class="modal-close-float">✕</button><div class="modal"><div class="modal-header"><div class="modal-title">统一关闭按钮</div></div><div class="modal-body">内容</div></div>';
    overlay.querySelector('button').addEventListener('click', () => overlay.remove());
    document.body.appendChild(overlay);
  });
  const normalized = page.getByRole('dialog', { name: '统一关闭按钮' });
  await normalized.waitFor();
  const normalizedClose = normalized.getByRole('button', { name: '关闭弹窗' });
  await normalizedClose.click();
  await normalized.waitFor({ state: 'detached' });
  process.stdout.write('  close-control ok\n');

  // Escape closes only the topmost dismissible dialog and restores focus.
  await page.evaluate(() => {
    const opener = document.createElement('button');
    opener.id = 'e2e-opener';
    opener.textContent = '打开弹窗';
    document.body.appendChild(opener);
    opener.focus();
  });
  const parent = await addModal(page, { id: 'e2e-parent', title: '父弹窗', close: true });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-parent') !== null, '父弹窗打开后应接管焦点');
  const child = await addModal(page, { id: 'e2e-child', title: '子弹窗', close: true });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-child') !== null, '子弹窗打开后应接管焦点');
  await pressEscape(page);
  await child.waitFor({ state: 'detached' });
  await parent.waitFor({ state: 'visible' });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-parent') !== null, '关闭子弹窗后焦点应回到父弹窗');
  await pressEscape(page);
  await parent.waitFor({ state: 'detached' });
  await assertEventually(page, () => document.activeElement?.id === 'e2e-opener', '关闭父弹窗后焦点应回到触发按钮');
  process.stdout.write('  escape-nesting-focus-restore ok\n');

  // Mandatory dialogs without a close control cannot be bypassed with Escape.
  const mandatory = await addModal(page, { id: 'e2e-mandatory', title: '必选操作', close: false });
  await pressEscape(page);
  await mandatory.waitFor({ state: 'visible' });
  await removeTestModals(page);
  process.stdout.write('  mandatory-dialog ok\n');

  // Backdrop dismissal is opt-in; clicks inside never count as backdrop clicks.
  const guarded = await addModal(page, { id: 'e2e-guarded', title: '保护表单', close: true, backdropCloses: false });
  await page.locator('#e2e-guarded').click({ position: { x: 2, y: 2 } });
  await guarded.waitFor({ state: 'visible' });
  await removeTestModals(page);
  const dismissible = await addModal(page, { id: 'e2e-dismissible', title: '轻量预览', close: true, backdropCloses: true });
  await dismissible.getByRole('button', { name: '第一个操作' }).click();
  await dismissible.waitFor({ state: 'visible' });
  await page.locator('#e2e-dismissible').click({ position: { x: 2, y: 2 } });
  await dismissible.waitFor({ state: 'detached' });
  process.stdout.write('  backdrop-policy ok\n');

  // Tab and Shift+Tab wrap inside the active dialog.
  const focusDialog = await addModal(page, { id: 'e2e-focus', title: '焦点锁定', close: true });
  // Wait for the modal normalizer's requestAnimationFrame focus handoff before
  // exercising the keyboard loop, otherwise that pending handoff can race this check.
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const close = focusDialog.getByRole('button', { name: '关闭弹窗' });
  await close.focus();
  await assertEventually(page, () => document.activeElement?.getAttribute('aria-label') === '关闭弹窗', '焦点锁定测试应先聚焦关闭按钮');
  await page.evaluate(() => document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Tab', code: 'Tab', shiftKey: true, bubbles: true, cancelable: true,
  })));
  await assertEventually(page, () => document.activeElement?.textContent === '最后一个操作', 'Shift+Tab 应从首元素回到末元素');
  await page.evaluate(() => document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Tab', code: 'Tab', bubbles: true, cancelable: true,
  })));
  await assertEventually(page, () => document.activeElement?.getAttribute('aria-label') === '关闭弹窗', 'Tab 应从末元素回到首元素');
  await removeTestModals(page);
  process.stdout.write('  focus-trap ok\n');

  // Low-height desktop and mobile layouts retain reachable header/body/footer.
  for (const viewport of [{ width: 1024, height: 420 }, { width: 390, height: 667 }]) {
    await page.setViewportSize(viewport);
    const responsive = await addModal(page, { id: 'e2e-responsive', title: '响应式弹窗', close: true, tall: true });
    const box = await responsive.boundingBox();
    assert(box, '响应式弹窗应可见');
    assert(box.y >= 0 && box.y + box.height <= viewport.height + 1, `弹窗应限制在 ${viewport.width}x${viewport.height} 视口内`);
    await responsive.getByRole('button', { name: '关闭弹窗' }).waitFor({ state: 'visible' });
    await responsive.getByRole('button', { name: '最后一个操作' }).waitFor({ state: 'visible' });
    const overflow = await responsive.locator('.modal-body').evaluate(node => node.scrollHeight > node.clientHeight);
    assert.equal(overflow, true, '长内容应在弹窗正文内部滚动');
    await removeTestModals(page);
  }
  process.stdout.write('  responsive-layout ok\n');
}

async function runLazyAdminChecks(page) {
  assert.equal(await page.evaluate(() => !!window.EMIE.modules.adminShell), false,
    '登录首屏不应加载管理后台');
  assert.equal(await page.evaluate(() => performance.getEntriesByType('resource')
    .some(entry => /\/js\/(admin-|designer-evaluation)/.test(entry.name))), false,
    '首屏不应请求管理后台脚本');
  let releaseFailure;
  let failedOnce = false;
  const requests = [];
  await page.route('**/js/admin-users.js*', async route => {
    requests.push(route.request().url());
    if (!failedOnce) {
      failedOnce = true;
      await new Promise(resolve => { releaseFailure = resolve; });
      await route.abort('failed');
    } else {
      await route.continue();
    }
  });
  // 同时进入两个管理视图只发起一轮加载；加载期间切页不应被旧结果覆盖。
  const firstRequest = page.waitForRequest('**/js/admin-users.js*');
  await page.evaluate(() => {
    const e = window.EMIE;
    window.lazyAdminSaved = { view: e.state.currentView, cache: e.state.cache.orders,
      get: e.actions.apiGet, dashboard: e.actions.renderDashboard, html: document.getElementById('mainContent').innerHTML };
    e.actions.apiGet = async () => ({});
    e.actions.renderDashboard = async main => { main.textContent = 'lazy-newer-dashboard'; };
    e.state.cache.orders = [{}];
    e.state.currentView = 'admin';
    window.lazyAdminFirst = e.actions.render();
    window.lazyAdminConcurrent = e.loadViewModules('workload').then(() => 'ok', () => 'failed');
  });
  await firstRequest;
  await page.evaluate(async () => {
    window.EMIE.state.currentView = 'dashboard';
    await window.EMIE.actions.render();
  });
  assert.equal(await page.locator('#mainContent').textContent(), 'lazy-newer-dashboard');
  assert.equal(requests.length, 1, '并发加载不应重复下载同一模块');
  releaseFailure();
  await page.evaluate(async () => {
    await window.lazyAdminFirst;
    if (await window.lazyAdminConcurrent !== 'failed') throw new Error('应向调用方报告加载失败');
  });
  assert.equal(await page.locator('#mainContent').textContent(), 'lazy-newer-dashboard',
    '旧页面加载失败不应覆盖当前页面');
  const retried = await page.evaluate(async () => {
    const e = window.EMIE;
    await Promise.all([e.loadViewModules('admin'), e.loadViewModules('designer-performance')]);
    const shell = e.modules.adminShell;
    await e.loadViewModules('workload');
    return !!e.modules.admin && !!e.modules.designerEvaluation && e.modules.adminShell === shell;
  });
  assert.equal(retried, true, '重试和跨入口切换应复用已加载模块');
  assert.equal(requests.length, 2, '只重试失败模块');
  assert.match(requests[1], /retry=1/);
  await page.unroute('**/js/admin-users.js*');
  await page.evaluate(() => {
    const e = window.EMIE, saved = window.lazyAdminSaved;
    e.actions.apiGet = saved.get;
    e.actions.renderDashboard = saved.dashboard;
    e.state.currentView = saved.view;
    e.state.cache.orders = saved.cache;
    document.getElementById('mainContent').innerHTML = saved.html;
  });
  process.stdout.write('  lazy-admin-first-load-concurrency-retry-navigation ok\n');
}

async function runNavigationDataChecks(page) {
  const result = await page.evaluate(async () => {
    const e = window.EMIE;
    const routes = [
      ['dashboard', 'renderDashboard'], ['workload', 'renderAdminWorkload'],
      ['orders', 'renderOrderList'], ['channel', 'renderOrderList'], ['regular', 'renderOrderList'],
      ['design-needs', 'renderOrderList'], ['tasks', 'renderMyTasks'], ['task-market', 'renderTaskMarket'],
      ['materials', 'renderMaterialMarket'], ['image-library', 'renderImageLibrary'],
      ['other-tasks', 'renderDepartmentTasks'], ['scoring', 'renderScoringView'], ['points', 'renderPointsView'],
      ['designer-performance', 'renderAdminDesignerPerformance'], ['admin', 'renderAdmin'], ['logs', 'renderAdmin'],
    ];
    const main = document.getElementById('mainContent');
    const saved = { view: e.state.currentView, role: e.state.currentRole, cache: e.state.cache.orders,
      get: e.actions.apiGet, html: main.innerHTML };
    const originalRenderers = Object.fromEntries(routes.map(([, action]) => [action, e.actions[action]]));
    const requests = [], rendered = [];
    try {
      e.state.currentRole = 'admin';
      for (const action of Object.keys(originalRenderers)) {
        e.actions[action] = async container => {
          rendered.push([e.state.currentView, action]);
          container.textContent = 'rendered-' + e.state.currentView;
        };
      }
      e.actions.apiGet = async url => { requests.push(url); return url.startsWith('/projects?') ? [] : {}; };
      for (const [view] of routes) {
        e.state.currentView = view;
        e.state.cache.orders = [];
        await e.actions.render();
        if (!main.textContent.includes('rendered-' + view)) throw new Error('页面未完成渲染: ' + view);
      }
      return { requests, rendered, expected: routes };
    } finally {
      Object.assign(e.actions, originalRenderers);
      e.actions.apiGet = saved.get;
      e.state.currentView = saved.view;
      e.state.currentRole = saved.role;
      e.state.cache.orders = saved.cache;
      main.innerHTML = saved.html;
    }
  });
  assert.deepEqual(result.rendered, result.expected, '导航应继续调用各页面的数据渲染入口');
  assert.equal(result.requests.filter(url => url.startsWith('/projects?')).length, 0,
    '进入任一页面均不应预取未使用的全量项目列表');
  assert.equal(result.requests.filter(url => url === '/projects/badge-stats').length, result.expected.length,
    '侧栏统计仍应使用独立的最新服务端结果');
  process.stdout.write('  navigation-no-unused-full-project-prefetch ok\n');
}

async function runNavigationTransitionChecks(page) {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.evaluate(async () => {
    const e = window.EMIE;
    const main = document.getElementById('mainContent');
    const app = document.getElementById('appContainer');
    const login = document.getElementById('loginPage');
    const fixture = window.navigationTransitionFixture = {
      saved: {
        actions: { apiGet: e.actions.apiGet, renderDashboard: e.actions.renderDashboard, renderAdmin: e.actions.renderAdmin },
        state: { currentView: e.state.currentView, currentRole: e.state.currentRole, currentUserId: e.state.currentUserId },
        projectListState: e.projectListState, projectListPreset: e.projectListPreset,
        html: main.innerHTML, className: main.className, appDisplay: app.style.display, loginDisplay: login.style.display,
      },
    };
    const pendingTasks = new Promise(resolve => { fixture.releaseTasks = resolve; });
    const pendingAdmin = new Promise(resolve => { fixture.releaseAdmin = resolve; });
    e.actions.apiGet = async url => {
      if (url === '/projects/my-subtasks') {
        fixture.requestedTasks = true;
        return pendingTasks;
      }
      if (url === '/admin/configs') return pendingAdmin;
      if (url === '/projects/task-market') return [];
      if (url.startsWith('/projects/page?')) return { items: [], total: 0, totalPages: 0, page: 0 };
      if (url === '/projects/badge-stats' && e.state.currentView === 'orders') {
        fixture.failedBadge = true;
        throw new Error('模拟侧栏统计失败');
      }
      return {};
    };
    e.actions.renderDashboard = async container => { container.innerHTML = '<h2>模拟过渡工作台</h2>'; };
    e.actions.renderAdmin = async container => {
      container.innerHTML = '<h2>模拟过渡管理页</h2><button>管理选项卡</button>';
      await e.actions.apiGet('/admin/configs');
    };
    e.state.currentView = 'dashboard';
    e.state.currentRole = 'designer';
    e.state.currentUserId = 'transition-ui-designer';
    app.style.display = '';
    login.style.display = 'none';
    await e.actions.render();
  });
  const main = page.locator('#mainContent');
  const indicator = page.locator('#pageLoadingStatus');
  const ordersHeading = main.getByRole('heading', { name: /全部项目/ });
  try {
    const loading = await page.evaluate(() => {
      const e = window.EMIE;
      e.state.currentView = 'tasks';
      window.navigationTransitionFixture.pending = e.actions.render();
      const main = document.getElementById('mainContent');
      const indicator = document.getElementById('pageLoadingStatus');
      return { busy: main.getAttribute('aria-busy'), inert: main.inert, text: main.textContent,
        indicatorVisible: !!indicator?.getClientRects().length };
    });
    assert.equal(loading.busy, 'true');
    assert.equal(loading.inert, true);
    assert.match(loading.text, /模拟过渡工作台/, '同一账号切页时保留旧内容直到新页就绪');
    assert.equal(loading.indicatorVisible, false, '短加载先不闪现加载提示');
    await assertEventually(page, () => window.navigationTransitionFixture.requestedTasks, '任务请求应处于等待中');
    await indicator.waitFor({ state: 'visible' });
    assert.equal(await indicator.getAttribute('role'), 'status');

    const renderView = view => page.evaluate(async view => {
      const e = window.EMIE;
      e.state.currentView = view;
      await e.actions.render();
      const main = document.getElementById('mainContent');
      const animation = getComputedStyle(main).animationName;
      const effect = main.getAnimations().find(motion => motion.animationName === 'pageEnter')?.effect;
      const start = effect?.getKeyframes()[0];
      return { entered: main.classList.contains('page-entered'), animation,
        duration: effect?.getTiming().duration, opacity: Number(start?.opacity),
        translateY: start?.transform ? new DOMMatrixReadOnly(start.transform).m42 : null,
        childAnimation: getComputedStyle(main.firstElementChild).animationName };
    }, view);
    const ordersMotion = await renderView('orders');
    await ordersHeading.waitFor();
    await assertEventually(page, () => window.navigationTransitionFixture.failedBadge, '应尝试更新侧栏统计');
    assert.equal(ordersMotion.entered, true, '真实页面切换时运行统一进入过渡');
    assert.equal(ordersMotion.animation, 'pageEnter');
    assert.equal(ordersMotion.duration, 220);
    assert.equal(ordersMotion.opacity, 0.58);
    assert.equal(ordersMotion.translateY, 7);
    assert.equal(ordersMotion.childAnimation, 'none', '内容只随页面容器过渡一次');
    assert.notEqual(await main.getAttribute('aria-busy'), 'true');
    assert.equal(await main.evaluate(node => node.inert), false);
    await indicator.waitFor({ state: 'hidden' });
    await page.evaluate(async () => {
      const fixture = window.navigationTransitionFixture;
      fixture.releaseTasks([{ id: 96001, name: '迟到的子任务', status: 'pending', designerId: 'transition-ui-designer' }]);
      await fixture.pending;
    });
    assert.equal(await ordersHeading.isVisible(), true, '过期任务响应和徽章请求失败均不能覆盖新页面');
    assert.doesNotMatch(await main.textContent(), /迟到的子任务|加载失败/);
    const entering = page.locator('#mainContent.page-entered');
    await entering.waitFor({ state: 'detached', timeout: 2_000 });
    assert.equal((await renderView('orders')).animation, 'none', '同页刷新不重复播放导航动画');
    assert.equal((await renderView('dashboard')).animation, 'pageEnter');
    await entering.waitFor({ state: 'detached', timeout: 2_000 });
    const revisitMotion = await renderView('orders');
    assert.equal(revisitMotion.animation, 'pageEnter', '再次进入同一页面仍播放导航动画');
    assert.equal(revisitMotion.duration, 220);
    await entering.waitFor({ state: 'detached', timeout: 2_000 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => {
      const e = window.EMIE;
      e.state.currentView = 'admin';
      window.navigationTransitionFixture.pendingAdminRender = e.actions.render();
    });
    await assertEventually(page, () => {
      const main = document.getElementById('mainContent');
      return main.textContent.includes('模拟过渡管理页') && !main.inert && main.getAttribute('aria-busy') !== 'true';
    }, '管理页面外框已渲染时应可操作，无需等待二级内容');
    await main.getByRole('button', { name: '管理选项卡' }).click({ trial: true });
    assert.equal(await main.evaluate(node => getComputedStyle(node).animationName), 'none', '减少动态效果时跳过页面动画');
    const drawer = await addModal(page, { id: 'projectSubTaskDetailModal', title: '减少动态效果抽屉' });
    assert.equal(await drawer.evaluate(node => getComputedStyle(node).animationName), 'none', '减少动态效果时跳过抽屉进入动画');
    const removed = await page.evaluate(() => {
      window.EMIE.actions.closeM('projectSubTaskDetailModal');
      return !document.getElementById('projectSubTaskDetailModal');
    });
    assert.equal(removed, true, '减少动态效果时关闭抽屉应立即移除');
  } finally {
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.evaluate(async () => {
      const e = window.EMIE;
      const { saved, releaseTasks, pending, releaseAdmin, pendingAdminRender } = window.navigationTransitionFixture;
      releaseTasks([]);
      releaseAdmin({});
      await Promise.all([pending, pendingAdminRender]);
      Object.assign(e.actions, saved.actions);
      Object.assign(e.state, saved.state);
      e.projectListState = saved.projectListState;
      e.projectListPreset = saved.projectListPreset;
      const main = document.getElementById('mainContent');
      main.innerHTML = saved.html;
      main.className = saved.className;
      document.getElementById('appContainer').style.display = saved.appDisplay;
      document.getElementById('loginPage').style.display = saved.loginDisplay;
      delete window.navigationTransitionFixture;
    });
  }
  process.stdout.write('  navigation-loading-latest-view-and-reduced-motion ok\n');
}

async function runPointRuleChecks(page) {
  const rules = [
    { ruleCode: 'D30_A', category: '产品设计', subcategory: '概念', description: '产品提案', points: 2 },
    { ruleCode: 'D30_B', category: '产品设计', subcategory: '深化', description: '结构深化', points: 10 },
    { ruleCode: 'D30_C', category: '包装设计', description: '包装任务', points: 6 },
    ...Array.from({ length: 12 }, (_, index) => ({ ruleCode: `D30_C${index + 1}`, category: '包装设计', description: `包装任务${index + 1}`, points: 1 })),
  ];
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(async rules => {
    if (!window.EMIE.actions.renderPointRulePicker) await import('/js/project-tasks.js');
    const original = window.EMIE.actions.apiGet;
    window.EMIE.actions.apiGet = url => {
      if (url === '/points/rules') return Promise.resolve(rules);
      if (url === '/projects/101') return Promise.resolve({ tasks: [{ id: 201 }] });
      return original(url);
    };
    try { await window.EMIE.actions.openTaskChangeBonus(101, 201); }
    finally { window.EMIE.actions.apiGet = original; }
  }, rules);
  const modal = page.locator('#taskChangeBonusModal');
  await modal.waitFor({ state: 'visible' });
  const picker = modal.locator('[data-change-point-rules]');
  assert.equal(await picker.isVisible(), false, '默认无分修改隐藏规则');
  await modal.locator('[name="withPoints"][value="true"]').check();
  assert.equal(await modal.locator('[name="withPoints"]:checked').inputValue(), 'true');
  await picker.waitFor({ state: 'visible' });
  const search = picker.locator('[data-point-search]');
  assert.equal(await picker.locator('[name="difficultyMultiplier"]').count(), 0, '积分选择器不再显示难度系数');
  await search.fill(' d30_b ');
  assert.equal(await picker.locator('.point-rule-row').count(), 1, '关键词不选大类也能查编号');
  await picker.locator('[data-point-rule-code="D30_B"]').click();
  await search.fill('深化');
  assert.equal(await picker.locator('[name="pointRuleCode"]').inputValue(), 'D30_B', '仍匹配的选择应保留');
  await picker.locator('[data-point-category-tab="包装设计"]').click();
  assert.equal(await picker.locator('[name="pointRuleCode"]').inputValue(), '', '大类失配应清空所选规则');
  assert.match(await picker.locator('.point-rule-empty').textContent(), /没有匹配的积分规则/);
  await search.fill('');
  assert.equal(await picker.locator('.point-rule-row').count(), 10, '分页后每页最多显示10条规则');
  await picker.locator('[data-point-category-tab="产品设计"]').click();
  assert.equal(await picker.locator('[data-point-subcategory-tabs]').isVisible(), true);
  assert.equal(await picker.locator('.point-rule-row').count(), 2, '二级不选展示该大类全部规则');
  await picker.locator('[data-point-subcategory-tab="深化"]').click();
  await search.fill('提案');
  assert.match(await picker.locator('.point-rule-empty').textContent(), /没有匹配的积分规则/, '关键词与二级取交集');
  await search.fill('');
  assert.equal(await picker.locator('.point-rule-row').count(), 1);
  await picker.locator('[data-point-rule-code="D30_B"]').click();
  await picker.locator('[data-point-category-tab="包装设计"]').click();
  assert.equal(await picker.locator('[data-point-subcategory-tabs]').isVisible(), false, '包装不显示二级');
  assert.match(await picker.locator('.point-rule-pagination').textContent(), /共 13 条 · 1 \/ 2 页/);
  await picker.locator('.point-rule-pagination').getByRole('button', { name: '下一页' }).click();
  assert.match(await picker.locator('.point-rule-pagination').textContent(), /2 \/ 2 页/);
  await picker.locator('[data-point-rule-code="D30_C12"]').click();
  await picker.locator('.point-rule-pagination').getByRole('button', { name: '上一页' }).click();
  assert.equal(await picker.locator('[name="pointRuleCode"]').inputValue(), 'D30_C12', '切页时保留页外的已选规则');
  await picker.locator('[data-point-rule-code="D30_C"]').click();
  await modal.locator('[name="withPoints"][value="false"]').check();
  assert.equal(await modal.locator('[name="withPoints"]:checked').inputValue(), 'false');
  assert.equal(await picker.isVisible(), false, '切换无分修改隐藏规则');
  await modal.locator('[name="withPoints"][value="true"]').check();
  assert.equal(await modal.locator('[name="withPoints"]:checked').inputValue(), 'true');
  assert.equal(await picker.isVisible(), true);
  await modal.locator('[data-emie-action="click:task-change-bonus-close"]').first().click();
  await modal.waitFor({ state: 'detached' });
  await page.evaluate(async rules => {
    const original = window.EMIE.actions.apiGet;
    const originalEnhanceDateInputs = window.enhanceDateInputs;
    window.enhanceDateInputs ??= () => {};
    window.EMIE.actions.apiGet = url => url === '/points/rules' ? Promise.resolve(rules) : original(url);
    try { await window.EMIE.actions.taskReject(101, 201); }
    finally {
      window.EMIE.actions.apiGet = original;
      if (originalEnhanceDateInputs === undefined) delete window.enhanceDateInputs;
      else window.enhanceDateInputs = originalEnhanceDateInputs;
    }
  }, rules);
  const reject = page.locator('#taskRejectModal');
  const rejectRules = reject.locator('[data-reject-point-rules]');
  assert.equal(await reject.locator('[name="rejectWithPoints"]:checked').inputValue(), 'false');
  assert.equal(await rejectRules.isVisible(), false, '默认选否并隐藏积分规则');
  await reject.locator('[name="rejectWithPoints"][value="true"]').check();
  await rejectRules.waitFor({ state: 'visible' });
  await reject.locator('[name="rejectWithPoints"][value="false"]').check();
  assert.equal(await rejectRules.isVisible(), false, '选否后隐藏积分规则');
  await reject.locator('[data-emie-action="click:task-reject-close"]').click();
  process.stdout.write('  point-rule-picker-and-revision-points ok\n');
}

async function runVisualTaskRuleChecks(page) {
  const rules = [
    { ruleCode: 'VIS_V3_05', category: '包装设计', subcategory: '包装设计', description: '常规包装设计｜固定模板重新排版（无配件、无 UI）', points: 4 },
    { ruleCode: 'VIS_V3_06', category: '包装设计', subcategory: '包装设计', description: '常规包装设计｜固定模板重新排版（有配件、有 UI）', points: 6 },
    { ruleCode: 'VIS_V3_27', category: '产品设计', subcategory: '概念设计', description: '现有产品延伸设计｜其它 IP 款从 0 到 1 设计', points: 10 },
    { ruleCode: 'VIS_V3_30', category: '产品设计', subcategory: '3D 设计送审', description: '现有产品延伸设计', points: 5 },
    { ruleCode: 'VIS_V3_DISABLED', category: '停用分类', description: '停用的设计规则', points: 1, enabled: false },
    { ruleCode: 'D30_OLD', category: '产品设计', description: '旧设计规则', points: 8, enabled: false },
    { ruleCode: 'GENERAL', category: '通用', description: '其他积分', points: 1 },
  ];
  const visualRuleCodes = Array.from({ length: 30 }, (_, index) => 'VIS_V3_' + String(index + 5).padStart(2, '0'));
  const otherCategories = ['宝描', '海报视觉', '展位设计'];
  visualRuleCodes.forEach((ruleCode, index) => {
    if (!rules.some(rule => rule.ruleCode === ruleCode)) {
      rules.push({ ruleCode, category: otherCategories[index % otherCategories.length],
        subcategory: '测试分组', description: '测试任务类型 ' + ruleCode, points: 1 });
    }
  });
  await page.evaluate(rules => {
    const e = window.EMIE;
    const task = {
      id: 301, supportsChangeTiers: true, pointRuleCode: 'VIS_V3_05',
      smallChangeNoteSnapshot: '调整文字与配色', largeChangeNoteSnapshot: '重新排版与定义风格',
    };
    const legacyTask = {
      id: 302, status: 'pending', name: '历史子任务', assigneeRole: 'designer',
      pointRuleCode: 'D30_OLD', basePointSnapshot: 4,
    };
    const original = Object.fromEntries(['apiGet', 'apiPost', 'apiPut', 'hasPermission', 'refreshAfterMutation']
      .map(key => [key, e.actions[key]]));
    const fixture = window.visualTaskRuleFixture = { original, users: e.state.users, gets: [], writes: [] };
    e.state.users = { ...e.state.users, designer: [{ userId: 777, name: '测试设计师' }] };
    e.actions.hasPermission = () => true;
    e.actions.refreshAfterMutation = async () => {};
    e.actions.apiGet = async url => {
      fixture.gets.push(url);
      if (url === '/points/rules') return rules;
      if (url === '/projects/301') return { tasks: [task, legacyTask] };
      return original.apiGet(url);
    };
    const capture = async (url, body) => { fixture.writes.push({ url, body }); };
    e.actions.apiPost = capture;
    e.actions.apiPut = capture;
  }, rules);
  const addModal = page.locator('#addSubTaskModal');
  const editModal = page.locator('#editTaskModal');
  const changeModal = page.locator('#taskChangeBonusModal');
  try {
    await page.evaluate(() => window.EMIE.actions.addSubTask(301));
    await addModal.waitFor();
    const picker = addModal.locator('.point-rule-picker');
    const visibleRuleCodes = () => picker.locator('[data-point-rule-code]')
      .evaluateAll(rows => rows.map(row => row.dataset.pointRuleCode));
    const selectedRule = picker.locator('[name="pointRuleCode"]');
    const allCategories = picker.locator('[data-point-category-tab=""]');
    const allSubcategories = picker.locator('[data-point-subcategory-tab=""]');
    const secondLevel = picker.locator('[data-point-subcategory-tabs]');
    const pagination = picker.locator('.point-rule-pagination');
    const withoutUi = picker.locator('[data-point-rule-code="VIS_V3_05"]');
    const withUi = picker.locator('[data-point-rule-code="VIS_V3_06"]');
    assert.equal(await allCategories.getAttribute('aria-selected'), 'true');
    assert.equal(await secondLevel.isVisible(), false);
    assert.equal(await picker.locator('[data-point-category-tab="通用"]').count(), 0);
    assert.equal(await picker.locator('[data-point-category-tab="停用分类"]').count(), 0);
    for (let pageIndex = 0; pageIndex < 3; pageIndex += 1) {
      assert.deepEqual(await visibleRuleCodes(), visualRuleCodes.slice(pageIndex * 10, (pageIndex + 1) * 10));
      assert.match(await pagination.innerText(), new RegExp(`共 30 条 · ${pageIndex + 1} / 3 页`));
      if (pageIndex < 2) await pagination.getByRole('button', { name: '下一页' }).click();
    }
    assert.equal(await pagination.getByRole('button', { name: '下一页' }).isDisabled(), true);
    const search = picker.getByRole('searchbox', { name: '搜索积分规则' });
    await search.fill('VIS_V3_30');
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_30']);
    await search.clear();
    assert.deepEqual(await visibleRuleCodes(), visualRuleCodes.slice(0, 10));

    await withUi.click();
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_06');
    assert.equal(await allCategories.getAttribute('aria-selected'), 'true');
    await addModal.locator('[name="name"]').fill('未筛选分类的测试任务');
    await addModal.locator('[name="workflowStage"]').selectOption('design');
    const plannedDate = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    await addModal.locator('[name="plannedDate"]').fill(plannedDate);
    await addModal.locator('[name="designerId"]').selectOption('777');
    await addModal.getByRole('button', { name: '确认添加' }).click();
    await addModal.waitFor({ state: 'detached' });
    const created = await page.evaluate(() => window.visualTaskRuleFixture.writes[0]);
    assert.equal(created.url, '/projects/301/tasks');
    assert.equal(created.body.pointRuleCode, 'VIS_V3_06');

    await page.evaluate(() => window.EMIE.actions.addSubTask(301));
    await addModal.waitFor();
    await picker.locator('[data-point-category-tab="包装设计"]').click();
    assert.equal(await allSubcategories.getAttribute('aria-selected'), 'true');
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_05', 'VIS_V3_06']);
    assert.match(await withoutUi.innerText(), /无配件、无 UI[\s\S]*4 分/);
    assert.match(await withUi.innerText(), /有配件、有 UI[\s\S]*6 分/);
    await withoutUi.click();
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_05');
    await withUi.click();
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_06');

    await picker.locator('[data-point-category-tab="产品设计"]').click();
    assert.equal(await selectedRule.inputValue(), '');
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_27', 'VIS_V3_30']);
    await picker.locator('[data-point-rule-code="VIS_V3_27"]').click();
    await picker.locator('[data-point-subcategory-tab="概念设计"]').click();
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_27']);
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_27');
    await picker.locator('[data-point-subcategory-tab="3D 设计送审"]').click();
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_30']);
    assert.equal(await selectedRule.inputValue(), '');
    await picker.locator('[data-point-rule-code="VIS_V3_30"]').click();
    await allSubcategories.click();
    assert.deepEqual(await visibleRuleCodes(), ['VIS_V3_27', 'VIS_V3_30']);
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_30');

    await picker.locator('[data-point-subcategory-tab="3D 设计送审"]').click();
    await allCategories.click();
    assert.equal(await secondLevel.isVisible(), false);
    assert.equal(await selectedRule.inputValue(), 'VIS_V3_30');
    assert.deepEqual(await visibleRuleCodes(), visualRuleCodes.slice(0, 10));
    await addModal.locator('[data-emie-action="click:task-add-close"]').first().click();
    await addModal.waitFor({ state: 'detached' });

    await page.evaluate(() => window.EMIE.actions.editTask(301, 302));
    await editModal.waitFor();
    const editPicker = editModal.locator('.point-rule-picker');
    assert.equal(await editPicker.locator('[data-point-category-tab="通用"]').count(), 0);
    assert.match(await editPicker.locator('.point-rule-selected').innerText(), /旧设计规则（历史绑定） · 4 分/);
    await editPicker.locator('[data-point-category-tab="包装设计"]').click();
    assert.equal(await editPicker.locator('[name="pointRuleCode"]').inputValue(), 'D30_OLD');
    await page.evaluate(() => window.EMIE.actions.submitEditTask(301, 302));
    await editModal.waitFor({ state: 'detached' });
    const edited = await page.evaluate(() => window.visualTaskRuleFixture.writes.at(-1));
    assert.equal(edited.url, '/projects/301/tasks/302');
    assert.equal(Object.hasOwn(edited.body, 'pointRuleCode'), false);

    await page.evaluate(() => { window.visualTaskRuleFixture.gets.length = 0; });
    for (const tier of ['small', 'large', 'none']) {
      await page.evaluate(() => window.EMIE.actions.openTaskChangeBonus(301, 301));
      await changeModal.waitFor();
      const options = changeModal.getByRole('radiogroup', { name: '本次修改类型' });
      assert.deepEqual(await options.getByRole('radio').evaluateAll(radios => radios.map(radio => radio.getAttribute('aria-label'))),
        ['不给积分', '小改', '大改']);
      assert.equal(await options.locator('input:checked').inputValue(), 'small');
      assert.deepEqual(await options.locator('.task-change-tier-note').allTextContents(), ['调整文字与配色', '重新排版与定义风格']);
      const noteColor = await options.locator('.task-change-tier-note').first().evaluate(note => getComputedStyle(note).color);
      assert.equal(noteColor, 'rgb(75, 85, 99)');
      assert.doesNotMatch(await options.innerText(), /\d+(?:\.\d+)?\s*分/);
      assert.equal(await changeModal.locator('[name="pointRuleCode"], [name="withPoints"]').count(), 0);
      await options.locator(`input[value="${tier}"]`).check();
      await changeModal.locator('[name="reason"]').fill('测试修改意见');
      await changeModal.getByRole('button', { name: '发起修改' }).click();
      await changeModal.waitFor({ state: 'detached' });
      const sent = await page.evaluate(() => window.visualTaskRuleFixture.writes.at(-1));
      assert.equal(sent.url, '/projects/301/subtasks/301/change-bonus');
      assert.deepEqual(Object.keys(sent.body).sort(), ['changeReferenceImagesJson', 'changeTier', 'reason', 'requestId']);
      assert.equal(sent.body.changeTier, tier);
      assert.equal(sent.body.reason, '测试修改意见');
    }
    assert.deepEqual(await page.evaluate(() => window.visualTaskRuleFixture.gets), Array(3).fill('/projects/301'));
  } finally {
    await page.evaluate(() => {
      const e = window.EMIE;
      const { original, users } = window.visualTaskRuleFixture;
      Object.assign(e.actions, original);
      e.state.users = users;
      ['addSubTaskModal', 'editTaskModal', 'taskChangeBonusModal'].forEach(id => document.getElementById(id)?.remove());
      delete window.visualTaskRuleFixture;
    });
  }
  process.stdout.write('  visual-v3-task-rule-hierarchy-and-change-options ok\n');
}

async function runAdminSubcategoryChecks(page) {
  await page.evaluate(async () => {
    if (!window.EMIE.actions.createPointRule) await import('/js/admin-scoring.js');
    const e = window.EMIE;
    window.adminRuleMocks = { get: e.actions.apiGet, post: e.actions.apiPost, put: e.actions.apiPut, alert: e.actions.showSystemAlert };
    window.adminRulePayloads = [];
    const rule = { ruleCode: 'TEST_ADMIN', category: '产品设计', subcategory: '概念', points: 2, description: '测试', enabled: true };
    window.adminRuleStore = [rule];
    e.actions.apiGet = async url => url === '/points/rules' ? window.adminRuleStore : url === '/points/categories' ? ['产品设计', '包装设计'] : url.startsWith('/performance/designer-monthly-report') ? { from: '2026-09-01T00:00', to: '2026-10-01T00:00', month: '2026-09', designers: [] } : [];
    e.actions.apiPost = async (url, body) => { window.adminRulePayloads.push({ method: 'POST', url, body }); window.adminRuleStore.push({ ...body, enabled: true }); return body; };
    e.actions.apiPut = async (url, body) => { window.adminRulePayloads.push({ method: 'PUT', url, body }); Object.assign(window.adminRuleStore.find(r => url.endsWith('/' + r.ruleCode)), body); return body; };
    e.actions.showSystemAlert = () => {};
    const container = document.createElement('div'); container.id = 'adminContent'; document.body.append(container);
    await e.actions.renderAdminPoints(container);
    await e.actions.createPointRule();
  });
  try {
    let modal = page.locator('#createPointRuleModal');
    await modal.waitFor({ state: 'visible' });
    const optional = modal.locator('[name="subcategory"]');
    assert.equal(await optional.getAttribute('maxlength'), '50');
    assert.equal(await optional.getAttribute('required'), null);
    await modal.locator('[name="ruleCode"]').fill('NEW_ADMIN');
    await optional.fill(' 深化 ');
    await modal.locator('[data-action="save"]').click();
    await modal.waitFor({ state: 'detached' });
    assert.equal(await page.evaluate(() => window.adminRulePayloads[0].body.subcategory), '深化');
    await page.evaluate(() => window.EMIE.actions.createPointRule());
    modal = page.locator('#createPointRuleModal');
    await modal.locator('[name="ruleCode"]').fill('EMPTY_ADMIN');
    await modal.locator('[data-action="save"]').click();
    await modal.waitFor({ state: 'detached' });
    assert.equal(await page.evaluate(() => window.adminRulePayloads[1].body.subcategory), '');
    const item = page.locator('.admin-point-rule-item[data-rule-code="test_admin"]');
    let edit = item.locator('[id^="pr_subcategory_"]');
    assert.equal(await edit.inputValue(), '概念');
    await edit.fill('优化');
    await item.locator('[data-emie-action="click:scoring-save-rule"]').click();
    await page.waitForFunction(() => window.adminRulePayloads.length === 3);
    assert.equal(await page.evaluate(() => window.adminRulePayloads[2].body.subcategory), '优化');
    await page.evaluate(() => window.EMIE.actions.renderAdminPoints(document.getElementById('adminContent')));
    edit = item.locator('[id^="pr_subcategory_"]');
    assert.equal(await edit.inputValue(), '优化', '编辑后加载应保留二级分类');
    await edit.fill('');
    await item.locator('[data-emie-action="click:scoring-save-rule"]').click();
    await page.waitForFunction(() => window.adminRulePayloads.length === 4);
    assert.equal(await page.evaluate(() => window.adminRulePayloads[3].body.subcategory), '');
    await page.evaluate(() => {
      const holder = document.createElement('div'); holder.id = 'adminRulePicker';
      holder.innerHTML = window.EMIE.actions.renderPointRulePicker(window.adminRuleStore);
      document.body.append(holder);
    });
    const picker = page.locator('#adminRulePicker');
    await picker.locator('[data-point-category-tab="产品设计"]').click();
    await picker.locator('[data-point-subcategory-tab="深化"]').click();
    assert.equal(await picker.locator('[data-point-rule-code="NEW_ADMIN"]').count(), 1, '新建二级分类须出现在任务规则选择器');

  } finally {
    await page.evaluate(() => {
      const mocks = window.adminRuleMocks;
      Object.assign(window.EMIE.actions, { apiGet: mocks.get, apiPost: mocks.post, apiPut: mocks.put, showSystemAlert: mocks.alert });
      document.getElementById('adminContent')?.remove();
      document.getElementById('createPointRuleModal')?.remove();
      document.getElementById('adminRulePicker')?.remove();
    });
  }
  process.stdout.write('  admin-rule-optional-subcategory-save ok\n');
}

async function runPointLogChecks(page) {
  await page.evaluate(async () => {
    if (!window.EMIE.actions.renderAdminPointLogs) await import('/js/admin-point-logs.js');
    const e = window.EMIE;
    window.pointLogMock = { get: e.actions.apiGet, filters: e.adminState.pointLogFilters };
    window.pointLogRequests = [];
    e.actions.apiGet = async url => {
      window.pointLogRequests.push(url);
      const page = Number(new URL(url, location.origin).searchParams.get('page') || 0);
      if (url.startsWith('/points/audit')) return { items: [], page, total: 0, totalPages: 0, historicalCount: 2 };
      return { items: [{ id: 'task:1', userId: 'designer_test', userName: '测试设计师', source: 'TASK_BASE',
        points: 2.5, ruleCode: 'D30_6', ruleDescription: '常规包装', reason: '<img onerror="alert(1)">',
        submittedAt: '2026-10-08T10:00:00', confirmedAt: '2026-10-08T11:00:00',
        createdAt: '2026-10-08T11:00:01', createdByName: '测试企划', deliveryVersionNo: 1, tracking: 'tracked' }],
        page, total: 21, totalPages: 2, summary: { positive: 2.5, negative: -0.5, net: 2 } };
    };
    e.adminState.pointLogFilters = {};
    e.adminState.pointLogPage = e.adminState.pointAuditPage = 0;
    const container = document.createElement('div'); container.id = 'adminContent'; document.body.append(container);
    await e.actions.renderAdminPointLogs(container);
  });
  try {
    const container = page.locator('#adminContent');
    assert.equal(await container.locator('#pointLog-personKeyword').getAttribute('type'), 'search');
    await container.locator('#pointLogResults').getByText('测试设计师').waitFor();
    assert.match(await container.locator('#pointLogResults').innerText(), /2\.5/);
    assert.match(await container.locator('#pointLogResults').innerText(), /测试企划/);
    assert.match(await container.locator('#pointAuditResults').innerText(), /历史未跟踪版本 2/);
    assert.equal(await container.locator('#pointLogResults img').count(), 0, '日志文本必须转义');
    await container.locator('#pointLog-personKeyword').fill('designer_test');
    await container.locator('#pointLog-from').fill('2026-10-01');
    await container.locator('#pointLog-source').selectOption('TASK_BASE');
    await container.locator('#pointLog-keyword').fill('包装');
    await container.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForFunction(() => window.pointLogRequests.length >= 4);
    const query = await page.evaluate(() => window.pointLogRequests.at(-2));
    const params = new URL(query, baseURL).searchParams;
    assert.equal(params.get('personKeyword'), 'designer_test');
    assert.equal(params.get('source'), 'TASK_BASE');
    assert.equal(params.get('keyword'), '包装');
    await container.locator('[data-kind="logs"][data-page="1"]').click();
    await page.waitForFunction(() => window.pointLogRequests.some(url => url.startsWith('/points/logs') && new URL(url, location.origin).searchParams.get('page') === '1'));
    await container.locator('[data-emie-action="click:point-logs-reset"]').click();
    await container.locator('#pointLog-personKeyword').waitFor();
    assert.equal(await container.locator('#pointLog-personKeyword').inputValue(), '');
  } finally {
    await page.evaluate(() => {
      window.EMIE.actions.apiGet = window.pointLogMock.get;
      window.EMIE.adminState.pointLogFilters = window.pointLogMock.filters;
      document.getElementById('adminContent')?.remove();
    });
  }
  process.stdout.write('  point-log-filter-pagination-submission-time ok\n');
}

async function assertEventually(page, predicate, message) {
  await page.waitForFunction(predicate, undefined, { timeout: 2_000 }).catch(() => {
    throw new assert.AssertionError({ message });
  });
}

async function runDesignerEvaluationChecks(page) {
  await page.evaluate(async () => {
    const e = window.EMIE;
    window.evaluationMocks = { get: e.actions.apiGet, put: e.actions.apiPut, permission: e.actions.hasPermission, role: e.state.currentRole };
    window.evaluationStore = [];
    window.evaluationWrites = [];
    e.actions.apiGet = async url => {
      const params = new URL(url, location.origin).searchParams;
      if (url.startsWith('/performance/designer-targets')) return [{ userId: 'e2e-designer', userName: '测试设计师', targetPoints: window.evaluationTarget || 0, configured: !!window.evaluationTarget }];
      if (url.startsWith('/performance/designer-evaluations/completion')) return { completionScore: window.evaluationNoTasks ? null : 100, earnedPoints: 100, targetPoints: window.evaluationNoTasks ? 0 : 100, completionReason: window.evaluationNoTasks ? '目标积分未配置' : '月度所得积分 100 ÷ 目标积分 100 × 100 = 100 分。' };
      if (url.startsWith('/performance/designer-evaluations')) return window.evaluationStore.filter(row => row.monthKey.startsWith(params.get('period')));
      if (url.startsWith('/performance/designer-monthly-report')) return { month: params.get('month'), designers: [{ userId: 'e2e-designer', designer: '测试设计师', completedCount: 0, score: 12, tasks: [] }] };
      return [];
    };
    e.actions.apiPut = async (url, body) => {
      if (url.startsWith('/performance/designer-targets/')) { window.evaluationTarget = body.targetPoints; return body; }
      if (window.evaluationConflict) throw new Error('评分已被修改，请关闭评分窗口，刷新页面后重试');
      window.evaluationWrites.push({ url, body });
      const monthKey = url.split('/').at(-2);
      const row = { ...body, monthKey, userId: 'e2e-designer', version: body.version == null ? 0 : body.version + 1, updatedAt: '2026-10-13T10:00:00', totalScore: body.completionScore * 0.7 + (body.collaborationScore + body.attendanceScore + body.weeklyReportScore) * 0.1 - body.weeklyReportDeduction };
      window.evaluationStore = [row];
      return row;
    };
    const host = document.createElement('div'); host.id = 'evaluationTestHost'; document.body.append(host);
    await e.actions.renderAdminDesignerPerformance(host);
  });
  const host = page.locator('#evaluationTestHost');
  const initialViewport = page.viewportSize();
  try {
    assert.match(await host.innerText(), /综合绩效：待评分/);
    await host.locator('.designer-target-settings summary').click();
    await host.locator('#dt_points_0').fill('100');
    await host.locator('[data-emie-action="click:scoring-save-target"]').click();
    await page.waitForFunction(() => document.querySelector('#evaluationTestHost .designer-target-settings')?.textContent.includes('已配置'));
    await page.waitForFunction(() => document.querySelector('#evaluationTestHost .designer-evaluation-preview')?.textContent.includes('100.0 分'));
    await host.locator('[data-emie-action="click:designer-evaluation-edit"]').click();
    const modal = page.locator('#designerEvaluationModal');
    await modal.waitFor({ state: 'visible' });
    await modal.locator('[name="completionScore"]').waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => {
      const box = document.querySelector('#designerEvaluationModal .modal').getBoundingClientRect();
      return box.left >= -1 && box.right <= innerWidth + 1;
    });
    const box = await modal.locator('.modal').boundingBox();
    assert(box.x >= -1 && box.x + box.width <= 391, '评分说明弹窗应适配手机宽度');
    assert(await modal.locator('.modal-body').evaluate(element => element.scrollHeight > element.clientHeight), '长评分说明应在弹窗内滚动');
    assert.equal(await modal.locator('[name="completionScore"]').getAttribute('readonly'), '');
    assert.equal(await modal.locator('[name="completionScore"]').inputValue(), '100');
    assert.equal(await modal.locator('.designer-evaluation-fields input').count(), 4);
    const fields = ['collaborationScore', 'attendanceScore', 'weeklyReportScore'];
    for (const name of fields) await modal.locator(`[name="${name}"]`).fill('100');
    assert.equal(await modal.locator('[name$="Reason"]').count(), 0, '评分只解释标准，不要求填写逐项说明');
    assert.match(await modal.innerText(), /100 ÷ 目标积分 100/);
    assert.match(await modal.innerText(), /90–100 分：积极主动/);
    await modal.locator('[name="weeklyReportDeduction"]').fill('4');
    await modal.locator('[data-emie-action="click:designer-evaluation-save"]').click();
    assert.equal(await page.evaluate(() => window.evaluationWrites.length), 0, '非法扣分不得保存');
    await modal.locator('[name="weeklyReportDeduction"]').fill('5');
    await modal.locator('[name="comment"]').fill('<img src=x onerror=alert(1)>');
    assert.match(await modal.locator('#designerEvaluationTotal').innerText(), /95\.00/);
    await modal.locator('[data-emie-action="click:designer-evaluation-save"]').click();
    await modal.waitFor({ state: 'detached' });
    await page.waitForFunction(() => document.querySelector('#evaluationTestHost').textContent.includes('综合绩效：95.00'));
    assert.equal(await host.locator('.designer-evaluation-summary img').count(), 0, '评语需转义');
    assert.equal(await host.locator('.designer-evaluation-result').count(), 4, '保存后显示四项评分');
    assert.match(await host.locator('.designer-performance-summary').innerText(), /1 \/ 1/);
    assert.equal(await host.locator('.designer-evaluation-basis').getAttribute('open'), null, '评分依据默认收起');
    await host.locator('.designer-evaluation-basis summary').click();
    assert.match(await host.locator('.designer-evaluation-basis').innerText(), /90–100 分：积极主动/);
    await host.locator('.designer-evaluation-basis summary').click();
    const performanceBox = await host.locator('.designer-performance').boundingBox();
    assert(performanceBox.x >= -1 && performanceBox.x + performanceBox.width <= 391, '绩效卡片应适配手机宽度');
    assert(await host.locator('.designer-performance').evaluate(element => element.scrollWidth <= element.clientWidth + 1), '绩效卡片不能水平溢出');
    await host.screenshot({ path: path.join(artifactRoot, 'designer-performance-mobile.png') });
    await page.setViewportSize({ width: 1440, height: 1100 });
    await host.screenshot({ path: path.join(artifactRoot, 'designer-performance-desktop.png') });

    const payload = await page.evaluate(() => window.evaluationWrites[0].body);
    assert.equal(payload.version, null);
    assert.equal(payload.completionScore, 100);
    assert.equal(payload.aestheticScore, undefined);
    assert.equal(payload.weeklyReportDeduction, 5);
    assert.equal(payload.collaborationReason, undefined);
    await host.locator('[data-emie-action="click:designer-evaluation-edit"]').click();
    assert.equal(await modal.locator('[name="collaborationScore"]').inputValue(), '100');
    await page.evaluate(() => { window.evaluationConflict = true; });
    await modal.locator('[data-emie-action="click:designer-evaluation-save"]').click();
    await page.waitForFunction(() => document.getElementById('designerEvaluationError').textContent.includes('刷新'));
    assert.equal(await modal.isVisible(), true, '冲突应保留填写内容');
    await modal.locator('[data-emie-action="click:designer-evaluation-close"]').first().click();
    await page.evaluate(() => { window.evaluationConflict = false; window.evaluationNoTasks = true; });
    await host.locator('[data-emie-action="click:designer-evaluation-edit"]').click();
    await modal.locator('[name="completionScore"]').waitFor();
    assert.equal(await modal.locator('[name="completionScore"]').getAttribute('readonly'), '');
    assert.match(await modal.innerText(), /目标积分未配置/);
    assert.equal(await modal.locator('[data-emie-action="click:designer-evaluation-save"]').isDisabled(), true);
    await modal.locator('[data-emie-action="click:designer-evaluation-close"]').first().click();
    await page.evaluate(() => {
      window.evaluationNoTasks = false;
      window.evaluationConflict = false;
      window.evaluationStore = [{ userId: 'e2e-designer', monthKey: '2026-01', totalScore: 100.01, version: 0 }, { userId: 'e2e-designer', monthKey: '2026-02', totalScore: 100, version: 0 }];
    });
    await host.locator('#designerPerformanceScope').selectOption('year');
    await host.locator('#designerPerformanceMonth').fill('2026');
    await host.locator('#designerPerformanceMonth').dispatchEvent('change');
    await page.waitForFunction(() => document.querySelector('#evaluationTestHost').textContent.includes('已评分 2/12'));
    assert.match(await host.innerText(), /年度平均绩效：100\.01/);
    await host.locator('.designer-evaluation-summary summary').click();
    assert.equal(await host.locator('.designer-evaluation-months > div').count(), 12);
    assert.equal(await host.locator('.designer-evaluation-months > div').filter({ hasText: '待评分' }).count(), 10);
    const permissions = await page.evaluate(() => {
      const e = window.EMIE; e.actions.hasPermission = () => true;
      e.state.currentRole = 'admin'; const admin = e.actions.canNavigateTo('designer-performance');
      e.state.currentRole = 'designer'; const designer = e.actions.canNavigateTo('designer-performance');
      return { admin, designer };
    });
    assert.deepEqual(permissions, { admin: true, designer: true });
    console.log('  designer-monthly-evaluation-save-conflict-and-annual-average ok');
  } finally {
    if (initialViewport) await page.setViewportSize(initialViewport);
    await page.evaluate(() => {
      const e = window.EMIE; const mocks = window.evaluationMocks;
      e.actions.apiGet = mocks.get; e.actions.apiPut = mocks.put; e.actions.hasPermission = mocks.permission; e.state.currentRole = mocks.role;
      e.actions.closeM('designerEvaluationModal'); document.getElementById('evaluationTestHost')?.remove();
    });
  }
}

await fs.mkdir(artifactRoot, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: await executablePath() });
let failed = false;
try {
  for (let run = 1; run <= repeat; run += 1) {
    const context = await browser.newContext();
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    const page = await context.newPage();
    if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
      const staticRoot = new URL('../../src/main/resources/static/', import.meta.url);
      for (const [extension, contentType] of [['js', 'text/javascript'], ['css', 'text/css']]) {
        await page.route(`**/${extension}/*.${extension}*`, async route => {
          const filename = path.basename(new URL(route.request().url()).pathname);
          await route.fulfill({ contentType, body: await fs.readFile(new URL(`${extension}/${filename}`, staticRoot), 'utf8') });
        });
      }
      await page.route(baseURL + '/', async route => route.fulfill({
        contentType: 'text/html', body: await fs.readFile(new URL('index.html', staticRoot), 'utf8'),
      }));
    }
    const browserErrors = [];
    page.on('pageerror', error => browserErrors.push(error.message));
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
      await page.locator('html[data-app-ready]').waitFor({ timeout: 15_000 });
      await runLazyAdminChecks(page);
      await runNavigationDataChecks(page);
      await runNavigationTransitionChecks(page);
      await runChecks(page);
      await runPointRuleChecks(page);
      await runVisualTaskRuleChecks(page);
      await runAdminSubcategoryChecks(page);
      await runPointLogChecks(page);
      await runDesignerEvaluationChecks(page);
      await runDesignerPersonalPerformanceChecks(page, artifactRoot);
      await runPointsLedgerLinkChecks(page, artifactRoot);
      assert.deepEqual(browserErrors, [], `页面脚本错误: ${browserErrors.join('; ')}`);
      await context.tracing.stop();
      process.stdout.write(`modal_e2e run=${run}/${repeat} ok\n`);
    } catch (error) {
      failed = true;
      const prefix = path.join(artifactRoot, `run-${run}`);
      await page.screenshot({ path: `${prefix}.png`, fullPage: true }).catch(() => {});
      await context.tracing.stop({ path: `${prefix}-trace.zip` }).catch(() => {});
      await fs.writeFile(`${prefix}-error.txt`, `${error.stack || error}\nURL: ${page.url()}\n`).catch(() => {});
      console.error(`modal_e2e run=${run}/${repeat} failed: ${error.message}`);
      break;
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}

if (failed) process.exitCode = 1;
