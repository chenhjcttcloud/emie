const EMIE = window.EMIE;
const esc = value => EMIE.actions.escHtml(String(value ?? ''));
let activeVisualAiSettings = null;

function settingsActive(session) {
  return !!session && activeVisualAiSettings === session && session.overlay.isConnected;
}

function closeVisualAiSettings() {
  const session = activeVisualAiSettings;
  activeVisualAiSettings = null;
  session?.overlay.remove();
}

function visualAiIndexSummary(stats) {
  if (!stats || typeof stats !== 'object') return '';
  const count = key => Math.max(0, Number(stats[key]) || 0);
  return `图库文件 ${count('total')} 份 · 已分析 ${count('ready')} · 待分析 ${count('pending')} · 未成功 ${count('failed')}${count('unsupported') ? ` · 暂不支持 ${count('unsupported')}` : ''}${count('queued') ? ` · 排队中 ${count('queued')}` : ''}`;
}

function renderVisualAiSettings(session, config) {
  if (!settingsActive(session)) return;
  session.config = config;
  const storageAvailable = config.keyStorageAvailable !== false;
  const keyConfigured = config.keyConfigured === true;
  session.overlay.innerHTML = `<div class="modal"><div class="modal-header"><div><div class="modal-title">图档 AI 识图设置</div><div class="form-hint">为任务推荐增加图片内容匹配能力</div></div><button type="button" class="modal-close" data-emie-action="click:visual-ai-settings-close" aria-label="关闭">✕</button></div><div class="modal-body">
    <p class="visual-ai-settings-description">开启后，系统会分析图档和任务参考图中的主题、画面元素与风格，用于推荐相关图档。已有分析结果会重复使用。</p>
    <div class="visual-ai-settings-status${storageAvailable ? '' : ' is-warning'}" role="status">${keyConfigured ? '✓ 已配置 API Key，密钥不会在页面中显示。' : '尚未配置 API Key，当前仍可使用 IP 分类与任务需求匹配。'}${!storageAvailable ? '<br>服务器尚未配置密钥加密存储，暂时不能在此保存新 Key。请联系管理员配置后再试。' : ''}${config.message ? `<br>${esc(config.message)}` : ''}</div>
    <form id="visualAiSettingsForm" data-emie-action="submit:visual-ai-settings-save">
      <div class="form-group"><label class="visual-ai-settings-toggle"><input id="visualAiEnabled" type="checkbox" ${config.enabled === true ? 'checked' : ''}>开启 AI 图片分析</label></div>
      <div class="form-group"><label class="form-label" for="visualAiBaseUrl">API 接口地址</label><input id="visualAiBaseUrl" class="form-input" type="url" required maxlength="500" spellcheck="false" value="${esc(config.baseUrl || 'https://api.moonshot.cn/v1')}"><div class="form-hint">使用支持图片理解的 Chat Completions 接口。</div></div>
      <div class="form-group"><label class="form-label" for="visualAiModel">模型名称</label><input id="visualAiModel" class="form-input" required maxlength="150" spellcheck="false" value="${esc(config.model || 'kimi-k2.6')}"></div>
      <div class="form-group"><label class="form-label" for="visualAiApiKey">API Key${keyConfigured ? '（已配置）' : ''}</label><input id="visualAiApiKey" class="form-input" type="password" autocomplete="new-password" maxlength="4000" spellcheck="false" placeholder="${keyConfigured ? '留空保留现有 Key' : '请输入 API Key'}" ${storageAvailable ? '' : 'disabled'}><div class="form-hint">${keyConfigured ? '如需更换，填写新 Key；留空不会修改原有密钥。' : '密钥仅由后端使用，不会返回到浏览器。'}</div></div>
      <div class="visual-ai-settings-index"><strong>已有图档分析</strong><p id="visualAiIndexSummary">${esc(visualAiIndexSummary(config.indexStats) || '开启 AI 后，可为图库中的已有文件补充内容分析。')}</p><button id="visualAiIndexButton" type="button" class="btn btn-outline btn-sm" data-emie-action="click:visual-ai-settings-index" ${!keyConfigured || config.enabled !== true ? 'disabled' : ''}>分析一批已有图档</button><small>每批最多 48 个文件。已完成且未变化的文件会复用分析结果。</small></div>
      <div id="visualAiSettingsMessage" class="visual-ai-settings-message" role="status" hidden></div>
    </form>
  </div><div class="modal-footer"><button type="button" class="btn btn-outline" data-emie-action="click:visual-ai-settings-close">关闭</button><button id="visualAiSettingsSubmit" type="submit" form="visualAiSettingsForm" class="btn btn-primary">保存设置</button></div></div>`;
}

