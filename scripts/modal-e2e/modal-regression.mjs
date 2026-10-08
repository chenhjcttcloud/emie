import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';

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
    window.EMIE.actions.apiGet = url => url === '/points/rules' ? Promise.resolve(rules) : original(url);
    try { await window.EMIE.actions.openTaskChangeBonus(101, 201); }
    finally { window.EMIE.actions.apiGet = original; }
  }, rules);
  const modal = page.locator('#taskChangeBonusModal');
  await modal.waitFor({ state: 'visible' });
  const picker = modal.locator('[data-change-point-rules]');
  assert.equal(await picker.isVisible(), false, '默认无分修改隐藏规则');
  await modal.locator('label').filter({ hasText: '本次修改有积分' }).click();
  assert.equal(await modal.locator('[name="withPoints"]').isChecked(), true);
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
  await modal.locator('label').filter({ hasText: '本次修改有积分' }).click();
  assert.equal(await modal.locator('[name="withPoints"]').isChecked(), false);
  assert.equal(await picker.isVisible(), false, '切换无分修改隐藏规则');
  await modal.locator('label').filter({ hasText: '本次修改有积分' }).click();
  assert.equal(await modal.locator('[name="withPoints"]').isChecked(), true);
  assert.equal(await picker.isVisible(), true);
  await modal.locator('[data-emie-action="click:task-change-bonus-close"]').first().click();
  await modal.waitFor({ state: 'detached' });
  await page.evaluate(async rules => {
    const original = window.EMIE.actions.apiGet;
    window.enhanceDateInputs ??= () => {};
    window.EMIE.actions.apiGet = url => url === '/points/rules' ? Promise.resolve(rules) : original(url);
    try { await window.EMIE.actions.taskReject(101, 201); }
    finally { window.EMIE.actions.apiGet = original; }
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
    assert.equal(await container.locator('#pointLog-userId').getAttribute('type'), 'text');
    await container.locator('#pointLogResults').getByText('测试设计师').waitFor();
    assert.match(await container.locator('#pointLogResults').innerText(), /2\.5/);
    assert.match(await container.locator('#pointLogResults').innerText(), /测试企划/);
    assert.match(await container.locator('#pointAuditResults').innerText(), /历史未跟踪版本 2/);
    assert.equal(await container.locator('#pointLogResults img').count(), 0, '日志文本必须转义');
    await container.locator('#pointLog-userId').fill('designer_test');
    await container.locator('#pointLog-from').fill('2026-10-01');
    await container.locator('#pointLog-source').selectOption('TASK_BASE');
    await container.locator('#pointLog-keyword').fill('包装');
    await container.locator('[data-emie-action="click:point-logs-query"]').click();
    await page.waitForFunction(() => window.pointLogRequests.length >= 4);
    const query = await page.evaluate(() => window.pointLogRequests.at(-2));
    const params = new URL(query, baseURL).searchParams;
    assert.equal(params.get('userId'), 'designer_test');
    assert.equal(params.get('source'), 'TASK_BASE');
    assert.equal(params.get('keyword'), '包装');
    await container.locator('[data-kind="logs"][data-page="1"]').click();
    await page.waitForFunction(() => window.pointLogRequests.some(url => url.startsWith('/points/logs') && new URL(url, location.origin).searchParams.get('page') === '1'));
    await container.locator('[data-emie-action="click:point-logs-reset"]').click();
    await container.locator('#pointLog-userId').waitFor();
    assert.equal(await container.locator('#pointLog-userId').inputValue(), '');
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

await fs.mkdir(artifactRoot, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: await executablePath() });
let failed = false;
try {
  for (let run = 1; run <= repeat; run += 1) {
    const context = await browser.newContext();
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    const page = await context.newPage();
    if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
      await page.route('**/js/*.js*', async route => {
        const filename = path.basename(new URL(route.request().url()).pathname);
        await route.fulfill({ contentType: 'text/javascript', body: await fs.readFile(path.resolve('src/main/resources/static/js', filename), 'utf8') });
      });
    }
    const browserErrors = [];
    page.on('pageerror', error => browserErrors.push(error.message));
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
      await page.locator('html[data-app-ready]').waitFor({ timeout: 15_000 });
      await runChecks(page);
      await runPointRuleChecks(page);
      await runAdminSubcategoryChecks(page);
      await runPointLogChecks(page);
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
