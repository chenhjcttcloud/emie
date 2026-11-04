const EMIE = window.EMIE;
const VIEW_LABELS = Object.freeze({ dashboard: '工作台', workload: '工作量', orders: '全部项目', channel: '渠道定制单', regular: '公司常规品', 'design-needs': '设计/送审需求', tasks: '我的子任务', 'other-tasks': '其他子任务', 'task-market': '接单市场', materials: '素材广场', 'image-library': '图档库', points: '积分', 'designer-performance': '设计师绩效', admin: '系统管理', logs: '操作日志' });
const MODE_LABELS = Object.freeze({ ai: 'AI 回答', guide: '系统操作指引', search: '图档检索' });
const STATUS_LABELS = Object.freeze({ pending: '待接单', accepted: '进行中', delivered: '待确认', completed: '已完成', approved: '已完成', rejected: '已驳回', revision_requested: '待修改', planner_approved: '待后续确认' });
const SHORTCUTS = ['我是新员工，从哪里开始？', '我今天应该先做什么？', '当前任务下一步怎么处理？', '帮我找相关图档'];
const EMPTY_IMAGE = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==';
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const text = (value, limit = 240) => typeof value === 'string' ? value.slice(0, limit) : '';
const id = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : null;
const state = { identity: '', open: false, messages: [], file: null, busy: false, controller: null, configController: null, configOpen: false, configSnapshot: null, configSelectedId: 'server', configBusy: false, deleteProfileId: '', modelController: null, modelInfo: null, sequence: 0, contextKey: '' };
let widget;
let observerScheduled = false;
let discoveryController = null;
let discoveryModels = [];
let discoverySequence = 0;
let materialDragDepth = 0;
let refreshAssistantPosition = () => {};

function has(permission) { return !Array.isArray(EMIE.state.permissions) || EMIE.state.permissions.includes(permission); }

function canSearchImages() {
  return ['admin', 'planner', 'designer'].includes(EMIE.state.currentRole)
    && has('page.image_library.view');
}

function visibleSuggestion(question) {
  return typeof question === 'string' && question.trim()
    && (canSearchImages() || !/(找图|(?:查找|搜索|推荐|相关|相似).*(?:图档|图片|素材))/.test(question));
}

function canUploadMaterial() {
  return canSearchImages() && has('file.upload') && state.modelInfo?.visionSupported !== false;
}

function currentIdentity() {
  const s = EMIE.state;
  return s.authUser && s.currentUserId && localStorage.getItem('design_pm_token')
    ? `${s.currentUserId}|${s.currentRole}|${s.permissionVersion ?? ''}` : '';
}

function currentContext() {
  const view = Object.hasOwn(VIEW_LABELS, EMIE.state.currentView) ? EMIE.state.currentView : 'dashboard';
  const context = { view };
  const detail = ['projectDetailModal', 'projectSubTaskDetailModal', 'publishedSubTaskDetailModal']
    .map(name => document.getElementById(name)).filter(element => element && element.dataset.modalClosing !== '1').at(-1);
  if (detail) {
    const projectId = id(detail.dataset.assistantProjectId), taskId = id(detail.dataset.assistantTaskId);
    if (projectId) context.projectId = projectId;
    if (projectId && taskId) context.taskId = taskId;
  }
  return context;
}

function contextLabel() {
  const context = currentContext();
  return `${VIEW_LABELS[context.view]}${context.projectId ? ` · 项目 #${context.projectId}` : ''}${context.taskId ? ` · 任务 #${context.taskId}` : ''}`;
}

function resetAssistant() {
  resetModelDiscovery();
  state.sequence++;
  state.controller?.abort(); state.configController?.abort(); state.modelController?.abort();
  state.controller = null; state.configController = null; state.modelController = null; state.modelInfo = null; state.configSnapshot = null; state.configSelectedId = 'server'; state.configBusy = false; state.deleteProfileId = '';
  state.messages = []; state.file = null; state.busy = false; state.open = false; state.configOpen = false; state.contextKey = '';
  if (widget) {
    clearMaterialDrop(); materialFeedback('');
    widget.querySelector('#businessAssistantInput').value = '';
    widget.querySelector('#businessAssistantFile').value = '';
    widget.querySelector('#businessAssistantConfig').replaceChildren();
    renderMessages(); renderShell();
  }
}

function renderShell() {
  if (!widget) return;
  widget.classList.toggle('has-business-dialog', !!document.querySelector('.modal-overlay:not(.file-preview-overlay)'));
  widget.hidden = !currentIdentity() || document.getElementById('appContainer')?.style.display === 'none';
  const panel = widget.querySelector('#businessAssistantPanel');
  panel.hidden = !state.open;
  panel.classList.toggle('is-settings', state.configOpen);
  panel.setAttribute('aria-busy', String(state.busy));
  widget.querySelector('.business-assistant-upload').hidden = !canUploadMaterial();
  widget.querySelector('#businessAssistantLauncher').setAttribute('aria-expanded', String(state.open));
  widget.querySelector('#businessAssistantContext').textContent = contextLabel();
  const modelLabel = widget.querySelector('#businessAssistantModel');
  modelLabel.textContent = state.modelInfo ? `当前模型：${state.modelInfo.modelName}${state.modelInfo.enabled === false ? ' · 已停用' : ''}` : '模型由管理员统一配置';
  modelLabel.title = modelLabel.textContent;
  widget.querySelector('#businessAssistantModelNotice').hidden = state.modelInfo?.visionSupported !== false;
  widget.querySelector('#businessAssistantSettings').hidden = EMIE.state.currentRole !== 'admin' || !has('admin.config.view');
  widget.querySelector('#businessAssistantSettings').disabled = state.busy;
  widget.querySelector('#businessAssistantChat').hidden = state.configOpen;
  widget.querySelector('#businessAssistantConfig').hidden = !state.configOpen;
  widget.querySelector('#businessAssistantSend').disabled = state.busy;
  widget.querySelector('#businessAssistantFile').disabled = state.busy;
  widget.querySelector('#businessAssistantInput').disabled = state.busy;
  const attachment = widget.querySelector('#businessAssistantAttachment');
  attachment.hidden = !state.file;
  attachment.querySelector('span').textContent = state.file ? `参考素材：${state.file.name}` : '';
  widget.querySelector('#businessAssistantPending').hidden = !state.busy;
  if (state.busy || !state.open || state.configOpen || !canUploadMaterial()) clearMaterialDrop();
  refreshAssistantPosition();
}

function syncShell() {
  observerScheduled = false;
  if (!widget) return;
  const identity = currentIdentity();
  if (identity !== state.identity) {
    resetAssistant(); state.identity = identity;
  }
  const key = `${identity}|${JSON.stringify(currentContext())}|${document.getElementById('appContainer')?.style.display}|${!!document.querySelector('.modal-overlay')}`;
  if (key !== state.contextKey) { state.contextKey = key; renderShell(); }
}

function scheduleSync() {
  if (!observerScheduled) { observerScheduled = true; requestAnimationFrame(syncShell); }
}

function sourceButton(source) {
  const view = text(source?.view, 40), projectId = id(source?.projectId), taskId = id(source?.taskId);
  if (!Object.hasOwn(VIEW_LABELS, view)) return '';
  return `<button type="button" class="business-assistant-source" data-assistant-action="source" data-view="${esc(view)}"${projectId ? ` data-project-id="${projectId}"` : ''}${projectId && taskId ? ` data-task-id="${taskId}"` : ''}>↗ ${esc(text(source.title, 100) || VIEW_LABELS[view])}</button>`;
}

