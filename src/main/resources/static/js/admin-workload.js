const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

const WORKLOAD_ROLES = { sales: '销售', promotion: '产品推广', planner: '产品企划', designer: '设计师', supplychain: '供应链' };
const WORKLOAD_RANGES = [
  { key: 'day', label: '今日' }, { key: 'week', label: '本周' }, { key: 'month', label: '本月' }, { key: 'quarter', label: '本季度' },
  { key: 'half-year', label: '本半年' }, { key: 'year', label: '本年度' }, { key: 'all', label: '总览' }, { key: 'custom', label: '自定义日期' },
];

async function renderAdminWorkload(container) {
  EMIE.workloadContainer = container;
  container.innerHTML = '<div class="loading">加载中</div>';
  try {
    const state = EMIE.adminState;
    const customQuery = state.workloadRange === 'custom' && state.workloadStartDate && state.workloadEndDate
      ? `&startDate=${encodeURIComponent(state.workloadStartDate)}&endDate=${encodeURIComponent(state.workloadEndDate)}` : '';
    const data = await apiGet('/admin/workload/timeline?range=' + state.workloadRange + customQuery);
    const summary = data._summary || {};
    const members = Object.entries(WORKLOAD_ROLES).flatMap(([role, roleLabel]) =>
      (data[role]?.users || []).map(user => ({ ...user, role, roleLabel, ...workloadMemberMetrics(user), statusLabel: '' })));
    const query = String(state.workloadQuery || '').toLowerCase();
    const visible = members.filter(user => !query || String(user.name || '').toLowerCase().includes(query));
    const roleFilter = state.workloadRole || 'all';
    const filtered = visible.filter(user => roleFilter === 'all' || user.role === roleFilter);
    filtered.sort((a, b) => workloadMemberComparator(a, b, state.workloadSort || 'attention'));
    const workload = filtered.reduce((sum, user) => sum + user.workloadTotal, 0);
    const completed = filtered.reduce((sum, user) => sum + user.completedTotal, 0);
    const points = filtered.reduce((sum, user) => sum + Number(user.performancePoints || 0), 0);
    const performanceMonth = summary.performanceMonth;
    state.workloadVisibleMembers = Object.fromEntries(filtered.map(user => [user.userId, user]));
    state.workloadPerformanceMonth = performanceMonth;

    container.innerHTML = `
      <section class="workload-page-head"><div><p class="workload-kicker">TEAM WORKLOAD</p><h2>员工工作量</h2><p>${escHtml(summary.rangeLabel || '当前范围')} · 先判断团队，再查看员工明细</p></div><div class="workload-range"><span>时间范围</span>${WORKLOAD_RANGES.map(option => `<button class="workload-range-btn ${option.key === state.workloadRange ? 'active' : ''}" data-emie-action="click:workload-range" data-range="${option.key}">${option.label}</button>`).join('')}</div></section>
      <div class="workload-custom-range" style="display:${state.workloadRange === 'custom' ? 'flex' : 'none'};"><input type="date" class="form-input" value="${escHtml(state.workloadStartDate || '')}" data-emie-action="change:workload-start-date" aria-label="开始日期"><span>至</span><input type="date" class="form-input" value="${escHtml(state.workloadEndDate || '')}" data-emie-action="change:workload-end-date" aria-label="结束日期"><button class="btn btn-primary btn-sm" data-emie-action="click:workload-apply-dates">查询</button></div>
      <div class="workload-toolbar"><input class="form-input" placeholder="搜索员工姓名" value="${escHtml(state.workloadQuery || '')}" data-emie-action="input:workload-query"><select class="form-select" data-emie-action="change:workload-sort"><option value="attention" ${(state.workloadSort || 'attention') === 'attention' ? 'selected' : ''}>优先关注</option><option value="total" ${state.workloadSort === 'total' ? 'selected' : ''}>按总量排序</option><option value="pending" ${state.workloadSort === 'pending' ? 'selected' : ''}>按未完成排序</option><option value="rate" ${state.workloadSort === 'rate' ? 'selected' : ''}>按完成率排序</option></select></div>
      <div class="workload-role-filter"><span>员工角色</span><button class="${roleFilter === 'all' ? 'active' : ''}" data-emie-action="click:workload-role" data-role="all">全部</button>${Object.entries(WORKLOAD_ROLES).map(([role, label]) => `<button class="${roleFilter === role ? 'active' : ''}" data-emie-action="click:workload-role" data-role="${role}">${label}<small>${(data[role]?.users || []).length}</small></button>`).join('')}</div>
      <section class="workload-summary-grid"><div><small>统计员工</small><strong>${filtered.length}</strong><span>当前筛选范围</span></div><div><small>工作总量</small><strong>${workload}</strong><span>项目、任务及需求</span></div><div><small>整体完成率</small><strong>${workload ? Math.round(Math.min(100, completed / workload * 100)) + '%' : '—'}</strong><span>按已完成工作计算</span></div><div><small>绩效积分</small><strong>${performanceMonth ? points : '—'}</strong><span>${performanceMonth ? `${performanceMonth} 归属月` : '仅完整自然月展示'}</span></div></section>
      <div class="workload-provenance"><strong>数据可追溯</strong><span>工作量来自项目、子任务、设计/选审需求的创建与完成记录。</span><span>绩效积分来自已入账积分流水及调账流水。</span><span>${escHtml(summary.performanceSource || '')}</span></div>
      <section class="workload-member-panel"><div class="workload-member-head"><div><h3>员工工作量明细</h3><p>点击员工查看工作构成、完成情况和数据来源。</p></div><span>${filtered.length} 位员工</span></div><div class="workload-member-list workload-member-cards">${workloadMemberGroups(filtered, roleFilter, performanceMonth) || '<div class="empty">未找到员工</div>'}</div></section>`;
  } catch (error) {
    container.innerHTML = `<div class="empty"><p>加载失败: ${escHtml(error.message)}</p></div>`;
  }
}

