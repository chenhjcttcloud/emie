const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const swrFetch = (...args) => EMIE.actions.swrFetch(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const openScoring = (...args) => EMIE.actions.openScoring(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const compareTaskPriority = (...args) => EMIE.actions.compareTaskPriority(...args);
const matchesSearchText = (...args) => EMIE.actions.matchesSearchText(...args);
const isDateInRange = (...args) => EMIE.actions.isDateInRange(...args);

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
      plannerId: item.plannerId,
      plannerName: item.plannerName,
      plannedDate: item.plannedDate,
      lastActivityAt: item.lastActivityAt,
      designerId: item.designerId,
      designerName: item.designerName,
      selfScore: item.selfScore,
      selfAesthetics: item.selfAesthetics,
      selfInnovation: item.selfInnovation,
      scoringRecords: item.scoringRecords || [],
      isPending: !!item.isPending,
      itemKind: item.itemKind || 'project_task',
      requirementId: item.requirementId,
    };
    pendingTasks.push(t);
  }

  // 待评分优先；有最近评分/审核动态的任务其次，再按计划完成时间和编号稳定排序。
  pendingTasks.sort((a, b) => {
    if (a.isPending !== b.isPending) return a.isPending ? -1 : 1;
    const dateCompare = compareTaskPriority(a, b);
    return dateCompare;
  });

  const pendingCount = pendingTasks.filter(t => t.isPending).length;
  const doneCount = pendingTasks.filter(t => !t.isPending).length;
  const plannerOptions = [...new Map((EMIE.state.users?.planner || [])
    .filter(user => user.userId)
    .map(user => [user.userId, user.name || user.userId])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1], 'zh-CN'));

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
      <select class="form-select" data-emie-onchange="filterScoringView()" style="min-width:120px;" id="scoringTypeFilter">
        <option value="all">全部项目类型</option>
        <option value="channel_custom">渠道定制单</option>
        <option value="regular">公司常规品</option>
        <option value="design_requirement">设计/送审需求</option>
      </select>
      <select class="form-select" data-emie-onchange="filterScoringView()" style="min-width:120px;" id="scoringStageFilter">
        <option value="all">全部审核阶段</option>
        <option value="first">一审</option>
        <option value="second">二审</option>
      </select>
      <select class="form-select" data-emie-onchange="filterScoringView()" style="min-width:140px;" id="scoringPlannerFilter">
        <option value="all">全部产品企划</option>
        ${plannerOptions.map(([id, name]) => `<option value="${escHtml(id)}">${escHtml(name)}</option>`).join('')}
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." data-emie-oninput="filterScoringView()" style="min-width:180px;" id="scoringSearch">
      <input type="date" class="form-input" id="scoringDateStart" data-emie-onchange="filterScoringView()" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="scoringDateEnd" data-emie-onchange="filterScoringView()" style="min-width:130px;" title="计划完成日期止">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterScoringView()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetScoringFilters()">↺ 重置</button>
    </div>
    <div id="scoringContainer">${renderScoringCards(pendingTasks)}</div>
  `;
  EMIE.dashboardState.scoringCache = pendingTasks;
  if (EMIE.scoringFilterPreset) {
    const preset = EMIE.scoringFilterPreset;
    EMIE.scoringFilterPreset = null;
    const filterEl = document.getElementById('scoringFilter');
    if (filterEl) filterEl.value = preset;
    applyFilterScoringView();
  }
}

function filterScoringView() {
  clearTimeout(filterScoringView._timer);
  EMIE.dashboardState.scoringPage = 1;
  filterScoringView._timer = setTimeout(applyFilterScoringView, 100);
}

function applyFilterScoringView() {
  const filter = document.getElementById('scoringFilter')?.value || 'all';
  const projectType = document.getElementById('scoringTypeFilter')?.value || 'all';
  const stage = document.getElementById('scoringStageFilter')?.value || 'all';
  const plannerId = document.getElementById('scoringPlannerFilter')?.value || 'all';
  const q = document.getElementById('scoringSearch')?.value || '';
  const dateStart = document.getElementById('scoringDateStart')?.value;
  const dateEnd = document.getElementById('scoringDateEnd')?.value;
  let list = EMIE.dashboardState.scoringCache || [];

  if (filter === 'pending') list = list.filter(t => t.isPending);
  else if (filter === 'done') list = list.filter(t => !t.isPending);
  if (projectType !== 'all') list = list.filter(t => t.projectType === projectType);
  if (stage !== 'all') list = list.filter(t => t.scoringRecords?.some(r => r.reviewStage === stage));
  if (plannerId !== 'all') list = list.filter(t => t.plannerId === plannerId);

  if (q) list = list.filter(t => matchesSearchText(q, t.id, t.projectId, t.name, t.projectName, t.plannerName, t.designerName));
  list = list.filter(t => isDateInRange(t.plannedDate, dateStart, dateEnd));

  const c = document.getElementById('scoringContainer');
  if (c) c.innerHTML = renderScoringCards(list);
}

function resetScoringFilters() {
  const filterEl = document.getElementById('scoringFilter');
  const typeEl = document.getElementById('scoringTypeFilter');
  const stageEl = document.getElementById('scoringStageFilter');
  const plannerEl = document.getElementById('scoringPlannerFilter');
  const searchEl = document.getElementById('scoringSearch');
  const dateStartEl = document.getElementById('scoringDateStart');
  const dateEndEl = document.getElementById('scoringDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (typeEl) typeEl.value = 'all';
  if (stageEl) stageEl.value = 'all';
  if (plannerEl) plannerEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  EMIE.dashboardState.scoringPage = 1;
  filterScoringView();
}

function changeScoringPage(page) {
  EMIE.dashboardState.scoringPage = Math.max(1, Number(page) || 1);
  applyFilterScoringView();
}

function renderScoringPager(total, page, pageSize) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  if (total <= pageSize) return '';
  const current = Math.min(page, pages);
  return `<div class="pagination" style="display:flex;justify-content:center;align-items:center;gap:6px;margin:12px 0;">
    <button class="btn btn-outline btn-sm" ${current <= 1 ? 'disabled' : ''} data-emie-onclick="changeScoringPage(${current - 1})">上一页</button>
    <span style="font-size:12px;color:var(--gray-600);min-width:42px;text-align:center;">${current} / ${pages}</span>
    <button class="btn btn-outline btn-sm" ${current >= pages ? 'disabled' : ''} data-emie-onclick="changeScoringPage(${current + 1})">下一页</button>
  </div>`;
}

function renderScoringCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无需要评分的任务</p></div>`;
  const pageSize = 10;
  const page = Math.max(1, Number(EMIE.dashboardState.scoringPage) || 1);
  const pages = Math.max(1, Math.ceil(tasks.length / pageSize));
  const current = Math.min(page, pages);
  EMIE.dashboardState.scoringPage = current;
  const visibleTasks = tasks.slice((current - 1) * pageSize, current * pageSize);
  return `<div class="card">
    ${visibleTasks.map(t => {
      const isPending = t.isPending;
      const statusIcon = isPending ? '⏳' : '✅';
      const statusText = isPending ? '待评分' : '已评分';
      const statusCls = isPending ? 'badge-pending' : 'badge-completed';
      const allRoles = t.scoringRecords;
      return `<div class="subtask-card" style="border-left:3px solid ${isPending ? 'var(--warning)' : 'var(--success)'};">
        <div class="subtask-header">
          <div class="subtask-name">
            <span class="subtask-number" style="background:${isPending ? 'var(--warning)' : 'var(--success)'};">${statusIcon}</span>
            ${escHtml(t.name)}
          </div>
          <span class="badge ${statusCls}">${statusText}</span>
        </div>
        <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">
          📁 ${t.projectType === 'design_requirement' ? '需求' : '项目'} #${t.projectId} ${t.projectType === 'channel_custom' ? '📦 渠道定制' : t.projectType === 'design_requirement' ? '🎨 设计/送审' : '🏭 常规品'} — ${escHtml(t.projectName || '')}
          ${t.plannerName ? ` · 👤 产品企划：${escHtml(t.plannerName)}` : ''}
          ${t.plannedDate ? ` · 📅 ${formatDate(t.plannedDate)}` : ''}
        </div>
        <div style="margin-top:8px;">
          <div style="font-size:12px;font-weight:600;color:var(--gray-600);margin-bottom:6px;">评分状态</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            ${allRoles.map(r => {
              const scored = r.score != null;
              return `<span style="padding:4px 10px;border-radius:6px;font-size:12px;background:${scored ? 'var(--success-light)' : 'var(--warning-light)'};color:${scored ? 'var(--success)' : 'var(--warning)'};">
                ${r.stage === 'self' ? '设计师自评' : roleLabel(r.role)}: ${scored ? `✅ ${r.score}分` : r.status === 'waiting' ? '等待中' : '⏳ 待评分'}
              </span>`;
            }).join('')}
          </div>
        </div>
        <div class="subtask-actions" style="margin-top:10px;">
          ${t.isAdminView || t.isDesignerView ? '' : (isPending ? `<button class="btn btn-primary btn-sm" data-emie-onclick="${t.itemKind === 'design_requirement' ? `openDesignRequirementScore(${t.requirementId},${EMIE.state.currentRole === 'designer'})` : `openScoring(${t.projectId},${t.id})`}">⭐ 立即评分</button>` : '')}
          <button class="btn btn-outline btn-sm" data-emie-onclick="${t.itemKind === 'design_requirement' ? `openDesignRequirementDetail(${t.requirementId})` : `openProjectDetail(${t.projectId})`}">查看${t.itemKind === 'design_requirement' ? '需求' : '项目'}</button>
        </div>
      </div>`;
    }).join('')}
  </div>${renderScoringPager(tasks.length, current, pageSize)}`;
}

// 设计师视角: 展示分配给自己的子任务卡片

EMIE.registerActions({
  renderScoringView,
  filterScoringView,
  applyFilterScoringView,
  resetScoringFilters,
  renderScoringCards,
  changeScoringPage,
});

EMIE.registerModule('dashboardScoring', {
  renderScoringView,
  filterScoringView,
  resetScoringFilters,
  renderScoringCards,
  changeScoringPage,
});
