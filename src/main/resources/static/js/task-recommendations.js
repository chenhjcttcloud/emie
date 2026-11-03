const EMIE = window.EMIE;
const EMPTY_IMAGE = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==';
const recommendationSessions = new WeakMap();
const recommendationDrawers = new WeakMap();
const esc = value => EMIE.actions.escHtml(String(value ?? ''));
const validId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;

function canViewTaskRecommendations() {
  return ['admin', 'planner', 'designer'].includes(EMIE.state.currentRole)
    && (!EMIE.actions.hasPermission || EMIE.actions.hasPermission('page.image_library.view'));
}
function recommendationIdentity() { return `${EMIE.state.currentRole}|${EMIE.state.currentUserId || ''}`; }

function taskRecommendationsPlaceholder() {
  return canViewTaskRecommendations() ? '<section class="task-recommendations" data-task-recommendations aria-label="推荐图档"></section>' : '';
}

function fileUrl(file, thumbnail = false) {
  const storedName = String(file?.storedName || '');
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$/.test(storedName) || storedName.includes('..')) return '';
  return `/api/files/${thumbnail ? 'thumbnail' : 'download'}/${encodeURIComponent(storedName)}`;
}

function authenticatedUrl(url) {
  if (!url) return '';
  if (EMIE.actions.authenticatedFileUrl) return EMIE.actions.authenticatedFileUrl(url);
  const token = localStorage.getItem('design_pm_token');
  return token ? `${url}?authToken=${encodeURIComponent(token)}` : url;
}

function itemId(item) { return Number(item?.libraryItemId || item?.id); }
function itemFiles(item) { return (Array.isArray(item?.images) ? item.images : []).filter(file => fileUrl(file)); }
function currentSession(element) {
  const drawer = element.closest('[data-recommendation-drawer]');
  if (drawer) return recommendationDrawers.get(drawer);
  return recommendationSessions.get(element.closest('[data-task-recommendations]'));
}
function alive(session) {
  return !!session && session.container.isConnected && recommendationSessions.get(session.container) === session
    && session.identity === recommendationIdentity() && canViewTaskRecommendations()
    && session.container.closest?.('.modal-overlay')?.dataset?.modalClosing !== '1';
}
function recommendationPath(session) { return `/projects/${session.projectId}/subtasks/${session.taskId}/image-recommendations`; }

function renderRecommendationCard(session, item, linked = false) {
  const id = itemId(item), files = itemFiles(item), cover = files[0];
  const associated = session.associated.has(id);
  const tags = [item.ipName, ...(Array.isArray(item.subOptions) ? item.subOptions : [])].filter(Boolean).slice(0, 3);
  const reasons = [...new Set((Array.isArray(item.reasons) ? item.reasons : []).filter(reason => typeof reason === 'string' && reason.trim()))].slice(0, 3);
  return `<article class="task-recommendation-card${associated ? ' is-associated' : ''}">
    <button type="button" class="task-recommendation-cover" data-emie-action="click:task-recommendation-preview" data-library-id="${id}" aria-label="预览${esc(item.name)}" ${cover ? '' : 'disabled'}>
      ${cover ? `<img src="${EMPTY_IMAGE}" data-auth-src="${esc(authenticatedUrl(fileUrl(cover, true)))}" alt="${esc(item.name)}" loading="lazy" decoding="async">` : '<span class="task-recommendation-no-image">暂无预览</span>'}
      ${files.length > 1 ? `<span class="task-recommendation-file-count">${files.length} 个文件</span>` : ''}
      ${associated ? '<span class="task-recommendation-associated">✓ 已关联</span>' : ''}
    </button>
    <div class="task-recommendation-card-content">
      <h4 title="${esc(item.name)}">${esc(item.name || '未命名图档')}</h4>
      <div class="task-recommendation-tags">${tags.map(tag => `<span>${esc(tag)}</span>`).join('')}</div>
      ${reasons.length && !linked ? `<ul class="task-recommendation-reasons">${reasons.map(reason => `<li>${esc(reason)}</li>`).join('')}</ul>` : `<p class="task-recommendation-linked-hint">${linked ? '已保存在此任务的关联图档中' : '可预览图档，确认是否适合本次任务'}</p>`}
      <div class="task-recommendation-file-actions"><button type="button" data-emie-action="click:task-recommendation-preview" data-library-id="${id}" ${cover ? '' : 'disabled'}>预览</button><button type="button" data-emie-action="click:task-recommendation-download" data-library-id="${id}" ${cover ? '' : 'disabled'}>下载</button></div>
      ${session.data.canAssociate || associated ? `<button type="button" class="btn btn-outline btn-sm task-recommendation-associate" data-emie-action="click:task-recommendation-associate" data-library-id="${id}" ${associated || session.associating || session.loading ? 'disabled' : ''}>${associated ? '✓ 已关联任务' : session.associating === id ? '正在关联…' : '＋ 关联任务'}</button>` : ''}
    </div>
  </article>`;
}