async function openVisualAiSettings() {
  if (EMIE.state.currentRole !== 'admin') return;
  if (activeVisualAiSettings?.overlay.isConnected) return;
  const overlay = document.createElement('div');
  overlay.id = 'visualAiSettingsModal'; overlay.className = 'modal-overlay visual-ai-settings';
  overlay.innerHTML = '<div class="modal"><div class="modal-header"><div class="modal-title">图档 AI 识图设置</div><button class="modal-close" data-emie-action="click:visual-ai-settings-close" aria-label="关闭">✕</button></div><div class="modal-body"><div class="task-recommendations-loading" role="status"><span aria-hidden="true"></span>正在读取配置…</div></div></div>';
  overlay.addEventListener('click', event => { if (event.target === overlay) closeVisualAiSettings(); });
  const session = { overlay, saving: false, config: null };
  activeVisualAiSettings = session; document.body.appendChild(overlay);
  try {
    const config = await EMIE.actions.apiGet('/admin/visual-ai-config');
    if (settingsActive(session)) renderVisualAiSettings(session, config);
  } catch (error) {
    if (!settingsActive(session)) return;
    overlay.querySelector('.modal-body').innerHTML = `<div class="task-recommendations-feedback is-error" role="alert">${esc(error?.message || '读取 AI 配置失败，请关闭后重试')}</div>`;
  }
}

async function saveVisualAiSettings(event) {
  event?.preventDefault();
  const session = activeVisualAiSettings;
  if (!settingsActive(session) || session.saving || session.indexing || !session.config || EMIE.state.currentRole !== 'admin') return false;
  const form = session.overlay.querySelector('#visualAiSettingsForm');
  if (!form.reportValidity()) return false;
  const message = session.overlay.querySelector('#visualAiSettingsMessage');
  const submit = session.overlay.querySelector('#visualAiSettingsSubmit');
  const input = session.overlay.querySelector('#visualAiApiKey');
  const body = { baseUrl: session.overlay.querySelector('#visualAiBaseUrl').value.trim().replace(/\/+$/, ''), model: session.overlay.querySelector('#visualAiModel').value.trim(), enabled: session.overlay.querySelector('#visualAiEnabled').checked };
  if (!input.disabled && input.value.trim()) body.apiKey = input.value.trim();
  session.saving = true; submit.disabled = true; submit.textContent = '正在保存…'; message.hidden = true;
  try {
    const config = await EMIE.actions.apiPost('/admin/visual-ai-config', body);
    if (!settingsActive(session)) return false;
    input.value = '';
    renderVisualAiSettings(session, config);
    const feedback = session.overlay.querySelector('#visualAiSettingsMessage');
    feedback.hidden = false; feedback.className = 'visual-ai-settings-message is-success'; feedback.textContent = '设置已保存。重新打开任务即可使用新的识图配置。';
  } catch (error) {
    if (!settingsActive(session)) return false;
    message.hidden = false; message.className = 'visual-ai-settings-message is-error'; message.textContent = error?.message || '设置保存失败，请重试';
  } finally {
    session.saving = false;
    if (settingsActive(session)) { const button = session.overlay.querySelector('#visualAiSettingsSubmit'); button.disabled = false; button.textContent = '保存设置'; }
  }
  return false;
}

async function indexVisualAiLibrary() {
  const session = activeVisualAiSettings;
  if (!settingsActive(session) || session.saving || session.indexing || EMIE.state.currentRole !== 'admin' || !session.config?.enabled || !session.config?.keyConfigured) return;
  session.indexing = true;
  try {
    const confirmed = await EMIE.actions.showSystemConfirm('将发送最多 48 个已有图档文件给当前配置的 AI 服务进行图片分析，并按服务商规则计费。是否继续？');
    if (!confirmed || !settingsActive(session)) return;
    const button = session.overlay.querySelector('#visualAiIndexButton');
    const message = session.overlay.querySelector('#visualAiSettingsMessage');
    session.overlay.querySelector('#visualAiSettingsSubmit').disabled = true;
    button.disabled = true; button.textContent = '正在安排分析…'; message.hidden = true;
    try {
      const result = await EMIE.actions.apiPost('/admin/visual-ai-config/index-library', {});
      if (!settingsActive(session)) return;
      session.config.indexStats = result.indexStats;
      session.overlay.querySelector('#visualAiIndexSummary').textContent = visualAiIndexSummary(result.indexStats);
      message.hidden = false; message.className = 'visual-ai-settings-message is-success';
      message.textContent = result.message || `已安排 ${Math.max(0, Number(result.queued) || 0)} 个文件进行分析。重新打开设置可查看最新进度。`;
    } catch (error) {
      if (!settingsActive(session)) return;
      message.hidden = false; message.className = 'visual-ai-settings-message is-error'; message.textContent = error?.message || '安排分析失败，请稍后重试';
    } finally {
      if (settingsActive(session)) {
        const current = session.overlay.querySelector('#visualAiIndexButton'); current.disabled = false; current.textContent = '分析一批已有图档';
        session.overlay.querySelector('#visualAiSettingsSubmit').disabled = false;
      }
    }
  } finally { session.indexing = false; }
}

const register = EMIE.actions.registerEventAction;
register?.('visual-ai-settings-open', openVisualAiSettings);
register?.('visual-ai-settings-close', closeVisualAiSettings);
register?.('visual-ai-settings-save', saveVisualAiSettings);
register?.('visual-ai-settings-index', indexVisualAiLibrary);
EMIE.registerModule('visualAiSettings', { openVisualAiSettings, closeVisualAiSettings });
