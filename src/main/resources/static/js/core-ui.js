const EMIE = window.EMIE;
const doneOpenModal = (...args) => EMIE.actions.doneOpenModal(...args);
const isModalOpen = (...args) => EMIE.actions.isModalOpen(...args);

// ==================== 状态标签 ====================
function getProjectStatusInfo(status) {
  return {
    draft: { label: '草稿', cls: 'badge-pending' },
    pending_planner: { label: '待企划接单', cls: 'badge-pending' },
    planner_accepted: { label: '企划已接单', cls: 'badge-progress' },
    in_progress: { label: '进行中', cls: 'badge-progress' },
    paused: { label: '已暂停', cls: 'badge-pending' },
    pending_terminate: { label: '终止确认中', cls: 'badge-rejected' },
    terminated: { label: '已终止', cls: 'badge-rejected' },
    completed: { label: '已完成', cls: 'badge-completed' },
    completed_pending_score: { label: '待评分', cls: 'badge-pending' },
  }[status] || { label: status, cls: '' };
}

function getTaskStatusInfo(status) {
  return {
    pending: { label: '待接单', cls: 'badge-pending', icon: '⏳' },
    accepted: { label: '设计中', cls: 'badge-progress', icon: '🎨' },
    delivered: { label: '待送审', cls: 'badge-pending', icon: '📤' },
    submitted_for_review: { label: '送审中', cls: 'badge-progress', icon: '🔎' },
    planner_approved: { label: '待评分', cls: 'badge-pending', icon: '⏳' },
    sales_approved: { label: '待确认', cls: 'badge-pending', icon: '⏳' },
    admin_approved: { label: '待确认', cls: 'badge-pending', icon: '⏳' },
    approved: { label: '已通过', cls: 'badge-completed', icon: '✅' },
    completed: { label: '已完成', cls: 'badge-completed', icon: '✅' },
    rejected: { label: '已驳回', cls: 'badge-rejected', icon: '↩️' },
  }[status] || { label: status, cls: '', icon: '❓' };
}

// 全局任务排序：待处理优先，其次最近动态，再按截止日期和编号稳定排序。
function compareTaskPriority(a, b) {
  const rank = { pending: 0, rejected: 1, delivered: 2, submitted_for_review: 3, planner_approved: 3, sales_approved: 3, admin_approved: 3, accepted: 4, completed: 9, approved: 9 };
  const statusCompare = (rank[a?.status] ?? 8) - (rank[b?.status] ?? 8);
  if (statusCompare) return statusCompare;
  const activityCompare = String(b?.lastActivityAt || '').localeCompare(String(a?.lastActivityAt || ''));
  if (activityCompare) return activityCompare;
  const dateCompare = String(a?.plannedDate || '9999-12-31').localeCompare(String(b?.plannedDate || '9999-12-31'));
  return dateCompare || (Number(b?.id || 0) - Number(a?.id || 0));
}

// ==================== 格式化 ====================
function formatDate(d) {
  if (!d) return '-';
  const m = d.match(/^\d{4}-\d{2}-\d{2}/);
  return m ? m[0] : d;
}

/** 渲染项目评分（带颜色） */
function renderScore(score) {
  if (score === null || score === undefined) return '<span style="color:var(--gray-300);">-</span>';
  const num = parseFloat(score);
  if (isNaN(num)) return '<span style="color:var(--gray-300);">-</span>';
  let color;
  if (num >= 8) color = '#3B6D11';
  else if (num >= 6) color = '#854F0B';
  else if (num >= 4) color = '#A32D2D';
  else color = 'var(--gray-400)';
  return `<span style="font-weight:600;color:${color};">${num.toFixed(1)}</span>`;
}