function imagePath(file, thumbnail) {
  const name = text(file?.storedName, 255);
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$/.test(name) || name.includes('..')) return '';
  return `/api/files/${thumbnail ? 'thumbnail' : 'download'}/${encodeURIComponent(name)}`;
}

function taskCard(task) {
  const projectId = id(task?.projectId), taskId = id(task?.id);
  if (!projectId || !taskId) return '';
  return `<button type="button" class="business-assistant-task" data-assistant-action="task" data-project-id="${projectId}" data-task-id="${taskId}"><strong>${esc(text(task.name, 160) || `任务 #${taskId}`)}</strong><span>${esc(STATUS_LABELS[task.status] || text(task.status, 30))}${task.deadline ? ` · ${esc(text(task.deadline, 30))}` : ''} ↗</span></button>`;
}

function imageCard(item, messageIndex, imageIndex) {
  const files = Array.isArray(item?.images) ? item.images : [], file = files.find(value => imagePath(value, false));
  if (!file || !id(item.id)) return '';
  const reasons = (Array.isArray(item.reasons) ? item.reasons : []).filter(value => typeof value === 'string').slice(0, 3);
  return `<article class="business-assistant-image"><img src="${EMPTY_IMAGE}" data-auth-src="${esc(imagePath(file, true))}" alt="${esc(text(item.name, 160))}" loading="lazy"><div><strong>${esc(text(item.name, 160) || '图档')}</strong>${item.ipName ? `<span>${esc(text(item.ipName, 100))}</span>` : ''}${reasons.length ? `<p>${reasons.map(reason => esc(text(reason, 160))).join(' · ')}</p>` : ''}<div class="business-assistant-image-actions"><button type="button" data-assistant-action="preview" data-message="${messageIndex}" data-image="${imageIndex}">预览</button><button type="button" data-assistant-action="download" data-message="${messageIndex}" data-image="${imageIndex}">下载</button><button type="button" data-assistant-action="source" data-view="image-library">图档库 ↗</button></div></div></article>`;
}

function renderMessages() {
  const messages = widget?.querySelector('#businessAssistantMessages');
  if (!messages) return;
  if (!state.messages.length) {
    messages.innerHTML = `<div class="business-assistant-welcome"><div class="business-assistant-welcome-icon" aria-hidden="true">✦</div><h3>你好，我是小觅</h3><p>你的工作与设计助手。可以询问操作流程、查询自己的待办，或描述需求查找图档。打开项目或任务后，可结合当前内容提问。</p><div class="business-assistant-shortcuts">${SHORTCUTS.filter(visibleSuggestion).map(question => `<button type="button" data-assistant-action="suggestion" data-question="${esc(question)}">${esc(question)}</button>`).join('')}</div><small>查询沿用当前账号权限，正式业务操作在原页面完成。</small></div>`;
    return;
  }
  messages.innerHTML = state.messages.map((message, messageIndex) => {
    if (message.role === 'user') return `<div class="business-assistant-message is-user"><div>${esc(text(message.content, 2000))}${message.attachmentName ? `<small>附件：${esc(text(message.attachmentName, 255))}</small>` : ''}</div></div>`;
    const data = message.data || {}, sources = Array.isArray(data.sources) ? data.sources.slice(0, 8) : [], tasks = Array.isArray(data.tasks) ? data.tasks.slice(0, 10) : [], images = Array.isArray(data.images) ? data.images.slice(0, 8) : [];
    return `<div class="business-assistant-message is-assistant"><div class="business-assistant-mode">${esc(MODE_LABELS[data.mode] || '系统提示')}${data.modelName ? ` · ${esc(text(data.modelName, 100))}` : ''}</div><div class="business-assistant-answer">${esc(text(message.content, 14000))}</div>${data.serviceMessage ? `<p class="business-assistant-service" role="status">${esc(text(data.serviceMessage, 800))}</p>` : ''}${tasks.length ? `<div class="business-assistant-tasks">${tasks.map(taskCard).join('')}</div>` : ''}${images.length ? `<div class="business-assistant-images">${images.map((image, imageIndex) => imageCard(image, messageIndex, imageIndex)).join('')}</div>` : ''}${sources.length ? `<div class="business-assistant-sources">${sources.map(sourceButton).join('')}</div>` : ''}${Array.isArray(data.suggestions) ? `<div class="business-assistant-shortcuts">${data.suggestions.slice(0, 4).filter(visibleSuggestion).map(question => `<button type="button" data-assistant-action="suggestion" data-question="${esc(text(question, 200))}">${esc(text(question, 200))}</button>`).join('')}</div>` : ''}</div>`;
  }).join('');
  messages.scrollTop = messages.scrollHeight;
}

function openBusinessAssistant() {
  syncShell();
  if (!currentIdentity()) return;
  state.open = true; renderShell(); refreshCurrentModel();
  widget.querySelector('#businessAssistantInput').focus();
}

function closeBusinessAssistant() {
  if (state.configOpen) closeSettings();
  else resetModelDiscovery();
  state.open = false; clearMaterialDrop(); renderShell();
  widget.querySelector('#businessAssistantLauncher').focus();
}

async function fetchAssistant(path, options, controller) {
  const token = localStorage.getItem('design_pm_token');
  const response = await fetch(`/api/assistant/${path}`, { ...options, signal: controller.signal, credentials: 'same-origin', headers: { ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...(token ? { 'X-Auth-Token': token } : {}), ...options.headers } });
  if (response.status === 401) { EMIE.actions.handleLogout?.(); throw new Error('登录已失效，请重新登录'); }
  const raw = await response.json().catch(() => ({}));
  const data = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  if (!response.ok) throw new Error(text(data.error, 500) || `助手服务暂不可用（${response.status}）`);
  return data;
}

async function uploadMaterial(file, controller) {
  const token = localStorage.getItem('design_pm_token');
  const body = new FormData(); body.append('file', file);
  const response = await fetch('/api/files/upload', { method: 'POST', body, credentials: 'same-origin', signal: controller.signal, headers: token ? { 'X-Auth-Token': token } : {} });
  if (response.status === 401) { EMIE.actions.handleLogout?.(); throw new Error('登录已失效，请重新登录'); }
  const raw = await response.json().catch(() => ({}));
  const data = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  if (!response.ok || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$/.test(data.storedName || '') || data.storedName.includes('..')) throw new Error(text(data.error, 500) || '素材上传失败，请重试');
  return data.storedName;
}

async function sendMessage(question) {
  syncShell();
  const input = widget.querySelector('#businessAssistantInput');
  const message = String(question ?? input.value).trim();
  if (!currentIdentity() || state.busy || (!message && !state.file)) return;
  if (message.length > 2000) return;
  const identity = state.identity, sequence = ++state.sequence, file = canUploadMaterial() ? state.file : null;
  const history = state.messages.filter(entry => !entry.error).slice(-8).map(entry => ({ role: entry.role, content: text(entry.content, 2000) }));
  const context = currentContext(), controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 65000);
  state.controller = controller; state.busy = true; state.file = null;
  clearMaterialDrop(); materialFeedback('');
  state.messages.push({ role: 'user', content: message || '请根据参考素材推荐相关图档。', attachmentName: file?.name });
  state.messages = state.messages.slice(-16);
  input.value = ''; widget.querySelector('#businessAssistantFile').value = '';
  renderMessages(); renderShell();
  try {
    const attachmentStoredName = file ? await uploadMaterial(file, controller) : undefined;
    if (identity !== currentIdentity() || sequence !== state.sequence) return;
    const data = await fetchAssistant('chat', { method: 'POST', body: JSON.stringify({ message: message || '请根据参考素材推荐相关图档。', history, context, ...(attachmentStoredName ? { attachmentStoredName } : {}) }) }, controller);
    if (identity !== currentIdentity() || sequence !== state.sequence) return;
    updateModelInfo(data);
    state.messages.push({ role: 'assistant', content: text(data.answer, 14000) || '暂无可显示的回答，请换个方式描述需求。', data });
  } catch (error) {
    if (identity !== currentIdentity() || sequence !== state.sequence) return;
    state.messages.push({ role: 'assistant', content: error.name === 'AbortError' ? '请求超时，请稍后重试。' : error.name === 'TypeError' ? '无法连接助手服务，请检查网络后重试。' : text(error.message, 500) || '助手暂时不可用，请稍后重试。', error: true });
  } finally {
    clearTimeout(timer);
    if (identity === currentIdentity() && sequence === state.sequence) {
      state.busy = false; state.controller = null; renderMessages(); renderShell();
      if (state.open) input.focus();
    }
  }
}

