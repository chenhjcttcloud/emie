const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const swrFetch = (...args) => EMIE.actions.swrFetch(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const openScoring = (...args) => EMIE.actions.openScoring(...args);

async function renderScoringView(main, role, uid) {
  // 使用专用聚合端点（1 次 API 替代 N+1 次）
  const pendingItems = await swrFetch(`scoring_${role}_${uid}`,
    () => apiGet(`/scoring/pending?role=${role}&userId=${uid}`),
    15000
  );
  let pendingTasks = [];

  for (const item of pendingItems) {
    const t = {
      id: item.taskId,
      name: item.taskName,
      status: item.taskStatus,
      projectId: item.projectId,
      projectType: item.projectType,
      projectName: item.projectName,
      plannedDate: item.plannedDate,
      designerId: item.designerId,
      designerName: item.designerName,
      selfScore: item.selfScore,
      selfAesthetics: item.selfAesthetics,
      selfInnovation: item.selfInnovation,
      scoringRecords: item.scoringRecords || [],
      isPending: !!item.isPending,
    };
    pendingTasks.push(t);
  }

  // 待评分在前，已评分在后
  pendingTasks.sort((a, b) => {
    if (a.isPending && !b.isPending) return -1;
    if (!a.isPending && b.isPending) return 1;
    return 0;
  });

  const pendingCount = pendingTasks.filter(t => t.isPending).length;
  const doneCount = pendingTasks.filter(t => !t.isPending).length;

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">⭐ 评分中心 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">待评分 ${pendingCount} / 已评分 ${doneCount}</span></h2>
    </div>
    <div class="filter-bar">
      <select class="form-select" data-emie-onchange="filterScoringView()" style="min-width:120px;" id="scoringFilter">
        <option value="all">全部</option>
        <option value="pending">待评分</option>
        <option value="done">已评分</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." data-emie-oninput="filterScoringView()" style="min-width:180px;" id="scoringSearch">
      <input type="date" class="form-input" id="scoringDateStart" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="scoringDateEnd" style="min-width:130px;" title="计划完成日期止">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterScoringView()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetScoringFilters()">↺ 重置</button>
    </div>
    <div id="scoringContainer">${renderScoringCards(pendingTasks)}</div>
  `;
  EMIE.dashboardState.scoringCache = pendingTasks;
}

function filterScoringView() {
  clearTimeout(filterScoringView._timer);
  filterScoringView._timer = setTimeout(applyFilterScoringView, 100);
}

function applyFilterScoringView() {
  const filter = document.getElementById('scoringFilter')?.value || 'all';
  const q = document.getElementById('scoringSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('scoringDateStart')?.value;
  const dateEnd = document.getElementById('scoringDateEnd')?.value;
  let list = EMIE.dashboardState.scoringCache || [];

  if (filter === 'pending') list = list.filter(t => t.isPending);
  else if (filter === 'done') list = list.filter(t => !t.isPending);

  if (q) list = list.filter(t => String(t.projectId).includes(q) || (t.name || '').toLowerCase().includes(q) || (t.projectName || '').toLowerCase().includes(q));

  if (dateStart || dateEnd) {
    list = list.filter(t => {
      if (!t.plannedDate) return !dateStart && !dateEnd;
      if (dateStart && t.plannedDate < dateStart) return false;
      if (dateEnd && t.plannedDate > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('scoringContainer');
  if (c) c.innerHTML = renderScoringCards(list);
}

function resetScoringFilters() {
  const filterEl = document.getElementById('scoringFilter');
  const searchEl = document.getElementById('scoringSearch');
  const dateStartEl = document.getElementById('scoringDateStart');
  const dateEndEl = document.getElementById('scoringDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterScoringView();
}

function renderScoringCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无需要评分的任务</p></div>`;
  return `<div class="card">
    ${tasks.map(t => {
      const isPending = t.isPending;
      const statusIcon = isPending ? '⏳' : '✅';
      const statusText = isPending ? '待评分' : '已评分';
      const statusCls = isPending ? 'badge-pending' : 'badge-completed';
      const allRoles = t.scoringRecords;
      return `<div class="subtask-card" style="border-left:3px solid ${isPending ? 'var(--warning)' : 'var(--success)'};">
        <div class="subtask-header">
          <div class="subtask-name">
            <span class="subtask-number" style="background:${isPending ? 'var(--warning)' : 'var(--success)'};">${statusIcon}</span>
            ${t.name}
          </div>
          <span class="badge ${statusCls}">${statusText}</span>
        </div>
        <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">
          📁 项目 #${t.projectId} ${t.projectType === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'} — ${t.projectName || ''}
          ${t.plannedDate ? ` · 📅 ${formatDate(t.plannedDate)}` : ''}
        </div>
        <div style="margin-top:8px;">
          <div style="font-size:12px;font-weight:600;color:var(--gray-600);margin-bottom:6px;">评分状态</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            ${allRoles.map(r => {
              const scored = r.score != null;
              return `<span style="padding:4px 10px;border-radius:6px;font-size:12px;background:${scored ? 'var(--success-light)' : 'var(--warning-light)'};color:${scored ? 'var(--success)' : 'var(--warning)'};">
                ${roleLabel(r.role)}: ${scored ? `✅ ${r.score}分` : '⏳ 待评分'}
              </span>`;
            }).join('')}
          </div>
        </div>
        <div class="subtask-actions" style="margin-top:10px;">
          ${t.isAdminView || t.isDesignerView ? '' : (isPending ? `<button class="btn btn-primary btn-sm" data-emie-onclick="openScoring(${t.projectId},${t.id})">⭐ 立即评分</button>` : '')}
          <button class="btn btn-outline btn-sm" data-emie-onclick="openProjectDetail(${t.projectId})">查看项目</button>
        </div>
      </div>`;
    }).join('')}
  </div>`;
}

// 设计师视角: 展示分配给自己的子任务卡片

EMIE.registerActions({
  renderScoringView,
  filterScoringView,
  applyFilterScoringView,
  resetScoringFilters,
  renderScoringCards,
});

EMIE.registerModule('dashboardScoring', {
  renderScoringView,
  filterScoringView,
  resetScoringFilters,
  renderScoringCards,
});