function recommendationExplanation() {
  return '根据任务需求、IP 分类和图档名称与标签，查找可复用的图档。';
}

function renderRecommendations(session) {
  if (!alive(session)) return;
  const data = session.data || {}, items = session.items || [];
  const recommendedIds = new Set(items.map(itemId));
  const linked = session.linkedItems.filter(item => !recommendedIds.has(itemId(item)));
  const header = `<div class="task-recommendations-heading"><div><h3><span aria-hidden="true">✦</span> 推荐图档</h3><p>${esc(recommendationExplanation(data))}</p></div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:task-recommendations-refresh" ${session.loading || session.associating ? 'disabled' : ''}>${session.loading ? '正在匹配…' : '更新推荐'}</button></div>`;
  const feedback = session.feedback ? `<div class="task-recommendations-feedback${session.error ? ' is-error' : ''}" role="${session.error ? 'alert' : 'status'}">${esc(session.feedback)}</div>` : '';
  const content = session.loading && !items.length && !linked.length
    ? '<div class="task-recommendations-loading" role="status"><span aria-hidden="true"></span>正在查找相关图档…</div>'
    : items.length
      ? `<div class="task-recommendations-grid">${items.map(item => renderRecommendationCard(session, item)).join('')}</div>`
      : `<div class="task-recommendations-empty"><span aria-hidden="true">⌕</span><strong>${session.error ? '暂时无法加载推荐' : data.analysisPending ? '正在分析相关图片' : '暂未找到相关图档'}</strong><p>${session.error ? '可以点击“更新推荐”重试。' : data.analysisPending ? '分析完成后，重新打开任务或点击“更新推荐”即可查看。' : '完善任务需求、IP 分类或参考图后，会重新匹配图库中的真实图档。'}</p></div>`;
  const linkedSection = linked.length ? `<div class="task-recommendations-linked"><h4>已关联的其他图档 <span>${linked.length}</span></h4><div class="task-recommendations-grid">${linked.map(item => renderRecommendationCard(session, item, true)).join('')}</div></div>` : '';
  session.container.setAttribute('aria-busy', session.loading || session.associating ? 'true' : 'false');
  session.container.innerHTML = header + feedback + content + linkedSection;
}

function acceptRecommendationData(session, data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.items)) throw new Error('推荐数据暂不可用，请稍后重试');
  session.data = data;
  session.items = data.items.filter(item => validId(itemId(item))).slice(0, 8);
  session.linkedItems = (Array.isArray(data.linkedItems) ? data.linkedItems : []).filter(item => validId(itemId(item)));
  session.associated = new Set((Array.isArray(data.associatedLibraryItemIds) ? data.associatedLibraryItemIds : []).filter(validId).map(Number));
}

async function loadTaskRecommendations(session, refresh = false) {
  if (!alive(session) || session.loading || session.associating) return;
  const sequence = ++session.sequence;
  session.loading = true; session.feedback = ''; session.error = false;
  renderRecommendations(session);
  try {
    const data = await EMIE.actions.apiGet(`${recommendationPath(session)}?limit=8${refresh ? '&refresh=true' : ''}`);
    if (!alive(session) || sequence !== session.sequence) return;
    acceptRecommendationData(session, data);
  } catch (error) {
    if (!alive(session) || sequence !== session.sequence) return;
    session.error = true;
    session.feedback = error?.message || '推荐加载失败，请稍后重试';
  } finally {
    if (alive(session) && sequence === session.sequence) { session.loading = false; renderRecommendations(session); }
  }
}

function mountTaskRecommendations(root, { projectId, taskId } = {}) {
  if (!root || !validId(projectId) || !validId(taskId) || !canViewTaskRecommendations()) return null;
  let container = root.matches?.('[data-task-recommendations]') ? root : root.querySelector('[data-task-recommendations]');
  if (!container) {
    const body = root.querySelector('.modal-body');
    if (!body) return null;
    container = document.createElement('section');
    container.className = 'task-recommendations'; container.dataset.taskRecommendations = ''; container.setAttribute('aria-label', '推荐图档'); body.appendChild(container);
  }
  const session = { container, projectId: Number(projectId), taskId: Number(taskId), identity: recommendationIdentity(), sequence: 0, loading: false, associating: null, data: {}, items: [], linkedItems: [], associated: new Set(), feedback: '', error: false };
  recommendationSessions.set(container, session);
  loadTaskRecommendations(session);
  return container;
}

function findRecommendationItem(session, id) { return [...session.items, ...session.linkedItems].find(item => itemId(item) === Number(id)); }