function workloadMemberMetrics(user) {
  const workloadTotal = Number(user.created || user.assigned || 0) + Number(user.designRequirements || 0);
  const completedTotal = Number(user.completed || 0) + Number(user.completedDesignRequirements || 0);
  const pending = Math.max(0, workloadTotal - completedTotal);
  const completionRate = workloadTotal ? Math.min(100, completedTotal / workloadTotal * 100) : 0;
  return { workloadTotal, completedTotal, pending, completionRate, statusLabel: user.roleLabel, statusKey: '', statusOrder: 0 };
}

function workloadMemberComparator(a, b, sort) {
  if (sort === 'total') return b.workloadTotal - a.workloadTotal;
  if (sort === 'pending') return b.pending - a.pending;
  if (sort === 'rate') return a.completionRate - b.completionRate;
  return b.pending - a.pending;
}

function workloadMemberRow(user, performanceMonth) {
  const expanded = EMIE.adminState.workloadExpandedUserId === user.userId;
  const created = user.role === 'promotion' ? '—' : Number(user.created || user.assigned || 0);
  const projectLabel = user.role === 'planner' ? '新增项目' : user.role === 'sales' ? '新建项目' : '新建子任务';
  const initial = String(user.name || '?').trim().slice(0, 1);
  return `<article class="workload-member workload-card ${user.role} ${user.statusKey} ${expanded ? 'expanded' : ''}"><button class="workload-member-row" data-emie-action="click:workload-member" data-user-id="${escHtml(user.userId)}" aria-expanded="${expanded}"><span class="workload-person"><i>${escHtml(initial)}</i><span><strong>${escHtml(user.name)}</strong><small>${user.statusLabel}</small></span></span><span class="workload-card-main"><strong>${user.workloadTotal}</strong><small>本期工作量</small></span><span class="workload-card-progress"><span><b>完成 ${user.workloadTotal ? Math.round(user.completionRate) : 0}%</b><small>${user.pending} 项待完成</small></span><i><em style="width:${user.completionRate}%"></em></i></span><span class="workload-card-points"><strong>${performanceMonth ? Number(user.performancePoints || 0) : '—'}</strong><small>绩效积分</small></span><span class="workload-expand-icon">${expanded ? '收起 ⌃' : '查看 ⌄'}</span></button>${expanded ? `<div class="workload-member-detail"><div><small>工作构成</small><p>${projectLabel} <b>${created}</b> · 渠道定制 <b>${user.channelCustomProjects || 0}</b> · 常规品 <b>${user.regularProjects || 0}</b> · 设计/选审需求 <b>${user.designRequirements || 0}</b></p></div><div><small>完成来源</small><p>已完成 <b>${user.completedTotal}</b>，渠道 <b>${user.completedChannelProjects || 0}</b>、常规 <b>${user.completedRegularProjects || 0}</b>${user.role === 'designer' ? `、需求 <b>${user.completedDesignRequirements || 0}</b>` : ''}。</p></div><div><small>数据来源</small><p>工作量取自项目、子任务和需求记录；绩效积分取自归属月的积分流水及调账流水。</p></div></div>` : ''}</article>`;
}

