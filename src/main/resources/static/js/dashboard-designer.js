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
const taskCancelReject = (...args) => EMIE.actions.taskCancelReject(...args);
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

  if (bucket === 'pending') myTasks = myTasks.filter(t => ['pending', 'delivered', 'submitted_for_review', 'planner_approved'].includes(t.status));
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
        <option value="planner_approved">待企划确认（历史）</option>
        <option value="sales_approved">销售已验收</option>
        <option value="admin_approved">管理员已验收</option>
        <option value="rejected">已驳回</option><option value="revision_requested">待修改</option>
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
  EMIE.dashboardState.designerTaskFilteredCache = myTasks;
  EMIE.dashboardState.designerTaskPage = 0;
  if (endpoint === '/projects/department-subtasks') renderDesignerTaskPage();
}

function renderDesignerTaskPage(list = EMIE.dashboardState.designerTaskFilteredCache || EMIE.dashboardState.designerTaskCache || []) {
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
    ? ['delivered', 'submitted_for_review', 'planner_approved'].includes(t.status)
    : t.status === status);
  if (projectType !== 'all') list = list.filter(t => t.projectType === projectType);

  if (q) list = list.filter(t => matchesSearchText(q, t.id, t.projectId, t.name, t.projectName, t.details, t.designerName));
  list = list.filter(t => isDateInRange(t.plannedDate, dateStart, dateEnd));
  EMIE.dashboardState.designerTaskFilteredCache = list;

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
    { icon: '🔄', label: '进行中', cls: 'task-group-active', open: true, statuses: ['accepted', 'rejected', 'revision_requested'] },
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
        const activeRejection = [...rejectionRecords].reverse().find(record => !record.cancelled && record.cycleId);
        const isRedelivering = t.status === 'accepted' && (t.pendingChangeBonusRequestId || rejectionRecords.some(record => !record.cancelled));
        const canCancelRejection = !readOnly && t.status === 'rejected' && activeRejection?.reviewerRole === EMIE.state.currentRole && (
          (EMIE.state.currentRole === 'planner' && String(t.plannerId || '') === String(getCurrentUserId()))
          || (EMIE.state.currentRole === 'sales' && t.projectType === 'channel_custom' && String(t.salesId || '') === String(getCurrentUserId()))
          || (EMIE.state.currentRole === 'admin' && t.projectType !== 'channel_custom')
        );
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
            ${latestDelivery?.submittedAt || t.submittedForReviewAt ? `<div class="subtask-meta-item">成果提交：<strong>${fmtDT(latestDelivery?.submittedAt || t.submittedForReviewAt)}</strong></div>` : ''}
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${renderTaskPointSummary(t)}
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${escHtml(t.details)}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'revision_requested' || t.pendingChangeBonusRequestId ? '修改意见' : t.status === 'rejected' ? '驳回意见' : '验收意见'}：${escHtml(t.reviewComments)}</div>` : ''}
          ${taskDetailFiles(t.pendingChangeReferenceImagesJson, true)}
          ${latestDelivery ? `<div style="margin-top:12px;padding:10px 12px;border:1px solid var(--gray-200);border-radius:8px;background:var(--gray-50);"><div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;"><strong style="font-size:13px;">${escHtml(taskDeliveryRounds(deliveryVersions, rejectionRecords).at(-1)?.label || '最新提交')} · 最新成果</strong><span style="font-size:11px;color:var(--gray-400);">${fmtDT(latestDelivery.submittedAt)}</span></div><div style="font-size:12px;color:var(--gray-700);white-space:pre-wrap;">${escHtml(latestDelivery.deliverables || '未填写文字交付内容')}</div>${taskDetailFiles(latestDelivery.referenceImagesJson, true)}${taskDetailFiles(latestDelivery.attachmentsJson, false)}</div>` : ''}
          <div class="subtask-actions">
            ${!readOnly && t.status === 'pending' && !t._unassigned && String(t.designerId || '') === String(getCurrentUserId()) ? `<button class="btn btn-primary btn-sm" data-emie-action="click:designer-accept" data-project-id="${t.projectId}" data-task-id="${t.id}">✅ 接单</button>` : ''}
            ${!readOnly && t._unassigned ? `<button class="btn btn-success btn-sm" data-emie-action="click:designer-accept-market" data-project-id="${t.projectId}" data-task-id="${t.id}" data-task-snapshot="${escHtml(JSON.stringify(t))}">⚡ 抢单</button>` : ''}
            ${!readOnly && t.status === 'accepted' && t.designerId === getCurrentUserId() ? (isRedelivering ? `<button class="btn btn-primary btn-sm" data-emie-action="click:designer-redeliver" data-project-id="${t.projectId}" data-task-id="${t.id}">📤 重新交付</button>` : `<button class="btn btn-warning btn-sm" data-emie-action="click:designer-withdraw" data-project-id="${t.projectId}" data-task-id="${t.id}">↩️ 退单</button><button class="btn btn-primary btn-sm" data-emie-action="click:designer-deliver" data-project-id="${t.projectId}" data-task-id="${t.id}">📤 交付成果</button>`) : ''}
            ${!readOnly && EMIE.state.currentRole === 'designer' && String(t.designerId || '') === String(getCurrentUserId()) && ['rejected', 'revision_requested'].includes(t.status) ? `<button class="btn btn-warning btn-sm" data-emie-action="click:designer-revision" data-project-id="${t.projectId}" data-task-id="${t.id}">🛠️ 确认修改</button>` : ''}
            ${canCancelRejection ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-cancel-reject" data-project-id="${t.projectId}" data-task-id="${t.id}" data-cycle-id="${activeRejection.cycleId}">↩️ 撤销驳回</button>` : ''}
        ${!readOnly && ['delivered', 'submitted_for_review'].includes(t.status) && t.designerId === getCurrentUserId() ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-correct" data-project-id="${t.projectId}" data-task-id="${t.id}">📝 更正当前交付</button>` : ''}
            ${plannerView && String(t.plannerId || '') === String(getCurrentUserId()) && (!t.assigneeRole || t.assigneeRole === 'designer') && t.status === 'completed' ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-request-change" data-project-id="${t.projectId}" data-task-id="${t.id}">修改</button>` : ''}
            ${t.status !== 'completed' ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-detail" data-task-id="${t.id}">查看子任务详情${modificationCount ? `（${modificationCount}）` : ''}</button>` : ''}
            ${t.status !== 'completed' ? `<button class="btn btn-outline btn-sm" data-emie-action="click:designer-detail-project" data-project-id="${t.projectId}">查看项目</button>` : ''}
          </div>
        </div>`;
      }).join('')}
    </div>`;
}

function taskDetailFiles(json, images) {
  const renderer = images ? EMIE.actions.renderSubTaskImages : EMIE.actions.renderTaskAttachments;
  return typeof renderer === 'function' && json ? renderer(json) : '';
}

function rejectionPointInfo(record) {
  if (record.withPoints === true) {
    return `本次加分：${record.pointRuleDescription || record.pointRuleCode || '积分规则'}（${record.pointRuleCode || '未知编号'}） · ${Number(record.points || 0)} 分`;
  }
  return record.withPoints === false ? '本次不加分' : '';
}

function deliveryPointNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? String(Math.round((number + Number.EPSILON) * 100) / 100) : '0';
}

function deliveryPointAmount(value) {
  return `${Number(value) > 0 ? '+' : ''}${deliveryPointNumber(value)} 分`;
}

function taskDeliveryRounds(versions = [], rejectionRecords = []) {
  const chronological = [...versions].reverse();
  if (chronological.every(version => version.versionNo != null)) {
    chronological.sort((left, right) => Number(left.versionNo) - Number(right.versionNo));
  }
  const rounds = [];
  chronological.forEach(version => {
    if (version.submissionType !== 'correction' || !rounds.length) rounds.push({ versions: [] });
    rounds[rounds.length - 1].versions.push(version);
  });
  rounds.forEach((round, index) => {
    round.first = round.versions[0];
    round.current = round.versions[round.versions.length - 1];
    round.confirmation = [...round.versions].reverse().find(version => version.confirmedAt) || round.current;
    round.revisionNo = round.versions.find(version => Number(version.revisionNo) > 0)?.revisionNo;
    round.origin = [...round.versions].reverse().find(version => ['initial', 'revision', 'rejection'].includes(version.submissionOrigin))?.submissionOrigin
      || (round.revisionNo ? 'revision' : round.first.submissionType === 'initial' ? 'initial'
        : round.first.submissionType === 'redelivery' ? 'rejection' : 'unknown');
    const requestId = round.current.pointRequestId || round.first.pointRequestId;
    const previousTime = rounds[index - 1]?.current.submittedAt;
    const nextTime = rounds[index + 1]?.versions[0].submittedAt;
    const timestamp = value => value ? Date.parse(String(value).replace(' ', 'T')) : NaN;
    const cycleId = round.current.rejectionCycleId || round.first.rejectionCycleId;
    const activeRecords = rejectionRecords.filter(record => !record.cancelled);
    // Link by request or time bounds, never by a rejection count or delivery array position.
    round.requirement = activeRecords.find(record => cycleId && String(record.cycleId) === String(cycleId))
      || activeRecords.find(record => requestId && record.pointRequestId === requestId)
      || (round.origin === 'rejection' ? [...activeRecords].reverse().find(record =>
        timestamp(record.reviewedAt) <= timestamp(round.first.submittedAt)
        && (!previousTime || timestamp(record.reviewedAt) >= timestamp(previousTime))) : null);
    round.rejection = activeRecords.find(record => timestamp(record.reviewedAt) >= timestamp(round.current.submittedAt)
      && (!nextTime || timestamp(record.reviewedAt) <= timestamp(nextTime)));
    const awarded = round.versions.filter(version => version.pointStatus === 'awarded');
    round.awardedPoints = awarded.reduce((total, version) => total + Number(version.awardedPoints || 0), 0);
    round.pointStatus = awarded.length ? 'awarded' : round.current.pointStatus || 'unrecorded';
    round.label = round.origin === 'revision' && round.revisionNo ? `第 ${round.revisionNo} 次修改`
      : round.origin === 'initial' ? '首次交付'
      : round.origin === 'rejection' ? '驳回后重交'
      : round.origin === 'revision' ? '修改后提交' : '历史交付';
  });
  return rounds;
}

function deliveryRoundStatus(round) {
  if (round.pointStatus === 'awarded') return `已入账 ${deliveryPointAmount(round.awardedPoints)}`;
  if (round.rejection && !round.confirmation.confirmedAt) return round.pointStatus === 'no_points' ? '已驳回 · 本次无积分' : '已驳回 · 未入账';
  if (round.pointStatus === 'no_points') return round.confirmation.confirmedAt ? '本次无积分' : '待验收 · 本次无积分';
  if (round.pointStatus === 'missing') return '已验收 · 未查到入账';
  if (round.pointStatus === 'unrecorded') return '历史积分未记录';
  const expected = round.current.expectedPoints;
  return expected == null ? '待验收 · 分数未记录'
    : Number(expected) === 0 && !round.current.pointRuleCode ? '待验收 · 本次无积分'
    : `待验收 · 预计 ${deliveryPointAmount(expected)}`;
}

function deliveryConfirmationText(version) {
  if (!version.confirmedAt) return '';
  const name = version.confirmedByName || version.confirmedBy || '确认人未记录';
  return `${name} · ${fmtDT(version.confirmedAt)}`;
}

function renderTaskPointSummary(task, options = {}) {
  const summary = task.pointSummary;
  const rounds = taskDeliveryRounds(task.deliveryVersions || [], task.rejectionRecords || []);
  const latest = rounds[rounds.length - 1];
  const pendingRequest = task.pendingChangeBonusRequestId;
  const pendingDelivery = latest && ['pending', 'no_points'].includes(latest.pointStatus)
    && !latest.confirmation.confirmedAt && !latest.rejection;
  const pendingSubmitted = pendingRequest && latest?.current.pointRequestId === pendingRequest && pendingDelivery;
  let pending = '';
  if (pendingRequest) {
    const revisionNo = pendingSubmitted ? latest.revisionNo : null;
    const label = Number(revisionNo) > 0 ? `第 ${revisionNo} 次修改` : '本次修改';
    const expected = pendingSubmitted ? latest.current.expectedPoints : task.pendingChangeBonusPoints;
    const points = task.pendingChangeRuleCode || (pendingSubmitted && latest.current.pointRuleCode)
      ? (expected == null ? '积分待确认' : `预计 ${deliveryPointAmount(expected)}`) : '本次无积分';
    const stage = pendingSubmitted ? '待验收' : task.status === 'revision_requested' || task.status === 'rejected' ? '待修改' : '修改中';
    pending = `${label} · ${stage} · ${points}`;
  } else if (pendingDelivery) {
    pending = `${latest.label} · ${deliveryRoundStatus(latest)}`;
  } else if (!rounds.length && task.basePointSnapshot != null) {
    pending = `预计基础积分 ${deliveryPointNumber(task.basePointSnapshot)} 分 · 验收后入账`;
  }
  const breakdown = summary ? [
    `首次 ${deliveryPointNumber(summary.basePoints)} 分`,
    `修改 ${deliveryPointNumber(summary.changePoints)} 分`,
    ...(Number(summary.otherPoints) ? [`其他 ${deliveryPointNumber(summary.otherPoints)} 分`] : []),
  ].join(' + ') : '';
  return `<div class="task-point-summary"${summary ? ` data-awarded-points="${deliveryPointNumber(summary.awardedPoints)}"` : ''}>
    <div class="task-point-total"><span>累计已入账</span><strong>${summary ? `${deliveryPointNumber(summary.awardedPoints)}<small> 分</small>` : '记录待补充'}</strong>${rounds.length && options.historyLink !== false ? `<button type="button" class="task-point-history-link" data-emie-action="click:designer-history" data-task-id="${task.id}">交付与积分记录 ›</button>` : ''}</div>
    ${breakdown ? `<div class="task-point-breakdown">${escHtml(breakdown)}</div>` : ''}
    ${pending ? `<div class="task-point-pending">${escHtml(pending)}</div>` : latest ? `<div class="task-point-latest">最近：${escHtml(latest.label)} · ${escHtml(deliveryRoundStatus(latest))}</div>` : ''}
    ${summary?.hasUntrackedAwards ? `<div class="task-point-note">其中历史未关联 ${deliveryPointNumber(summary.untrackedPoints)} 分，暂无法对应具体交付。</div>` : ''}
  </div>`;
}

function renderDeliveryRounds(versions, rejectionRecords = [], task = {}) {
  const rounds = taskDeliveryRounds(versions, rejectionRecords);
  return rounds.length ? `<div class="delivery-round-list">${[...rounds].reverse().map((round, index) => {
    const current = round.current;
    const confirmation = round.confirmation;
    const history = round.versions.slice(0, -1).reverse();
    const requirement = current.changeReason || round.first.changeReason || current.rejectionReason
      || round.first.rejectionReason || round.requirement?.reason;
    const rule = current.pointRuleDescription || current.pointRuleCode;
    const awaitingConfirmation = ['pending', 'no_points'].includes(round.pointStatus) && !confirmation.confirmedAt;
    const confirmationText = deliveryConfirmationText(confirmation);
    return `<details class="delivery-round" data-delivery-round="${escHtml(String(round.first.versionNo || round.first.id || index))}" data-point-status="${escHtml(round.pointStatus)}"${index === 0 ? ' open' : ''}>
      <summary class="delivery-round-summary">
        <div class="delivery-round-heading"><strong>${escHtml(round.label)}</strong><span class="delivery-point-status is-${escHtml(round.pointStatus)}">${escHtml(deliveryRoundStatus(round))}</span>${history.length ? `<span class="delivery-correction-count">含 ${history.length} 次更正</span>` : ''}</div>
        <div class="delivery-round-meta"><span>提交 ${current.submittedAt ? fmtDT(current.submittedAt) : '时间未记录'}</span>${confirmationText ? `<span>确认 ${escHtml(confirmationText)}</span>` : ''}</div>
      </summary>
      <div class="delivery-round-body">
        ${requirement ? `<section class="delivery-round-step"><h4>修改要求</h4><p>${escHtml(requirement)}</p>${taskDetailFiles(current.changeReferenceImagesJson || round.first.changeReferenceImagesJson || round.requirement?.rejectionReferenceImagesJson, true)}${taskDetailFiles(round.requirement?.rejectionAttachmentsJson, false)}</section>` : ''}
        <section class="delivery-round-step"><h4>本轮成果</h4><div class="delivery-step-meta">${escHtml(current.submittedByName || task.designerName || '提交人未记录')} · ${current.submittedAt ? fmtDT(current.submittedAt) : '时间未记录'}</div><p>${escHtml(current.deliverables || '未填写文字交付内容')}</p>${taskDetailFiles(current.referenceImagesJson, true)}${taskDetailFiles(current.attachmentsJson, false)}</section>
        <section class="delivery-round-step"><h4>验收记录</h4><div class="delivery-step-meta">${confirmationText ? escHtml(confirmationText) : round.rejection ? '已驳回，待重新交付' : awaitingConfirmation ? '等待企划验收' : '历史验收信息未记录'}</div>${confirmation.reviewComments ? `<p>${escHtml(confirmation.reviewComments)}</p>` : ''}${round.rejection ? `<p class="delivery-rejection-reason">驳回意见：${escHtml(round.rejection.reason || '未填写')}</p>` : ''}</section>
        <section class="delivery-round-step"><h4>积分入账</h4><strong class="delivery-point-status is-${escHtml(round.pointStatus)}">${escHtml(deliveryRoundStatus(round))}</strong>${rule ? `<div class="delivery-step-meta">规则：${escHtml(rule)}${current.pointRuleCode && current.pointRuleDescription ? `（${escHtml(current.pointRuleCode)}）` : ''}</div>` : ''}${confirmation.awardedAt ? `<div class="delivery-step-meta">入账时间 ${fmtDT(confirmation.awardedAt)}</div>` : ''}${round.pointStatus === 'missing' ? '<p class="task-point-note">本轮已有验收记录，未查到关联积分流水，未计入本轮已入账。</p>' : ''}</section>
        ${history.length ? `<details class="delivery-correction-history"><summary>查看本轮更正留痕（${history.length}）</summary>${history.map(version => `<div class="delivery-correction-item"><div class="delivery-step-meta">${escHtml(version.changeSummary || '原交付记录')} · ${version.submittedAt ? fmtDT(version.submittedAt) : ''}</div><p>${escHtml(version.deliverables || '未填写文字交付内容')}</p>${taskDetailFiles(version.referenceImagesJson, true)}${taskDetailFiles(version.attachmentsJson, false)}</div>`).join('')}</details>` : ''}
      </div>
    </details>`;
  }).join('')}</div>` : '';
}

function openDeliveryHistory(taskId) {
  if (document.getElementById('deliveryHistoryModal')) return;
  const projectTask = EMIE.projectState.currentProjectDetail?.tasks?.find(item => Number(item.id) === Number(taskId));
  const dashboardTask = (EMIE.dashboardState.designerTaskCache || []).find(item => Number(item.id) === Number(taskId));
  const task = document.getElementById('projectDetailModal') ? projectTask || dashboardTask : dashboardTask || projectTask;
  if (!task) return;
  const versions = Array.isArray(task.deliveryVersions) ? task.deliveryVersions : [];
  const records = Array.isArray(task.rejectionRecords) ? task.rejectionRecords : [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'deliveryHistoryModal';
  modal.innerHTML = `<div class="modal delivery-history-modal"><div class="modal-header"><button class="modal-close" data-emie-action="click:designer-history-close">✕</button><div class="modal-header-left"><div class="modal-title">交付与积分记录 · ${escHtml(task.name || '-')}</div><div class="delivery-history-hint">每次交付、修改要求和实际入账分别留痕；主动更正收在对应交付内</div></div></div><div class="modal-body">${renderTaskPointSummary(task, { historyLink: false })}${renderDeliveryRounds(versions, records, task) || '<div class="empty"><p>暂无交付记录</p></div>'}</div><div class="modal-footer"><button class="btn btn-primary" data-emie-action="click:designer-history-close">关闭</button></div></div>`;
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
  modal.dataset.assistantProjectId = String(task.projectId);
  modal.dataset.assistantTaskId = String(task.id);
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
          ${task.deliveryVersions?.[0]?.submittedAt || task.submittedForReviewAt ? `<div class="detail-item"><div class="detail-label">成果提交时间</div><div class="detail-value">${fmtDT(task.deliveryVersions?.[0]?.submittedAt || task.submittedForReviewAt)}</div></div>` : ''}
        </div>
        ${renderTaskPointSummary(task)}
        <div class="detail-item" style="margin-top:12px;"><div class="detail-label">任务要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.details || '未填写')}</div></div>
        ${taskDetailFiles(task.referenceImagesJson, true)}
        ${taskDetailFiles(task.attachmentsJson, false)}
        ${task.deliverables ? `<div class="detail-item" style="margin-top:12px;"><div class="detail-label">当前交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
        ${deliveryVersions.length ? `<div class="delivery-section-heading">交付与积分记录</div>${renderDeliveryRounds(deliveryVersions, records, task)}` : ''}
        <details class="delivery-all-requirements"><summary>全部修改要求（含驳回及取消记录）</summary>
        <div style="margin-top:18px;">
          <div style="font-size:14px;font-weight:700;margin-bottom:8px;">修改要求记录 <span style="color:var(--gray-400);font-weight:400;">(${records.length})</span></div>
          ${records.length ? records.map(record => `
            <details style="border:1px solid #F3C1C1;border-radius:9px;margin-bottom:8px;overflow:hidden;">
              <summary style="display:flex;align-items:center;gap:8px;padding:10px 12px;background:#FFF8F8;cursor:pointer;list-style:none;">
                <strong style="color:#A32D2D;white-space:nowrap;">第 ${record.attemptNo} 次修改要求</strong>
                <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--gray-600);">${escHtml(record.reason || '未填写修改意见')}</span>
                ${rejectionPointInfo(record) ? `<span style="font-size:11px;color:var(--gray-500);white-space:nowrap;">${escHtml(rejectionPointInfo(record))}</span>` : ''}
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
                  ${rejectionPointInfo(record) ? `<div class="detail-label" style="margin-top:8px;color:var(--gray-600);">${escHtml(rejectionPointInfo(record))}</div>` : ''}
                  ${taskDetailFiles(record.rejectionReferenceImagesJson, true)}
                  ${taskDetailFiles(record.rejectionAttachmentsJson, false)}
                </div>
              </div>
            </details>`).join('') : '<div class="empty" style="padding:24px;"><p>暂无修改要求记录</p></div>'}
        </div>
        </details>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-action="click:designer-detail-project" data-project-id="${task.projectId}">查看所属项目</button>
        ${deliveryVersions.length ? `<button class="btn btn-outline" data-emie-action="click:designer-history" data-task-id="${task.id}">交付与积分记录</button>` : ''}
        ${EMIE.state.currentRole === 'designer' && task.status === 'pending' ? `<button class="btn btn-primary" data-emie-action="click:designer-accept" data-project-id="${task.projectId}" data-task-id="${task.id}">✅ 接单</button>` : ''}
        ${EMIE.state.currentRole === 'planner' && ['delivered', 'submitted_for_review', 'planner_approved'].includes(task.status) ? `<button class="btn btn-success" data-emie-action="click:designer-approve" data-project-id="${task.projectId}" data-task-id="${task.id}" data-project-type="${escHtml(task.projectType || 'regular')}">✅ 验收通过</button>` : ''}
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
  renderTaskPointSummary,
  renderDeliveryRounds,
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
  registerEventAction('designer-cancel-reject', (_event, el) => taskCancelReject(Number(el.dataset.projectId), Number(el.dataset.taskId), Number(el.dataset.cycleId)));
  registerEventAction('designer-detail-close', () => closeM('publishedSubTaskDetailModal'));
  registerEventAction('designer-history-close', () => closeM('deliveryHistoryModal'));
  registerEventAction('designer-request-change', (_event, el) => EMIE.actions.openTaskChangeBonus(Number(el.dataset.projectId), Number(el.dataset.taskId)));
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
