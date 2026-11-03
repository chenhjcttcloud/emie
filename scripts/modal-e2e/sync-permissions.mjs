import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '同步权限模拟检查仅允许本机测试环境');
const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [], requests = [], mutations = [];
let statsFail = false;
page.on('pageerror', error => errors.push(error.message));

await page.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url());
  const fulfill = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
  requests.push({ path: url.pathname, method: request.method() });
  if (request.method() !== 'GET') mutations.push(url.pathname);
  if (url.pathname === '/api/admin/sync/stats') {
    if (statsFail) return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ error: '模拟状态不可用' }) });
    return fulfill({ pending: 17, processing: 1, fail: 2, done: 38, lastSuccessAt: '2026-11-03 10:00:00', lastFailure: { id: 21, entityType: 'project', entityId: 9, error: '模拟失败' } });
  }
  if (url.pathname === '/api/admin/sync/v2/status') return fulfill({ active: true, activatable: true, stagingStatus: 'prepared', rollbackAvailable: true, tables: [{ configured: true, name: '项目主表' }] });
  if (url.pathname === '/api/admin/configs') return fulfill({ feishu_base: [{ configKey: 'feishu.base.fieldMappings', configValue: '{}' }] });
  if (url.pathname === '/api/admin/users') return fulfill([]);
  return fulfill({});
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  for (const [pattern, directory, contentType] of [['**/js/*.js*', 'js', 'text/javascript'], ['**/css/*.css*', 'css', 'text/css']]) {
    await page.route(pattern, async route => route.fulfill({ contentType, body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static', directory, path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  }
  await page.route(baseURL + '/', async route => route.fulfill({ contentType: 'text/html', body: await fs.readFile(path.join(repoRoot, 'src/main/resources/static/index.html'), 'utf8') }));
}

const basePermissions = ['page.admin.view', 'admin.config.view', 'admin.user.manage'];
const viewPermission = 'admin.feishu_sync.view', executePermission = 'admin.feishu_sync.execute';
const mutationActions = ['admin-feishu-sync', 'admin-retry-sync', 'admin-feishu-v2-prepare', 'admin-feishu-v2-activate', 'admin-feishu-v2-rollback'];
const action = name => page.locator(`[data-emie-action="click:${name}"]`);
async function permissions(items) {
  await page.evaluate(items => { window.EMIE.state.permissions = items; }, items);
}
async function render(tab, items) {
  await permissions(items);
  await page.evaluate(async tab => {
    const e = window.EMIE;
    e.adminState.currentTab = tab;
    await e.actions.renderAdmin(document.getElementById('mainContent'), 'admin', 'sync-ui-admin');
  }, tab);
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.modules?.adminShell || window.EMIE?.loadViewModules);
  await page.evaluate(async () => {
    const e = window.EMIE;
    await e.loadViewModules?.('admin');
    e.state.currentRole = 'admin'; e.state.currentUserId = 'sync-ui-admin';
    e.state.authUser = { userId: 'sync-ui-admin', name: '同步权限模拟管理员', role: 'admin' };
    localStorage.setItem('design_pm_token', 'sync-ui-simulation-only');
    document.getElementById('loginPage').style.display = 'none'; document.getElementById('appContainer').style.display = '';
    e.actions.showAdminToast = () => {};
    e.actions.showSystemAlert = async () => {};
    e.actions.showSystemConfirm = async () => true;
  });

  await render('feishuV2', [...basePermissions, viewPermission]);
  assert.match(await page.locator('#adminContent').innerText(), /V2 已激活/);
  assert.match(await page.locator('#adminContent').innerText(), /执行同步.*权限/);
  for (const name of mutationActions) assert.equal(await action(name).count(), 0, `只读账号不展示 ${name}`);
  assert.equal(await page.locator('#feishuV2AppToken').count(), 0);
  const readsBeforeRefresh = requests.filter(item => item.path === '/api/admin/sync/stats').length;
  await action('admin-feishu-v2-refresh').click();
  await page.waitForFunction(() => document.querySelector('#adminContent')?.textContent.includes('V2 已激活'));
  assert.ok(requests.filter(item => item.path === '/api/admin/sync/stats').length > readsBeforeRefresh);

  await render('dashboard', [...basePermissions, viewPermission]);
  assert.match(await page.locator('.admin-sync-card').innerText(), /17/);
  for (const name of ['admin-feishu-sync', 'admin-retry-sync']) assert.equal(await action(name).count(), 0);
  statsFail = true;
  await render('dashboard', [...basePermissions, viewPermission]);
  assert.equal(await page.locator('.admin-config-overview').count(), 1, '同步状态失败不阻断概览');
  assert.doesNotMatch(await page.locator('.admin-sync-card').innerText(), /运行正常/);
  statsFail = false;

  const readsBeforeDeny = requests.filter(item => item.path.startsWith('/api/admin/sync/')).length;
  await render('feishuV2', basePermissions);
  assert.equal(await page.locator('.admin-tab[data-tab="feishuV2"]').count(), 0);
  assert.equal(await page.locator('.admin-tab[data-tab="feishuFields"]').count(), 0);
  assert.match(await page.locator('#adminContent').innerText(), /没有查看飞书同步状态的权限/);
  assert.equal(requests.filter(item => item.path.startsWith('/api/admin/sync/')).length, readsBeforeDeny, '禁止查看时不请求同步接口');

  await render('feishuFields', [...basePermissions, viewPermission]);
  assert.equal(await action('admin-save-feishu-fields').count(), 0);
  assert.ok(await page.locator('.feishu-field-target').count() > 0);
  assert.equal(await page.locator('.feishu-field-target:enabled, .feishu-field-enabled:enabled').count(), 0);
  assert.equal(await action('admin-diagnose-feishu-fields').count(), 1);

  const allPermissions = [...basePermissions, viewPermission, executePermission, 'admin.config.edit'];
  await render('feishuV2', allPermissions);
  for (const name of mutationActions) assert.equal(await action(name).count(), 1, `执行账号展示 ${name}`);
  // 模拟页面打开后权限被撤回：旧按钮不得继续发起写入。
  await permissions([...basePermissions, viewPermission]);
  for (const name of mutationActions) await action(name).click();
  await render('feishuFields', allPermissions);
  await permissions([...basePermissions, viewPermission]);
  await action('admin-save-feishu-fields').click();
  assert.deepEqual(mutations, [], '只读账号、失效按钮均不能发起写请求');
  assert.deepEqual(errors, []);
  process.stdout.write('sync-permissions: readonly status/refresh, denied overview, readonly mappings, stale action guards passed; external writes=0\n');
} finally {
  await browser.close();
}
