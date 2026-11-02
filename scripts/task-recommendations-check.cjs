const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const escapeHtml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');

function runtime(sourceFile) {
  const handlers = new Map(), requests = [], posts = [];
  const EMIE = { state: { currentRole: 'admin' }, actions: {
    escHtml: escapeHtml,
    registerEventAction: (name, handler) => handlers.set(name, handler),
    apiGet: path => { const request = { path, ...deferred() }; requests.push(request); return request.promise; },
    apiPost: (path, body) => { const request = { path, body, ...deferred() }; posts.push(request); return request.promise; },
    authenticatedFileUrl: url => `${url}?authToken=test-token`,
  }, registerModule(name, api) { this[name] = api; } };
  const document = { getElementById: () => null };
  const context = vm.createContext({ window: { EMIE }, document, localStorage: { getItem: () => 'test-token' }, setInterval: () => { throw new Error('recommendations must not poll'); } });
  vm.runInContext(fs.readFileSync(sourceFile, 'utf8'), context);
  return { EMIE, handlers, requests, posts, document, context };
}

function host() {
  return { isConnected: true, innerHTML: '', attrs: {}, matches: selector => selector === '[data-task-recommendations]', setAttribute(name, value) { this.attrs[name] = value; } };
}
function card(id, overrides = {}) {
  return { id, libraryItemId: id, name: `图档${id}`, ipName: '测试 IP', subOptions: ['系列一'], images: [{ storedName: `image-${id}.png`, name: `${id}.png`, size: 10, url: 'https://untrusted.invalid/picture.png' }], reasons: ['同一系列'], ...overrides };
}
function response(items, overrides = {}) { return { items, linkedItems: [], associatedLibraryItemIds: [], canAssociate: true, status: 'READY', aiConfigured: true, analysisPending: false, ...overrides }; }
function button(container, id) { return { dataset: { libraryId: String(id) }, closest: selector => selector === '[data-task-recommendations]' ? container : null }; }

async function checkRecommendations() {
  const r = runtime('src/main/resources/static/js/task-recommendations.js');
  const container = host();
  r.EMIE.taskRecommendations.mountTaskRecommendations(container, { projectId: 7, taskId: 11 });
  assert.equal(r.requests[0].path, '/projects/7/subtasks/11/image-recommendations?limit=8');
  assert.match(container.innerHTML, /正在查找相关图档/);
  const untrusted = card(3, { name: '<img src=x onerror=alert(1)>', reasons: ['<script>alert(1)</script>'], images: [{ storedName: '../secret.png', url: 'javascript:alert(1)' }] });
  r.requests[0].resolve(response([card(1), untrusted], { linkedItems: [card(2)], associatedLibraryItemIds: [2] }));
  await tick();
  assert.match(container.innerHTML, /已关联的其他图档/);
  assert.match(container.innerHTML, /同一系列/);
  assert.match(container.innerHTML, /&lt;script&gt;/);
  assert.doesNotMatch(container.innerHTML, /<script>|<img src=x|javascript:|untrusted\.invalid|secret\.png/);
  assert.match(container.innerHTML, /\/api\/files\/thumbnail\/image-1\.png/);

  const pendingAssociation = r.handlers.get('task-recommendation-associate')(null, button(container, 1));
  r.handlers.get('task-recommendation-associate')(null, button(container, 1));
  assert.equal(r.posts.length, 1, 'double click must not create concurrent association requests');
  assert.equal(r.posts[0].path, '/projects/7/subtasks/11/image-recommendations/1/associate');
  r.posts[0].resolve(response([card(1)], { associatedLibraryItemIds: [1, 2], linkedItems: [card(2)] }));
  await pendingAssociation;
  assert.match(container.innerHTML, /已关联到此任务/);
  assert.match(container.innerHTML, /✓ 已关联任务/);
  r.handlers.get('task-recommendation-associate')(null, button(container, 1));
  assert.equal(r.posts.length, 1, 'an associated card must not submit again');

  r.handlers.get('task-recommendations-refresh')(null, button(container, 1));
  assert.match(r.requests[1].path, /limit=8&refresh=true$/);
  r.requests[1].reject(new Error('暂时无法连接服务'));
  await tick();
  assert.match(container.innerHTML, /暂时无法连接服务/);
  assert.match(container.innerHTML, /图档1/, 'refresh failure keeps previous useful cards');

  const reused = host();
  r.EMIE.taskRecommendations.mountTaskRecommendations(reused, { projectId: 7, taskId: 12 });
  r.EMIE.taskRecommendations.mountTaskRecommendations(reused, { projectId: 7, taskId: 13 });
  r.requests[3].resolve(response([card(13)])); await tick();
  r.requests[2].resolve(response([card(12)])); await tick();
  assert.match(reused.innerHTML, /图档13/);
  assert.doesNotMatch(reused.innerHTML, /图档12/, 'late response from another task must not replace current cards');

  const closed = host();
  r.EMIE.taskRecommendations.mountTaskRecommendations(closed, { projectId: 7, taskId: 14 });
  const beforeClose = closed.innerHTML; closed.isConnected = false;
  r.requests[4].resolve(response([card(14)])); await tick();
  assert.equal(closed.innerHTML, beforeClose, 'closed details must not be repainted by an outstanding request');

  const readOnly = host();
  r.EMIE.taskRecommendations.mountTaskRecommendations(readOnly, { projectId: 7, taskId: 15 });
  r.requests[5].resolve(response([card(15)], { canAssociate: false, aiConfigured: false, status: 'METADATA' })); await tick();
  assert.match(readOnly.innerHTML, /AI 识图尚未开启/);
  assert.doesNotMatch(readOnly.innerHTML, /click:task-recommendation-associate/);
  r.handlers.get('task-recommendation-associate')(null, button(readOnly, 15));
  assert.equal(r.posts.length, 1, 'read-only details must not submit an association');

  r.EMIE.state.currentRole = 'supplychain';
  assert.equal(r.EMIE.taskRecommendations.taskRecommendationsPlaceholder(), '');
  assert.equal(r.EMIE.taskRecommendations.mountTaskRecommendations(host(), { projectId: 7, taskId: 16 }), null);
  assert.equal(r.requests.length, 6, 'roles without library access must not request recommendation data');
  r.EMIE.state.currentRole = 'designer'; r.EMIE.actions.hasPermission = () => false;
  assert.equal(r.EMIE.taskRecommendations.taskRecommendationsPlaceholder(), '', 'disabled library page permission hides recommendations');
}