function updateModelInfo(data) {
  const modelName = text(data?.modelName || data?.displayName, 100), modelId = text(data?.modelId || data?.activeProfileId, 80);
  if (!modelName || !modelId) return;
  state.modelInfo = { modelName, modelId, provider: text(data.provider, 30), visionSupported: data.visionSupported !== false, enabled: typeof data.enabled === 'boolean' ? data.enabled : state.modelInfo?.modelId === modelId ? state.modelInfo.enabled : true };
  if (!state.modelInfo.visionSupported) {
    state.file = null;
    if (widget) widget.querySelector('#businessAssistantFile').value = '';
  }
  renderShell();
}

async function refreshCurrentModel() {
  if (!currentIdentity()) return;
  state.modelController?.abort();
  const controller = new AbortController(), identity = state.identity;
  state.modelController = controller;
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const data = await fetchAssistant('model', { method: 'GET' }, controller);
    if (identity === currentIdentity() && state.modelController === controller) updateModelInfo(data);
  } catch { /* A model status lookup must not prevent local guidance or opening a conversation. */ }
  finally { clearTimeout(timer); }
}

async function openSettings() {
  syncShell();
  resetModelDiscovery();
  if (EMIE.state.currentRole !== 'admin' || !has('admin.config.view') || state.busy) return;
  state.configOpen = true; state.configSelectedId = ''; state.deleteProfileId = ''; renderShell();
  const container = widget.querySelector('#businessAssistantConfig');
  container.scrollTop = 0;
  delete container.dataset.profileId;
  container.innerHTML = '<p class="business-assistant-config-loading" role="status">正在读取模型配置…</p>';
  state.configController?.abort();
  const controller = new AbortController(), identity = state.identity;
  state.configController = controller;
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const config = await fetchAssistant('config', { method: 'GET' }, controller);
    if (identity !== currentIdentity() || state.configController !== controller || !state.configOpen) return;
    applySettings(config, config.activeProfileId || 'server');
  } catch (error) {
    if (identity === currentIdentity() && state.configController === controller && state.configOpen) container.innerHTML = `<p class="business-assistant-config-error" role="alert">${esc(error.name === 'AbortError' ? '配置读取超时，请重试。' : text(error.message, 500))}</p><button type="button" class="btn btn-outline" data-assistant-action="config-back">返回对话</button>`;
  } finally { clearTimeout(timer); }
}