function workloadMemberGroups(users, roleFilter, performanceMonth) {
  if (roleFilter !== 'all') return users.map(user => workloadMemberRow(user, performanceMonth)).join('');
  return Object.entries(WORKLOAD_ROLES).map(([role, label]) => {
    const group = users.filter(user => user.role === role);
    if (!group.length) return '';
    const attention = group.filter(user => user.statusKey === 'risk' || user.statusKey === 'watch').length;
    return `<div class="workload-role-group"><div><strong>${label}</strong><span>${group.length} 人${attention ? ` · ${attention} 人需关注` : ''}</span></div></div>${group.map(user => workloadMemberRow(user, performanceMonth)).join('')}`;
  }).join('');
}

function switchWorkloadRange(range) {
  EMIE.adminState.workloadRange = range;
  if (range === 'custom') {
    const today = new Date(), monthStart = new Date(today.getFullYear(), today.getMonth(), 1), format = date => date.toLocaleDateString('en-CA');
    EMIE.adminState.workloadStartDate ||= format(monthStart);
    EMIE.adminState.workloadEndDate ||= format(today);
  }
  renderWorkload();
}
function setWorkloadDate(kind, value) { EMIE.adminState[kind === 'start' ? 'workloadStartDate' : 'workloadEndDate'] = value; }
function applyWorkloadDates() { const { workloadStartDate: start, workloadEndDate: end } = EMIE.adminState; if (!start || !end) return EMIE.actions.showSystemAlert('请选择开始日期和结束日期'); if (start > end) return EMIE.actions.showSystemAlert('结束日期不能早于开始日期'); renderWorkload(); }
function setWorkloadQuery(value) { EMIE.adminState.workloadQuery = value || ''; renderWorkload(); }
function setWorkloadSort(value) { EMIE.adminState.workloadSort = value || 'attention'; renderWorkload(); }
function setWorkloadRole(role) { EMIE.adminState.workloadRole = role || 'all'; renderWorkload(); }
function toggleWorkloadMember(userId) {
  const state = EMIE.adminState;
  const user = state.workloadVisibleMembers?.[userId];
  if (!user) return;
  state.workloadExpandedUserId = state.workloadExpandedUserId === userId ? null : userId;
  const button = document.querySelector(`[data-emie-action="click:workload-member"][data-user-id="${CSS.escape(userId)}"]`);
  const card = button?.closest('.workload-member');
  if (card) card.outerHTML = workloadMemberRow(user, state.workloadPerformanceMonth);
}
function renderWorkload() { const container = EMIE.workloadContainer || document.getElementById('adminContent'); if (container) renderAdminWorkload(container); }

EMIE.registerActions({ renderAdminWorkload, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, toggleWorkloadMember });
const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('workload-range', (_event, element) => switchWorkloadRange(element.dataset.range));
  registerEventAction('workload-query', (_event, element) => setWorkloadQuery(element.value));
  registerEventAction('workload-sort', (_event, element) => setWorkloadSort(element.value));
  registerEventAction('workload-role', (_event, element) => setWorkloadRole(element.dataset.role));
  registerEventAction('workload-start-date', (_event, element) => setWorkloadDate('start', element.value));
  registerEventAction('workload-end-date', (_event, element) => setWorkloadDate('end', element.value));
  registerEventAction('workload-apply-dates', () => applyWorkloadDates());
  registerEventAction('workload-member', (_event, element) => toggleWorkloadMember(element.dataset.userId));
}
EMIE.registerModule('adminWorkload', { renderAdminWorkload, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, toggleWorkloadMember });
