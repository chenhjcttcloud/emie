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
  return `<div class="date-picker" style="position:relative;">
    <input id="${inputId}" type="date" class="form-input" name="${name}" value="${val}" ${req} min="${new Date().toISOString().split('T')[0]}" aria-label="${ph}" autocomplete="off" style="min-height:38px;" data-emie-onclick="try{if(typeof this.showPicker==='function')this.showPicker()}catch(e){}" data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">
  </div>`;
}

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
    if (EMIE.projectState.formModified) {
      showSaveConfirmModal();
      return;
    }
  }
  document.getElementById(id)?.remove();
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
  saveCreateDraft,
  discardCreateDraft,
  showLoading,
});

EMIE.registerModule('coreUi', {
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
  saveCreateDraft,
  discardCreateDraft,
  showLoading,
});