function previewRecommendationFile(session, item, index = 0) {
  if (!alive(session)) return;
  const files = itemFiles(item), file = files[index];
  if (!file) return;
  if (/\.ai$/i.test(String(file.name || file.storedName))) return EMIE.actions.openFilePreview(fileUrl(file), file.name || item.name, Number(file.size || 0));
  const gallery = files.filter(entry => !/\.ai$/i.test(String(entry.name || entry.storedName))).map(entry => ({ src: authenticatedUrl(fileUrl(entry)), name: entry.name || item.name }));
  const source = authenticatedUrl(fileUrl(file));
  EMIE.actions.previewImage(source, file.name || item.name, gallery, gallery.findIndex(entry => entry.src === source));
}

function downloadRecommendationFile(session, item, index = 0) {
  if (!alive(session)) return;
  const file = itemFiles(item)[index];
  if (file) EMIE.actions.doDirectDownload(authenticatedUrl(fileUrl(file)), file.name || item.name);
}

function closeRecommendationFiles() { document.getElementById('taskRecommendationFilesModal')?.remove(); }
function openRecommendationFiles(session, item) {
  if (!alive(session)) return;
  closeRecommendationFiles();
  const drawer = document.createElement('div');
  drawer.id = 'taskRecommendationFilesModal'; drawer.className = 'modal-overlay modal-detail-drawer'; drawer.dataset.recommendationDrawer = '';
  recommendationDrawers.set(drawer, session);
  drawer.innerHTML = `<div class="modal"><div class="modal-header"><div><div class="modal-title">${esc(item.name || '图档文件')}</div><div class="form-hint">选择文件进行预览或下载</div></div><button class="modal-close" data-emie-action="click:task-recommendation-files-close" aria-label="关闭">✕</button></div><div class="modal-body"><div class="task-recommendation-files">${itemFiles(item).map((file, index) => `<article><img src="${EMPTY_IMAGE}" data-auth-src="${esc(authenticatedUrl(fileUrl(file, true)))}" alt="${esc(file.name || item.name)}" loading="lazy"><strong>${esc(file.name || item.name)}</strong><div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:task-recommendation-file-preview" data-library-id="${itemId(item)}" data-file-index="${index}">预览</button><button type="button" class="btn btn-primary btn-sm" data-emie-action="click:task-recommendation-file-download" data-library-id="${itemId(item)}" data-file-index="${index}">下载</button></div></article>`).join('')}</div></div><div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-recommendation-files-close">关闭</button></div></div>`;
  drawer.addEventListener('click', event => { if (event.target === drawer) closeRecommendationFiles(); });
  document.body.appendChild(drawer);
}

async function associateRecommendation(session, id) {
  if (!alive(session) || !session.data.canAssociate || session.loading || session.associating || session.associated.has(Number(id))) return;
  const item = findRecommendationItem(session, id);
  if (!item) return;
  const sequence = ++session.sequence;
  session.associating = Number(id); session.feedback = ''; session.error = false; renderRecommendations(session);
  try {
    const data = await EMIE.actions.apiPost(`${recommendationPath(session)}/${itemId(item)}/associate`, {});
    if (!alive(session) || sequence !== session.sequence) return;
    acceptRecommendationData(session, data);
    session.feedback = '已关联到此任务，重新打开任务后仍可查看。';
  } catch (error) {
    if (!alive(session) || sequence !== session.sequence) return;
    session.error = true; session.feedback = error?.message || '关联失败，请稍后重试';
  } finally {
    if (alive(session) && sequence === session.sequence) { session.associating = null; renderRecommendations(session); }
  }
}

const register = EMIE.actions.registerEventAction;
register?.('task-recommendations-refresh', (_event, element) => loadTaskRecommendations(currentSession(element), true));
for (const action of ['preview', 'download']) {
  register?.(`task-recommendation-${action}`, (_event, element) => {
    const session = currentSession(element);
    if (!alive(session)) return;
    const item = findRecommendationItem(session, element.dataset.libraryId);
    if (!item) return;
    if (itemFiles(item).length > 1) openRecommendationFiles(session, item);
    else if (action === 'preview') previewRecommendationFile(session, item);
    else downloadRecommendationFile(session, item);
  });
  register?.(`task-recommendation-file-${action}`, (_event, element) => {
    const session = currentSession(element);
    if (!alive(session)) return closeRecommendationFiles();
    const item = findRecommendationItem(session, element.dataset.libraryId);
    if (!item) return;
    if (action === 'preview') previewRecommendationFile(session, item, Number(element.dataset.fileIndex));
    else downloadRecommendationFile(session, item, Number(element.dataset.fileIndex));
  });
}
register?.('task-recommendation-associate', (_event, element) => associateRecommendation(currentSession(element), Number(element.dataset.libraryId)));
register?.('task-recommendation-files-close', closeRecommendationFiles);
EMIE.registerModule('taskRecommendations', { taskRecommendationsPlaceholder, mountTaskRecommendations });
