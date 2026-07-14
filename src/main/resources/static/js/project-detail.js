const EMIE = window.EMIE;
const getCurrentUserName = (...args) => EMIE.actions.getCurrentUserName(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const tryOpenModal = (...args) => EMIE.actions.tryOpenModal(...args);
const doneOpenModal = (...args) => EMIE.actions.doneOpenModal(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const fmtDT = (...args) => EMIE.actions.fmtDT(...args);
const fmtSize = (...args) => EMIE.actions.fmtSize(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const renderScoringMini = (...args) => EMIE.actions.renderScoringMini(...args);
const refreshAfterMutation = (...args) => EMIE.actions.refreshAfterMutation(...args);
const render = (...args) => EMIE.actions.render(...args);
const openFilePreview = (...args) => EMIE.actions.openFilePreview(...args);
const showDownloadOptions = (...args) => EMIE.actions.showDownloadOptions(...args);
const shareProject = (...args) => EMIE.actions.shareProject(...args);
const addSubTask = (...args) => EMIE.actions.addSubTask(...args);
const editTask = (...args) => EMIE.actions.editTask(...args);
const deleteTask = (...args) => EMIE.actions.deleteTask(...args);
const taskAccept = (...args) => EMIE.actions.taskAccept(...args);
const taskDeliver = (...args) => EMIE.actions.taskDeliver(...args);
const taskRedeliver = (...args) => EMIE.actions.taskRedeliver(...args);
const taskApprove = (...args) => EMIE.actions.taskApprove(...args);
const taskReject = (...args) => EMIE.actions.taskReject(...args);
const openScoring = (...args) => EMIE.actions.openScoring(...args);

// ==================== 项目详情 ====================
async function openProjectDetail(pid) {
  if (!tryOpenModal('projectDetailModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'projectDetailModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('projectDetailModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-header-left"><div class="modal-title">${detail.type === 'channel_custom' ? '📦 渠道定制项目' : '🏭 常规品设计项目'} #${detail.id}</div></div>
        </div>
        <div class="modal-body">${renderProjectDetailContent(detail)}</div>
        <div class="modal-footer" id="detailActions">${renderProjectActions(detail)}</div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('projectDetailModal');
  } catch (e) {
    doneOpenModal('projectDetailModal');
    alert('加载失败: ' + e.message);
  }
}

function renderProjectDetailContent(detail) {
  const isChannel = detail.type === 'channel_custom';
  // 进度：approved/completed/sales_approved/admin_approved 算完成
  const totalTasks = detail.tasks.length;
  const doneStatuses = ['delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const doneTasks = detail.tasks.filter(t => {
    return doneStatuses.includes(t.status);
  }).length;
  const pct = totalTasks ? Math.round(doneTasks / totalTasks * 100) : 0;

  return `
    ${detail.complianceItems ? (() => { try {
      const items = JSON.parse(detail.complianceItems);
      return `<div style="background:#FEF3C7;border:1px solid #FDE68A;border-radius:8px;padding:12px 16px;margin-bottom:16px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:600;color:#92400E;white-space:nowrap;">⚠️ 合规处罚提醒</span>
        <span style="font-size:12px;color:#92400E;white-space:nowrap;">该产品涉及以下合规事项，请关注供应商资质：</span>
        ${items.map(i => `<span style="display:inline-block;padding:3px 10px;border-radius:10px;font-size:12px;font-weight:500;background:#FDE68A;color:#92400E;">${escHtml(i)}</span>`).join('')}
      </div>`;
    } catch(e) { return ''; } })() : ''}
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:20px;">
      <span class="badge ${escHtml(detail.statusCls)}" style="font-size:13px;padding:5px 14px;">${escHtml(detail.statusLabel)}</span>
      <span style="font-size:12px;color:var(--gray-400);">创建：${fmtDT(detail.createdAt)}</span>
      <span style="font-size:12px;color:var(--gray-400);">更新：${fmtDT(detail.updatedAt)}</span>
      ${detail.tasks.length > 0 ? `<div class="progress-bar" style="flex:1;max-width:200px;"><div class="progress-fill" style="width:${pct}%;"></div></div><span style="font-size:12px;color:var(--gray-500);">${pct}%</span>` : ''}
    </div>

    <div class="detail-section">
      <div class="detail-section-title">📋 项目信息</div>
      <div class="detail-grid">
        <div class="detail-item"><div class="detail-label">项目类型</div><div class="detail-value">${isChannel ? '渠道定制单' : '公司常规品'}</div></div>
        <div class="detail-item"><div class="detail-label">产品名称</div><div class="detail-value">${escHtml(displayText(detail.productName, '未设置'))}</div></div>
        <div class="detail-item"><div class="detail-label">IP</div><div class="detail-value">${escHtml(displayText(detail.ipName, '无IP'))}</div></div>
        ${isChannel ? `<div class="detail-item"><div class="detail-label">需求方（销售）</div><div class="detail-value">${escHtml(detail.salesName || '-')}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">产品企划</div><div class="detail-value">${escHtml(detail.plannerName || '-')}</div></div>
        ${detail.productCategory ? `<div class="detail-item"><div class="detail-label">产品类目</div><div class="detail-value">${escHtml(detail.productCategory)}${detail.productCategory === '其他' && detail.productCategoryNote ? `（${escHtml(detail.productCategoryNote)}）` : ''}</div></div>` : ''}
        ${detail.priceRange ? `<div class="detail-item"><div class="detail-label">参考零售价</div><div class="detail-value">${escHtml(detail.priceRange)}</div></div>` : ''}
        ${detail.targetMarket ? `<div class="detail-item"><div class="detail-label">目标市场</div><div class="detail-value">${(() => { try { return JSON.parse(detail.targetMarket).map(escHtml).join('、'); } catch(e) { return escHtml(detail.targetMarket); } })()}</div></div>` : ''}
        ${detail.complianceItems ? `<div class="detail-item" style="grid-column:1/-1;"><div class="detail-label" style="color:var(--warning);font-weight:600;">⚠️ 合规处罚<span style="color:var(--gray-400);font-weight:400;font-size:12px;margin-left:6px;">提醒产品企划关注相关供应商是否有相关资质</span></div><div class="detail-value" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:4px;">${(() => { try { return JSON.parse(detail.complianceItems).map(i => `<span style="display:inline-block;padding:4px 12px;border-radius:12px;font-size:12px;font-weight:500;background:#FEF3C7;color:#92400E;border:1px solid #FDE68A;">⚠ ${escHtml(i)}</span>`).join(''); } catch(e) { return escHtml(detail.complianceItems); } })()}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">要求完成时间</div><div class="detail-value">${formatDate(detail.deadline)}</div></div>
      </div>
      <div style="margin-top:8px;"><div class="detail-label">产品要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.productRequirements || '-')}</div></div>
      ${detail.description ? `<div style="margin-top:4px;"><div class="detail-label">细节描述</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.description)}</div></div>` : ''}
      ${renderProjectReferenceImages(detail)}
      ${renderProjectAttachments(detail)}
    </div>

    <div class="detail-section">
      <div class="detail-section-title">
        📌 子任务列表
        <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${doneTasks}/${detail.tasks.length} 完成</span>
        ${(EMIE.state.currentRole === 'planner') && (detail.status === 'planner_accepted' || detail.status === 'in_progress' || detail.status === 'completed' || detail.status === 'completed_pending_score') ? `<button class="btn btn-primary btn-sm" style="margin-left:auto;" data-emie-onclick="addSubTask(${detail.id})">➕ 添加子任务</button>` : ''}
      </div>
      ${detail.tasks.length === 0 ? `<div class="empty" style="padding:30px;"><div class="empty-icon">📭</div><p>暂无子任务，等待产品企划添加</p></div>` : ''}
      ${detail.tasks.filter(t => {
        // 设计师只看设计师的任务，供应链只看供应链的任务
        if (EMIE.state.currentRole === 'designer') return !t.assigneeRole || t.assigneeRole === 'designer';
        if (EMIE.state.currentRole === 'supplychain') return t.assigneeRole === 'supplychain';
        return true;
      }).map((t, i) => renderSubTaskCard(detail, t, i)).join('')}
    </div>

    ${renderProjectScoringSummary(detail)}

    ${renderProjectPipeline(detail)}

    <div class="detail-section">
      <div class="detail-section-title">📜 操作日志</div>
      <div class="timeline">${detail.logs.map(l => `
        <div class="timeline-item"><div class="timeline-dot done"></div><div class="timeline-content"><div class="timeline-title">${cleanLogAction(l.action)}</div><div class="timeline-time">${renderLogLabel(l)} · ${fmtDT(l.time)}</div></div></div>
      `).join('')}</div>
    </div>`;
}

// 清理操作日志文本：删除末尾的（xxx）、（planner已评）等
function cleanLogAction(action) {
  if (!action) return '';
  return action.replace(/\s*（[^）]*）\s*$/, '').trim();
}

function renderLogLabel(l) {
  const roleName = l.role === 'sales' ? '销售' : l.role === 'planner' ? '产品企划' : l.role === 'designer' ? '设计师' : l.role === 'supplychain' ? '供应链' : l.role === 'admin' ? '管理员' : l.role;
  const isScore = l.action && l.action.includes('评分');
  // 评分日志特殊格式
  if (isScore) {
    // 提取角色名: "子任务评分：xxx（planner已评）"
    const match = l.action.match(/（(.+?)已评/);
    const scoreRole = match ? match[1] : l.role;
    const scoreRoleName = scoreRole === 'sales' ? '销售' : scoreRole === 'planner' ? '产品企划' : scoreRole === 'designer' ? '设计师' : scoreRole === 'supplychain' ? '供应链' : scoreRole;
    return `（${scoreRoleName}：${l.user} 已评）`;
  }
  return `（${roleName}：${l.user}）`;
}

function renderSubTaskCard(detail, task, idx) {
  const tsi = getTaskStatusInfo(task.status);
  const isPlanner = EMIE.state.currentRole === 'planner';
  const myTask = ['designer', 'supplychain', 'planner'].includes(EMIE.state.currentRole) && task.designerId === getCurrentUserId();
  const needScore = task.scoringRecords && task.scoringRecords.some(sr => sr.score == null && sr.role === EMIE.state.currentRole);
  const doneStatuses = ['delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const isDone = doneStatuses.includes(task.status);

  return `<div class="subtask-card${isDone ? ' completed' : ''}">
    <div class="subtask-header">
      <div class="subtask-name"><span class="subtask-number">${idx + 1}</span> ${escHtml(task.name)}</div>
      <span class="badge ${tsi.cls}">${tsi.label}</span>
    </div>
    <div class="subtask-meta">
      <div class="subtask-meta-item">👤 负责人：<strong>${escHtml(task.designerName || '待分配')}</strong>${task.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${task.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : task.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : 'background:#FEF2F2;color:#DC2626;'}">${task.assigneeRole === 'supplychain' ? '供应链' : task.assigneeRole === 'planner' ? '企划' : '设计师'}</span>` : ''}</div>
      <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(task.plannedDate)}</strong></div>
      ${task.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(task.actualDate)}</strong></div>` : ''}
    </div>
    ${task.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:6px;white-space:pre-wrap;">📝 ${escHtml(task.details)}</div>` : ''}
    ${task.referenceImagesJson ? renderSubTaskImages(task.referenceImagesJson) : ''}
    ${task.attachmentsJson ? renderTaskAttachments(task.attachmentsJson) : ''}

    ${task.status === 'delivered' || task.status === 'planner_approved' || task.status === 'sales_approved' || task.status === 'admin_approved' || task.status === 'approved' || task.status === 'completed' || task.status === 'rejected' ? `
    <div class="subtask-deliver">
      ${task.deliverables ? `<div class="detail-item"><div class="detail-label">交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
    </div>` : ''}

    ${task.reviewComments ? `<div class="review-box ${task.status === 'rejected' ? 'rejected' : 'approved'}"><strong>${task.status === 'rejected' ? '驳回意见' : '验收意见'}：</strong>${escHtml(task.reviewComments)}</div>` : ''}

    ${task.scoringRecords && ['planner_approved', 'sales_approved', 'admin_approved', 'approved', 'completed'].includes(task.status) ? renderScoringMini(task, isDone) : ''}

    <div class="subtask-actions">
      ${/* 企划验收（首轮）：常规品直接通过；渠道定制单进入企划确认状态 */''}
      ${isPlanner && task.status === 'delivered' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'${detail.type}')">✅ 验收通过</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${/* 渠道定制单：销售第二轮确认 */''}
      ${EMIE.state.currentRole === 'sales' && detail.type === 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'channel_custom')">✅ 销售确认通过</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${EMIE.state.currentRole === 'admin' && detail.type !== 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'regular')">✅ 管理确认通过</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${myTask && task.status === 'pending' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskAccept(${detail.id},${task.id})">✅ 接单</button>` : ''}
      ${myTask && task.status === 'accepted' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskDeliver(${detail.id},${task.id})">📤 交付成果</button>` : ''}
      ${myTask && task.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="taskRedeliver(${detail.id},${task.id})">📤 重新交付</button>` : ''}
      ${isPlanner && detail.status !== 'paused' && (task.status === 'pending' || task.status === 'accepted') ? `
        <button class="btn btn-outline btn-sm" data-emie-onclick="editTask(${detail.id},${task.id})">✏️ 编辑</button>
        <button class="btn btn-outline btn-sm" data-emie-onclick="deleteTask(${detail.id},${task.id})" style="color:var(--danger);border-color:var(--danger);">🗑️ 删除</button>
      ` : ''}
      ${needScore ? `<button class="btn btn-warning btn-sm" data-emie-onclick="openScoring(${detail.id},${task.id})">⭐ 评分</button>` : ''}
    </div>
  </div>`;
}

function renderProjectActions(detail) {
  let actions = '';
  const canManageProject = EMIE.state.currentRole === 'planner' || EMIE.state.currentRole === 'sales' || EMIE.state.currentRole === 'admin';

  if (EMIE.state.currentRole === 'planner' && detail.status === 'pending_planner' && detail.type === 'channel_custom') {
    actions += `<button class="btn btn-primary" data-emie-onclick="plannerAcceptProject(${detail.id})">✅ 接单</button>`;
  }

  if (canManageProject) {
    const activeStatuses = ['pending_planner', 'planner_accepted', 'in_progress'];

    // 终止按钮（含暂停状态，暂停也可终止）
    if (activeStatuses.includes(detail.status) || detail.status === 'paused') {
      actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="terminateProject(${detail.id})">终止项目</button>`;
    }
    // 终止确认中 - 对方可以确认
    if (detail.status === 'pending_terminate') {
      actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="terminateProject(${detail.id})">确认终止</button>`;
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="cancelTerminate(${detail.id})" style="color:var(--gray-600);border-color:var(--gray-300);">↩️ 取消终止</button>`;
    }

    // 暂停 / 继续
    if (activeStatuses.includes(detail.status)) {
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="pauseProject(${detail.id})" style="color:var(--primary);border-color:var(--primary);">暂停</button>`;
    }
    if (detail.status === 'paused') {
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="resumeProject(${detail.id})" style="color:var(--success);border-color:var(--success);">继续</button>`;
    }
  }

  if (detail.status === 'terminated') {
    actions += `<span style="color:var(--danger);font-size:13px;font-weight:600;">⛔ 该项目已终止，无法进行任何操作</span>`;
  }
  // 管理员可永久删除项目
  if (EMIE.state.currentRole === 'admin') {
    actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="deleteProject(${detail.id})" title="永久删除项目和所有关联数据">🗑️ 删除</button>`;
  }
  actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="shareProject(${detail.id})">🔗 分享</button>`;
  actions += `<button class="btn btn-outline" data-emie-onclick="closeM('projectDetailModal')">关闭</button>`;
  return actions;
}

// 飞书兼容的确认弹窗
function showConfirmDialog(message, onConfirm, confirmText, cancelText) {
  if (document.getElementById('confirmDialogOverlay')) return;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'confirmDialogOverlay';
  overlay.style.zIndex = '300';
  overlay.innerHTML = `
    <div class="modal" style="max-width:380px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-onclick="this.closest('.modal-overlay').remove()">✕</button>
        <div class="modal-header-left"><div class="modal-title">⚠️ 确认操作</div></div>
      </div>
      <div class="modal-body" style="text-align:center;padding:28px 20px;">
        <p style="font-size:14px;color:var(--gray-700);margin:0;line-height:1.6;">${message}</p>
      </div>
      <div class="modal-footer" style="justify-content:center;gap:12px;padding:12px 20px;">
        <button class="btn btn-outline" data-emie-onclick="this.closest('.modal-overlay').remove()" style="padding:8px 20px;">${cancelText || '取消'}</button>
        <button class="btn btn-danger" id="confirmDialogOk" style="padding:8px 20px;">${confirmText || '确定'}</button>
      </div>
    </div>`;
  overlay.querySelector('#confirmDialogOk').onclick = function() { overlay.remove(); onConfirm(); };
  document.body.appendChild(overlay);
}

async function terminateProject(pid) {
  showConfirmDialog('确定要终止该项目吗？终止后项目将无法恢复。', async () => {
    try {
      await apiPost(`/projects/${pid}/terminate`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function cancelTerminate(pid) {
  showConfirmDialog('确定要取消终止吗？', async () => {
    try {
      await apiPost(`/projects/${pid}/cancel-terminate`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function deleteProject(pid) {
  showConfirmDialog('⚠️ 确定要永久删除项目 #' + pid + ' 吗？<br>此操作不可恢复！<br>子任务、日志、评分记录将一并删除。', async () => {
    try {
      await apiDelete(`/projects/${pid}`);
      closeM('projectDetailModal');
      EMIE.state.cache.orders = [];
      Object.keys(SWR_CACHE).forEach(k => delete SWR_CACHE[k]);
      render();
    } catch(e) { alert('删除失败: ' + e.message); }
  });
}

async function pauseProject(pid) {
  showConfirmDialog('确定要暂停该项目吗？暂停期间无法进行任何操作。', async () => {
    try {
      await apiPost(`/projects/${pid}/pause`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function resumeProject(pid) {
  try {
    await apiPost(`/projects/${pid}/resume`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
    await refreshAfterMutation(pid);
  } catch(e) { alert('操作失败: ' + e.message); }
}

// 渲染项目参考图片
// 兼容历史数据中的旧域名、/uploads 路径和缺少当前下载路径的记录。
function normalizeFileUrl(fileOrUrl) {
  const raw = typeof fileOrUrl === 'string' ? fileOrUrl : (fileOrUrl?.url || '');
  const storedName = typeof fileOrUrl === 'object' && fileOrUrl?.storedName
    ? fileOrUrl.storedName
    : raw.split('?')[0].split('/').pop();
  if (!storedName) return raw;
  return '/api/files/download/' + encodeURIComponent(storedName);
}

function storedNameFromFile(fileOrUrl) {
  if (typeof fileOrUrl === 'object' && fileOrUrl?.storedName) return fileOrUrl.storedName;
  const normalized = normalizeFileUrl(fileOrUrl).split('?')[0];
  const rawName = normalized.split('/').pop() || '';
  try { return decodeURIComponent(rawName); } catch (e) { return rawName; }
}

function isPreviewableFile(fileName) {
  return /\.(pdf|ppt|pptx)$/i.test(fileName || '');
}

function renderAttachmentActions(fileOrUrl, compact = false) {
  const fileName = typeof fileOrUrl === 'object'
    ? (fileOrUrl?.name || storedNameFromFile(fileOrUrl))
    : storedNameFromFile(fileOrUrl);
  const fileSize = typeof fileOrUrl === 'object' ? (fileOrUrl?.size || 0) : 0;
  const fileUrl = normalizeFileUrl(fileOrUrl);
  const previewButton = isPreviewableFile(fileName)
    ? `<button class="attachment-action-btn preview" data-emie-onclick="event.stopPropagation();openFilePreview('${escJsString(fileUrl)}','${escJsString(fileName)}',${fileSize})" title="在线预览">${compact ? '👁' : '👁 预览'}</button>`
    : '';
  return `<span class="attachment-actions">${previewButton}<button class="attachment-action-btn download" data-emie-onclick="event.stopPropagation();showDownloadOptions('${escJsString(fileUrl)}','${escJsString(fileName)}',${fileSize})" title="下载选项">${compact ? '⬇' : '⬇ 下载'}</button></span>`;
}

function renderProjectReferenceImages(detail) {
  if (!detail.referenceImagesJson) return '';
  let imgs;
  try { imgs = JSON.parse(detail.referenceImagesJson); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => normalizeFileUrl(u) + '?token=' + encodeURIComponent(token || '');
  return `<div style="margin-top:8px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" title="${escHtml(img.name || '')}" class="img-clickable" loading="lazy" decoding="async" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
          <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escJsString(normalizeFileUrl(img))}','${escJsString(img.name || 'image.png')}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>`).join('')}
    </div></div>`;
}

/* 子任务参考图 */
function renderSubTaskImages(jsonStr) {
  if (!jsonStr) return '';
  let imgs;
  try { imgs = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => normalizeFileUrl(u) + '?token=' + encodeURIComponent(token || '');
  return `<div style="margin-top:8px;padding-left:4px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" class="img-clickable" loading="lazy" decoding="async" style="width:60px;height:60px;object-fit:cover;border-radius:4px;border:1px solid var(--gray-200);cursor:pointer;">
          <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escJsString(normalizeFileUrl(img))}','${escJsString(img.name || 'image.png')}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>`).join('')}
    </div></div>`;
}

// 渲染项目附件
function renderProjectAttachments(detail) {
  if (!detail.attachmentsJson) return '';
  let atts;
  try { atts = JSON.parse(detail.attachmentsJson); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';
  return `<div style="margin-top:8px;"><div class="detail-label">📎 附件</div>
    ${atts.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
      <span>📎</span><span class="attachment-name" style="flex:1;">${escHtml(a.name)}</span>
      ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
      ${renderAttachmentActions(a)}
    </div>`).join('')}
    </div>`;
}

// 渲染子任务交付附件
function renderTaskAttachments(jsonStr) {
  if (!jsonStr) return '';
  let atts;
  try { atts = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';

  // 分离图片和附件文件
  const images = atts.filter(a => a.name && a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));
  const files = atts.filter(a => !a.name || !a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));

  let html = '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => normalizeFileUrl(u) + '?token=' + encodeURIComponent(token || '');
  // 图片预览
  if (images.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">🖼️ 交付图片</div>
      <div class="image-preview" style="margin-top:4px;">
        ${images.map(img => `<div style="position:relative;display:inline-block;">
            <img src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" class="img-clickable" loading="lazy" decoding="async" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
            <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escJsString(normalizeFileUrl(img))}','${escJsString(img.name || 'image.png')}',${img.size || 0})" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
        </div>`).join('')}
      </div></div>`;
  }
  // 附件下载
  if (files.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">📎 交付附件</div>
      ${files.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
        <span>📎</span><span class="attachment-name" style="flex:1;">${escHtml(a.name)}</span>
        ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
        ${renderAttachmentActions(a)}
      </div>`).join('')}
      </div>`;
  }
  return html;
}

function renderProjectScoringSummary(detail) {
  const approvedTasks = detail.tasks.filter(t => ['approved', 'completed', 'sales_approved', 'admin_approved'].includes(t.status) && t.scoringRecords && t.scoringRecords.length > 0);
  if (approvedTasks.length === 0) return '';
  const allScoredTasks = approvedTasks.filter(t => t.scoringRecords.every(sr => sr.score != null));

  let html = `<div class="detail-section"><div class="detail-section-title">⭐ 项目评分汇总 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${allScoredTasks.length}/${approvedTasks.length} 已完成</span></div>`;
  approvedTasks.forEach((task, i) => {
    const records = task.scoringRecords;
    let ta = 0, tw = 0;
    records.forEach(r => {
      if (r.score != null) {
        ta += r.score * r.weight;
        tw += r.weight;
      }
    });
    const final = tw > 0 ? (ta / tw).toFixed(0) : null;
    html += `<div style="display:flex;align-items:center;gap:12px;padding:8px 12px;background:var(--gray-50);border-radius:6px;margin-bottom:6px;font-size:13px;">
      <span style="font-weight:600;">#${i + 1} ${task.name}</span>
      <span style="flex:1;"></span>
      ${final ? `<span style="font-size:16px;font-weight:700;color:var(--primary);">${final}分</span>` : `<span style="color:var(--gray-400);">评分中…</span>`}
    </div>`;
  });
  html += `</div>`;
  return html;
}

// ===== 项目进度管道 =====
function renderProjectPipeline(detail) {
  const isChannel = detail.type === 'channel_custom';
  const tasks = detail.tasks || [];

  // 定义一个管道的 5 个阶段
  const stages = isChannel
    ? [
        { key: 'create', label: '创建项目', detail: '销售 ' + (detail.salesName || '') },
        { key: 'accept', label: '企划接单', detail: detail.plannerName || '' },
        { key: 'execute', label: '子任务执行', detail: '' },
        { key: 'planner_score', label: '企划评分', detail: '' },
        { key: 'sales_confirm', label: '销售确认', detail: '' },
      ]
    : [
        { key: 'create', label: '创建项目', detail: '销售 ' + (detail.salesName || '') },
        { key: 'accept', label: '企划接单', detail: detail.plannerName || '' },
        { key: 'execute', label: '子任务执行', detail: '' },
        { key: 'planner_score', label: '企划评分', detail: '' },
        { key: 'admin_confirm', label: '管理确认', detail: '' },
      ];

  // 计算各阶段状态: done / current / pending / error
  const status = detail.status;
  const taskStatuses = tasks.map(t => t.status);

  function stageState(key) {
    switch (key) {
      case 'create':
        return 'done';
      case 'accept':
        return !['pending_planner'].includes(status) ? 'done' : 'current';
      case 'execute': {
        if (taskStatuses.length === 0) return status === 'completed' ? 'done' : 'current';
        const allDelivered = taskStatuses.every(s => ['delivered','planner_approved','sales_approved','admin_approved','completed'].includes(s));
        if (allDelivered) return 'done';
        const anyActive = taskStatuses.some(s => ['accepted','delivered'].includes(s));
        return anyActive ? 'current' : 'pending';
      }
      case 'planner_score': {
        if (taskStatuses.length === 0) return 'pending';
        const allScored = taskStatuses.every(s => ['planner_approved','sales_approved','admin_approved','completed'].includes(s));
        if (allScored) return 'done';
        const anyDelivered = taskStatuses.some(s => s === 'delivered');
        return anyDelivered ? 'current' : 'pending';
      }
      case 'sales_confirm':
      case 'admin_confirm': {
        if (taskStatuses.length === 0) return 'pending';
        const targetStatus = key === 'sales_confirm' ? 'sales_approved' : 'admin_approved';
        const allDone = taskStatuses.every(s => s === 'completed');
        if (allDone) return 'done';
        const awaitingConfirm = taskStatuses.some(s => s === 'planner_approved');
        return awaitingConfirm ? 'current' : 'pending';
      }
    }
    return 'pending';
  }

  // 构建下一步提示
  function getNextHint() {
    if (['terminated', 'pending_terminate'].includes(status)) {
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⛔', title: '项目已终止', text: '该项目已被终止，无法继续操作。' };
    }
    if (status === 'paused') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '⏸️', title: '项目已暂停', text: '点击"继续"按钮可恢复项目。' };
    }
    if (status === 'completed') {
      return { color: '#3B6D11', bg: '#EAF3DE', border: '#C0DD97', icon: '🎉', title: '项目已完成', text: '所有子任务已验收评分完毕。' };
    }

    if (status === 'pending_planner') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待企划接单', text: '产品企划 ' + (detail.plannerName || '待指定') + ' 需要先接单才能开始工作。' };
    }

    // 子任务层面分析
    const pendingTasks = tasks.filter(t => t.status === 'pending');
    const unassignedTasks = tasks.filter(t => t.status === 'pending' && (!t.designerId || t.designerId === ''));
    const acceptedTasks = tasks.filter(t => t.status === 'accepted');
    const deliveredTasks = tasks.filter(t => t.status === 'delivered');
    const plannerApprovedTasks = tasks.filter(t => t.status === 'planner_approved');
    const rejectedTasks = tasks.filter(t => t.status === 'rejected');

    if (rejectedTasks.length > 0) {
      const names = rejectedTasks.map(t => t.name).join('、');
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⚠️', title: '有子任务被驳回', text: '子任务「' + names + '」需要设计师重新交付。' };
    }
    // 优先级：已企划评分 → 已交付 → 执行中 → 待分配
    if (plannerApprovedTasks.length > 0) {
      const names = plannerApprovedTasks.map(t => t.name).join('、');
      const confirmer = isChannel ? '销售' : '管理';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待' + confirmer + '确认', text: '子任务「' + names + '」企划已评分通过，等待' + confirmer + '确认。' };
    }
    if (deliveredTasks.length > 0) {
      const names = deliveredTasks.map(t => t.name).join('、');
      const role = isChannel ? '企划' : '企划';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待验收评分', text: '子任务「' + names + '」已交付，等待' + role + '验收评分。' };
    }
    if (acceptedTasks.length > 0) {
      const names = acceptedTasks.map(t => t.name).join('、');
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待交付', text: '子任务「' + names + '」正在执行中，等待子任务负责人交付成果。' };
    }
    if (unassignedTasks.length > 0) {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待分配子任务', text: '还有 ' + unassignedTasks.length + ' 个子任务未指派，请先指派负责人。' };
    }
    return null;
  }

  // 生成管道圆点 HTML
  const stageHtml = stages.map((s, i) => {
    const st = stageState(s.key);
    const isLast = i === stages.length - 1;
    let dotStyle, labelColor, detailColor;
    if (st === 'done') {
      dotStyle = 'background:#3B6D11;';
      labelColor = 'color:#3B6D11;';
      detailColor = 'color:var(--color-text-tertiary);';
    } else if (st === 'current') {
      dotStyle = 'background:#EF9F27;box-shadow:0 0 0 4px #FAEEDA;';
      labelColor = 'color:#854F0B;font-weight:600;';
      detailColor = 'color:#854F0B;';
    } else if (st === 'error') {
      dotStyle = 'background:#E24B4A;';
      labelColor = 'color:#A32D2D;';
      detailColor = 'color:#A32D2D;';
    } else {
      dotStyle = 'background:var(--color-border-tertiary);';
      labelColor = 'color:var(--color-text-tertiary);';
      detailColor = 'color:var(--color-text-tertiary);';
    }
    const connector = !isLast ? `<div style="position:absolute;top:14px;left:56%;right:-16px;height:3px;background:${st === 'done' ? '#3B6D11' : 'var(--color-border-tertiary)'};z-index:-1;"></div>` : '';
    const dotInner = st === 'done' ? '<span style="color:#fff;font-size:11px;">✓</span>' : st === 'current' ? '<span style="color:#fff;font-size:12px;">●</span>' : '';
    return `<div style="flex:1;text-align:center;position:relative;">
      <div style="width:28px;height:28px;border-radius:50%;margin:0 auto 6px;display:flex;align-items:center;justify-content:center;${dotStyle}">${dotInner}</div>
      <div style="font-size:11px;${labelColor}">${s.label}</div>
      <div style="font-size:10px;${detailColor};margin-top:2px;">${st === 'done' ? '已完成' : st === 'current' ? '进行中' : '待进行'}${s.detail ? ' · ' + s.detail : ''}</div>
      ${connector}
    </div>`;
  }).join('');

  const hint = getNextHint();
  const hintHtml = hint ? `
    <div style="margin-top:16px;background:${hint.bg};border-radius:8px;padding:12px 16px;border:0.5px solid ${hint.border};">
      <div style="display:flex;align-items:center;gap:8px;">
        <span style="font-size:16px;">${hint.icon}</span>
        <div>
          <div style="font-size:12px;font-weight:500;color:${hint.color};">${hint.title}</div>
          <div style="font-size:12px;color:${hint.color};opacity:0.85;">${hint.text}</div>
        </div>
      </div>
    </div>` : '';

  return `<div class="detail-section">
    <div class="detail-section-title">🔵 项目进度 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${isChannel ? '渠道定制单' : '公司常规品'}</span></div>
    <div style="padding:20px 8px 8px;">
      <div style="display:flex;gap:0;">${stageHtml}</div>
      ${hintHtml}
    </div>
    <div style="margin-top:8px;font-size:11px;color:var(--color-text-tertiary);display:flex;gap:16px;padding:0 4px;">
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#3B6D11;vertical-align:middle;margin-right:4px;"></span>已完成</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#EF9F27;vertical-align:middle;margin-right:4px;"></span>进行中</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:var(--color-border-tertiary);vertical-align:middle;margin-right:4px;"></span>待进行</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#E24B4A;vertical-align:middle;margin-right:4px;"></span>异常</span>
    </div>
  </div>`;
}
async function plannerAcceptProject(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, userId: getCurrentUserId() });
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// 从列表直接接单（不需要打开弹窗）
async function plannerAcceptFromList(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, userId: getCurrentUserId() });
    EMIE.state.cache.orders = [];
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}


EMIE.registerActions({
  openProjectDetail,
  renderProjectDetailContent,
  cleanLogAction,
  renderLogLabel,
  renderSubTaskCard,
  renderProjectActions,
  showConfirmDialog,
  terminateProject,
  cancelTerminate,
  deleteProject,
  pauseProject,
  resumeProject,
  normalizeFileUrl,
  storedNameFromFile,
  isPreviewableFile,
  renderAttachmentActions,
  renderProjectReferenceImages,
  renderSubTaskImages,
  renderProjectAttachments,
  renderTaskAttachments,
  renderProjectScoringSummary,
  renderProjectPipeline,
  plannerAcceptProject,
  plannerAcceptFromList,
});

EMIE.registerModule('projectDetail', {
  openProjectDetail,
  renderProjectDetailContent,
  renderProjectActions,
  terminateProject,
  cancelTerminate,
  deleteProject,
  pauseProject,
  resumeProject,
  normalizeFileUrl,
  renderAttachmentActions,
  plannerAcceptProject,
  plannerAcceptFromList,
});
