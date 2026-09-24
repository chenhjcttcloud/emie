const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const fmtDT = (...args) => EMIE.actions.fmtDT(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const taskAccept = (...args) => EMIE.actions.taskAccept(...args);
const taskDeliver = (...args) => EMIE.actions.taskDeliver(...args);
const taskRedeliver = (...args) => EMIE.actions.taskRedeliver(...args);
const taskCorrectDelivery = (...args) => EMIE.actions.taskCorrectDelivery(...args);
const taskConfirmRevision = (...args) => EMIE.actions.taskConfirmRevision(...args);
const taskApprove = (...args) => EMIE.actions.taskApprove(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const matchesSearchText = (...args) => EMIE.actions.matchesSearchText(...args);
const isDateInRange = (...args) => EMIE.actions.isDateInRange(...args);
const compareTaskPriority = (...args) => EMIE.actions.compareTaskPriority(...args);
async function renderDesignerTasks(main, uid, bucket = 'all', role = EMIE.state.currentRole,
                                   endpoint = '/projects/my-subtasks', readOnly = false) {
  const marketView = endpoint === '/projects/task-market';
  let rows = await apiGet(endpoint);
  if (!readOnly && role === 'designer' && endpoint === '/projects/my-subtasks') {
    const marketRows = await apiGet('/projects/task-market');
    const known = new Set(rows.map(task => Number(task.id)));
    rows = rows.concat(marketRows.filter(task => !known.has(Number(task.id))));
  }
  let myTasks = rows.map(task => ({
    ...task,
    projectName: (task.projectName || '').substring(0, 30),
    _unassigned: task.allocationStatus === 'market_open'
  }));

  if (bucket === 'pending') myTasks = myTasks.filter(t => ['pending', 'delivered', 'submitted_for_review'].includes(t.status));
  if (bucket === 'completed') myTasks = myTasks.filter(t => ['approved', 'completed'].includes(t.status));
  myTasks.sort(compareTaskPriority);

  EMIE.dashboardState.designerTasksReadOnly = readOnly;
  const taskEmoji = role === 'promotion' ? '📣' : role === 'supplychain' ? '🛒' : role === 'planner' ? '📋' : '🎨';
  const pageTitle = marketView ? '接单市场' : endpoint === '/projects/department-subtasks' ? '其他子任务' : bucket === 'pending' ? '待处理子任务' : bucket === 'completed' ? '已完成子任务' : '我的子任务';
  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">${taskEmoji} ${pageTitle} <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${myTasks.length})</span></h2>
    <div class="filter-bar">
      ${marketView ? '<button class="btn btn-primary btn-sm" data-emie-action="click:task-market-refresh">↻ 刷新</button>' : ''}
      ${!marketView && bucket !== 'completed' ? `<select class="form-select" data-emie-action="change:designer-filter" style="min-width:120px;" id="designerTaskFilter" aria-label="任务归属">
        <option value="all">全部任务</option>
        <option value="unassigned">待认领</option>
        <option value="mine">我的任务</option>
      </select>` : ''}
      ${bucket === 'all' ? `<select class="form-select" data-emie-action="change:designer-filter" style="min-width:120px;" id="designerTaskStatusFilter">
        <option value="all">全部状态</option>
        <option value="pending">待接单</option>
        <option value="accepted">进行中</option>
        <option value="delivered">待验收</option>
        <option value="planner_approved">一审通过</option>
        <option value="sales_approved">销售已验收</option>
        <option value="admin_approved">管理员已验收</option>
        <option value="rejected">已驳回</option>
        <option value="completed">已完成</option>
        <option value="approved">已通过</option>
      </select>` : ''}
      <select class="form-select" data-emie-action="change:designer-filter" style="min-width:120px;" id="designerTaskTypeFilter">
        <option value="all">全部项目类型</option>
        <option value="channel_custom">渠道定制单</option>
        <option value="regular">公司常规品</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." data-emie-action="input:designer-filter" style="min-width:180px;" id="designerTaskSearch">
      <input type="date" class="form-input" id="designerTaskDateStart" data-emie-action="change:designer-filter" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="designerTaskDateEnd" data-emie-action="change:designer-filter" style="min-width:130px;" title="计划完成日期止">
      <button class="btn btn-outline btn-sm" data-emie-action="click:designer-reset">↺ 重置</button>
    </div>
    <div id="designerTaskContainer">${renderDesignerTaskCards(myTasks, readOnly)}</div>
    ${endpoint === '/projects/department-subtasks' ? '<div id="designerTaskPagination" class="project-pagination"></div>' : ''}
  `;
  EMIE.dashboardState.designerTaskCache = myTasks;
  EMIE.dashboardState.designerTaskPage = 0;
  if (endpoint === '/projects/department-subtasks') renderDesignerTaskPage();
}

function renderDesignerTaskPage(list = EMIE.dashboardState.designerTaskCache || []) {
  const pageSize = 10;
  const pages = Math.max(1, Math.ceil(list.length / pageSize));
  const page = Math.min(Math.max(EMIE.dashboardState.designerTaskPage || 0, 0), pages - 1);
  EMIE.dashboardState.designerTaskPage = page;
  const container = document.getElementById('designerTaskContainer');
  if (container) container.innerHTML = renderDesignerTaskCards(list.slice(page * pageSize, (page + 1) * pageSize), EMIE.dashboardState.designerTasksReadOnly === true);
  const pagination = document.getElementById('designerTaskPagination');
  if (pagination) pagination.innerHTML = list.length > pageSize ? `<span>共 ${list.length} 个子任务 · ${page + 1} / ${pages} 页</span><div><button class="btn btn-outline btn-sm" ${page <= 0 ? 'disabled' : ''} data-emie-action="click:designer-page" data-page="${page - 1}">上一页</button><button class="btn btn-outline btn-sm" ${page >= pages - 1 ? 'disabled' : ''} data-emie-action="click:designer-page" data-page="${page + 1}">下一页</button></div>` : '';
}

async function renderTaskMarket(main, role, uid) {
  const readOnly = role !== 'designer';
  await renderDesignerTasks(main, uid, 'all', role, '/projects/task-market', readOnly);
}

function filterDesignerTasks() {
  clearTimeout(filterDesignerTasks._timer);
  filterDesignerTasks._timer = setTimeout(applyFilterDesignerTasks, 350);
}

function applyFilterDesignerTasks() {
  const filter = document.getElementById('designerTaskFilter')?.value || 'all';
  const status = document.getElementById('designerTaskStatusFilter')?.value || 'all';
  const projectType = document.getElementById('designerTaskTypeFilter')?.value || 'all';
  const q = document.getElementById('designerTaskSearch')?.value || '';
  const dateStart = document.getElementById('designerTaskDateStart')?.value;
  const dateEnd = document.getElementById('designerTaskDateEnd')?.value;
  let list = EMIE.dashboardState.designerTaskCache || [];

  if (filter === 'unassigned') list = list.filter(t => t._unassigned);
  else if (filter === 'mine') list = list.filter(t => !t._unassigned);
  if (status !== 'all') list = list.filter(t => status === 'delivered'
    ? ['delivered', 'submitted_for_review'].includes(t.status)
    : t.status === status);
  if (projectType !== 'all') list = list.filter(t => t.projectType === projectType);

  if (q) list = list.filter(t => matchesSearchText(q, t.id, t.projectId, t.name, t.projectName, t.details, t.designerName));
  list = list.filter(t => isDateInRange(t.plannedDate, dateStart, dateEnd));

  const c = document.getElementById('designerTaskContainer');
  if (EMIE.dashboardState.designerTasksReadOnly && document.getElementById('designerTaskPagination')) {
    EMIE.dashboardState.designerTaskPage = 0;
    renderDesignerTaskPage(list);
  } else if (c) c.innerHTML = renderDesignerTaskCards(list, EMIE.dashboardState.designerTasksReadOnly === true);
}

function resetDesignerTaskFilters() {
  const filterEl = document.getElementById('designerTaskFilter');
  const statusEl = document.getElementById('designerTaskStatusFilter');
  const typeEl = document.getElementById('designerTaskTypeFilter');
  const searchEl = document.getElementById('designerTaskSearch');
  const dateStartEl = document.getElementById('designerTaskDateStart');
  const dateEndEl = document.getElementById('designerTaskDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (statusEl) statusEl.value = 'all';
  if (typeEl) typeEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterDesignerTasks();
}

function renderDesignerTaskCards(tasks, readOnly = false) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无子任务</p></div>`;
  const groups = [
    { icon: '📥', label: '待接单', cls: 'task-group-pending', open: true, statuses: ['pending'] },
    { icon: '🔄', label: '进行中', cls: 'task-group-active', open: true, statuses: ['accepted', 'rejected'] },
    { icon: '📤', label: '待验收', cls: 'task-group-delivered', open: true, statuses: ['delivered', 'submitted_for_review', 'planner_approved', 'sales_approved', 'admin_approved'] },
    { icon: '✅', label: '已完成', cls: 'task-group-done', open: false, statuses: ['approved', 'completed'] },
  ];
  const visibleGroups = groups.map(group => ({ ...group, tasks: tasks.filter(task => group.statuses.includes(task.status)) })).filter(group => group.tasks.length);
  return `<div class="designer-task-summary">${visibleGroups.map(group => `<div class="designer-task-summary-item ${group.cls}"><span>${group.icon}</span><strong>${group.tasks.length}</strong><small>${group.label}</small></div>`).join('')}</div>
    <div class="designer-task-groups">${visibleGroups.map(group => `<details class="designer-task-group ${group.cls}" ${group.open ? 'open' : ''}><summary><span class="designer-task-group-title">${group.icon} ${group.label}</span><span class="designer-task-group-count">${group.tasks.length} 个</span><span class="designer-task-group-chevron">⌄</span></summary><div class="designer-task-group-body">${renderDesignerTaskCardsFlat(group.tasks, readOnly)}</div></details>`).join('')}</div>`;
}

function renderDesignerTaskCardsFlat(tasks, readOnly = false) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无子任务</p></div>`;
  const plannerView = EMIE.state.currentRole === 'planner';
  return `<div class="subtask-list">
      ${tasks.map(t => {
        const tsi = getTaskStatusInfo(t.status);
        const modificationCount = Array.isArray(t.rejectionRecords) ? t.rejectionRecords.length : 0;
        const deliveryVersions = Array.isArray(t.deliveryVersions) ? t.deliveryVersions : [];
        const latestDelivery = deliveryVersions[0];
        const rejectionRecords = Array.isArray(t.rejectionRecords) ? t.rejectionRecords : [];
        const isRedelivering = t.status === 'accepted' && rejectionRecords.some(record => !record.cancelled);
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} 子任务：${escHtml(t.name || '-')} <span class="subtask-project-inline">（所属项目：${escHtml(t.projectName || '未命名项目')}）</span> <span style="font-size:11px;color:var(--gray-400);font-weight:400;">#${t.id}</span></div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 ${plannerView ? '产品企划' : '负责人'}：<strong>${plannerView ? (t.plannerName || '<span style="color:var(--warning);">待指定</span>') : (t.designerName || '<span style="color:var(--warning);">待认领</span>')}</strong>${!plannerView && t.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${t.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : t.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : t.assigneeRole === 'promotion' ? 'background:#F5F3FF;color:#7C3AED;' : t.assigneeRole === 'sales' ? 'background:#FFF7ED;color:#C2410C;' : 'background:#FEF2F2;color:#DC2626;'}">${t.assigneeRole === 'supplychain' ? '供应链' : t.assigneeRole === 'planner' ? '企划' : t.assigneeRole === 'promotion' ? '产品推广' : t.assigneeRole === 'sales' ? '销售' : '设计师'}</span>` : ''}</div>
            ${t.relation ? `<div class="subtask-meta-item">🔗 我的关系：<strong>${t.relationLabel || (t.relation === 'publisher' ? '我发布的任务' : t.relation === 'market' ? '接单市场' : t.relation === 'department_member' ? '部门成员关联任务' : '我负责的任务')}</strong></div>` : ''}
            <div class="subtask-meta-item">🕒 发布时间：<strong>${fmtDT(t.relation === 'market' && t.marketPublishedAt ? t.marketPublishedAt : t.createdAt) || '-'}</strong></div>
            <div class="subtask-meta-item">📅 ${t.status === 'rejected' && t.rejectionRecords?.length && t.rejectionRecords[t.rejectionRecords.length - 1]?.requiredCompletionDate ? '驳回后要求完成' : '计划完成'}：<strong>${formatDate(t.status === 'rejected' && t.rejectionRecords?.length ? (t.rejectionRecords[t.rejectionRecords.length - 1].requiredCompletionDate || t.plannedDate) : t.plannedDate)}</strong></div>
            <div class="subtask-meta-item">⭐ 积分：<strong>${t.basePointSnapshot != null ? `${Number(t.basePointSnapshot)} 分` : '未设置'}</strong></div>
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${escHtml(t.details)}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'rejected' ? '驳回意见' : '验收意见'}：${escHtml(t.reviewComments)}</div>` : ''}
          ${latestDelivery ? `<div style="margin-top:12px;padding:10px 12px;border:1px solid var(--gray-200);border-radius:8px;background:var(--gray-50);"><div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;"><strong style="font-size:13px;">最新提交</strong><span style="font-size:11px;color:var(--gray-400);">${fmtDT(latestDelivery.submittedAt)}</span></div><div style="font-size:12px;color:var(--gray-700);white-space:pre-wrap;">${escHtml(latestDelivery.deliverables || '未填写文字交付内容')}</div>${taskDetailFiles(latestDelivery.referenceImagesJson, true)}${taskDetailFiles(latestDelivery.attachmentsJson, false)}</div>` : ''}
          <div class="subtask-actions">
            ${!readOnly && t.status === 'pending' && !t._unassigned ? `<button class="btn btn-primary btn-sm" data-emie-action="click:designer-accept" data-project-id="${t.projectId}" data-task-id="${t.id}">✅ 接单</button>` : ''}
            ${!readOnly && t._unassigned ? `<button class="btn btn-success btn-sm" data-emie-action="click:designer-accept-market" data-project-id="${t.projectId}" data-task-id="${t.id}" data-task-snapshot="${escHtml(JSON.stringify(t))}">⚡ 抢单</button>` : ''}
            ${!readOnly && t.status === 'accepted' && t.designerId === getCurrentUserId() ? (isRedelivering ? `<button class="btn btn-primary btn-sm" data-emie-action="click:designer-redeliver" data-project-id="${t.projectId}" data-task-id="${t.id}">📤 重新交付</button>` : `<button class="btn btn-warning btn-sm" data-emie-action="click:designer-withdraw" data-project-id="${t.projectId}" data-task-id="${t.id}">↩️ 退单</button><button class="btn btn-primary btn-sm" data-emie-action="click:designer-deliver" data-project-id="${t.projectId}" data-task-id="${t.id}">📤 交付成果</button>`) : ''}
            ${!readOnly && t.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-action="click:designer-revision" data-project-id="${t.projectId}" data-task-id="${t.id}">🛠️ 确认修改</button>` : ''}
        ${!readOnly && t.status === 'delivered' && t.designerId === getCurrentUserId() ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-correct" data-project-id="${t.projectId}" data-task-id="${t.id}">📝 更正当前交付</button>` : ''}
            ${deliveryVersions.length ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-history" data-task-id="${t.id}">📚 提交历史</button>` : ''}
            <button class="btn btn-outline btn-sm" data-emie-action="click:designer-detail" data-task-id="${t.id}">查看子任务详情${modificationCount ? `（${modificationCount}）` : ''}</button>
            <button class="btn btn-outline btn-sm" data-emie-action="click:designer-detail-project" data-project-id="${t.projectId}">查看项目</button>
          </div>
        </div>`;
      }).join('')}
    </div>`;
}

function taskDetailFiles(json, images) {
  const renderer = images ? EMIE.actions.renderSubTaskImages : EMIE.actions.renderTaskAttachments;
  return typeof renderer === 'function' && json ? renderer(json) : '';
}

function renderDeliveryRounds(versions, rejectionRecords = []) {
  const rounds = [];
  [...versions].reverse().forEach(version => {
    if (version.submissionType !== 'correction' || !rounds.length) rounds.push([]);
    rounds[rounds.length - 1].push(version);
  });
  return rounds.length ? `<div style="margin-top:12px;"><div style="font-size:13px;font-weight:700;margin-bottom:6px;">交付记录 <span style="color:var(--gray-400);font-weight:400;">(${rounds.length} 轮)</span></div>${rounds.reverse().map((revisions, index) => {
    const roundNo = rounds.length - index;
    const current = revisions[revisions.length - 1];
    const history = revisions.slice(0, -1).reverse();
    const rejection = rejectionRecords.find(record => Number(record.attemptNo) === roundNo);
    return `<details style="border:1px solid var(--gray-200);border-radius:8px;margin-bottom:5px;overflow:hidden;background:var(--gray-50);"><summary style="display:flex;align-items:center;gap:8px;padding:7px 10px;cursor:pointer;list-style:none;font-size:12px;"><strong>第 ${roundNo} 轮交付</strong><span class="badge ${roundNo > 1 ? 'badge-rejected' : 'badge-completed'}">${roundNo > 1 ? '驳回后重交' : '首次交付'}</span>${history.length ? `<span class="badge badge-pending">已修订 ${history.length} 次</span>` : ''}<span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--gray-600);">${escHtml(current.changeSummary || '')}</span><span style="color:var(--gray-400);white-space:nowrap;">${current.submittedAt ? fmtDT(current.submittedAt) : ''}</span><span style="color:var(--gray-400);">⌄</span></summary><div style="padding:10px 12px;border-top:1px solid var(--gray-200);background:#fff;"><div style="font-size:12px;color:var(--gray-700);white-space:pre-wrap;">${escHtml(current.deliverables || '未填写文字交付内容')}</div>${rejection ? `<div style="margin-top:8px;padding:8px 10px;border-radius:6px;background:#FFF8F8;color:#A32D2D;font-size:12px;"><strong>驳回意见：</strong>${escHtml(rejection.reason || '未填写驳回意见')}</div>` : ''}${taskDetailFiles(current.referenceImagesJson, true)}${taskDetailFiles(current.attachmentsJson, false)}${history.length ? `<details style="margin-top:10px;border-top:1px dashed var(--gray-200);padding-top:8px;"><summary style="cursor:pointer;color:var(--gray-500);font-size:12px;">查看修订留痕（${history.length}）</summary>${history.map(version => `<div style="margin-top:8px;padding:8px;background:var(--gray-50);border-radius:6px;"><div style="font-size:11px;color:var(--gray-500);">${escHtml(version.changeSummary || '原交付记录')} · ${version.submittedAt ? fmtDT(version.submittedAt) : ''}</div><div style="margin-top:5px;font-size:12px;white-space:pre-wrap;">${escHtml(version.deliverables || '未填写文字交付内容')}</div>${taskDetailFiles(version.referenceImagesJson, true)}${taskDetailFiles(version.attachmentsJson, false)}</div>`).join('')}</details>` : ''}</div></details>`;
  }).join('')}</div>` : '';
}

function openDeliveryHistory(taskId) {
  if (document.getElementById('deliveryHistoryModal')) return;
  const task = (EMIE.dashboardState.designerTaskCache || []).find(item => Number(item.id) === Number(taskId))
    || EMIE.projectState.currentProjectDetail?.tasks?.find(item => Number(item.id) === Number(taskId));
  if (!task) return;
  const versions = Array.isArray(task.deliveryVersions) ? task.deliveryVersions : [];
  const records = Array.isArray(task.rejectionRecords) ? task.rejectionRecords : [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'deliveryHistoryModal';
  modal.innerHTML = `<div class="modal" style="max-width:820px;"><div class="modal-header"><button class="modal-close" data-emie-action="click:designer-history-close">✕</button><div class="modal-header-left"><div class="modal-title">📚 提交历史 · ${escHtml(task.name || '-')}</div><div style="font-size:12px;color:var(--gray-400);margin-top:3px;">共 ${versions.filter(version => version.submissionType !== 'correction').length} 轮交付，主动更正记录收在对应轮次内</div></div></div><div class="modal-body">${renderDeliveryRounds(versions, records) || '<div class="empty"><p>暂无提交历史</p></div>'}</div><div class="modal-footer"><button class="btn btn-primary" data-emie-action="click:designer-history-close">关闭</button></div></div>`;
  document.body.appendChild(modal);
}

function openPublishedSubTaskDetail(taskId) {
  if (document.getElementById('publishedSubTaskDetailModal')) return;
  const task = (EMIE.dashboardState.designerTaskCache || []).find(item => Number(item.id) === Number(taskId));
  if (!task) return;
  const tsi = getTaskStatusInfo(task.status);
  const records = Array.isArray(task.rejectionRecords) ? task.rejectionRecords : [];
  const deliveryVersions = Array.isArray(task.deliveryVersions) ? task.deliveryVersions : [];
  const roleNames = { planner: '产品企划', sales: '销售', admin: '管理员', designer: '设计师', supplychain: '供应链', promotion: '产品推广' };
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'publishedSubTaskDetailModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:760px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-action="click:designer-detail-close">✕</button>
        <div class="modal-header-left">
          <div class="modal-title">子任务详情 · ${escHtml(task.name || '-')}</div>
          <div style="font-size:12px;color:var(--gray-400);margin-top:3px;">所属项目：${escHtml(task.projectName || '未命名项目')} · #${task.id}</div>
        </div>
        <span class="badge ${tsi.cls}">${tsi.label}</span>
      </div>
      <div class="modal-body">
        <div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;">
          <div class="detail-item"><div class="detail-label">负责人</div><div class="detail-value">${escHtml(task.designerName || '待分配')}</div></div>
          <div class="detail-item"><div class="detail-label">发布人</div><div class="detail-value">${escHtml(task.publisherName || '-')}</div></div>
          <div class="detail-item"><div class="detail-label">计划完成</div><div class="detail-value">${formatDate(task.plannedDate)}</div></div>
          <div class="detail-item"><div class="detail-label">实际完成</div><div class="detail-value">${task.actualDate ? formatDate(task.actualDate) : '-'}</div></div>
          <div class="detail-item"><div class="detail-label">积分</div><div class="detail-value">${task.basePointSnapshot != null ? `${Number(task.basePointSnapshot)} 分` : '未设置'}</div></div>
        </div>
        <div class="detail-item" style="margin-top:12px;"><div class="detail-label">任务要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.details || '未填写')}</div></div>
        ${taskDetailFiles(task.referenceImagesJson, true)}
        ${taskDetailFiles(task.attachmentsJson, false)}
        ${task.deliverables ? `<div class="detail-item" style="margin-top:12px;"><div class="detail-label">当前交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
        <div style="margin-top:18px;">
          <div style="font-size:14px;font-weight:700;margin-bottom:8px;">修改要求记录 <span style="color:var(--gray-400);font-weight:400;">(${records.length})</span></div>
          ${records.length ? records.map(record => `
            <details style="border:1px solid #F3C1C1;border-radius:9px;margin-bottom:8px;overflow:hidden;">
              <summary style="display:flex;align-items:center;gap:8px;padding:10px 12px;background:#FFF8F8;cursor:pointer;list-style:none;">
                <strong style="color:#A32D2D;white-space:nowrap;">第 ${record.attemptNo} 次修改要求</strong>
                <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--gray-600);">${escHtml(record.reason || '未填写修改意见')}</span>
                <span style="font-size:11px;color:var(--gray-400);white-space:nowrap;">${record.reviewedAt ? new Date(record.reviewedAt).toLocaleString('zh-CN', { hour12: false }) : '-'}</span>
              </summary>
              <div style="padding:14px;">
                <div style="padding:12px;background:var(--gray-50);border-radius:8px;">
                  <div class="detail-label">当轮提交内容 · ${escHtml(record.submittedByName || task.designerName || '-')}</div>
                  <div class="detail-value" style="white-space:pre-wrap;margin-top:5px;">${escHtml(record.deliverables || '未填写文字交付内容')}</div>
                  ${taskDetailFiles(record.referenceImagesJson, true)}
                  ${taskDetailFiles(record.attachmentsJson, false)}
                </div>
                <div style="margin-top:10px;padding:12px;background:#FFF8F8;border-radius:8px;">
                  <div class="detail-label" style="color:#A32D2D;">修改意见 · ${escHtml(roleNames[record.reviewerRole] || record.reviewerRole || '')} ${escHtml(record.reviewerName || '-')}</div>
                  <div class="detail-label" style="color:#A32D2D;margin-top:6px;">要求完成时间：${record.requiredCompletionDate ? formatDate(record.requiredCompletionDate) : '-'}</div>
                  <div class="detail-value" style="white-space:pre-wrap;margin-top:5px;">${escHtml(record.reason || '未填写修改意见')}</div>
                  ${taskDetailFiles(record.rejectionReferenceImagesJson, true)}
                  ${taskDetailFiles(record.rejectionAttachmentsJson, false)}
                </div>
              </div>
            </details>`).join('') : '<div class="empty" style="padding:24px;"><p>暂无修改要求记录</p></div>'}
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-action="click:designer-detail-project" data-project-id="${task.projectId}">查看所属项目</button>
        ${deliveryVersions.length ? `<button class="btn btn-outline" data-emie-action="click:designer-history" data-task-id="${task.id}">📚 提交历史</button>` : ''}
        ${EMIE.state.currentRole === 'designer' && task.status === 'pending' ? `<button class="btn btn-primary" data-emie-action="click:designer-accept" data-project-id="${task.projectId}" data-task-id="${task.id}">✅ 接单</button>` : ''}
        ${EMIE.state.currentRole === 'planner' && ['delivered', 'submitted_for_review'].includes(task.status) ? `<button class="btn btn-success" data-emie-action="click:designer-approve" data-project-id="${task.projectId}" data-task-id="${task.id}" data-project-type="${escHtml(task.projectType || 'regular')}">✅ 验收通过</button>` : ''}
        <button class="btn btn-primary" data-emie-action="click:designer-detail-close">关闭</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  // 详情抽屉的关闭按钮直接绑定兜底事件，兼容部分旧页面缓存未加载声明式事件运行时的情况。
  modal.querySelector('.modal-close')?.addEventListener('click', () => closeM('publishedSubTaskDetailModal'));
  modal.addEventListener('click', event => {
    if (event.target === modal) closeM('publishedSubTaskDetailModal');
  });
}