async function checkSettings() {
  const r = runtime('src/main/resources/static/js/visual-ai-settings.js');
  const fields = {
    '#visualAiSettingsForm': { reportValidity: () => true },
    '#visualAiBaseUrl': { value: 'https://api.moonshot.cn/v1/' },
    '#visualAiModel': { value: 'kimi-k2.6' },
    '#visualAiEnabled': { checked: true },
    '#visualAiApiKey': { value: '', disabled: false },
    '#visualAiSettingsSubmit': {}, '#visualAiSettingsMessage': {}, '#visualAiIndexButton': {}, '#visualAiIndexSummary': {},
  };
  const overlay = { isConnected: true, innerHTML: '', querySelector: selector => fields[selector] };
  r.context.testSession = { overlay, config: { keyConfigured: true, keyStorageAvailable: true }, saving: false };
  vm.runInContext('activeVisualAiSettings = testSession', r.context);
  const saving = r.handlers.get('visual-ai-settings-save')({ preventDefault() {} });
  assert.equal(r.posts[0].path, '/admin/visual-ai-config');
  assert.equal(r.posts[0].body.baseUrl, 'https://api.moonshot.cn/v1');
  assert.equal('apiKey' in r.posts[0].body, false, 'blank key must preserve the configured server key');
  assert.equal('clearKey' in r.posts[0].body, false);
  r.posts[0].resolve({ baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k2.6', enabled: true, keyConfigured: true, keyStorageAvailable: true });
  await saving;
  assert.match(fields['#visualAiSettingsMessage'].textContent, /设置已保存/);

  fields['#visualAiApiKey'].value = 'should-not-submit'; fields['#visualAiApiKey'].disabled = true;
  const noStorage = r.handlers.get('visual-ai-settings-save')({ preventDefault() {} });
  assert.equal('apiKey' in r.posts[1].body, false, 'disabled key storage must not send a key');
  r.posts[1].reject(new Error('没有权限')); await noStorage;
  assert.equal(fields['#visualAiSettingsMessage'].textContent, '没有权限');

  r.EMIE.actions.showSystemConfirm = async () => false;
  await r.handlers.get('visual-ai-settings-index')();
  assert.equal(r.posts.length, 2, 'cancelled backfill must not send any library files for analysis');
  r.EMIE.actions.showSystemConfirm = async () => true;
  const indexing = r.handlers.get('visual-ai-settings-index')(); await tick();
  assert.equal(r.posts[2].path, '/admin/visual-ai-config/index-library');
  r.handlers.get('visual-ai-settings-index')();
  assert.equal(r.posts.length, 3, 'backfill double click must not submit a second batch');
  r.posts[2].resolve({ queued: 4, indexStats: { total: 9, ready: 3, pending: 2, failed: 0, queued: 4 } }); await indexing;
  assert.match(fields['#visualAiIndexSummary'].textContent, /已分析 3/);
  assert.match(fields['#visualAiSettingsMessage'].textContent, /已安排 4/);

  r.EMIE.state.currentRole = 'designer';
  r.handlers.get('visual-ai-settings-open')();
  assert.equal(r.requests.length, 0, 'non-admin role cannot load the secret configuration UI');
  r.handlers.get('visual-ai-settings-save')({ preventDefault() {} });
  assert.equal(r.posts.length, 3, 'non-admin role cannot save configuration');
}

(async () => {
  await checkRecommendations();
  await checkSettings();
  console.log('task recommendation checks passed (safe rendering, task isolation, persistent associations, config key handling)');
})().catch(error => { console.error(error); process.exitCode = 1; });
