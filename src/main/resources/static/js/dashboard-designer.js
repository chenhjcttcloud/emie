const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const taskAccept = (...args) => EMIE.actions.taskAccept(...args);
const taskDeliver = (...args) => EMIE.actions.taskDeliver(...args);
const taskRedeliver = (...args) => EMIE.actions.taskRedeliver(...args);
const openScoring = (...args) => EMIE.actions.openScoring(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const matchesSearchText = (...args) => EMIE.actions.matchesSearchText(...args);
const isDateInRange = (...args) => EMIE.actions.isDateInRange(...args);

async function renderDesignerTasks(main, uid, bucket = 'all', role = EMIE.state.currentRole,
                                   endpoint = '/projects/my-subtasks', readOnly = false) {
  let myTasks = (await apiGet(endpoint)).map(task => ({
    ...task,
    projectName: (task.projectName || '').substring(0, 30),
    _unassigned: !task.designerId
  }));

  if (bucket === 'pending') myTasks = myTasks.filter(t => !['approved', 'completed'].includes(t.status));
  if (bucket === 'completed') myTasks = myTasks.filter(t => ['approved', 'completed'].includes(t.status));
  myTasks.sort((a, b) => {
    const done = task => ['approved', 'completed'].includes(task.status);
    const overdue = task => !done(task) && task.plannedDate && task.plannedDate < new Date().toISOString().slice(0, 10);
    const rank = task => overdue(task) ? 0 : (task.status === 'rejected' ? 1 : (task.status === 'delivered' ? 2 : (done(task) ? 4 : 3)));
    return rank(a) - rank(b) || (a.plannedDate || '9999-12-31').localeCompare(b.plannedDate || '9999-12-31') || (b.id - a.id);
  });

  EMIE.dashboardState.designerTasksReadOnly = readOnly;
  const taskEmoji = role === 'promotion' ? '📣' : role === 'supplychain' ? '🛒' : role === 'planner' ? '📋' : '🎨';
  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">${taskEmoji} ${bucket === 'pending' ? '待处理子任务' : bucket === 'completed' ? '已完成子任务' : '我的子任务'} <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${myTasks.length})</span></h2>
    <div class="filter-bar">
      ${bucket !== 'completed' ? `<select class="form-select" data-emie-onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskFilter" aria-label="任务归属">
        <option value="all">全部任务</option>
        <option value="unassigned">待认领</option>
        <option value="mine">我的任务</option>
      </select>` : ''}
      <select class="form-select" data-emie-onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskStatusFilter">
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
      </select>
      <select class="form-select" data-emie-onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskTypeFilter">
        <option value="all">全部项目类型</option>
        <option value="channel_custom">渠道定制单</option>
        <option value="regular">公司常规品</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." data-emie-oninput="filterDesignerTasks()" style="min-width:180px;" id="designerTaskSearch">
      <input type="date" class="form-input" id="designerTaskDateStart" data-emie-onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="designerTaskDateEnd" data-emie-onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期止">
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetDesignerTaskFilters()">↺ 重置</button>
    </div>
    <div id="designerTaskContainer">${renderDesignerTaskCards(myTasks, readOnly)}</div>
  `;
  EMIE.dashboardState.designerTaskCache = myTasks;
}

function filterDesignerTasks() {
  clearTimeout(filterDesignerTasks._timer);
  filterDesignerTasks._timer = setTimeout(applyFilterDesignerTasks, 100);
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
  if (status !== 'all') list = list.filter(t => t.status === status);
  if (projectType !== 'all') list = list.filter(t => t.projectType === projectType);

  if (q) list = list.filter(t => matchesSearchText(q, t.id, t.projectId, t.name, t.projectName, t.details, t.designerName));
  list = list.filter(t => isDateInRange(t.plannedDate, dateStart, dateEnd));

  const c = document.getElementById('designerTaskContainer');
  if (c) c.innerHTML = renderDesignerTaskCards(list, EMIE.dashboardState.designerTasksReadOnly === true);
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
  return `<div class="subtask-list">
      ${tasks.map(t => {
        const tsi = getTaskStatusInfo(t.status);
        const needScore = t.scoringRecords && t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        const modificationCount = Array.isArray(t.rejectionRecords) ? t.rejectionRecords.length : 0;
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} 子任务：${escHtml(t.name || '-')} <span class="subtask-project-inline">（所属项目：${escHtml(t.projectName || '未命名项目')}）</span> <span style="font-size:11px;color:var(--gray-400);font-weight:400;">#${t.id}</span></div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 负责人：<strong>${t.designerName || '<span style="color:var(--warning);">待认领</span>'}</strong>${t.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${t.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : t.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : t.assigneeRole === 'promotion' ? 'background:#F5F3FF;color:#7C3AED;' : t.assigneeRole === 'sales' ? 'background:#FFF7ED;color:#C2410C;' : 'background:#FEF2F2;color:#DC2626;'}">${t.assigneeRole === 'supplychain' ? '供应链' : t.assigneeRole === 'planner' ? '企划' : t.assigneeRole === 'promotion' ? '产品推广' : t.assigneeRole === 'sales' ? '销售' : '设计师'}</span>` : ''}</div>
            ${t.relation ? `<div class="subtask-meta-item">🔗 我的关系：<strong>${t.relation === 'publisher' ? '我发布的任务' : '我负责的任务'}</strong></div>` : ''}
            <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(t.plannedDate)}</strong></div>
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${escHtml(t.details)}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'rejected' ? '驳回意见' : '验收意见'}：${escHtml(t.reviewComments)}</div>` : ''}
          ${modificationCount ? `<div style="margin-top:10px;font-size:12px;color:#A32D2D;">↩ 有 ${modificationCount} 次修改要求记录，可在子任务详情中查看</div>` : ''}
          ${t.scoringRecords ? renderScoringMini(t) : ''}
          <div class="subtask-actions">
            ${!readOnly && t.status === 'pending' && !t._unassigned ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">✅ 接单</button>` : ''}
            ${!readOnly && t._unassigned ? `<button class="btn btn-success btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">📋 认领并接单</button>` : ''}
            ${!readOnly && t.status === 'accepted' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskDeliver(${t.projectId},${t.id})">📤 交付成果</button>` : ''}
            ${!readOnly && t.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="taskRedeliver(${t.projectId},${t.id})">📤 重新交付</button>` : ''}
            ${!readOnly && ['delivered', 'planner_approved', 'sales_approved', 'admin_approved'].includes(t.status) && t.designerId === getCurrentUserId() ? `<button class="btn btn-outline btn-sm" data-emie-onclick="taskCorrectDelivery(${t.projectId},${t.id})">📝 修正交付</button>` : ''}
            ${!readOnly && needScore ? `<button class="btn btn-warning btn-sm" data-emie-onclick="openScoring(${t.projectId},${t.id})">⭐ 评分</button>` : ''}
            <button class="btn btn-outline btn-sm" data-emie-onclick="openPublishedSubTaskDetail(${t.id})">查看子任务详情${modificationCount ? `（${modificationCount}）` : ''}</button>
            <button class="btn btn-outline btn-sm" data-emie-onclick="openProjectDetail(${t.projectId})">查看项目</button>
          </div>
        </div>`;
      }).join('')}
    </div>`;
}

function taskDetailFiles(json, images) {
  const renderer = images ? EMIE.actions.renderSubTaskImages : EMIE.actions.renderTaskAttachments;
  return typeof renderer === 'function' && json ? renderer(json) : '';
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
        <button class="modal-close" data-emie-onclick="closeM('publishedSubTaskDetailModal')">✕</button>
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
        </div>
        <div class="detail-item" style="margin-top:12px;"><div class="detail-label">任务要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.details || '未填写')}</div></div>
        ${taskDetailFiles(task.referenceImagesJson, true)}
        ${taskDetailFiles(task.attachmentsJson, false)}
        ${task.deliverables ? `<div class="detail-item" style="margin-top:12px;"><div class="detail-label">当前交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
        <div style="margin-top:18px;">
          <div style="font-size:14px;font-weight:700;margin-bottom:8px;">交付版本 <span style="color:var(--gray-400);font-weight:400;">(${deliveryVersions.length})</span></div>
          ${deliveryVersions.length ? deliveryVersions.map(version => `
            <details style="border:1px solid var(--gray-200);border-radius:9px;margin-bottom:8px;overflow:hidden;">
              <summary style="padding:10px 12px;background:var(--gray-50);cursor:pointer;list-style:none;">
                <strong>V${version.versionNo}</strong> · ${version.submissionType === 'correction' ? '主动修正' : version.submissionType === 'redelivery' ? '驳回后重交' : '首次交付'}
                <span style="margin-left:8px;color:var(--gray-500);">${escHtml(version.changeSummary || '')}</span>
              </summary>
              <div style="padding:12px;"><div class="detail-value" style="white-space:pre-wrap;">${escHtml(version.deliverables || '未填写文字交付内容')}</div>${taskDetailFiles(version.referenceImagesJson, true)}${taskDetailFiles(version.attachmentsJson, false)}</div>
            </details>`).join('') : '<div style="font-size:12px;color:var(--gray-400);">暂无交付版本记录</div>'}
        </div>
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
        <button class="btn btn-outline" data-emie-onclick="openProjectDetail(${task.projectId})">查看所属项目</button>
        <button class="btn btn-primary" data-emie-onclick="closeM('publishedSubTaskDetailModal')">关闭</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