EMIE.registerActions({
  renderDesignerTasks,
  renderTaskMarket,
  filterDesignerTasks,
  applyFilterDesignerTasks,
  resetDesignerTaskFilters,
  renderDesignerTaskCards,
  renderDesignerTaskPage,
  renderDesignerTaskPage,
  openPublishedSubTaskDetail,
  openDeliveryHistory,
});

EMIE.registerModule('dashboardDesigner', {
  renderDesignerTasks,
  renderTaskMarket,
  filterDesignerTasks,
  resetDesignerTaskFilters,
  renderDesignerTaskCards,
  openPublishedSubTaskDetail,
  openDeliveryHistory,
});

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('designer-filter', () => filterDesignerTasks());
  registerEventAction('designer-reset', () => resetDesignerTaskFilters());
  registerEventAction('task-market-refresh', () => {
    const main = document.querySelector('#mainContent, main');
    if (main) renderTaskMarket(main, EMIE.state.currentRole, getCurrentUserId());
  });
  registerEventAction('designer-accept', (_event, el) => taskAccept(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-withdraw', (_event, el) => withdrawAcceptedTask(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-deliver', (_event, el) => taskDeliver(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-redeliver', (_event, el) => taskRedeliver(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-revision', (_event, el) => taskConfirmRevision(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-detail-close', () => closeM('publishedSubTaskDetailModal'));
  registerEventAction('designer-history-close', () => closeM('deliveryHistoryModal'));
  registerEventAction('designer-history', (_event, el) => openDeliveryHistory(Number(el.dataset.taskId)));
  registerEventAction('designer-detail-project', (_event, el) => openProjectDetail(Number(el.dataset.projectId)));
  registerEventAction('designer-approve', (_event, el) =>
    taskApprove(Number(el.dataset.projectId), Number(el.dataset.taskId), el.dataset.projectType));
  registerEventAction('designer-accept-market', (_event, el) => {
    let snapshot;
    try { snapshot = JSON.parse(el.dataset.taskSnapshot || '{}'); } catch (_) { snapshot = undefined; }
    taskAccept(Number(el.dataset.projectId), Number(el.dataset.taskId), snapshot);
  });
  registerEventAction('designer-correct', (_event, el) => taskCorrectDelivery(Number(el.dataset.projectId), Number(el.dataset.taskId)));
  registerEventAction('designer-detail', (_event, el) => openPublishedSubTaskDetail(Number(el.dataset.taskId)));
  registerEventAction('designer-page', (_event, el) => { EMIE.dashboardState.designerTaskPage = Number(el.dataset.page); renderDesignerTaskPage(); });
}
