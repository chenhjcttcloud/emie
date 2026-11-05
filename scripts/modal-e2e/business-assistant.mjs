import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), '助手 UI 模拟检查仅允许本机测试环境');
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
const errors = [], chats = [], uploads = [], saves = [], pending = [], discoveries = [], pendingDiscoveries = [];
const lanProxyBase = 'http://192.168.200.118:8317/v1', directProxyRequests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => {
  if (new URL(request.url()).host === new URL(lanProxyBase).host) directProxyRequests.push(request.url());
});
await page.route(/^https?:\/\/192\.168\.200\.118:8317(?:\/|$)/, route => route.abort('blockedbyclient'));
const modelProfiles = [{ id: 'server', name: '服务器 Kimi（默认）', provider: 'kimi', dialect: 'kimi', thinkingMode: 'disabled', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k2.6', visionSupported: true, keyConfigured: true, builtin: true }];
let activeProfileId = 'server', modelEnabled = true;
let nextProfileId = 1;
const modelMeta = () => { const profile = modelProfiles.find(item => item.id === activeProfileId); return { modelName: profile.name, modelId: profile.id, provider: profile.provider, visionSupported: profile.visionSupported, enabled: modelEnabled }; };
const configView = () => { const active = modelProfiles.find(item => item.id === activeProfileId); return { ...active, enabled: modelEnabled, keyStorageAvailable: true, message: '', activeProfileId, profiles: modelProfiles.map(profile => ({ ...profile, active: profile.id === activeProfileId })) }; };
let nextDelay = false;
let discoveryMode = 'success';
const libraryItems = Array.from({ length: 24 }, (_, index) => ({
  id: index === 20 ? 601 : index + 1,
  name: index === 0 || index === 20 ? 'UI模拟 · 春日包装' : '其他图档 ' + index,
  ipName: '春日系列',
  images: [{ storedName: 'assistant-simulation-green.png', name: '绿色预览.png', url: 'https://untrusted.invalid/preview.png' }]
}));
let targetRemoved = false;
let nextLibraryRead;
let libraryDelete;

function holdResponse() {
  let markStarted, release;
  const started = new Promise(resolve => { markStarted = resolve; });
  const released = new Promise(resolve => { release = resolve; });
  return { started, release, pause: () => { markStarted(); return released; } };
}

await page.route('**/api/**', async route => {
  const url = new URL(route.request().url());
  const fulfill = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
  if (url.pathname === '/api/assistant/chat') {
    const body = route.request().postDataJSON(); chats.push(body);
    const colorSearch = body.message === '给我绿色图档';
    const lanChat = modelProfiles.find(profile => profile.id === activeProfileId).baseUrl === lanProxyBase;
    const response = body.attachmentStoredName || body.message === '按IP或风格查找图档' || colorSearch ? {
      ...modelMeta(),
      mode: 'search', answer: 'UI 模拟：找到相关图档。', serviceMessage: '当前按名称、IP 与文字标签匹配，未进行参考图视觉分析。',
      images: [{ id: 601, name: 'UI模拟 · 春日包装', ipName: '春日系列', reasons: ['同系列：春日'], images: [{ storedName: colorSearch ? 'assistant-simulation-green.png' : 'assistant-simulation-cover.ai', name: colorSearch ? '绿色预览.png' : 'UI模拟.ai' }] }],
      sources: [{ title: '查看图库', view: 'image-library' }], suggestions: []
    } : lanChat ? {
      ...modelMeta(), mode: 'ai', answer: 'UI 模拟：内网代理模型已处理本次提问。',
      serviceMessage: '', sources: [], tasks: [], suggestions: []
    } : {
      ...modelMeta(),
      mode: 'guide', answer: 'UI 模拟：根据当前任务先确认需求，再准备交付。<img src=x onerror="window.assistantXss=true">',
      serviceMessage: '模型暂不可用，以下为系统规则和实时查询结果。',
      sources: [{ title: '查看我的子任务', view: 'tasks' }, { title: '拒绝任意 URL', view: 'javascript:alert(1)' }],
      tasks: [{ id: 8001, projectId: 9001, name: 'UI模拟 · 包装设计', status: 'accepted', deadline: '2026-11-20' }],
      suggestions: ['如何交付这个任务？', '按IP或风格查找图档']
    };
    if (nextDelay) { nextDelay = false; pending.push({ route, response }); return; }
    return fulfill(response);
  }
  if (url.pathname === '/api/image-library') {
    const items = libraryItems.filter(item => !targetRemoved || item.id !== 601);
    const read = nextLibraryRead;
    nextLibraryRead = null;
    await read?.pause();
    return fulfill(items);
  }
  if (url.pathname === '/api/image-library/1' && route.request().method() === 'DELETE') {
    await libraryDelete?.pause();
    libraryItems.splice(libraryItems.findIndex(item => item.id === 1), 1);
    return fulfill({});
  }
  if (url.pathname === '/api/assistant/model') return fulfill(modelMeta());
  if (url.pathname === '/api/assistant/config/models') {
    const body = route.request().postDataJSON(); discoveries.push(body);
    if (discoveryMode === 'unsupported') return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: '此接口未提供模型列表，可手动填写模型名称' }) });
    const kimi = body.baseUrl.includes('moonshot'), lan = body.baseUrl === lanProxyBase;
    const response = {
      baseUrl: lan ? lanProxyBase : kimi ? 'https://api.moonshot.cn/v1' : 'https://api.deepseek.com',
      provider: lan ? 'custom' : kimi ? 'kimi' : 'deepseek', dialect: lan ? 'compatible' : kimi ? 'kimi' : 'deepseek', thinkingMode: 'auto',
      models: lan
        ? [{ id: 'lan-chat', name: 'lan-chat', visionSupported: false, visionKnown: true }]
        : kimi
        ? [{ id: 'kimi-k2.6', name: 'kimi-k2.6', visionSupported: true, visionKnown: true }]
        : [{ id: 'deepseek-chat', name: 'deepseek-chat', visionSupported: false, visionKnown: true }, { id: 'deepseek-reasoner', name: 'deepseek-reasoner', visionSupported: false, visionKnown: true }]
    };
    if (discoveryMode === 'delay') { pendingDiscoveries.push({ route, response }); return; }
    return fulfill(response);
  }
  if (url.pathname === '/api/assistant/config') {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON(); saves.push(body);
      if (body.operation === 'save') {
        const existing = modelProfiles.find(profile => profile.id === body.profile.id);
        const id = existing?.id || `ui-profile-${nextProfileId++}`;
        const { apiKey, ...profile } = body.profile;
        if (!existing && !apiKey) return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: '新模型需要自己的密钥' }) });
        const saved = { ...existing, ...profile, id, builtin: false, keyConfigured: !!apiKey || existing?.keyConfigured === true };
        if (existing) modelProfiles.splice(modelProfiles.indexOf(existing), 1, saved); else modelProfiles.push(saved);
        if (body.activate) activeProfileId = id;
        return fulfill({ ...configView(), savedProfileId: id });
      }
      if (body.operation === 'activate') activeProfileId = body.profileId;
      if (body.operation === 'setEnabled') modelEnabled = body.enabled;
      if (body.operation === 'delete') {
        modelProfiles.splice(modelProfiles.findIndex(profile => profile.id === body.profileId), 1);
        if (activeProfileId === body.profileId) activeProfileId = 'server';
      }
    }
    return fulfill(configView());
  }
  if (url.pathname === '/api/files/upload') {
    uploads.push(route.request().headers()['content-type']);
    return fulfill({ storedName: 'assistant-simulation-reference.png', name: 'ui-reference.png', size: 68 });
  }
  if (url.pathname.startsWith('/api/files/')) return route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5xkAAAAASUVORK5CYII=', 'base64') });
  return fulfill({});
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  await page.route('**/js/*.js*', async route => route.fulfill({ contentType: 'text/javascript', body: await fs.readFile(path.resolve('src/main/resources/static/js', path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  await page.route('**/css/*.css*', async route => route.fulfill({ contentType: 'text/css', body: await fs.readFile(path.resolve('src/main/resources/static/css', path.basename(new URL(route.request().url()).pathname)), 'utf8') }));
  await page.route(baseURL + '/', async route => route.fulfill({ contentType: 'text/html', body: await fs.readFile(path.resolve('src/main/resources/static/index.html'), 'utf8') }));
}

async function setIdentity(role, userId) {
  await page.evaluate(({ role, userId }) => {
    document.dispatchEvent(new Event('emie:identity-changing'));
    const e = window.EMIE;
    e.state.currentRole = role; e.state.currentUserId = userId; e.state.authUser = { userId, name: 'UI模拟员工', role };
    e.state.permissions = ['page.dashboard.view', 'page.subtasks.mine.view', 'page.projects.view', 'page.image_library.view', 'file.upload', 'admin.config.view', 'admin.config.edit'];
    e.state.permissionVersion = 'ui-simulation';
    e.state.currentView = 'tasks';
    localStorage.setItem('design_pm_token', 'business-assistant-ui-simulation-only');
    document.getElementById('loginPage').style.display = 'none'; document.getElementById('appContainer').style.display = '';
    document.documentElement.dataset.appReady = 'authenticated';
    const f = window.assistantFixture ||= { navigate: e.actions.navigate, sources: [], previews: [], rasterPreviews: [], downloads: [], opened: [] };
    e.actions.navigate = view => { f.sources.push(view); e.state.currentView = view; document.getElementById('mainContent').textContent = 'UI模拟页面 · ' + view; };
    e.actions.openProjectDetail = (projectId, taskId) => f.opened.push({ projectId, taskId });
    e.actions.openFilePreview = (...args) => f.previews.push(args);
    e.actions.previewImage = (...args) => f.rasterPreviews.push(args);
    e.actions.doDirectDownload = (...args) => f.downloads.push(args);
  }, { role, userId });
  await page.locator('#businessAssistantLauncher').waitFor({ state: 'visible' });
}
async function send(message) {
  await page.locator('#businessAssistantInput').fill(message);
  await page.locator('#businessAssistantSend').click();
  await page.locator('#businessAssistantPending').waitFor({ state: 'hidden' });
}

async function fileDrag(type, files = [{ name: 'dragged-reference.png', mimeType: 'image/png' }], selector = '#businessAssistantPanel') {
  return page.evaluate(({ type, files, selector }) => {
    const transfer = new DataTransfer();
    for (const file of files) transfer.items.add(new File([new Uint8Array(file.size || 4)], file.name, { type: file.mimeType || '' }));
    const event = new DragEvent(type, { dataTransfer: transfer, bubbles: true, cancelable: true });
    document.querySelector(selector).dispatchEvent(event);
    return event.defaultPrevented;
  }, { type, files, selector });
}

async function dragLauncherTo(x, y) {
  const launcher = page.locator('#businessAssistantLauncher');
  const before = await launcher.boundingBox();
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  await page.mouse.move(x, y, { steps: 12 });
  await page.mouse.up();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return { before, after: await launcher.boundingBox() };
}

async function assertPanelFits() {
  const bounds = await page.locator('#businessAssistantPanel').boundingBox(), viewport = page.viewportSize();
  assert.ok(bounds && bounds.x >= -1 && bounds.y >= -1 && bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1,
    `assistant panel fits viewport: ${JSON.stringify({ bounds, viewport })}`);
}

async function assertComposerAndCloseVisible() {
  const panel = await page.locator('#businessAssistantPanel').boundingBox();
  for (const selector of ['#businessAssistantSend', '[data-assistant-action="close"]']) {
    const bounds = await page.locator(selector).boundingBox();
    assert.ok(bounds && bounds.x >= panel.x && bounds.y >= panel.y && bounds.x + bounds.width <= panel.x + panel.width && bounds.y + bounds.height <= panel.y + panel.height,
      `${selector} remains fully inside the visible panel on a short screen: ${JSON.stringify({ bounds, panel })}`);
  }
}

async function setConfiguredModel(model) {
  let control = page.locator('#businessAssistantConfigForm [name="model"]');
  if (await control.evaluate(element => element.tagName) === 'SELECT') {
    if (await control.locator(`option[value="${model}"]`).count()) return control.selectOption(model);
    await page.locator('[data-assistant-action="config-manual-model"]').click();
    control = page.locator('#businessAssistantConfigForm input[name="model"]');
  }
  await control.fill(model);
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.modules?.businessAssistant && window.EMIE?.modules?.coreUi);
  assert.equal(await page.locator('#businessAssistantLauncher').isVisible(), false, 'assistant hidden before authentication');
  await setIdentity('designer', 'assistant-ui-designer');
  const moved = await dragLauncherTo(1050, 720);
  assert.ok(Math.hypot(moved.after.x - moved.before.x, moved.after.y - moved.before.y) >= 100, 'launcher follows a real pointer drag');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), false, 'dragging launcher does not open the panel');
  await page.locator('#businessAssistantLauncher').click();
  await page.locator('#businessAssistantInput').waitFor({ state: 'visible' });
  await assertPanelFits();
  assert.equal(await page.locator('#businessAssistantSettings').isVisible(), false);
  await page.getByRole('button', { name: '我是新员工，从哪里开始？', exact: true }).click();
  await page.locator('.business-assistant-answer').waitFor();
  assert.match(await page.locator('#businessAssistantMessages').innerText(), /系统操作指引/);
  assert.match(await page.locator('#businessAssistantMessages').innerText(), /模型暂不可用/);
  assert.equal(await page.evaluate(() => window.assistantXss), undefined);
  assert.equal(await page.locator('#businessAssistantMessages img[src="x"]').count(), 0);
  assert.equal(await page.locator('[data-view="javascript:alert(1)"]').count(), 0);
  assert.equal(chats[0].message, '我是新员工，从哪里开始？');
  assert.ok(!Object.hasOwn(chats[0], 'role') && !Object.hasOwn(chats[0], 'userId'));

  await page.locator('.business-assistant-task').click();
  assert.deepEqual(await page.evaluate(() => window.assistantFixture.opened[0]), { projectId: 9001, taskId: 8001 });
  await page.evaluate(() => {
    const modal = document.createElement('div');
    modal.id = 'publishedSubTaskDetailModal'; modal.className = 'modal-overlay';
    modal.dataset.assistantProjectId = '9001'; modal.dataset.assistantTaskId = '8001';
    modal.innerHTML = '<div class="modal"><div class="modal-header"><div class="modal-title">UI模拟 · 业务任务抽屉</div><button type="button" class="modal-close">✕</button></div><div class="modal-body"><input aria-label="业务任务输入框"><button type="button">业务任务操作</button></div></div>';
    modal.querySelector('.modal-close').addEventListener('click', () => modal.remove());
    document.body.appendChild(modal);
  });
  await page.waitForFunction(() => document.querySelector('#businessAssistantContext').textContent.includes('任务 #8001'));
  const businessDrawer = page.locator('#publishedSubTaskDetailModal');
  await businessDrawer.getByRole('button', { name: '关闭弹窗', exact: true }).waitFor();
  await page.waitForFunction(() => document.activeElement?.closest('#publishedSubTaskDetailModal') !== null);
  await page.locator('#businessAssistantInput').focus();
  await page.locator('#businessAssistantInput').press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement?.closest('#businessAssistantRoot') !== null), true, 'ordinary modal focus trap must allow assistant keyboard input');
  assert.equal(await businessDrawer.isVisible(), true);
  await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'businessAssistantInput', 'assistant can tab backwards while business drawer is open');
  await page.locator('#businessAssistantInput').press('Escape');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), false, 'Escape inside assistant collapses assistant');
  assert.equal(await businessDrawer.isVisible(), true, 'Escape inside assistant must preserve business drawer');
  await page.locator('#businessAssistantLauncher').click();

  await send('当前任务下一步怎么处理？');
  assert.deepEqual(chats.at(-1).context, { view: 'tasks', projectId: 9001, taskId: 8001 });
  assert.ok(chats.at(-1).history.length <= 8);
  await businessDrawer.getByRole('button', { name: '关闭弹窗', exact: true }).focus();
  await page.keyboard.press('Escape');
  await businessDrawer.waitFor({ state: 'detached' });
  await page.waitForFunction(() => !document.querySelector('#businessAssistantContext').textContent.includes('项目 #'));
  await send('查询我的待办');
  assert.deepEqual(chats.at(-1).context, { view: 'tasks' });

  await page.locator('#businessAssistantFile').setInputFiles({ name: 'ui-reference.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5xkAAAAASUVORK5CYII=', 'base64') });
  assert.equal(uploads.length, 0, 'selecting an attachment does not upload it');
  await send('找春日风格图档');
  assert.equal(uploads.length, 1);
  assert.equal(chats.at(-1).attachmentStoredName, 'assistant-simulation-reference.png');
  assert.match(await page.locator('.business-assistant-message').last().innerText(), /未进行参考图视觉分析/);
  await page.locator('.business-assistant-image [data-assistant-action="preview"]').click();
  await page.locator('.business-assistant-image [data-assistant-action="download"]').click();
  assert.equal(await page.evaluate(() => window.assistantFixture.previews.length), 1);
  assert.equal(await page.evaluate(() => window.assistantFixture.downloads.length), 1);
  await send('给我绿色图档');
  const colorCard = page.locator('.business-assistant-image').last();
  await colorCard.locator('[data-assistant-action="preview"]').click();
  assert.equal(await page.evaluate(() => window.assistantFixture.rasterPreviews.length), 1, 'raster image uses image preview');
  assert.equal(await page.evaluate(() => window.assistantFixture.previews.length), 1, 'raster image does not enter document preview');
  await page.screenshot({ path: '/tmp/emie-business-assistant-conversation-ui.png', fullPage: true });
  await page.evaluate(() => {
    const transfer = new DataTransfer();
    transfer.items.add(new File([new Uint8Array([137, 80, 78, 71])], 'pasted-simulation.png', { type: 'image/png' }));
    document.querySelector('#businessAssistantInput').dispatchEvent(new ClipboardEvent('paste', { clipboardData: transfer, bubbles: true, cancelable: true }));
  });
  assert.match(await page.locator('#businessAssistantAttachment').innerText(), /pasted-simulation.png/);
  assert.equal(uploads.length, 1, 'pasting a screenshot does not upload until sending');
  await page.locator('[data-assistant-action="remove-file"]').click();
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);

  const uploadsBeforeDrag = uploads.length, urlBeforeDrop = page.url();
  await fileDrag('dragenter');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), true, 'file drag shows a drop target');
  await page.screenshot({ path: '/tmp/emie-business-assistant-drop-ui.png', fullPage: true });
  assert.equal(await fileDrag('dragover'), true, 'file dragover allows drop');
  await fileDrag('dragleave');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false, 'leaving panel clears drop target');
  await fileDrag('dragenter');
  assert.equal(await fileDrag('drop'), true, 'file drop must not navigate away');
  assert.equal(page.url(), urlBeforeDrop);
  assert.match(await page.locator('#businessAssistantAttachment').innerText(), /dragged-reference.png/);
  assert.equal(uploads.length, uploadsBeforeDrag, 'dropping an image waits until Send to upload');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false, 'drop clears drag indicator');
  await page.locator('[data-assistant-action="remove-file"]').click();
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);
  await fileDrag('drop', [{ name: 'dragged-reference.pdf', mimeType: 'application/pdf' }]);
  assert.match(await page.locator('#businessAssistantAttachment').innerText(), /dragged-reference.pdf/);
  assert.equal(uploads.length, uploadsBeforeDrag);
  await send('查找这个 PDF 相关图档');
  assert.equal(uploads.length, uploadsBeforeDrag + 1, 'dropped PDF uploads only on Send');
  assert.equal(chats.at(-1).attachmentStoredName, 'assistant-simulation-reference.png');
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);

  await fileDrag('drop', [{ name: 'unsupported.exe', mimeType: 'application/octet-stream' }]);
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);
  assert.match(await page.locator('#businessAssistantFileStatus').innerText(), /支持图片、AI、PDF/);
  await fileDrag('drop', [{ name: 'too-large.pdf', mimeType: 'application/pdf', size: 15 * 1024 * 1024 + 1 }]);
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);
  assert.match(await page.locator('#businessAssistantFileStatus').innerText(), /15\s?MB/);
  await fileDrag('drop', [{ name: 'first.png', mimeType: 'image/png' }, { name: 'second.pdf', mimeType: 'application/pdf' }]);
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false, 'multiple files are not silently truncated to one');
  assert.match(await page.locator('#businessAssistantFileStatus').innerText(), /每次只能添加一个/);
  assert.equal(uploads.length, uploadsBeforeDrag + 1, 'invalid files never upload');

  const textDrag = await page.evaluate(() => {
    const transfer = new DataTransfer(); transfer.setData('text/plain', '普通拖动文字');
    return ['dragenter', 'dragover', 'drop'].map(type => {
      const event = new DragEvent(type, { dataTransfer: transfer, bubbles: true, cancelable: true });
      document.getElementById('businessAssistantInput').dispatchEvent(event);
      return event.defaultPrevented;
    });
  });
  assert.deepEqual(textDrag, [false, false, false], 'ordinary text drag keeps native editing behavior');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false);
  await page.evaluate(() => { window.EMIE.state.permissions = window.EMIE.state.permissions.filter(permission => permission !== 'file.upload'); window.EMIE.actions.openBusinessAssistant(); });
  await fileDrag('dragenter'); await fileDrag('drop');
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false, 'drop requires upload permission');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false);
  assert.equal(uploads.length, uploadsBeforeDrag + 1);
  await send('说明任务交付流程');
  const returnedSuggestion = page.locator('#businessAssistantMessages .is-assistant').last().getByRole('button', { name: '按IP或风格查找图档', exact: true });
  assert.equal(await returnedSuggestion.isVisible(), true, 'view-only designer can see image search suggestions without upload permission');
  await returnedSuggestion.click();
  await page.locator('#businessAssistantPending').waitFor({ state: 'hidden' });
  assert.equal(chats.at(-1).message, '按IP或风格查找图档');
  assert.equal(chats.at(-1).attachmentStoredName, undefined, 'text image search does not need an attachment');
  assert.equal(await page.locator('#businessAssistantMessages .is-assistant').last().locator('.business-assistant-image').count(), 1, 'view-only designer can receive image search results');
  assert.equal(uploads.length, uploadsBeforeDrag + 1, 'text image search does not upload');
  await page.evaluate(() => { window.EMIE.state.permissions = window.EMIE.state.permissions.filter(permission => permission !== 'page.image_library.view'); });
  await send('说明任务交付流程');
  assert.equal(await page.locator('#businessAssistantMessages .is-assistant').last().getByRole('button', { name: '按IP或风格查找图档', exact: true }).count(), 0, 'missing library-view permission hides returned image search suggestions');
  assert.equal(await page.locator('#businessAssistantMessages .is-assistant').last().getByRole('button', { name: '如何交付这个任务？', exact: true }).isVisible(), true);
  await page.evaluate(() => { window.EMIE.state.permissions.push('page.image_library.view'); });
  await page.evaluate(() => { window.EMIE.state.permissions.push('file.upload'); window.EMIE.actions.openBusinessAssistant(); });


  await page.evaluate(() => window.EMIE.actions.navigate('image-library'));
  await page.waitForFunction(() => document.querySelector('#businessAssistantContext').textContent === '图档库');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), true);
  assert.match(await page.locator('#businessAssistantMessages').innerText(), /UI 模拟/);
  await page.locator('#businessAssistantInput').press('Escape');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), false);
  await fileDrag('dragenter', undefined, '#businessAssistantLauncher');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), false, 'hovering a file over the launcher does not open it');
  const uploadsBeforeLauncherDrop = uploads.length;
  await fileDrag('drop', undefined, '#businessAssistantLauncher');
  assert.equal(await page.locator('#businessAssistantPanel').isVisible(), true, 'dropping on the launcher opens the assistant');
  assert.match(await page.locator('#businessAssistantAttachment').innerText(), /dragged-reference.png/);
  assert.equal(uploads.length, uploadsBeforeLauncherDrop);
  await page.locator('[data-assistant-action="remove-file"]').click();

  await fileDrag('dragenter');
  await page.locator('[data-assistant-action="new"]').click();
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false);
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false, 'new conversation resets drag indicator');

  nextDelay = true;
  await page.locator('#businessAssistantInput').fill('延迟答复用于身份隔离测试');
  await page.locator('#businessAssistantSend').click();
  await page.waitForFunction(() => document.querySelector('#businessAssistantSend').disabled);
  const uploadsBeforeBusyDrop = uploads.length;
  await fileDrag('dragenter'); await fileDrag('drop');
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false, 'drop is blocked while awaiting a response');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false);
  assert.equal(uploads.length, uploadsBeforeBusyDrop);
  await setIdentity('sales', 'assistant-ui-sales');
  await page.locator('#businessAssistantLauncher').click();
  assert.equal(await page.locator('#businessAssistantMessages .business-assistant-message').count(), 0, 'identity switch clears previous conversation');
  assert.equal(await page.locator('.business-assistant-upload').isVisible(), false);
  await fileDrag('drop');
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false, 'role without image search cannot attach a dropped file');
  assert.equal(await page.getByRole('button', { name: '帮我找相关图档', exact: true }).count(), 0);
  for (const held of pending.splice(0)) { try { await held.route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(held.response) }); } catch { /* request aborted after identity switch */ } }
  await page.waitForTimeout(150);
  assert.equal(await page.locator('#businessAssistantMessages .business-assistant-message').count(), 0, 'late response cannot cross identities');
  await send('说明任务交付流程');
  assert.equal(await page.getByRole('button', { name: '按IP或风格查找图档', exact: true }).count(), 0, 'sales cannot see returned image search suggestions');
  const allowedSuggestion = page.locator('#businessAssistantMessages .is-assistant').last().getByRole('button', { name: '如何交付这个任务？', exact: true });
  assert.equal(await allowedSuggestion.isVisible(), true, 'non-library suggestions remain available');
  await allowedSuggestion.click();
  await page.locator('#businessAssistantPending').waitFor({ state: 'hidden' });
  assert.equal(chats.at(-1).message, '如何交付这个任务？');

  await setIdentity('admin', 'assistant-ui-admin');
  await page.locator('#businessAssistantLauncher').click();
  await page.locator('#businessAssistantSettings').click();
  await page.locator('.business-assistant-server-profile').waitFor();
  assert.equal(await page.locator('#businessAssistantProfile').inputValue(), 'server');
  assert.match(await page.locator('.business-assistant-active-model').innerText(), /服务器 Kimi/);
  assert.equal(await page.locator('[data-assistant-action="config-delete"]').count(), 0, 'server default cannot be deleted');
  assert.equal(await page.locator('.business-assistant-server-profile input[name="apiKey"]').inputValue(), '');
  assert.equal(await page.locator('.business-assistant-server-profile input[name="apiKey"]').isDisabled(), true);
  await page.locator('[data-assistant-action="config-new"]').click();
  await page.locator('#businessAssistantConfigForm').waitFor();
  assert.equal(await page.locator('#businessAssistantModelAdvanced').evaluate(element => element.open), false);
  assert.equal(await page.locator('#businessAssistantConfigForm button[name="save"]').isDisabled(), true);
  const savesBeforeDiscovery = saves.length;
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://api.deepseek.com');
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-simulation-custom-key');
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.locator('#businessAssistantDiscoveredModel option[value="deepseek-chat"]').waitFor({ state: 'attached' });
  assert.equal(discoveries.at(-1).baseUrl, 'https://api.deepseek.com');
  assert.equal(discoveries.at(-1).apiKey, 'ui-simulation-custom-key');
  assert.equal(saves.length, savesBeforeDiscovery, 'fetching models does not save or activate any profile');
  assert.match(await page.locator('.business-assistant-active-model').innerText(), /服务器 Kimi/);
  assert.ok(await page.locator('#businessAssistantConfigForm input[name="name"]').inputValue(), 'display name generated automatically');
  assert.equal(await page.locator('#businessAssistantConfigForm select[name="provider"]').inputValue(), 'deepseek');
  assert.equal(await page.locator('#businessAssistantConfigForm select[name="dialect"]').inputValue(), 'deepseek');
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="visionSupported"]').isChecked(), false);
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="apiKey"]').inputValue(), 'ui-simulation-custom-key');
  await page.locator('#businessAssistantConfigForm button[name="save"]').click();
  await page.getByText('模型配置已保存，当前使用模型未改变。', { exact: true }).waitFor();
  assert.equal(saves.at(-1).operation, 'save');
  assert.equal(saves.at(-1).profile.provider, 'deepseek');
  assert.equal(saves.at(-1).profile.apiKey, 'ui-simulation-custom-key');
  assert.equal(saves.at(-1).activate, false);
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="apiKey"]').inputValue(), '');
  assert.match(await page.locator('.business-assistant-active-model').innerText(), /服务器 Kimi/);
  const savedProfileId = await page.locator('#businessAssistantProfile').inputValue();
  assert.notEqual(savedProfileId, 'server');
  await setConfiguredModel('deepseek-reasoner');
  await page.locator('#businessAssistantConfigForm button[name="save"]').click();
  await page.getByText('模型配置已保存，当前使用模型未改变。', { exact: true }).waitFor();
  assert.equal(saves.at(-1).profile.id, savedProfileId);
  assert.ok(!Object.hasOwn(saves.at(-1).profile, 'apiKey'), 'blank key retains only the selected profile credential');
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://different.example/v1');
  const beforeChangedEndpoint = saves.length;
  if (!await page.locator('#businessAssistantConfigForm button[name="save"]').isDisabled()) await page.locator('#businessAssistantConfigForm button[name="save"]').click();
  assert.equal(saves.length, beforeChangedEndpoint, 'changing endpoint requires its own key');
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://api.deepseek.com');
  await page.locator('[data-assistant-action="config-activate"]').click();
  await page.getByText('已切换当前模型，全体成员下一次提问生效。', { exact: true }).waitFor();
  assert.deepEqual(saves.at(-1), { operation: 'activate', profileId: savedProfileId });
  assert.match(await page.locator('#businessAssistantModel').innerText(), /deepseek/i);
  assert.equal(await page.locator('.business-assistant-upload').evaluate(element => element.hidden), true, 'text-only model does not accept image understanding uploads');
  await page.locator('[data-assistant-action="config-back"]').click();
  await fileDrag('dragenter'); await fileDrag('drop');
  assert.equal(await page.locator('#businessAssistantAttachment').isVisible(), false, 'text-only model rejects dropped reference material');
  assert.equal(await page.locator('#businessAssistantPanel').evaluate(element => element.classList.contains('is-file-dragover')), false);
  await page.locator('#businessAssistantSettings').click();
  await page.locator('#businessAssistantEnabled').waitFor();
  await page.locator('#businessAssistantEnabled').uncheck();
  await page.getByText('模型对话已停用，系统指引与文字检索仍可使用。', { exact: true }).waitFor();
  assert.deepEqual(saves.at(-1), { operation: 'setEnabled', enabled: false });
  await page.locator('#businessAssistantEnabled').check();
  await page.getByText('模型对话已启用。', { exact: true }).waitFor();
  await page.locator('[data-assistant-action="config-delete"]').click();
  await page.locator('#businessAssistantDeleteConfirm').waitFor({ state: 'visible' });
  assert.match(await page.locator('#businessAssistantDeleteConfirm').innerText(), /切回服务器默认 Kimi/);
  const beforeDelete = saves.length;
  await page.locator('[data-assistant-action="config-delete-cancel"]').click();
  assert.equal(saves.length, beforeDelete);
  await page.locator('[data-assistant-action="config-delete"]').click();
  await page.locator('[data-assistant-action="config-delete-confirm"]').click();
  await page.getByText('模型配置已删除。', { exact: true }).waitFor();
  assert.equal(await page.locator('#businessAssistantProfile').inputValue(), 'server');
  assert.match(await page.locator('#businessAssistantModel').innerText(), /服务器 Kimi/);
  assert.equal(await page.locator('.business-assistant-upload').evaluate(element => element.hidden), false);
  await page.locator('[data-assistant-action="config-new"]').click();
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://api.moonshot.cn/v1');
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-simulation-another-key');
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.locator('#businessAssistantDiscoveredModel option[value="kimi-k2.6"]').waitFor({ state: 'attached' });
  assert.equal(await page.locator('#businessAssistantConfigForm select[name="thinkingMode"]').inputValue(), 'auto');
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="visionSupported"]').isChecked(), true);
  await page.locator('#businessAssistantModelAdvanced > summary').click();
  await page.locator('#businessAssistantConfigForm input[name="name"]').fill('备用 Kimi');
  await page.locator('#businessAssistantConfigForm button[name="saveAndUse"]').click();
  await page.getByText('模型已保存并切换，全体成员下一次提问生效。', { exact: true }).waitFor();
  assert.equal(saves.at(-1).activate, true);
  const backupProfileId = await page.locator('#businessAssistantProfile').inputValue();
  assert.match(await page.locator('#businessAssistantModel').innerText(), /备用 Kimi/);
  await page.locator('#businessAssistantProfile').selectOption('server');
  await page.locator('.business-assistant-server-profile').waitFor();
  assert.match(await page.locator('.business-assistant-active-model').innerText(), /备用 Kimi/);
  assert.match(await page.locator('#businessAssistantModel').innerText(), /备用 Kimi/, 'selecting an editor does not activate it');
  await page.getByRole('button', { name: '使用服务器默认', exact: true }).click();
  await page.getByText('已切换当前模型，全体成员下一次提问生效。', { exact: true }).waitFor();
  await page.locator('#businessAssistantProfile').selectOption(backupProfileId);
  await page.locator('[data-assistant-action="config-delete"]').click();
  assert.match(await page.locator('#businessAssistantDeleteConfirm').innerText(), /当前使用的模型不会改变/);
  await page.locator('[data-assistant-action="config-delete-confirm"]').click();
  await page.getByText('模型配置已删除。', { exact: true }).waitFor();
  assert.match(await page.locator('#businessAssistantModel').innerText(), /服务器 Kimi/);

  // 内网兼容代理保留 HTTP 与自定义端口；模型请求统一由本机业务 API 转发。
  await page.locator('[data-assistant-action="config-new"]').click();
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill(lanProxyBase + '/chat/completions');
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-lan-proxy-key');
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.locator('#businessAssistantDiscoveredModel option[value="lan-chat"]').waitFor({ state: 'attached' });
  assert.deepEqual(discoveries.at(-1), { baseUrl: lanProxyBase, apiKey: 'ui-lan-proxy-key' });
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').inputValue(), lanProxyBase);
  assert.equal(await page.locator('#businessAssistantConfigForm select[name="provider"]').inputValue(), 'custom');
  assert.equal(await page.locator('#businessAssistantConfigForm select[name="dialect"]').inputValue(), 'compatible');
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="visionSupported"]').isChecked(), false);
  await page.locator('#businessAssistantConfigForm button[name="saveAndUse"]').click();
  await page.getByText('模型已保存并切换，全体成员下一次提问生效。', { exact: true }).waitFor();
  const lanProfileId = await page.locator('#businessAssistantProfile').inputValue();
  assert.notEqual(lanProfileId, 'server');
  assert.equal(saves.at(-1).activate, true);
  assert.equal(saves.at(-1).profile.baseUrl, lanProxyBase);
  assert.equal(saves.at(-1).profile.model, 'lan-chat');
  assert.equal(saves.at(-1).profile.provider, 'custom');
  assert.equal(saves.at(-1).profile.dialect, 'compatible');
  assert.equal(saves.at(-1).profile.apiKey, 'ui-lan-proxy-key');
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="apiKey"]').inputValue(), '');
  assert.match(await page.locator('#businessAssistantModel').innerText(), /lan-chat/);
  await page.locator('[data-assistant-action="config-back"]').click();
  await send('通过内网代理说明任务下一步');
  assert.equal(chats.at(-1).message, '通过内网代理说明任务下一步');
  const lanAnswer = page.locator('#businessAssistantMessages .is-assistant').last();
  assert.match(await lanAnswer.locator('.business-assistant-mode').innerText(), /AI 回答 · lan-chat/);
  assert.match(await lanAnswer.locator('.business-assistant-answer').innerText(), /内网代理模型已处理本次提问/);
  assert.equal(Object.hasOwn(chats.at(-1), 'apiKey'), false, 'chat never sends a model credential from the browser');
  assert.equal(Object.hasOwn(chats.at(-1), 'baseUrl'), false, 'chat uses the server-selected model profile');
  assert.deepEqual(directProxyRequests, [], 'browser never requests the private model proxy directly');
  await page.locator('#businessAssistantSettings').click();
  await page.locator('#businessAssistantConfigForm').waitFor();
  assert.equal(await page.locator('#businessAssistantProfile').inputValue(), lanProfileId);
  await page.locator('[data-assistant-action="config-delete"]').click();
  assert.match(await page.locator('#businessAssistantDeleteConfirm').innerText(), /切回服务器默认 Kimi/);
  await page.locator('[data-assistant-action="config-delete-confirm"]').click();
  await page.getByText('模型配置已删除。', { exact: true }).waitFor();
  assert.equal(await page.locator('#businessAssistantProfile').inputValue(), 'server');
  assert.match(await page.locator('#businessAssistantModel').innerText(), /服务器 Kimi/);

  // 网关未实现模型列表时仍可手动配置；读取失败不切换全局模型。
  discoveryMode = 'unsupported';
  await page.locator('[data-assistant-action="config-new"]').click();
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://gateway.example/v1/chat/completions');
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-manual-gateway-key');
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.locator('#businessAssistantDiscoveryFeedback').getByText(/未提供模型列表/).waitFor();
  await page.locator('[data-assistant-action="config-manual-model"]').click();
  await page.locator('#businessAssistantConfigForm input[name="model"]').fill('gateway-chat');
  await page.locator('#businessAssistantConfigForm button[name="save"]').click();
  await page.getByText('模型配置已保存，当前使用模型未改变。', { exact: true }).waitFor();
  assert.equal(saves.at(-1).profile.model, 'gateway-chat');
  assert.equal(saves.at(-1).profile.baseUrl, 'https://gateway.example/v1');
  assert.equal(saves.at(-1).profile.provider, 'custom');
  assert.equal(saves.at(-1).profile.dialect, 'compatible');
  assert.match(await page.locator('#businessAssistantModel').innerText(), /服务器 Kimi/);
  await page.locator('[data-assistant-action="config-delete"]').click();
  await page.locator('[data-assistant-action="config-delete-confirm"]').click();
  await page.getByText('模型配置已删除。', { exact: true }).waitFor();

  // 地址或密钥改变后，不得把旧响应填入新配置，也不能使用旧列表保存。
  discoveryMode = 'delay';
  await page.locator('[data-assistant-action="config-new"]').click();
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://api.deepseek.com');
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-stale-discovery-key');
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.waitForFunction(() => document.querySelector('[data-assistant-action="config-discover"]')?.disabled);
  assert.equal(pendingDiscoveries.length, 1);
  await page.locator('#businessAssistantConfigForm input[name="baseUrl"]').fill('https://api.moonshot.cn/v1');
  const obsolete = pendingDiscoveries.shift();
  await obsolete.route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(obsolete.response) }).catch(() => {});
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#businessAssistantDiscoveredModel option[value="deepseek-chat"]').count(), 0);
  assert.equal(await page.locator('#businessAssistantConfigForm button[name="save"]').isDisabled(), true);
  discoveryMode = 'success';
  await page.locator('[data-assistant-action="config-discover"]').click();
  await page.locator('#businessAssistantDiscoveredModel option[value="kimi-k2.6"]').waitFor({ state: 'attached' });
  assert.equal(await page.locator('#businessAssistantConfigForm input[name="apiKey"]').inputValue(), 'ui-stale-discovery-key');
  assert.equal(saves.filter(item => item.profile?.apiKey === 'ui-stale-discovery-key').length, 0);
  await page.locator('#businessAssistantConfigForm input[name="apiKey"]').fill('ui-replaced-discovery-key');
  assert.equal(await page.locator('#businessAssistantDiscoveredModel option[value="kimi-k2.6"]').count(), 0);
  assert.equal(await page.locator('#businessAssistantConfigForm button[name="save"]').isDisabled(), true);
  await page.locator('[data-assistant-action="config-list"]').click();
  await page.locator('.business-assistant-server-profile').waitFor();
  assert.equal(await page.locator('#businessAssistantProfile').inputValue(), 'server');
  assert.equal(await page.locator('#businessAssistantConfig').evaluate(element => element.scrollTop), 0);

  await page.locator('[data-assistant-action="config-back"]').click();
  assert.equal(await page.locator('.business-assistant-upload').isVisible(), true);
  await page.evaluate(() => { window.EMIE.state.permissions = window.EMIE.state.permissions.filter(permission => permission !== 'admin.config.edit'); });
  await page.locator('#businessAssistantSettings').click();
  await page.locator('.business-assistant-server-profile').waitFor();
  assert.equal(await page.locator('#businessAssistantEnabled').isDisabled(), true);
  assert.equal(await page.locator('[data-assistant-action="config-new"]').isDisabled(), true);
  await page.locator('[data-assistant-action="config-back"]').click();
  await setIdentity('designer', 'assistant-ui-new-model-viewer');
  await page.locator('#businessAssistantLauncher').click();
  await page.waitForFunction(() => document.querySelector('#businessAssistantModel').textContent.includes('服务器 Kimi'));
  assert.equal(await page.locator('#businessAssistantSettings').isVisible(), false);

  for (const [x, y] of [[20, 20], [1420, 980]]) {
    await dragLauncherTo(x, y);
    assert.equal(await page.locator('#businessAssistantPanel').isVisible(), true, 'dragging an open launcher keeps the panel open');
    await assertPanelFits();
  }

  await page.setViewportSize({ width: 390, height: 600 });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await dragLauncherTo(195, 300);
  await assertPanelFits();
  await assertComposerAndCloseVisible();
  await page.screenshot({ path: '/tmp/emie-business-assistant-short-screen-ui.png', fullPage: true });

  await page.setViewportSize({ width: 390, height: 780 });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await assertPanelFits();
  for (const [x, y] of [[15, 15], [375, 765]]) { await dragLauncherTo(x, y); await assertPanelFits(); }
  const bounds = await page.locator('#businessAssistantPanel').boundingBox();
  assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 391, 'mobile panel fits viewport');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  await page.screenshot({ path: '/tmp/emie-business-assistant-ui.png', fullPage: true });
  const persistedLauncher = await page.locator('#businessAssistantLauncher').boundingBox();
  assert.ok(await page.evaluate(() => {
    const saved = JSON.parse(localStorage.getItem('design_pm_assistant_position'));
    return Number.isFinite(saved?.x) && Number.isFinite(saved?.y);
  }), 'dragged position is saved locally');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.modules?.businessAssistant && window.EMIE?.modules?.businessAssistantPosition);
  await setIdentity('designer', 'assistant-ui-position-reload');
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const restoredLauncher = await page.locator('#businessAssistantLauncher').boundingBox();
  assert.ok(Math.abs(restoredLauncher.x - persistedLauncher.x) <= 1 && Math.abs(restoredLauncher.y - persistedLauncher.y) <= 1, 'launcher position survives a reload');
  await page.locator('#businessAssistantLauncher').click();
  await assertPanelFits();
  await page.evaluate(() => localStorage.setItem('design_pm_assistant_position', '{broken-position'));
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.EMIE?.modules?.businessAssistant && window.EMIE?.modules?.businessAssistantPosition);
  await setIdentity('planner', 'assistant-ui-invalid-position');
  await page.locator('#businessAssistantLauncher').click();
  await assertPanelFits();
  // Use the real navigation and library renderer for a target on page three.
  await page.evaluate(() => {
    window.EMIE.actions.navigate = window.assistantFixture.navigate;
    window.EMIE.imageLibraryState = { page: 0, search: '其他图档' };
  });
  await send('给我绿色图档');
  const libraryLink = page.locator('.business-assistant-image').last().getByRole('button', { name: '图档库 ↗', exact: true });
  await libraryLink.click();
  const locatedCard = page.locator('.image-library-card[data-item-id="601"]');
  await locatedCard.waitFor();
  await page.locator('#businessAssistantPanel').waitFor({ state: 'hidden' });
  assert.match(await page.locator('#imageLibraryPagination').innerText(), /3 \/ 3/);
  assert.equal(await page.locator('#imageLibrarySearch').inputValue(), '');
  assert.equal(await locatedCard.evaluate(card => card === document.activeElement), true);
  await page.waitForFunction(() => {
    const card = document.querySelector('.image-library-card[data-item-id="601"]');
    const bounds = card?.getBoundingClientRect();
    return bounds && bounds.top >= 0 && bounds.bottom <= innerHeight;
  });
  assert.equal(await page.locator('.image-library-card[data-item-id="1"]').count(), 0, 'same-name record on page one is not selected');
  await locatedCard.locator('a').click();
  const previewSource = await page.evaluate(() => window.assistantFixture.rasterPreviews.at(-1)[0]);
  assert.equal(new URL(previewSource, baseURL).pathname, '/api/files/download/assistant-simulation-green.png');
  assert.equal(new URL(previewSource, baseURL).origin, new URL(baseURL).origin);


  // Deleting an earlier item moves the target from page three to page two.
  await page.evaluate(() => {
    window.EMIE.imageLibraryState.page = 0;
    window.EMIE.actions.navigate('image-library');
  });
  libraryDelete = holdResponse();
  await page.locator('.image-library-card[data-item-id="1"]').getByRole('button', { name: '删除', exact: true }).click();
  const deleting = page.waitForRequest(request => request.method() === 'DELETE' && new URL(request.url()).pathname === '/api/image-library/1');
  await page.locator('#systemConfirmModal [data-action="confirm"]').click();
  await deleting;
  await libraryDelete.started;
  await page.locator('#businessAssistantLauncher').click();
  const staleRead = holdResponse();
  nextLibraryRead = staleRead;
  await libraryLink.click();
  await staleRead.started;
  libraryDelete.release();
  await page.waitForFunction(() => document.activeElement?.matches('.image-library-card[data-item-id="601"]'));
  assert.match(await page.locator('#imageLibraryPagination').innerText(), /2 \/ 3/);
  assert.equal(await page.locator('#imageLibraryTotal').innerText(), '23');
  const staleResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/image-library');
  staleRead.release();
  await (await staleResponse).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.match(await page.locator('#imageLibraryPagination').innerText(), /2 \/ 3/, 'an older list cannot overwrite the post-delete page');
  assert.equal(await page.locator('#imageLibraryTotal').innerText(), '23');
  assert.equal(await locatedCard.evaluate(card => card === document.activeElement), true);

  await page.locator('#businessAssistantLauncher').click();
  targetRemoved = true;
  await libraryLink.click();
  await page.getByRole('status').filter({ hasText: '该图档已移除或当前无法查看。' }).waitFor();
  assert.equal(await locatedCard.count(), 0);
  await page.evaluate(() => { document.dispatchEvent(new Event('emie:identity-changing')); window.EMIE.state.authUser = null; localStorage.removeItem('design_pm_token'); document.getElementById('appContainer').style.display = 'none'; });
  await page.locator('#businessAssistantLauncher').waitFor({ state: 'hidden' });
  assert.deepEqual(directProxyRequests, [], 'all model proxy traffic stays behind the assistant API');
  assert.deepEqual(errors, []);
  console.log('business assistant UI checks passed (local API simulation, real core-ui keyboard integration, identity isolation, context, material search, exact library item navigation, file drop validation/permissions/deferred upload, draggable launcher/corner positioning/resize, endpoint/key model discovery, LAN proxy profiles without direct browser traffic, manual fallback, stale credential cancellation, model profiles, server default, credential isolation, mobile); screenshot=/tmp/emie-business-assistant-ui.png');
} catch (error) {
  await page.screenshot({ path: '/tmp/emie-business-assistant-ui-failure.png', fullPage: true }).catch(() => {});
  throw error;
} finally { await context.close(); await browser.close(); }
