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
function response(items, overrides = {}) { return { items, linkedItems: [], associatedLibraryItemIds: [], canAssociate: true, status: 'METADATA', aiConfigured: false, analysisPending: false, ...overrides }; }
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
  assert.match(readOnly.innerHTML, /图档名称与标签/);
  assert.doesNotMatch(readOnly.innerHTML, /AI 识图|图片内容正在分析/);
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

function checkRetiredImageAnalysis() {
  const bootstrap = fs.readFileSync('src/main/resources/static/js/bootstrap.js', 'utf8');
  assert.doesNotMatch(bootstrap, /import ['"].*visual-ai-settings\.js/);
  for (const file of ['admin-shell.js', 'image-library.js']) {
    assert.doesNotMatch(fs.readFileSync(`src/main/resources/static/js/${file}`, 'utf8'), /click:visual-ai-settings-open/);
  }
  assert.equal(fs.existsSync('src/main/java/com/emie/designpm/recommendation/controller/VisualAiConfigController.java'), false);
  assert.equal(fs.existsSync('src/main/java/com/emie/designpm/imagelibrary/event/ImageLibraryChangedEvent.java'), false);
  const routes = fs.readFileSync('src/test/resources/route-inventory.txt', 'utf8');
  assert.doesNotMatch(routes, /\/api\/admin\/visual-ai-config/);
}

(async () => {
  await checkRecommendations();
  checkRetiredImageAnalysis();
  console.log('task recommendation checks passed (safe rendering, task isolation, persistent associations, retired automatic analysis)');
})().catch(error => { console.error(error); process.exitCode = 1; });