const MODEL_PRESETS = Object.freeze({
  kimi: { label: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k2.6', dialect: 'kimi', thinkingMode: 'disabled', visionSupported: true },
  deepseek: { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', dialect: 'deepseek', thinkingMode: 'auto', visionSupported: false },
  qwen: { label: '阿里百炼（Qwen）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus', dialect: 'qwen', thinkingMode: 'auto', visionSupported: false },
  openai: { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: '', dialect: 'openai', thinkingMode: 'auto', visionSupported: false },
  custom: { label: '自定义兼容服务', baseUrl: '', model: '', dialect: 'compatible', thinkingMode: 'auto', visionSupported: false }
});
const DIALECT_LABELS = Object.freeze({ compatible: '通用兼容格式', kimi: 'Kimi', deepseek: 'DeepSeek', qwen: '阿里百炼', openai: 'OpenAI' });

function settingProfiles(config = state.configSnapshot) {
  if (!Array.isArray(config?.profiles)) return [];
  return config.profiles.filter(profile => profile && typeof profile.id === 'string' && profile.id.length <= 80).slice(0, 25);
}

function applySettings(config, selectedId = state.configSelectedId) {
  resetModelDiscovery();
  state.configSnapshot = config;
  const profiles = settingProfiles(config);
  state.configSelectedId = selectedId === 'new' || profiles.some(profile => profile.id === selectedId) ? selectedId : config.activeProfileId || 'server';
  state.deleteProfileId = ''; state.configBusy = false;
  const active = profiles.find(profile => profile.id === config.activeProfileId);
  state.modelController?.abort();
  if (active) updateModelInfo({ modelName: active.name, modelId: active.id, provider: active.provider, visionSupported: active.visionSupported, enabled: config.enabled });
  renderSettings(config);
}

function renderSettings(config) {
  const container = widget.querySelector('#businessAssistantConfig');
  const scrollTop = container.scrollTop, previousProfile = container.dataset.profileId;
  const editable = has('admin.config.edit'), profiles = settingProfiles(config), active = profiles.find(profile => profile.id === config.activeProfileId);
  const selected = profiles.find(profile => profile.id === state.configSelectedId), creating = state.configSelectedId === 'new';
  const profile = creating ? { name: '', provider: 'custom', dialect: 'compatible', thinkingMode: 'auto', baseUrl: '', model: '', visionSupported: false, keyConfigured: false } : selected;
  const storage = config.keyStorageAvailable !== false && editable;
  const options = profiles.map(item => `<option value="${esc(item.id)}" ${item.id === state.configSelectedId ? 'selected' : ''}>${esc(text(item.name, 100) || item.model || '模型配置')}${item.id === config.activeProfileId ? ' · 使用中' : ''}</option>`).join('');
  const builtin = profile?.builtin === true || profile?.id === 'server';
  const fields = !profile ? '<p class="business-assistant-config-error">未找到模型配置，请重新打开设置。</p>' : builtin
    ? `<div class="business-assistant-server-profile">
        <div class="business-assistant-section-title"><span aria-hidden="true">◇</span><strong>服务器默认 Kimi</strong><span class="business-assistant-default-badge">默认配置</span></div>
        <p>由服务器提供接口和密钥，不能在此修改或删除。添加其他模型不会覆盖服务器配置。</p>
        <dl><dt>模型</dt><dd>${esc(text(profile.model, 150))}</dd><dt>密钥</dt><dd>${profile.keyConfigured ? '已配置（不回显）' : '服务器尚未配置'}</dd></dl>
        <input name="apiKey" type="password" value="" disabled aria-label="服务器密钥不回显" autocomplete="new-password">
        ${profile.id !== config.activeProfileId ? `<button type="button" class="btn btn-primary btn-sm" data-assistant-action="config-activate" data-profile-id="server" ${editable ? '' : 'disabled'}>使用服务器默认</button>` : '<span class="business-assistant-profile-active">✓ 当前使用中</span>'}
      </div>`
    : `<form id="businessAssistantConfigForm" data-creating="${creating}" data-manual-model="false">
        <div class="business-assistant-editor-heading"><div><h4>${creating ? '添加新模型' : '编辑模型配置'}</h4><p>${creating ? '填写接口地址和 Key，获取后选择想使用的模型即可。' : '可直接保存修改；重新获取模型列表时，需要填写此服务的 Key。'}</p></div>${creating ? '<button type="button" class="business-assistant-editor-back" data-assistant-action="config-list">返回模型列表</button>' : ''}</div>
        <div class="business-assistant-connect-fields">
          <label>接口地址<input name="baseUrl" type="url" required maxlength="500" value="${esc(text(profile.baseUrl, 500))}" placeholder="https://api.example.com/v1" spellcheck="false" ${editable ? '' : 'readonly'}></label>
          <label>API Key${profile.keyConfigured ? '<span class="business-assistant-key-badge">已配置</span>' : ''}<input name="apiKey" type="password" maxlength="4000" autocomplete="new-password" ${creating || !profile.keyConfigured ? 'required' : ''} placeholder="${profile.keyConfigured ? '留空保留密钥；获取列表时请重新填写' : '粘贴此服务商的 API Key'}" ${storage ? '' : 'disabled'}></label>
          <p id="businessAssistantKeyHint" class="business-assistant-config-note">${!editable ? '当前账号没有修改配置权限。' : !storage ? '服务器尚未配置密钥加密存储，暂不能保存新密钥。' : profile.keyConfigured ? '保存时留空保留此配置的密钥；更换接口地址时须填写新密钥。' : '获取列表不会保存或切换模型；密钥仅在保存配置时加密存储。'}</p>
          <button type="button" class="btn btn-outline business-assistant-discover" data-assistant-action="config-discover" ${storage ? '' : 'disabled'}><span aria-hidden="true">↻</span> 获取模型列表</button>
        </div>
        <div id="businessAssistantDiscoveryFeedback" class="business-assistant-discovery-feedback" role="status" hidden></div>
        <div class="business-assistant-model-choice">
          <label for="businessAssistantDiscoveredModel">选择模型</label>
          <div id="businessAssistantModelControl"><select id="businessAssistantDiscoveredModel" name="model" required ${!editable || !profile.model ? 'disabled' : ''}>${profile.model ? `<option value="${esc(text(profile.model, 150))}" selected>${esc(text(profile.model, 150))}</option>` : '<option value="">获取后在这里选择模型</option>'}</select></div>
          <p id="businessAssistantModelHint" class="business-assistant-config-note">${profile.model ? '已保存的模型。更换模型可重新获取列表，或手动填写。' : '名称、服务商和请求格式将自动设置，无需逐项填写。'}</p>
          <button type="button" class="business-assistant-manual-model" data-assistant-action="config-manual-model" ${editable ? '' : 'disabled'}>无法获取？手动填写模型</button>
        </div>
        <details id="businessAssistantModelAdvanced" class="business-assistant-dialect business-assistant-model-advanced"><summary><span>高级设置</span><small>自动配置，可按需调整</small></summary>
          <div class="business-assistant-form-grid">
            <label>配置名称<input name="name" maxlength="100" data-auto-name="${creating}" value="${esc(text(profile.name, 100))}" placeholder="自动使用模型名称" ${editable ? '' : 'readonly'}></label>
            <label>服务商<select name="provider" ${editable ? '' : 'disabled'}>${Object.entries(MODEL_PRESETS).map(([value, preset]) => `<option value="${value}" ${profile.provider === value ? 'selected' : ''}>${preset.label}</option>`).join('')}</select></label>
            <label>请求兼容格式<select name="dialect" ${editable ? '' : 'disabled'}>${Object.entries(DIALECT_LABELS).map(([value, label]) => `<option value="${value}" ${profile.dialect === value ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
            <label>思考模式<select name="thinkingMode" ${editable && ['kimi', 'deepseek', 'qwen'].includes(profile.dialect) ? '' : 'disabled'}>${Object.entries({ auto: '遵循模型默认', disabled: '关闭思考', enabled: '开启思考' }).map(([value, label]) => `<option value="${value}" ${(profile.thinkingMode || 'auto') === value ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
          </div>
          <div class="business-assistant-vision-option"><label class="business-assistant-config-toggle"><input name="visionSupported" type="checkbox" ${profile.visionSupported === true ? 'checked' : ''} ${editable ? '' : 'disabled'}><span>支持参考图片理解<small>优先采用接口返回的能力信息；未提供时默认关闭，可按服务商说明调整。</small></span></label></div>
        </details>
        ${!creating ? `<div class="business-assistant-profile-operations"><button type="button" class="btn btn-outline btn-sm" data-assistant-action="config-activate" data-profile-id="${esc(profile.id)}" ${!editable || profile.id === config.activeProfileId ? 'disabled' : ''}>${profile.id === config.activeProfileId ? '当前使用中' : '使用此模型'}</button><button type="button" class="business-assistant-delete-profile" data-assistant-action="config-delete" data-profile-id="${esc(profile.id)}" ${editable ? '' : 'disabled'}>删除配置</button></div>` : ''}
        <div class="business-assistant-config-actions"><button type="submit" class="btn btn-outline" name="save" ${storage && profile.model ? '' : 'disabled'}>保存配置</button><button type="submit" class="btn btn-primary" name="saveAndUse" ${storage && profile.model ? '' : 'disabled'}>保存并使用</button></div>
      </form>`;
  container.innerHTML = `
    <div class="business-assistant-settings-heading"><div><span>模型与服务</span><h3>小觅模型设置</h3></div><button type="button" class="business-assistant-back" data-assistant-action="config-back">← 返回对话</button></div>
    <p class="business-assistant-config-intro">由管理员统一配置，为全体成员选择合适的助手模型。</p>
    <div class="business-assistant-active-model">
      <div class="business-assistant-active-icon" aria-hidden="true">✦</div><div class="business-assistant-active-copy"><span>当前使用</span><strong>${esc(text(active?.name, 100) || '服务器默认 Kimi')}</strong><small>${esc(text(active?.model, 150))}</small></div><span class="business-assistant-model-state ${config.enabled === false ? 'is-disabled' : ''}"><i></i>${config.enabled === false ? '已停用' : '使用中'}</span>
    </div>
    <label class="business-assistant-config-toggle business-assistant-enabled-row"><span>启用模型对话<small>关闭后仍可使用系统指引和文字检索</small></span><input id="businessAssistantEnabled" type="checkbox" ${config.enabled !== false ? 'checked' : ''} ${editable ? '' : 'disabled'}></label>
    <section class="business-assistant-model-library"><div class="business-assistant-profile-picker"><label>模型配置<select id="businessAssistantProfile" aria-label="已保存的模型">${options}${creating ? '<option value="new" selected>＋ 添加新模型</option>' : ''}</select></label><button type="button" class="btn btn-outline btn-sm" data-assistant-action="config-new" ${editable ? '' : 'disabled'}>＋ 添加模型</button></div><p class="business-assistant-config-note">选择配置可查看详情；使用或保存并使用后，才会切换全系统模型。</p></section>
    <div class="business-assistant-profile-editor">${fields}</div>
    <div id="businessAssistantDeleteConfirm" class="business-assistant-delete-confirm" role="alert" hidden></div><p id="businessAssistantConfigFeedback" class="business-assistant-service" role="status" hidden></p>
    ${config.message ? `<p class="business-assistant-service">${esc(text(config.message, 800))}</p>` : ''}`;
  container.dataset.profileId = state.configSelectedId;
  container.scrollTop = previousProfile === state.configSelectedId ? scrollTop : 0;
  if (state.configBusy) disableSettings(true);
}

function scrollToModelEditor() {
  const container = widget.querySelector('#businessAssistantConfig');
  const editor = container.querySelector('.business-assistant-profile-editor');
  if (editor) container.scrollTop += editor.getBoundingClientRect().top - container.getBoundingClientRect().top - 14;
}

function resetModelDiscovery() {
  discoverySequence++;
  discoveryController?.abort(); discoveryController = null; discoveryModels = [];
  const form = widget?.querySelector('#businessAssistantConfigForm');
  const button = form?.querySelector('[data-assistant-action="config-discover"]');
  if (button) { button.disabled = !has('admin.config.edit') || state.configSnapshot?.keyStorageAvailable === false; button.innerHTML = '<span aria-hidden="true">↻</span> 获取模型列表'; }
  if (form) updateModelSaveButtons(form);
}

function normalizeModelEndpoint(value) {
  const clean = value.trim().replace(/\/+$/, '');
  try {
    const url = new URL(clean);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) return clean;
    let path = url.pathname.replace(/\/+$/, '').replace(/\/(?:chat\/completions|models)$/, '');
    if (['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'dashscope-us.aliyuncs.com'].includes(url.hostname) && path === '/api/v1') path = '/compatible-mode/v1';
    if (!path) {
      if (['api.moonshot.cn', 'api.moonshot.ai', 'api.openai.com'].includes(url.hostname)) path = '/v1';
      else if (['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'dashscope-us.aliyuncs.com'].includes(url.hostname)) path = '/compatible-mode/v1';
    }
    const explicitDefaultPort = /^https:\/\/[^/?#]+:443(?:\/|$)/i.test(clean);
    return url.origin + (explicitDefaultPort ? ':443' : '') + path;
  } catch { return clean; }
}

function normalizeFormEndpoint(form) {
  const current = form.elements.baseUrl.value.trim();
  const selected = settingProfiles().find(profile => profile.id === state.configSelectedId);
  // A saved profile keeps its exact endpoint so a blank key can retain that profile's credential.
  if (selected && current.replace(/\/+$/, '') === selected.baseUrl) return;
  form.elements.baseUrl.value = normalizeModelEndpoint(current);
}

function inferredModelService(baseUrl) {
  let host = '';
  try { host = new URL(baseUrl).hostname.toLowerCase(); } catch { /* Wait for a valid endpoint. */ }
  const provider = ['api.moonshot.cn', 'api.moonshot.ai'].includes(host) ? 'kimi'
    : host === 'api.deepseek.com' ? 'deepseek'
      : ['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'dashscope-us.aliyuncs.com'].includes(host) ? 'qwen'
        : host === 'api.openai.com' ? 'openai' : 'custom';
  return { provider, dialect: provider === 'custom' ? 'compatible' : provider, thinkingMode: 'auto' };
}

function applyModelService(form, service) {
  const defaults = inferredModelService(form.elements.baseUrl.value);
  form.elements.provider.value = Object.hasOwn(MODEL_PRESETS, service?.provider) ? service.provider : defaults.provider;
  form.elements.dialect.value = Object.hasOwn(DIALECT_LABELS, service?.dialect) ? service.dialect : defaults.dialect;
  form.elements.thinkingMode.value = ['auto', 'enabled', 'disabled'].includes(service?.thinkingMode) ? service.thinkingMode : 'auto';
  updateThinkingControl(form);
}

function updateAutomaticName(form, model, name = model) {
  const control = form.elements.name;
  if (control.dataset.autoName === 'true' || !control.value.trim()) {
    control.value = text(name, 100); control.dataset.autoName = 'true';
  }
}

function updateModelSaveButtons(form) {
  const allowed = EMIE.state.currentRole === 'admin' && has('admin.config.edit') && state.configSnapshot?.keyStorageAvailable !== false;
  const ready = allowed && !state.configBusy && !discoveryController && !!form.elements.model?.value.trim();
  form.querySelectorAll('button[type="submit"]').forEach(button => { button.disabled = !ready; });
}

function discoveryFeedback(message, error = false, loading = false) {
  const box = widget.querySelector('#businessAssistantDiscoveryFeedback');
  if (!box) return;
  box.hidden = !message; box.textContent = message;
  box.classList.toggle('is-error', error); box.classList.toggle('is-loading', loading);
}

function setModelControl(form, model = '', name = model) {
  const control = form.elements.model;
  if (control?.tagName === 'INPUT') control.value = text(model, 150);
  else if (control) {
    if (model && ![...control.options].some(option => option.value === model)) control.add(new Option(text(name, 150), model));
    control.value = model; control.disabled = !model || !has('admin.config.edit');
  }
  updateAutomaticName(form, model, name);
  updateModelSaveButtons(form);
}

function invalidateModelDiscovery(form, endpointChanged) {
  resetModelDiscovery();
  const button = form.querySelector('[data-assistant-action="config-discover"]');
  if (button) { button.disabled = !has('admin.config.edit') || state.configSnapshot?.keyStorageAvailable === false; button.innerHTML = '<span aria-hidden="true">↻</span> 获取模型列表'; }
  const selected = settingProfiles().find(profile => profile.id === state.configSelectedId);
  if (endpointChanged) { applyModelService(form); form.elements.visionSupported.checked = false; }
  if (form.dataset.manualModel !== 'true') {
    const unchanged = selected && form.elements.baseUrl.value.trim().replace(/\/+$/, '') === selected.baseUrl;
    const control = form.elements.model;
    if (control?.tagName === 'SELECT') {
      control.replaceChildren(new Option(unchanged ? selected.model : '获取后在这里选择模型', unchanged ? selected.model : ''));
      control.disabled = !unchanged || !has('admin.config.edit');
    }
    if (!unchanged && form.elements.name.dataset.autoName === 'true') form.elements.name.value = '';
  }
  updateRequiredProfileKey(form, selected); updateModelSaveButtons(form);
  discoveryFeedback('接口信息已更新，可重新获取模型列表。');
}

function selectDiscoveredModel(form) {
  const model = discoveryModels.find(item => item.id === form.elements.model.value);
  if (model) {
    form.elements.visionSupported.checked = model.visionSupported;
    updateAutomaticName(form, model.id, model.name || model.id);
    widget.querySelector('#businessAssistantModelHint').textContent = model.visionKnown
      ? model.visionSupported ? '该模型支持参考图片理解，其他设置已自动填写。' : '该模型使用文字对话，其他设置已自动填写。'
      : '其他设置已自动填写；接口未提供图片能力信息，可在高级设置中调整。';
  } else updateAutomaticName(form, form.elements.model.value.trim());
  updateModelSaveButtons(form);
}

function useManualModel() {
  if (EMIE.state.currentRole !== 'admin' || !has('admin.config.edit') || state.configBusy) return;
  const form = widget.querySelector('#businessAssistantConfigForm');
  if (!form) return;
  const model = form.elements.model?.value || '';
  normalizeFormEndpoint(form);
  resetModelDiscovery();
  form.dataset.manualModel = 'true';
  form.elements.visionSupported.checked = false;
  widget.querySelector('#businessAssistantModelControl').innerHTML = `<input id="businessAssistantDiscoveredModel" name="model" required maxlength="150" value="${esc(text(model, 150))}" placeholder="例如：服务商提供的模型 ID" spellcheck="false" aria-label="手动填写模型">`;
  widget.querySelector('#businessAssistantModelHint').textContent = '填写服务商提供的模型 ID，其他设置仍会自动处理。';
  const button = form.querySelector('[data-assistant-action="config-discover"]');
  button.disabled = state.configSnapshot?.keyStorageAvailable === false; button.innerHTML = '<span aria-hidden="true">↻</span> 获取模型列表';
  updateRequiredProfileKey(form, settingProfiles().find(profile => profile.id === state.configSelectedId));
  updateModelSaveButtons(form);
  discoveryFeedback('此接口也可手动添加模型。');
  form.elements.model.focus();
}

async function discoverModels(button) {
  if (EMIE.state.currentRole !== 'admin' || !has('admin.config.edit') || state.busy || state.configBusy || discoveryController) return;
  const form = button.closest('#businessAssistantConfigForm');
  if (!form || !form.elements.baseUrl.reportValidity()) return;
  const baseUrl = form.elements.baseUrl.value.trim(), apiKey = form.elements.apiKey.value.trim();
  if (!apiKey) { discoveryFeedback('请填写此接口的 API Key 后获取模型列表；已保存的密钥不会在此回显。', true); form.elements.apiKey.focus(); return; }
  if (apiKey.length > 4000 || !/^[\x21-\x7e]+$/.test(apiKey)) { discoveryFeedback('API Key 格式无效，请检查后重试。', true); return; }
  resetModelDiscovery();
  const controller = new AbortController(), sequence = discoverySequence, identity = currentIdentity();
  discoveryController = controller;
  button.disabled = true; button.textContent = '正在获取…';
  discoveryFeedback('正在连接接口并获取可用模型…', false, true); updateModelSaveButtons(form);
  const timer = setTimeout(() => controller.abort(), 25000);
  const current = () => sequence === discoverySequence && discoveryController === controller && form.isConnected && state.configOpen && identity === currentIdentity() && form.elements.baseUrl.value.trim() === baseUrl && form.elements.apiKey.value.trim() === apiKey;
  try {
    const result = await fetchAssistant('config/models', { method: 'POST', body: JSON.stringify({ baseUrl, apiKey }) }, controller);
    if (!current()) return;
    const models = Array.isArray(result.models) ? result.models.filter(item => item && typeof item.id === 'string' && item.id.trim() && item.id.length <= 150 && !/[\x00-\x1f\x7f]/.test(item.id)).slice(0, 300).map(item => ({ id: item.id, name: text(item.name, 150) || item.id, visionSupported: item.visionKnown === true && item.visionSupported === true, visionKnown: item.visionKnown === true })) : [];
    if (!models.length) throw new Error('接口没有返回可用模型，可尝试手动填写模型。');
    discoveryModels = models;
    const previous = form.elements.model?.value;
    const selected = models.some(model => model.id === previous) ? previous : models[0].id;
    form.dataset.manualModel = 'false';
    widget.querySelector('#businessAssistantModelControl').innerHTML = `<select id="businessAssistantDiscoveredModel" name="model" required>${models.map(model => `<option value="${esc(model.id)}" ${model.id === selected ? 'selected' : ''}>${esc(model.name)}${model.name !== model.id ? ` · ${esc(model.id)}` : ''}</option>`).join('')}</select>`;
    if (typeof result.baseUrl === 'string' && result.baseUrl.length <= 500) form.elements.baseUrl.value = result.baseUrl;
    applyModelService(form, result);
    selectDiscoveredModel(form);
    discoveryFeedback(`已获取 ${models.length} 个模型，选择后即可保存。`);
  } catch (error) {
    if (current()) discoveryFeedback(error.name === 'AbortError' ? '获取模型超时，请重试或手动填写模型。' : text(error.message, 500), true);
  } finally {
    clearTimeout(timer);
    if (discoveryController === controller) {
      discoveryController = null;
      if (form.isConnected) { button.disabled = !has('admin.config.edit') || state.configSnapshot?.keyStorageAvailable === false; button.innerHTML = '<span aria-hidden="true">↻</span> 获取模型列表'; updateModelSaveButtons(form); }
    }
  }
}

function disableSettings(disabled) {
  widget.querySelectorAll('#businessAssistantConfig input, #businessAssistantConfig select, #businessAssistantConfig button').forEach(control => {
    if (disabled) { control.dataset.previouslyDisabled = String(control.disabled); control.disabled = true; }
    else { control.disabled = control.dataset.previouslyDisabled === 'true'; delete control.dataset.previouslyDisabled; }
  });
}

function configFeedback(message, error = false) {
  const feedback = widget.querySelector('#businessAssistantConfigFeedback');
  if (feedback) { feedback.hidden = false; feedback.textContent = message; feedback.classList.toggle('is-error', error); }
}

async function configOperation(body, message, selectedId) {
  if (EMIE.state.currentRole !== 'admin' || !has('admin.config.edit') || state.busy || state.configBusy) return;
  resetModelDiscovery();
  const controller = new AbortController(), identity = state.identity;
  state.configController?.abort(); state.configController = controller; state.configBusy = true;
  disableSettings(true); configFeedback('正在保存…');
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const config = await fetchAssistant('config', { method: 'POST', body: JSON.stringify(body) }, controller);
    if (identity !== currentIdentity() || state.configController !== controller || !state.configOpen) return;
    applySettings(config, config.savedProfileId || selectedId || state.configSelectedId);
    configFeedback(message);
  } catch (error) {
    if (identity === currentIdentity() && state.configController === controller && state.configOpen) {
      state.configBusy = false; disableSettings(false);
      const toggle = widget.querySelector('#businessAssistantEnabled');
      if (toggle) toggle.checked = state.configSnapshot?.enabled !== false;
      configFeedback(error.name === 'AbortError' ? '保存超时，请稍后重试。' : text(error.message, 500), true);
    }
  } finally { clearTimeout(timer); }
}

async function saveSettings(event) {
  event.preventDefault();
  if (EMIE.state.currentRole !== 'admin' || !has('admin.config.edit') || state.busy || state.configBusy) return;
  const form = event.target, selected = settingProfiles().find(profile => profile.id === state.configSelectedId);
  if (discoveryController) return;
  normalizeFormEndpoint(form);
  updateRequiredProfileKey(form, selected);
  if (!form.elements.model?.value.trim()) { discoveryFeedback('请先获取并选择模型，或手动填写模型 ID。', true); return; }
  updateAutomaticName(form, form.elements.model.value.trim());
  if (!form.reportValidity()) return;
  const profile = { ...(selected ? { id: selected.id } : {}), name: form.elements.name.value.trim(), provider: form.elements.provider.value, dialect: form.elements.dialect.value, thinkingMode: form.elements.thinkingMode.value, baseUrl: form.elements.baseUrl.value.trim().replace(/\/+$/, ''), model: form.elements.model.value.trim(), visionSupported: form.elements.visionSupported.checked };
  const key = form.elements.apiKey;
  if (!key.disabled && key.value.trim()) profile.apiKey = key.value.trim();
  key.value = '';
  const activate = event.submitter?.name === 'saveAndUse';
  await configOperation({ operation: 'save', profile, activate }, activate ? '模型已保存并切换，全体成员下一次提问生效。' : '模型配置已保存，当前使用模型未改变。', selected?.id || 'new');
}

function updateRequiredProfileKey(form, selected) {
  if (!form?.elements?.apiKey) return;
  const endpointChanged = !!selected && (form.elements.provider.value !== selected.provider || form.elements.baseUrl.value.trim().replace(/\/+$/, '') !== selected.baseUrl);
  form.elements.apiKey.required = !selected || !selected.keyConfigured || endpointChanged;
  const hint = widget.querySelector('#businessAssistantKeyHint');
  if (hint && endpointChanged) hint.textContent = '服务商或接口地址已改变，请填写该服务的新密钥。';
}

function selectModelProvider(form) {
  const preset = MODEL_PRESETS[form.elements.provider.value];
  if (!preset) return;
  form.elements.baseUrl.value = preset.baseUrl;
  invalidateModelDiscovery(form, false);
  setModelControl(form, preset.model);
  form.elements.dialect.value = preset.dialect; form.elements.thinkingMode.value = preset.thinkingMode; form.elements.visionSupported.checked = preset.visionSupported;
  updateThinkingControl(form);
  updateRequiredProfileKey(form, settingProfiles().find(profile => profile.id === state.configSelectedId));
}

function updateThinkingControl(form) {
  const supported = ['kimi', 'deepseek', 'qwen'].includes(form.elements.dialect.value);
  if (!supported) form.elements.thinkingMode.value = 'auto';
  form.elements.thinkingMode.disabled = !has('admin.config.edit') || !supported;
}

function requestDeleteProfile(profileId) {
  const profile = settingProfiles().find(item => item.id === profileId);
  if (!profile || profile.builtin || profile.id === 'server' || !has('admin.config.edit')) return;
  state.deleteProfileId = profileId;
  const box = widget.querySelector('#businessAssistantDeleteConfirm');
  box.hidden = false;
  box.innerHTML = `<p>确定删除“${esc(text(profile.name, 80))}”吗？${profile.id === state.configSnapshot.activeProfileId ? '删除后全系统将切回服务器默认 Kimi。' : '当前使用的模型不会改变。'}</p><div><button type="button" class="btn btn-outline btn-sm" data-assistant-action="config-delete-cancel">取消</button><button type="button" class="btn btn-primary btn-sm" data-assistant-action="config-delete-confirm">确认删除</button></div>`;
  box.querySelector('button').focus();
  box.scrollIntoView({ block: 'nearest' });
}

function closeSettings() {
  resetModelDiscovery();
  state.configController?.abort(); state.configController = null; state.configOpen = false; state.configBusy = false; state.configSnapshot = null; state.deleteProfileId = '';
  widget.querySelector('#businessAssistantConfig').replaceChildren(); renderShell();
  widget.querySelector('#businessAssistantInput').focus();
}

function openSource(element) {
  const view = element.dataset.view, projectId = id(element.dataset.projectId), taskId = id(element.dataset.taskId);
  if (!Object.hasOwn(VIEW_LABELS, view)) return;
  if (projectId) EMIE.actions.openProjectDetail?.(projectId, taskId || undefined);
  else EMIE.actions.navigate?.(view);
  if (matchMedia('(max-width: 640px)').matches) closeBusinessAssistant();
}

function handleClick(event) {
  const button = event.target.closest('[data-assistant-action]');
  if (!button || !widget.contains(button)) return;
  const action = button.dataset.assistantAction;
  if (action === 'open') state.open ? closeBusinessAssistant() : openBusinessAssistant();
  else if (action === 'close') closeBusinessAssistant();
  else if (action === 'new') { state.controller?.abort(); state.sequence++; state.messages = []; state.file = null; state.busy = false; clearMaterialDrop(); materialFeedback(''); widget.querySelector('#businessAssistantFile').value = ''; if (state.configOpen) closeSettings(); renderMessages(); renderShell(); }
  else if (action === 'suggestion') sendMessage(button.dataset.question);
  else if (action === 'remove-file') { state.file = null; materialFeedback(''); widget.querySelector('#businessAssistantFile').value = ''; renderShell(); }
  else if (action === 'settings') openSettings();
  else if (action === 'config-back') closeSettings();
  else if (action === 'config-new' && has('admin.config.edit') && !state.configBusy) { resetModelDiscovery(); state.configSelectedId = 'new'; state.deleteProfileId = ''; renderSettings(state.configSnapshot); scrollToModelEditor(); }
  else if (action === 'config-list' && !state.configBusy) { resetModelDiscovery(); state.configSelectedId = state.configSnapshot?.activeProfileId || 'server'; state.deleteProfileId = ''; renderSettings(state.configSnapshot); }
  else if (action === 'config-discover') discoverModels(button);
  else if (action === 'config-manual-model') useManualModel();
  else if (action === 'config-activate') configOperation({ operation: 'activate', profileId: button.dataset.profileId }, '已切换当前模型，全体成员下一次提问生效。', button.dataset.profileId);
  else if (action === 'config-delete') requestDeleteProfile(button.dataset.profileId);
  else if (action === 'config-delete-cancel') { state.deleteProfileId = ''; widget.querySelector('#businessAssistantDeleteConfirm').hidden = true; }
  else if (action === 'config-delete-confirm' && state.deleteProfileId) configOperation({ operation: 'delete', profileId: state.deleteProfileId }, '模型配置已删除。', state.configSnapshot.activeProfileId === state.deleteProfileId ? 'server' : state.configSnapshot.activeProfileId);
  else if (action === 'source') openSource(button);
  else if (action === 'task') openSource({ dataset: { ...button.dataset, view: 'tasks' } });
  else if (action === 'preview' || action === 'download') {
    const item = state.messages[Number(button.dataset.message)]?.data?.images?.[Number(button.dataset.image)];
    const file = item?.images?.find(value => imagePath(value, false));
    const url = imagePath(file, false);
    if (!url) return;
    if (action === 'preview') {
      const name = text(file.name, 255) || text(file.storedName, 255);
      if (/\.(png|jpe?g|gif|webp|bmp)$/i.test(file.storedName)) {
        const gallery = item.images.filter(value => /\.(png|jpe?g|gif|webp|bmp)$/i.test(value.storedName) && imagePath(value, false)).map(value => ({ src: EMIE.actions.authenticatedFileUrl(imagePath(value, false)), name: text(value.name, 255) || text(value.storedName, 255) }));
        const source = EMIE.actions.authenticatedFileUrl(url);
        EMIE.actions.previewImage?.(source, name, gallery, gallery.findIndex(value => value.src === source));
      } else {
        EMIE.actions.openFilePreview?.(url, /\.(ai|pdf|pptx?)$/i.test(name) ? name : text(file.storedName, 255), 0);
      }
    } else EMIE.actions.doDirectDownload?.(url);
  }
}

function materialFeedback(message) {
  const status = widget?.querySelector('#businessAssistantFileStatus');
  if (status) { status.textContent = message; status.hidden = !message; }
}

function clearMaterialDrop() {
  materialDragDepth = 0;
  widget?.querySelector('#businessAssistantPanel').classList.remove('is-file-dragover');
  widget?.querySelector('#businessAssistantLauncher').classList.remove('is-file-dragover');
}

function canAttachMaterial() {
  return !!currentIdentity() && canUploadMaterial() && !state.busy && !state.configOpen;
}

function setMaterialFile(file) {
  if (!file || !canAttachMaterial()) return false;
  if (file.size > 15 * 1024 * 1024 || !/\.(jpg|jpeg|png|gif|bmp|webp|ai|pdf)$/i.test(file.name)) {
    widget.querySelector('#businessAssistantFile').value = '';
    materialFeedback('参考素材支持图片、AI、PDF，单个文件不超过 15MB。');
    return false;
  }
  state.file = file; materialFeedback('');
  renderShell();
  return true;
}

function isMaterialDrag(event) {
  return [...(event.dataTransfer?.types || [])].includes('Files') || !!event.dataTransfer?.files?.length;
}

function handleMaterialDrag(event) {
  if (!isMaterialDrag(event)) return;
  event.preventDefault(); event.stopPropagation();
  if (event.type === 'drop') {
    clearMaterialDrop();
    if (!canAttachMaterial()) {
      materialFeedback(state.busy ? '正在处理，请稍候再添加参考素材。' : state.configOpen ? '请返回对话后添加参考素材。' : state.modelInfo?.visionSupported === false ? '当前模型仅支持文字，请切换支持图片的模型后添加素材。' : '当前账号没有添加参考素材的权限。');
      return;
    }
    const files = [...(event.dataTransfer?.files || [])];
    if (files.length !== 1) { materialFeedback('每次只能添加一个参考素材，请选择一个文件。'); return; }
    if (setMaterialFile(files[0])) {
      if (!state.open) openBusinessAssistant();
      widget.querySelector('#businessAssistantInput').focus();
    }
    return;
  }
  if (!canAttachMaterial()) { event.dataTransfer.dropEffect = 'none'; clearMaterialDrop(); return; }
  event.dataTransfer.dropEffect = 'copy';
  if (event.type === 'dragenter') materialDragDepth++;
  widget.querySelector('#businessAssistantPanel').classList.add('is-file-dragover');
  widget.querySelector('#businessAssistantLauncher').classList.add('is-file-dragover');
}

function mountBusinessAssistant() {
  const app = document.getElementById('appContainer');
  if (!app || widget) return;
  widget = document.createElement('div'); widget.id = 'businessAssistantRoot'; widget.hidden = true;
  widget.innerHTML = `<button type="button" id="businessAssistantLauncher" class="business-assistant-launcher" title="拖动调整位置，点击打开小觅" aria-controls="businessAssistantPanel" aria-expanded="false" data-assistant-action="open"><span aria-hidden="true">✦</span><span>问问小觅</span></button><section id="businessAssistantPanel" class="business-assistant-panel" role="dialog" aria-modal="false" aria-labelledby="businessAssistantTitle" hidden><header class="business-assistant-header"><div><h2 id="businessAssistantTitle">小觅</h2><p id="businessAssistantContext"></p><p id="businessAssistantModel" class="business-assistant-model"></p></div><div><button id="businessAssistantSettings" type="button" class="business-assistant-icon" title="小觅设置" aria-label="小觅设置" data-assistant-action="settings" hidden>⚙</button><button type="button" class="business-assistant-icon" title="新对话" aria-label="新对话" data-assistant-action="new">＋</button><button type="button" class="business-assistant-icon" title="收起小觅" aria-label="收起小觅" data-assistant-action="close">✕</button></div></header><div id="businessAssistantChat" class="business-assistant-chat"><div id="businessAssistantMessages" class="business-assistant-messages" aria-live="polite" aria-relevant="additions text"></div><div id="businessAssistantPending" class="business-assistant-pending" role="status" hidden><span aria-hidden="true"></span>正在处理，请稍候…</div><form id="businessAssistantForm" class="business-assistant-composer"><p id="businessAssistantModelNotice" class="business-assistant-model-notice" hidden>当前模型仅支持文字。理解参考图需由管理员切换到支持图片的模型。</p><div id="businessAssistantAttachment" class="business-assistant-attachment" hidden><span></span><button type="button" aria-label="移除参考素材" data-assistant-action="remove-file">✕</button></div><p id="businessAssistantFileStatus" class="business-assistant-file-status is-error" role="status" hidden></p><label class="business-assistant-input-label" for="businessAssistantInput">你的问题</label><textarea id="businessAssistantInput" maxlength="2000" rows="2" placeholder="询问流程、待办，或描述想找的图档…"></textarea><div class="business-assistant-composer-actions"><label class="business-assistant-upload">＋ 参考素材<input id="businessAssistantFile" type="file" accept="image/jpeg,image/png,image/gif,image/bmp,image/webp,.ai,.pdf" aria-label="添加参考素材"></label><button id="businessAssistantSend" class="btn btn-primary btn-sm" type="submit">发送 ↗</button></div><p>按 Enter 发送，Shift + Enter 换行。可拖入图片、AI、PDF 或粘贴截图，每次一个，最大 15MB；素材仅在发送时上传。</p></form></div><div id="businessAssistantConfig" class="business-assistant-config" hidden></div><div class="business-assistant-drop-hint" aria-hidden="true"><span>＋</span><strong>松开添加参考素材</strong><small>图片、AI、PDF · 单个不超过 15MB<br>素材将在点击发送后上传</small></div></section>`;
  app.appendChild(widget);
  refreshAssistantPosition = EMIE.modules.businessAssistantPosition.attachAssistantPosition(widget);
  widget.addEventListener('click', handleClick);
  ['dragenter', 'dragover', 'drop'].forEach(type => widget.addEventListener(type, handleMaterialDrag));
  widget.addEventListener('dragleave', event => {
    if (!isMaterialDrag(event)) return;
    event.preventDefault(); event.stopPropagation();
    materialDragDepth = Math.max(0, materialDragDepth - 1);
    if (!materialDragDepth) clearMaterialDrop();
  });
  document.addEventListener('dragend', clearMaterialDrop);
  document.addEventListener('drop', clearMaterialDrop);
  document.addEventListener('dragleave', event => {
    if (event.target === document.documentElement && !event.relatedTarget) clearMaterialDrop();
  });
  window.addEventListener('blur', clearMaterialDrop);
  widget.addEventListener('submit', event => {
    if (event.target.id === 'businessAssistantForm') { event.preventDefault(); sendMessage(); }
    else if (event.target.id === 'businessAssistantConfigForm') saveSettings(event);
  });
  widget.querySelector('#businessAssistantInput').addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); sendMessage(); }
  });
  widget.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); state.configOpen ? closeSettings() : closeBusinessAssistant(); } });
  widget.addEventListener('change', event => {
    if (event.target.id === 'businessAssistantProfile' && !state.configBusy) { resetModelDiscovery(); state.configSelectedId = event.target.value; state.deleteProfileId = ''; renderSettings(state.configSnapshot); }
    else if (event.target.id === 'businessAssistantEnabled') configOperation({ operation: 'setEnabled', enabled: event.target.checked }, event.target.checked ? '模型对话已启用。' : '模型对话已停用，系统指引与文字检索仍可使用。');
    else if (event.target.name === 'provider' && event.target.closest('#businessAssistantConfigForm')) selectModelProvider(event.target.form);
    else if (event.target.name === 'model' && event.target.closest('#businessAssistantConfigForm')) selectDiscoveredModel(event.target.form);
    else if (event.target.name === 'dialect' && event.target.closest('#businessAssistantConfigForm')) updateThinkingControl(event.target.form);
  });
  widget.addEventListener('input', event => {
    const form = event.target.closest('#businessAssistantConfigForm');
    if (!form) return;
    if (['baseUrl', 'apiKey'].includes(event.target.name)) invalidateModelDiscovery(form, event.target.name === 'baseUrl');
    else if (event.target.name === 'model' && form.dataset.manualModel === 'true') { updateAutomaticName(form, form.elements.model.value.trim()); updateModelSaveButtons(form); }
    else if (event.target.name === 'name') event.target.dataset.autoName = 'false';
  });
  widget.querySelector('#businessAssistantFile').addEventListener('change', event => setMaterialFile(event.target.files?.[0]));
  widget.querySelector('#businessAssistantInput').addEventListener('paste', event => {
    const item = [...(event.clipboardData?.items || [])].find(value => value.kind === 'file' && value.type.startsWith('image/'));
    if (item) { event.preventDefault(); event.stopPropagation(); setMaterialFile(item.getAsFile()); }
  });
  renderMessages(); syncShell();
  new MutationObserver(scheduleSync).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'data-app-ready', 'data-assistant-project-id', 'data-assistant-task-id'] });
}

document.addEventListener('emie:identity-changing', () => { resetAssistant(); state.identity = ''; if (widget) widget.hidden = true; });
window.addEventListener('storage', event => { if (event.key === 'design_pm_token') { resetAssistant(); state.identity = ''; scheduleSync(); } });
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountBusinessAssistant, { once: true });
else mountBusinessAssistant();
EMIE.registerModule('businessAssistant', { openBusinessAssistant, closeBusinessAssistant });
