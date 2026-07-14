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

async function renderDesignerTasks(main, uid) {
  // 企划可能作为子任务负责人而非项目负责人，需查全部项目
  const roleParam = EMIE.state.currentRole === 'planner' ? 'admin' : EMIE.state.currentRole;
  const orders = await apiGet(`/projects?role=${roleParam}&userId=${uid}`);
  let myTasks = [];
  const myId = uid;

  // 并行拉取所有项目详情
  const details = await Promise.all(
    orders.map(order => apiGet(`/projects/${order.id}`).catch(() => null))
  );

  for (const detail of details) {
    if (!detail || !detail.tasks) continue;
    for (const t of detail.tasks) {
      const isMine = t.designerId === myId;
      const isUnassigned = !t.designerId || t.designerId === '';
      if (isMine) {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30) });
      } else if (isUnassigned && t.status === 'pending') {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30), _unassigned: true });
      }
      if ((t.status === 'approved' || t.status === 'completed') && t.scoringRecords) {
        const needScore = t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        if (needScore && !myTasks.find(mt => mt.id === t.id)) {
          myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30) });
        }
      }
    }
  }

  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">🎨 我的子任务 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${myTasks.length})</span></h2>
    <div class="filter-bar">
      <select class="form-select" data-emie-onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskFilter">
        <option value="all">全部</option>
        <option value="unassigned">待认领</option>
        <option value="mine">我的任务</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." data-emie-oninput="filterDesignerTasks()" style="min-width:180px;" id="designerTaskSearch">
      <input type="date" class="form-input" id="designerTaskDateStart" data-emie-onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="designerTaskDateEnd" data-emie-onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期止">
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
  const q = document.getElementById('designerTaskSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('designerTaskDateStart')?.value;
  const dateEnd = document.getElementById('designerTaskDateEnd')?.value;
  let list = EMIE.dashboardState.designerTaskCache || [];

  if (filter === 'unassigned') list = list.filter(t => t._unassigned);
  else if (filter === 'mine') list = list.filter(t => !t._unassigned);

  if (q) list = list.filter(t => String(t.projectId).includes(q) || (t.name || '').toLowerCase().includes(q) || (t.projectName || '').toLowerCase().includes(q));

  if (dateStart || dateEnd) {
    list = list.filter(t => {
      if (!t.plannedDate) return !dateStart && !dateEnd;
      if (dateStart && t.plannedDate < dateStart) return false;
      if (dateEnd && t.plannedDate > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('designerTaskContainer');
  if (c) c.innerHTML = renderDesignerTaskCards(list);
}

function renderDesignerTaskCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无子任务</p></div>`;
  return `<div class="card">
      ${tasks.map(t => {
        const tsi = getTaskStatusInfo(t.status);
        const needScore = t.scoringRecords && t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} ${t.name}</div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">📁 项目 #${t.projectId}：${t.projectName}</div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 负责人：<strong>${t.designerName || '<span style="color:var(--warning);">待认领</span>'}</strong>${t.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${t.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : t.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : 'background:#FEF2F2;color:#DC2626;'}">${t.assigneeRole === 'supplychain' ? '供应链' : t.assigneeRole === 'planner' ? '企划' : '设计师'}</span>` : ''}</div>
            <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(t.plannedDate)}</strong></div>
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${t.details}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'rejected' ? '驳回意见' : '验收意见'}：${t.reviewComments}</div>` : ''}
          ${t.scoringRecords ? renderScoringMini(t) : ''}
          <div class="subtask-actions">
            ${t.status === 'pending' && !t._unassigned ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">✅ 接单</button>` : ''}
            ${t._unassigned ? `<button class="btn btn-success btn-sm" data-emie-onclick="taskAccept(${t.projectId},${t.id})">📋 认领并接单</button>` : ''}
            ${t.status === 'accepted' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskDeliver(${t.projectId},${t.id})">📤 交付成果</button>` : ''}
            ${t.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="taskRedeliver(${t.projectId},${t.id})">📤 重新交付</button>` : ''}
            ${needScore ? `<button class="btn btn-warning btn-sm" data-emie-onclick="openScoring(${t.projectId},${t.id})">⭐ 评分</button>` : ''}
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
  renderDesignerTaskCards,
  renderScoringMini,
});

EMIE.registerModule('dashboardDesigner', {
  renderDesignerTasks,
  filterDesignerTasks,
  renderDesignerTaskCards,
  renderScoringMini,
});
