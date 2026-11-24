const EMIE = window.EMIE;
const PICKER_ID = 'projectFilePickerModal';
const IMAGE_EXTENSION = /\.(jpe?g|png|gif|bmp|webp)$/i;
const ATTACHMENT_EXTENSION = /\.(ai|step|stp|pdf|docx?|xlsx?|pptx?|txt|csv|zip|rar|7z|jpe?g|png|gif|bmp|webp)$/i;
const EMPTY_IMAGE = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==';
let activePicker = null;

const escape = value => EMIE.actions.escHtml(String(value ?? ''));
const identity = () => JSON.stringify([
  EMIE.state.currentUserId, EMIE.state.currentRole, EMIE.state.authUser?.userId,
  localStorage.getItem('design_pm_token'),
]);

function safeProjectFile(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const name = String(raw.name || raw.originalName || raw.storedName || '').trim();
  const path = [raw.url, raw.fileUrl, raw.path].find(value => typeof value === 'string' && value.trim())?.trim();
  const storedName = typeof raw.storedName === 'string' ? raw.storedName.trim() : '';
  if (!name || /[\x00-\x1f\x7f/\\]/.test(name) || /^(?:javascript|data|blob):/i.test(path || '')) return null;
  if (storedName && /[\x00-\x1f\x7f/\\]/.test(storedName)) return null;
  if (!storedName && !path) return null;
  // 原始文件名查询会随同名上传变化，不能作为跨子任务的稳定引用。
  if (!storedName && /(?:^|\/)download-by-original(?:[?#]|$)/i.test(path)) return null;
  let url;
  if (!storedName && path?.startsWith('/api/files/download/')) {
    let decoded;
    try { decoded = decodeURIComponent(path.slice('/api/files/download/'.length).split('?')[0]); } catch (_) { return null; }
    url = EMIE.actions.normalizeFileUrl({ name, storedName: decoded });
  } else url = EMIE.actions.normalizeFileUrl({ name, url: path, storedName });
  let key;
  let stableStoredName;
  if (url.startsWith('/api/files/download/')) {
    const encoded = url.slice('/api/files/download/'.length);
    let decoded;
    try { decoded = decodeURIComponent(encoded); } catch (_) { return null; }
    if (!decoded || /[\x00-\x1f\x7f/\\?#]/.test(decoded) || decoded === '.' || decoded === '..') return null;
    key = `stored:${decoded}`;
    stableStoredName = decoded;
  } else return null;
  return { key, file: { name, url, size: Math.max(0, Number(raw.size) || 0), storedName: stableStoredName } };
}

function projectFiles(project, kind) {
  const files = new Map();
  const add = (task, prefix) => {
    for (const entry of EMIE.actions.collectTaskOverviewFiles(task)) {
      const normalized = safeProjectFile(entry.file);
      if (!normalized || !(kind === 'reference' ? IMAGE_EXTENSION : ATTACHMENT_EXTENSION).test(normalized.file.name)) continue;
      if (!files.has(normalized.key)) files.set(normalized.key, { ...normalized, sources: new Set(), submittedAt: null, uploadedAt: null });
      const item = files.get(normalized.key);
      item.submittedAt = EMIE.actions.latestProjectFileTime(item.submittedAt, entry.submittedAt);
      item.uploadedAt = EMIE.actions.latestProjectFileTime(item.uploadedAt, entry.uploadedAt);
      const sources = [...(entry.sources || [])];
      for (const source of sources.length ? sources : ['上传文件']) {
        item.sources.add(`${prefix} · ${source}`);
      }
    }
  };
  add({ uploadedFiles: project.referenceImagesJson }, '项目参考图片');
  add({ uploadedFiles: project.attachmentsJson }, '项目附件');
  for (const task of Array.isArray(project.tasks) ? project.tasks : []) {
    if (task && typeof task === 'object') add(task, `子任务：${task.name || task.id || '未命名'}`);
  }
  const entries = [...files.values()];
  entries.forEach(entry => {
    const uploadedAt = EMIE.actions.parseProjectFileTime(project.projectFileUploadTimes?.[entry.file.storedName]);
    entry.uploadedAt = EMIE.actions.latestProjectFileTime(entry.uploadedAt, uploadedAt);
    EMIE.actions.setProjectFileTime(entry);
  });
  return entries.sort((first, second) => (second.timestamp ?? -Infinity) - (first.timestamp ?? -Infinity) || 0);
}

function isCurrent(picker) {
  return activePicker === picker && !picker.finished && picker.overlay.isConnected
    && picker.parentModal.isConnected && picker.parentModal.dataset.modalClosing !== '1'
    && picker.parentModal.dataset.projectId === picker.projectId
    && picker.overlay.dataset.modalClosing !== '1' && picker.identity === identity();
}

function finishPicker(picker, value) {
  if (!picker || picker.finished) return;
  picker.finished = true;
  picker.observer?.disconnect();
  document.removeEventListener('emie:identity-changing', picker.cancel);
  if (activePicker === picker) activePicker = null;
  if (picker.overlay.dataset.modalClosing !== '1') picker.overlay.remove();
  picker.resolve(value);
}

function selectedKeys(picker) {
  return new Set(picker.selected.map(safeProjectFile).filter(Boolean).map(entry => entry.key));
}

function remainingCount(picker) {
  return Math.max(0, picker.maxCount - picker.selected.length - (Number(picker.selected._pendingUploadCount) || 0));
}

function message(picker, text, { error = false, retry = false } = {}) {
  const area = picker.overlay.querySelector('[data-project-file-message]');
  area.hidden = !text;
  area.classList.toggle('is-error', error);
  area.innerHTML = text ? `<span>${escape(text)}</span>${retry ? '<button type="button" class="btn btn-outline btn-sm" data-emie-action="click:project-file-picker-retry">重试</button>' : ''}` : '';
}

function thumbnail(file) {
  return file.url.replace('/api/files/download/', '/api/files/thumbnail/');
}

function renderFiles(picker) {
  if (!isCurrent(picker)) { finishPicker(picker, null); return; }
  const added = selectedKeys(picker);
  for (const key of added) picker.checked.delete(key);
  const query = picker.query.trim().toLocaleLowerCase();
  const visible = picker.files.map((entry, index) => ({ entry, index })).filter(({ entry }) =>
    !query || `${entry.file.name} ${[...entry.sources].join(' ')}`.toLocaleLowerCase().includes(query));
  picker.overlay.querySelector('[data-project-file-list]').innerHTML = visible.map(({ entry, index }) => {
    const { key, file, sources } = entry;
    const existing = added.has(key);
    const checked = existing || picker.checked.has(key);
    const extension = file.name.split('.').pop().toUpperCase();
    const picture = IMAGE_EXTENSION.test(file.name);
    return `<label class="project-file-picker-item${checked ? ' is-selected' : ''}${existing ? ' is-existing' : ''}" data-file-time="${escape((entry.submittedAt || entry.uploadedAt)?.value || '')}" data-file-time-kind="${entry.timeKind}" data-file-timestamp="${entry.timestamp ?? ''}">
      <input type="checkbox" data-file-index="${index}" data-emie-action="change:project-file-picker-toggle" ${checked ? 'checked' : ''} ${existing ? 'disabled' : ''}>
      <span class="project-file-picker-preview">${picture
        ? `<img src="${EMPTY_IMAGE}" data-auth-src="${escape(thumbnail(file))}" alt="" loading="lazy" decoding="async">`
        : `<span class="project-file-picker-icon" aria-hidden="true">${/^(ZIP|RAR|7Z)$/.test(extension) ? '🗜️' : '📎'}<small>${escape(extension)}</small></span>`}</span>
      <span class="project-file-picker-info"><strong>${escape(file.name)}</strong><small>${escape([...sources].join('；'))}</small>
        ${EMIE.actions.renderProjectFileTime(entry)}
        <span class="project-file-picker-meta">${file.size ? escape(EMIE.actions.fmtSize(file.size)) : escape(extension)}${existing ? '<b>已添加</b>' : ''}</span></span>
    </label>`;
  }).join('');
  picker.overlay.querySelector('[data-project-file-count]').textContent = `已添加 ${picker.selected.length} 个 · 待引用 ${picker.checked.size} 个`;
  picker.overlay.querySelector('[data-emie-action="click:project-file-picker-confirm"]').disabled = picker.loading || !picker.checked.size;
  if (!picker.loading) {
    if (picker.loadError) message(picker, picker.loadError, { error: true, retry: true });
    else if (picker.notice) message(picker, picker.notice, { error: true });
    else if (!picker.files.length) message(picker, picker.kind === 'reference' ? '当前项目暂无可引用的图片' : '当前项目暂无可引用的附件');
    else if (!visible.length) message(picker, '没有匹配的文件，请尝试其他关键词');
    else message(picker, '');
  }
}

async function loadFiles(picker) {
  if (!isCurrent(picker)) { finishPicker(picker, null); return; }
  if (picker.loading) return;
  picker.loading = true;
  picker.notice = '';
  picker.loadError = '';
  picker.files = [];
  picker.checked.clear();
  message(picker, '正在加载项目文件…');
  renderFiles(picker);
  try {
    const project = await EMIE.actions.apiGet(`/projects/${picker.projectId}`, { fresh: true });
    if (!isCurrent(picker)) { finishPicker(picker, null); return; }
    if (!project || String(project.id) !== picker.projectId) throw new Error('项目资料不匹配，请重试');
    picker.files = projectFiles(project, picker.kind);
    picker.loading = false;
    renderFiles(picker);
  } catch (error) {
    if (!isCurrent(picker)) { finishPicker(picker, null); return; }
    picker.loading = false;
    picker.loadError = `项目文件加载失败：${error.message || '请稍后重试'}`;
    renderFiles(picker);
  }
}

async function pickProjectFiles({ projectId, kind, selected, maxCount, parentModal }) {
  if (activePicker || document.getElementById(PICKER_ID)) return null;
  const projectKey = String(projectId ?? '');
  if (!/^[1-9]\d*$/.test(projectKey) || !['reference', 'attachment'].includes(kind)
      || !Array.isArray(selected) || !parentModal?.isConnected || parentModal.dataset.modalClosing === '1'
      || parentModal.dataset.projectId !== projectKey) return null;
  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.id = PICKER_ID;
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `<div class="modal project-file-picker-modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">引用项目${kind === 'reference' ? '参考图片' : '附件'}</div><div class="form-hint">从当前项目及其子任务中选择已有文件，最新提交优先；无提交记录时显示上传时间</div></div><button type="button" class="modal-close" data-emie-action="click:project-file-picker-close">✕</button></div>
      <div class="modal-body"><div class="project-file-picker-toolbar"><input type="search" class="form-input" id="projectFilePickerSearch" aria-label="搜索项目文件" placeholder="搜索文件名或来源" data-emie-action="input:project-file-picker-search"><small>本次合计最多 ${kind === 'reference' ? 6 : 5} 个${kind === 'reference' ? '图片' : '附件'}</small></div>
        <div class="project-file-picker-message" data-project-file-message role="status" aria-live="polite"></div>
        <div class="project-file-picker-list" data-project-file-list></div></div>
      <div class="modal-footer"><span class="project-file-picker-count" data-project-file-count></span><div class="project-file-picker-actions"><button type="button" class="btn btn-outline" data-emie-action="click:project-file-picker-close">取消</button><button type="button" class="btn btn-primary" data-emie-action="click:project-file-picker-confirm" disabled>确认引用</button></div></div>
    </div>`;
    const picker = { projectId: projectKey, kind, selected, maxCount: Math.min(Number(maxCount) || (kind === 'reference' ? 6 : 5), kind === 'reference' ? 6 : 5), parentModal,
      identity: identity(), overlay, resolve, files: [], checked: new Set(), query: '', notice: '', loading: false, finished: false };
    activePicker = picker;
    picker.cancel = () => finishPicker(picker, null);
    document.addEventListener('emie:identity-changing', picker.cancel);
    document.body.appendChild(overlay);
    picker.observer = new MutationObserver(() => {
      if (!isCurrent(picker)) finishPicker(picker, null);
    });
    picker.observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-modal-closing', 'data-project-id'] });
    void loadFiles(picker);
  });
}

function toggleProjectFile(input) {
  const picker = activePicker;
  if (!picker || !isCurrent(picker)) { finishPicker(picker, null); return; }
  const entry = picker.files[Number(input.dataset.fileIndex)];
  if (!entry || selectedKeys(picker).has(entry.key)) return;
  picker.notice = '';
  if (!input.checked) picker.checked.delete(entry.key);
  else if (picker.checked.size >= remainingCount(picker)) {
    picker.notice = `合计最多 ${picker.maxCount} 个文件，请减少选择或等待上传完成`;
    input.checked = false;
  } else picker.checked.add(entry.key);
  renderFiles(picker);
  picker.overlay.querySelector(`input[data-file-index="${Number(input.dataset.fileIndex)}"]`)?.focus({ preventScroll: true });
}

function confirmProjectFiles() {
  const picker = activePicker;
  if (!picker || !isCurrent(picker)) { finishPicker(picker, null); return; }
  if (picker.loading) return;
  const added = selectedKeys(picker);
  const files = picker.files.filter(entry => picker.checked.has(entry.key) && !added.has(entry.key));
  if (files.length > remainingCount(picker)) {
    picker.notice = `合计最多 ${picker.maxCount} 个文件，请减少选择或等待上传完成`;
    renderFiles(picker);
    return;
  }
  finishPicker(picker, files.map(entry => ({ ...entry.file })));
}

const registerEventAction = EMIE.actions.registerEventAction;
registerEventAction('project-file-picker-close', () => finishPicker(activePicker, null));
registerEventAction('project-file-picker-retry', () => { if (activePicker) void loadFiles(activePicker); });
registerEventAction('project-file-picker-toggle', (_event, input) => toggleProjectFile(input));
registerEventAction('project-file-picker-search', (_event, input) => {
  if (!activePicker) return;
  activePicker.query = input.value;
  activePicker.notice = '';
  renderFiles(activePicker);
});
registerEventAction('project-file-picker-confirm', confirmProjectFiles);
EMIE.registerModule('projectFilePicker', { pickProjectFiles });