function fmtDT(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function fmtSize(s) {
  if (!s) return '';
  if (s < 1024) return s + 'B';
  if (s < 1048576) return (s / 1024).toFixed(1) + 'KB';
  return (s / 1048576).toFixed(1) + 'MB';
}

// 标记表单已修改
function formModified() { EMIE.projectState.formModified = true; }
function renderDatePicker(name, opts = {}) {
  const val = opts.value || '';
  const req = opts.required ? 'required' : '';
  const ph = opts.placeholder || 'yyyy-mm-dd';
  const inputId = `date_${name}_${Math.random().toString(36).slice(2, 8)}`;
  // min 用本地日期（yyyy-MM-dd），避免 toISOString() 的 UTC 偏差导致 UTC+8 早 8 点前差一天
  const now = new Date();
  const localToday = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  return `<div class="date-picker" style="position:relative;">
    <input id="${inputId}" type="date" class="form-input" name="${name}" value="${val}" ${req} min="${localToday}" aria-label="${ph}" autocomplete="off" style="min-height:38px;cursor:pointer;" data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">
  </div>`;
}

// 动态弹窗中的日期输入需要直接绑定用户点击事件，部分内置浏览器不会
// 将文档级代理事件视为有效的日期选择器手势。
function enhanceDateInputs(root = document) {
  root.querySelectorAll?.('input[type="date"]').forEach(input => {
    if (input.dataset.datePickerReady === '1') return;
    input.dataset.datePickerReady = '1';
    input.addEventListener('click', () => {
      try {
        if (typeof input.showPicker === 'function') input.showPicker();
      } catch (_) { /* 浏览器已自动打开或不支持 showPicker */ }
    });
  });
}

// 弹窗基础设施：旧模板、新模板都在插入 DOM 时统一收敛。
// 这里只处理容器、关闭和可访问性，不改写任何业务按钮的原有函数。
const DETAIL_DRAWER_MODAL_IDS = new Set([
  'projectDetailModal',
  'projectSubTaskDetailModal',
  'publishedSubTaskDetailModal',
  'taskRejectionRecordModal',
  'userTasksPopup',
  'designRequirementDetailModal',
  'createProjectModal',
]);
const modalFocusOrigins = new WeakMap();

function getTopModalOverlay() {
  const overlays = [...document.querySelectorAll('.modal-overlay')]
    .filter(overlay => getComputedStyle(overlay).display !== 'none');
  return overlays[overlays.length - 1] || null;
}

function getModalDialog(overlay) {
  return overlay?.querySelector(':scope > .modal, :scope > .file-preview-dialog') || null;
}

function requestModalClose(overlay) {
  if (!overlay) return;
  if (overlay.id === 'filePreviewOverlay' && EMIE.actions.closeFilePreview) {
    EMIE.actions.closeFilePreview();
    return;
  }
  if (overlay.id && typeof closeM === 'function') closeM(overlay.id);
  else animateModalClose(overlay);
}

function animateModalClose(overlay) {
  if (!overlay || !overlay.isConnected || overlay.dataset.modalClosing === '1') return;
  overlay.dataset.modalClosing = '1';
  overlay.classList.add('is-closing');
  window.setTimeout(() => overlay.remove(), 180);
}

function normalizeModalStructure(root = document) {
  const overlays = [];
  if (root.matches?.('.modal-overlay')) overlays.push(root);
  root.querySelectorAll?.('.modal-overlay').forEach(overlay => overlays.push(overlay));

  overlays.forEach(overlay => {
    const modal = getModalDialog(overlay);
    if (!modal) return;
    const header = modal.querySelector(':scope > .modal-header, :scope > .file-preview-header');
    const body = modal.querySelector(':scope > .modal-body, :scope > .file-preview-body');
    const footer = modal.querySelector(':scope > .modal-footer, :scope > .file-preview-footer');

    overlay.classList.toggle('modal-detail-drawer', DETAIL_DRAWER_MODAL_IDS.has(overlay.id));
    overlay.setAttribute('role', 'presentation');
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.setAttribute('tabindex', '-1');
    if (body) body.setAttribute('tabindex', '0');
    if (footer) footer.setAttribute('role', 'group');

    const title = header?.querySelector('.modal-title, .file-preview-title');
    if (title) {
      if (!title.id) title.id = `${overlay.id || 'modal'}Title`;
      modal.setAttribute('aria-labelledby', title.id);
    }

    const floatingClose = overlay.querySelector(':scope > .modal-close-float');
    let closeButton = header?.querySelector('.modal-close, .file-preview-close');
    if (!closeButton && floatingClose && header) {
      floatingClose.classList.remove('modal-close-float');
      floatingClose.classList.add('modal-close');
      header.appendChild(floatingClose);
      closeButton = floatingClose;
    }
    if (!closeButton && header && overlay.id) {
      closeButton = document.createElement('button');
      closeButton.type = 'button';
      closeButton.className = 'modal-close';
      closeButton.textContent = '✕';
      closeButton.addEventListener('click', () => requestModalClose(overlay));
      header.appendChild(closeButton);
    }
    if (closeButton) {
      closeButton.type = 'button';
      closeButton.setAttribute('aria-label', modal.classList.contains('file-preview-dialog') ? '关闭预览' : '关闭弹窗');
      closeButton.setAttribute('title', '关闭 (Esc)');
    }

    if (overlay.dataset.modalReady !== '1') {
      overlay.dataset.modalReady = '1';
      modalFocusOrigins.set(overlay, document.activeElement);
      requestAnimationFrame(() => {
        if (!overlay.isConnected || getTopModalOverlay() !== overlay) return;
        (closeButton || modal).focus({ preventScroll: true });
      });
    }
  });
  document.body.classList.toggle('has-open-modal', Boolean(document.querySelector('.modal-overlay')));
}

function restoreModalFocus(overlay) {
  const origin = modalFocusOrigins.get(overlay);
  if (origin?.isConnected) requestAnimationFrame(() => origin.focus({ preventScroll: true }));
}

// 页面大部分表单由模块按需动态渲染，统一监听新增节点，避免遗漏任何日期控件。
if (document.body) {
  enhanceDateInputs(document);
  normalizeModalStructure(document);
  new MutationObserver(mutations => {
    mutations.forEach(mutation => {
      mutation.addedNodes.forEach(node => {
        if (node.nodeType !== 1) return;
        enhanceDateInputs(node);
        normalizeModalStructure(node);
      });
      mutation.removedNodes.forEach(node => {
        if (node.nodeType !== 1) return;
        if (node.matches?.('.modal-overlay')) restoreModalFocus(node);
        node.querySelectorAll?.('.modal-overlay').forEach(restoreModalFocus);
      });
    });
    document.body.classList.toggle('has-open-modal', Boolean(document.querySelector('.modal-overlay')));
  }).observe(document.body, { childList: true, subtree: true });
}

document.addEventListener('keydown', event => {
  const overlay = getTopModalOverlay();
  if (!overlay) return;
  const modal = getModalDialog(overlay);
  if (!modal) return;

  if (event.key === 'Escape') {
    // 登录超时等强制流程没有关闭按钮，Esc 不应绕过必选操作。
    if (!modal.querySelector('.modal-close, .file-preview-close')) return;
    event.preventDefault();
    event.stopPropagation();
    requestModalClose(overlay);
    return;
  }
  if (event.key !== 'Tab') return;

  const focusable = [...modal.querySelectorAll('button:not([disabled]), [href], input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter(element => element.offsetParent !== null);
  if (!focusable.length) {
    event.preventDefault();
    modal.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (!modal.contains(document.activeElement)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}, true);


// 普通弹窗支持点击遮罩关闭；没有关闭按钮的强制弹窗仍需完成当前操作。
document.addEventListener('click', event => {
  const overlay = event.target?.closest?.('.modal-overlay');
  if (!overlay || event.target !== overlay) return;
  // 测试/特殊弹窗可通过 data-test-backdrop-closes 明确控制；业务普通弹窗默认允许点击遮罩关闭。
  if (overlay.dataset.testBackdropCloses === 'false') return;
  const modal = getModalDialog(overlay);
  if (!modal || !modal.querySelector('.modal-close, .file-preview-close')) return;
  event.preventDefault();
  event.stopPropagation();
  requestModalClose(overlay);
}, true);

function triggerDatePicker(btn) {
  const dateInput = btn.closest('.date-picker')?.querySelector('input[type="date"]');
  if (!dateInput) return;
  dateInput.focus({ preventScroll: true });
  try {
    if (typeof dateInput.showPicker === 'function') dateInput.showPicker();
    else dateInput.click();
  } catch (e) {
    dateInput.click();
  }
}

function safeRemove(el) {
  if (el && el.parentNode) el.parentNode.removeChild(el);
}

function escHtml(s) {
  if (!s) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// 统一处理接口中的 null / "null"，避免用户看到“xxx（null）”。
function displayText(value, fallback = '未设置') {
  if (value === null || value === undefined || String(value).trim().toLowerCase() === 'null' || String(value).trim() === '') return fallback;
  return String(value);
}

// 用于单引号包裹的内联事件参数；HTML 转义不能防止 JS 字符串被单引号截断。
function escJsString(s) {
  return String(s ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')
    .replace(/</g, '\\x3c')
    .replace(/>/g, '\\x3e');
}

// ==================== Modal 工具 ====================
function closeM(id, force) {
  // 创建项目弹窗关闭前做草稿检查（提交成功后 force=true 跳过）
  if (id === 'createProjectModal' && !force) {
    // 只在实际修改了表单内容时才弹出保存提示
    if (EMIE.actions.isCreateProjectFormDirty ? EMIE.actions.isCreateProjectFormDirty() : EMIE.projectState.formModified) {
      showSaveConfirmModal();
      return;
    }
  }
  animateModalClose(document.getElementById(id));
  doneOpenModal(id);
}

// 安全创建模态框（防止重复点击出现多个）
function openModal(id) {
  if (isModalOpen()) return;
  const existing = document.getElementById(id);
  if (existing) { existing.remove(); }
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = id;
  document.body.appendChild(modal);
  return modal;
}

function showSystemConfirm(message, title = '请确认操作') {
  return new Promise(resolve => {
    const id = 'systemConfirmModal';
    document.getElementById(id)?.remove();
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.id = id;
    overlay.style.zIndex = '400';
    document.body.appendChild(overlay);
    overlay.innerHTML = `<div class="modal" style="max-width:420px;"><div class="modal-header"><div class="modal-header-left"><div class="modal-title">⚠️ ${escHtml(title)}</div></div><button class="modal-close" aria-label="关闭">✕</button></div><div class="modal-body" style="padding:28px 24px;text-align:center;color:var(--gray-700);">${escHtml(message)}</div><div class="modal-footer" style="justify-content:center;gap:10px;"><button class="btn btn-outline" data-action="cancel">取消</button><button class="btn btn-primary" data-action="confirm">确定</button></div></div>`;
    const finish = value => { overlay.remove(); doneOpenModal(id); resolve(value); };
    overlay.querySelector('[data-action="cancel"]').onclick = () => finish(false);
    overlay.querySelector('[data-action="confirm"]').onclick = () => finish(true);
    overlay.querySelector('.modal-close').onclick = () => finish(false);
  });
}

function showSystemAlert(message, title = '提示') {
  const id = 'systemAlertModal';
  document.getElementById(id)?.remove();
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = id; overlay.style.zIndex = '450';
  document.body.appendChild(overlay);
  overlay.innerHTML = `<div class="modal" style="max-width:420px;"><div class="modal-header"><div class="modal-header-left"><div class="modal-title">ℹ️ ${escHtml(title)}</div></div><button class="modal-close" aria-label="关闭">✕</button></div><div class="modal-body" style="padding:28px 24px;text-align:center;color:var(--gray-700);white-space:pre-wrap;">${escHtml(message)}</div><div class="modal-footer" style="justify-content:center;"><button class="btn btn-primary" data-action="ok">知道了</button></div></div>`;
  const finish = () => { overlay.remove(); doneOpenModal(id); };
  overlay.querySelector('[data-action="ok"]').onclick = finish; overlay.querySelector('.modal-close').onclick = finish;
  requestAnimationFrame(() => overlay.querySelector('[data-action="ok"]')?.focus());
}
function showSystemInput(message, value = '', title = '请输入') {
  return new Promise(resolve => {
    const id='systemInputModal'; document.getElementById(id)?.remove(); const overlay=document.createElement('div'); overlay.className='modal-overlay'; overlay.id=id; overlay.style.zIndex='450'; document.body.appendChild(overlay);
    overlay.innerHTML=`<div class="modal" style="max-width:460px;"><div class="modal-header"><div class="modal-header-left"><div class="modal-title">✎ ${escHtml(title)}</div></div><button class="modal-close" aria-label="关闭">✕</button></div><div class="modal-body" style="padding:22px 24px;"><label class="form-label" style="display:block;margin-bottom:8px;">${escHtml(message)}</label><input class="form-input" id="systemInputValue" value="${escHtml(value)}" autocomplete="off"></div><div class="modal-footer"><button class="btn btn-outline" data-action="cancel">取消</button><button class="btn btn-primary" data-action="ok">确定</button></div></div>`;
    const input=overlay.querySelector('#systemInputValue'); const finish=v=>{overlay.remove();doneOpenModal(id);resolve(v);}; overlay.querySelector('[data-action="cancel"]').onclick=()=>finish(null); overlay.querySelector('.modal-close').onclick=()=>finish(null); overlay.querySelector('[data-action="ok"]').onclick=()=>finish(input.value); input.addEventListener('keydown',e=>{if(e.key==='Enter')finish(input.value);}); requestAnimationFrame(()=>input.focus());
  });
}
function showSystemSelect(message, options = [], title = '请选择') {
  return new Promise(resolve => { const id='systemSelectModal'; document.getElementById(id)?.remove(); const overlay=document.createElement('div'); overlay.className='modal-overlay'; overlay.id=id; overlay.style.zIndex='450'; document.body.appendChild(overlay); overlay.innerHTML=`<div class="modal" style="max-width:460px;"><div class="modal-header"><div class="modal-header-left"><div class="modal-title">☷ ${escHtml(title)}</div></div><button class="modal-close">✕</button></div><div class="modal-body" style="padding:22px 24px;"><label class="form-label" style="display:block;margin-bottom:8px;">${escHtml(message)}</label><select class="form-select" id="systemSelectValue"><option value="">请选择成员</option>${options.map(o=>`<option value="${escHtml(o.value)}">${escHtml(o.label)}</option>`).join('')}</select></div><div class="modal-footer"><button class="btn btn-outline" data-action="cancel">取消</button><button class="btn btn-primary" data-action="ok">确定</button></div></div>`; const select=overlay.querySelector('#systemSelectValue'); const finish=v=>{overlay.remove();doneOpenModal(id);resolve(v);}; overlay.querySelector('[data-action="cancel"]').onclick=()=>finish(null); overlay.querySelector('.modal-close').onclick=()=>finish(null); overlay.querySelector('[data-action="ok"]').onclick=()=>finish(select.value||null); requestAnimationFrame(()=>select.focus()); });
}

// 弹窗关闭确认
function showSaveConfirmModal() {
  const overlay = document.getElementById('createProjectModal');
  if (!overlay) return;
  // 创建项目弹窗本身必须保持打开，才能作为保存确认弹窗的背景。
  // 这里只需要防止重复创建确认层，不能使用 isModalOpen() 判断，
  // 因为它会把当前的 createProjectModal 也算作已打开弹窗。
  if (document.getElementById('saveConfirmOverlay')) return;
  const confirm = document.createElement('div');
  confirm.className = 'modal-overlay';
  confirm.id = 'saveConfirmOverlay';
  confirm.style.zIndex = '300';
  confirm.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-onclick="document.getElementById('saveConfirmOverlay')?.remove()">✕</button>
        <div class="modal-header-left"><div class="modal-title">💾 未保存的内容</div></div>
      </div>
      <div class="modal-body" style="text-align:center;padding:32px 24px;">
        <div style="font-size:40px;margin-bottom:12px;">📝</div>
        <p style="font-size:15px;color:var(--gray-700);margin-bottom:4px;">当前表单有已填写的内容</p>
        <p style="font-size:13px;color:var(--gray-500);">是否保存为草稿以便稍后继续？</p>
      </div>
      <div class="modal-footer" style="justify-content:center;gap:12px;">
        <button class="btn btn-outline" data-emie-onclick="discardCreateDraft()" style="padding:10px 24px;">不保存</button>
        <button class="btn btn-primary" data-emie-onclick="saveCreateDraft()" style="padding:10px 24px;">保存草稿</button>
      </div>
    </div>`;
  document.body.appendChild(confirm);
}

// 保存草稿
function saveCreateDraft() {
  const form = document.getElementById('createProjectForm');
  if (!form) return;
  const fd = new FormData(form);
  const draft = Object.fromEntries(fd.entries());
  draft._refImages = EMIE.projectState.createRefImages;
  draft._attachments = EMIE.projectState.createAttachments;
  draft._type = EMIE.projectState.createProjectType || 'channel_custom';
  sessionStorage.setItem('design_pm_create_draft', JSON.stringify(draft));
  document.getElementById('saveConfirmOverlay')?.remove();
  closeM('createProjectModal', true); // force 关闭，不再弹出保存提示
}

// 丢弃草稿
function discardCreateDraft() {
  sessionStorage.removeItem('design_pm_create_draft');
  document.getElementById('saveConfirmOverlay')?.remove();
  closeM('createProjectModal', true); // force 关闭，不再弹出保存提示
}



function showLoading(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
}


EMIE.registerActions({
  compareTaskPriority,
  showSystemConfirm,
  getProjectStatusInfo,
  getTaskStatusInfo,
  formatDate,
  renderScore,
  fmtDT,
  fmtSize,
  formModified,
  renderDatePicker,
  triggerDatePicker,
  safeRemove,
  escHtml,
  displayText,
  escJsString,
  closeM,
  openModal,
  showSaveConfirmModal,
  showSystemAlert,
  showSystemInput,
  showSystemSelect,
  saveCreateDraft,
  discardCreateDraft,
  showLoading,
});

EMIE.registerModule('coreUi', {
  compareTaskPriority,
  getProjectStatusInfo,
  getTaskStatusInfo,
  formatDate,
  renderScore,
  fmtDT,
  fmtSize,
  formModified,
  renderDatePicker,
  triggerDatePicker,
  safeRemove,
  escHtml,
  displayText,
  escJsString,
  closeM,
  openModal,
  showSaveConfirmModal,
  showSystemAlert,
  showSystemInput,
  showSystemSelect,
  saveCreateDraft,
  discardCreateDraft,
  showLoading,
});