function renderScoringMini(task, isDone) {
  if (!task.scoringRecords || !task.scoringRecords.length) return '';
  const records = task.scoringRecords;
      const allScored = records.filter(r => r.score != null).length;
  let ta = 0, tw = 0;
  records.forEach(r => {
    if (r.score != null) {
      ta += r.score * r.weight;
      tw += r.weight;
    }
  });
  const overall = tw > 0 ? (ta / tw).toFixed(0) : null;

  if (isDone) {
    return `<div style="margin-top:10px;padding:12px;background:#DCFCE7;border-radius:8px;border:1px solid #86EFAC;">
      <div style="font-size:12px;font-weight:600;color:#166534;margin-bottom:6px;">⭐ 评分 (${allScored}/${records.length}人)</div>
      <div style="display:flex;gap:12px;flex-wrap:wrap;font-size:11px;">
        ${records.map(r => `<span style="background:#fff;padding:2px 8px;border-radius:4px;">${roleLabel(r.role)}: ${r.score != null ? `✅ ${r.score}分` : '<span style="color:var(--gray-400);">⏳ 待评</span>'}</span>`).join('')}
      </div>
      ${overall ? `<div style="margin-top:8px;text-align:center;"><span style="font-size:12px;color:#15803D;">加权综合：</span><span style="font-size:24px;font-weight:700;color:#16A34A;">${overall}分</span></div>` : ''}
    </div>`;
  }

  return `<div style="margin-top:10px;padding:12px;background:var(--primary-light);border-radius:8px;">
    <div style="font-size:12px;font-weight:600;color:var(--primary);margin-bottom:6px;">⭐ 评分 (${allScored}/${records.length}人)</div>
    <div style="display:flex;gap:12px;flex-wrap:wrap;font-size:11px;">
      ${records.map(r => `<span style="background:#fff;padding:2px 8px;border-radius:4px;">${roleLabel(r.role)}: ${r.score != null ? `✅ ${r.score}分` : '<span style="color:var(--gray-400);">⏳ 待评</span>'}</span>`).join('')}
    </div>
    ${overall ? `<div style="margin-top:8px;text-align:center;"><span style="font-size:12px;color:var(--gray-500);">加权综合：</span><span style="font-size:24px;font-weight:700;color:var(--primary);">${overall}分</span></div>` : ''}
  </div>`;
}



EMIE.registerActions({
  renderDesignerTasks,
  filterDesignerTasks,
  applyFilterDesignerTasks,
  resetDesignerTaskFilters,
  renderDesignerTaskCards,
  openPublishedSubTaskDetail,
  renderScoringMini,
});

EMIE.registerModule('dashboardDesigner', {
  renderDesignerTasks,
  filterDesignerTasks,
  resetDesignerTaskFilters,
  renderDesignerTaskCards,
  openPublishedSubTaskDetail,
  renderScoringMini,
});
