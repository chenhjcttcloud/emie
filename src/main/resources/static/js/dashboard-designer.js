const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
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

  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">🎨 ${bucket === 'pending' ? '待处理子任务' : bucket === 'completed' ? '已完成子任务' : '我的子任务'} <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${myTasks.length})</span></h2>
    <div class="filter-bar">
      <select class="form-select" data-emie-onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskFilter">
        <option value="all">全部</option>
        <option value="unassigned">待认领</option>
        <option value="mine">我的任务</option>
      </select>
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
    <div id="designerTaskContainer">${renderDesignerTaskCards(myTasks)}</div>
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
  if (c) c.innerHTML = renderDesignerTaskCards(list);
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

function renderDesignerTaskCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无子任务</p></div>`;
  return `<div class="subtask-list">
      ${tasks.map(t => {
        const tsi = getTaskStatusInfo(t.status);
        const needScore = t.scoringRecords && t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} 子任务：${escHtml(t.name || '-')} <span style="font-size:11px;color:var(--gray-400);font-weight:400;">#${t.id}</span></div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div style="font-size:12px;color:var(--gray-500);margin-bottom:8px;padding:6px 8px;background:var(--gray-50);border-radius:6px;">所属项目：#${t.projectId} ${escHtml(t.projectName || '-')}</div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 负责人：<strong>${t.designerName || '<span style="color:var(--warning);">待认领</span>'}</strong>${t.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${t.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : t.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : 'background:#FEF2F2;color:#DC2626;'}">${t.assigneeRole === 'supplychain' ? '供应链' : t.assigneeRole === 'planner' ? '企划' : '设计师'}</span>` : ''}</div>
            ${t.relation ? `<div class="subtask-meta-item">🔗 我的关系：<strong>${t.relation === 'publisher' ? '我发布的任务' : '我负责的任务'}</strong></div>` : ''}
            <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(t.plannedDate)}</strong></div>
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${escHtml(t.details)}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'rejected' ? '驳回意见' : '验收意见'}：${escHtml(t.reviewComments)}</div>` : ''}
          ${t.scoringRecords ? renderScoringMini(t) : ''}
          <div class="subtask-actions">
            ${!readOnly && t.status === 'pending' && !t._unassigned ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">✅ 接单</button>` : ''}
            ${!readOnly && t._unassigned ? `<button class="btn btn-success btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">📋 认领并接单</button>` : ''}
            ${!readOnly && t.status === 'accepted' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskDeliver(${t.projectId},${t.id})">📤 交付成果</button>` : ''}
            ${!readOnly && t.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="taskRedeliver(${t.projectId},${t.id})">📤 重新交付</button>` : ''}
            ${!readOnly && needScore ? `<button class="btn btn-warning btn-sm" data-emie-onclick="openScoring(${t.projectId},${t.id})">⭐ 评分</button>` : ''}
            <button class="btn btn-outline btn-sm" data-emie-onclick="openProjectDetail(${t.projectId})">查看项目</button>
          </div>
        </div>`;
      }).join('')}
    </div>`;
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
  renderScoringMini,
});

EMIE.registerModule('dashboardDesigner', {
  renderDesignerTasks,
  filterDesignerTasks,
  resetDesignerTaskFilters,
  renderDesignerTaskCards,
  renderScoringMini,
});
