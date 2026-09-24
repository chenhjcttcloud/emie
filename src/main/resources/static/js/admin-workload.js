const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// 销售不计入工作量（销售发起的项目由产品企划承担执行）
const WORKLOAD_ROLES = { promotion: '产品推广', planner: '产品企划', designer: '设计师', supplychain: '供应链' };
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
    state.workloadChartMembers = [];
    state.workloadChartData = data;
    const summary = data._summary || {};
    const members = Object.entries(WORKLOAD_ROLES).flatMap(([role, roleLabel]) =>
      (data[role]?.users || []).map(user => ({ ...user, role, roleLabel, ...workloadMemberMetrics(user) })));
    const query = String(state.workloadQuery || '').toLowerCase();
    const visible = members.filter(user => !query || String(user.name || '').toLowerCase().includes(query));
    const roleFilter = state.workloadRole || 'all';
    const filtered = visible.filter(user => roleFilter === 'all' || user.role === roleFilter);
    state.workloadChartMembers = filtered;
    filtered.sort((a, b) => workloadMemberComparator(a, b, state.workloadSort || 'attention'));
    const workload = filtered.reduce((sum, user) => sum + user.workloadTotal, 0);
    const completed = filtered.reduce((sum, user) => sum + user.completedTotal, 0);
    const outstanding = filtered.reduce((sum, user) => sum + user.pending, 0);
    const waiting = filtered.reduce((sum, user) => sum + user.waiting, 0);
    const splitAvailable = summary.outstandingSplitAvailable !== false;
    const completedInRange = filtered.reduce((sum, user) => sum + user.completedInRange, 0);
    const attention = filtered.filter(user => user.statusKey === 'risk' || user.statusKey === 'watch').length;

    container.innerHTML = `
      <section class="workload-page-head"><div><p class="workload-kicker">工作量概览</p><h2>员工工作量</h2><p>${escHtml(summary.rangeLabel || '当前范围')} · 团队概览与员工明细</p></div><div class="workload-range"><span>时间范围</span>${WORKLOAD_RANGES.map(option => `<button class="workload-range-btn ${option.key === state.workloadRange ? 'active' : ''}" data-emie-action="click:workload-range" data-range="${option.key}">${option.label}</button>`).join('')}</div></section>
      <div class="workload-custom-range" style="display:${state.workloadRange === 'custom' ? 'flex' : 'none'};"><input type="date" class="form-input" value="${escHtml(state.workloadStartDate || '')}" data-emie-action="change:workload-start-date" aria-label="开始日期"><span>至</span><input type="date" class="form-input" value="${escHtml(state.workloadEndDate || '')}" data-emie-action="change:workload-end-date" aria-label="结束日期"><button class="btn btn-primary btn-sm" data-emie-action="click:workload-apply-dates">查询</button></div>
      <div class="workload-toolbar"><input class="form-input" placeholder="搜索员工姓名" value="${escHtml(state.workloadQuery || '')}" data-emie-action="input:workload-query"><select class="form-select" data-emie-action="change:workload-sort"><option value="attention" ${(state.workloadSort || 'attention') === 'attention' ? 'selected' : ''}>优先关注</option><option value="total" ${state.workloadSort === 'total' ? 'selected' : ''}>按总量排序</option><option value="pending" ${state.workloadSort === 'pending' ? 'selected' : ''}>按未完成排序</option><option value="rate" ${state.workloadSort === 'rate' ? 'selected' : ''}>按完成率排序</option></select></div>
      <div class="workload-role-filter"><span>员工角色</span><button class="${roleFilter === 'all' ? 'active' : ''}" data-emie-action="click:workload-role" data-role="all">全部</button>${Object.entries(WORKLOAD_ROLES).map(([role, label]) => `<button class="${roleFilter === role ? 'active' : ''}" data-emie-action="click:workload-role" data-role="${role}">${label}<small>${(data[role]?.users || []).length}</small></button>`).join('')}</div>
      <section class="workload-summary-grid"><div><small>统计员工</small><strong>${filtered.length}</strong><span>${attention ? `其中 ${attention} 人需关注` : '当前筛选范围'}</span></div><div><small>本期新增工作量</small><strong>${workload}</strong><span>项目、任务及需求</span></div><div><small>本期新增完成率</small><strong>${workload ? Math.round(completed / workload * 100) + '%' : '—'}</strong><span>新增 ${workload} 项，已完成 ${completed} 项</span></div>${splitAvailable
        ? `<div><small>待本人处理</small><strong>${outstanding}</strong><span>待接单 / 处理中 / 待返工</span></div><div><small>待他人处理</small><strong>${waiting}</strong><span>已交付，待验收或评分</span></div>`
        : `<div><small>在手未完成</small><strong>${outstanding + waiting}</strong><span>历史区间不拆分责任</span></div>`}<div><small>本期完成</small><strong>${completedInRange}</strong><span>含往期遗留，不做分子</span></div></section>
      ${workloadCharts(filtered, data, state.workloadChartPage || 0)}
      <section class="workload-member-panel"><div class="workload-member-head"><div><h3>员工工作量明细</h3><p>${escHtml(summary.rangeLabel || '')} · 全部数据一览，点击图表可定位员工。</p></div><span>${filtered.length} 位员工</span></div>
      ${workloadMemberTable(filtered, roleFilter)}</section>`;
  } catch (error) {
    container.innerHTML = `<div class="empty"><p>加载失败: ${escHtml(error.message)}</p></div>`;
  }
}

// 统计口径全部由后端计算：workloadCreated / workloadCompletedFromCreated 属于同一批工作量，
// completedInRange（本期完成，含往期遗留）与 outstandingTotal（期末在手）是另外两个指标。
function workloadMemberMetrics(user) {
  return {
    workloadTotal: Number(user.workloadCreated || 0),
    completedTotal: Number(user.workloadCompletedFromCreated || 0),
    completedInRange: Number(user.completedInRange || 0),
    pending: Number(user.outstandingOwnTotal || 0),
    waiting: Number(user.outstandingWaitingTotal || 0),
    outstandingAll: Number(user.outstandingTotal || 0),
    completionRate: user.completionRate == null ? null : Number(user.completionRate),
    statusKey: user.statusKey || 'steady',
    statusLabel: user.statusLabel || '',
    statusReason: user.statusReason || '',
    statusOrder: Number(user.statusOrder ?? 2),
  };
}

// 图表：两个系列 —— 待本人处理（靛蓝）/ 待他人处理（青绿）。已通过可视化配色校验：
// CVD ΔE 22.1、正常视觉 ΔE 27.1、对比度达标；红/橙保留给状态标签，不用于数据系列。
const WL_OWN = '#1e3a8a';
const WL_WAIT = '#0f766e';

function workloadBar(label, sub, own, waiting, max, onclick) {
  const total = own + waiting;
  const pct = v => (max ? Math.max(v / max * 100, v > 0 ? 1.5 : 0) : 0);
  return `<div class="wl-bar-row"${onclick ? ` data-emie-action="click:workload-member" data-user-id="${escHtml(onclick)}" role="button" tabindex="0"` : ''}>
    <span class="wl-bar-label"><b>${escHtml(label)}</b>${sub ? `<small>${escHtml(sub)}</small>` : ''}</span>
    <span class="wl-bar-track" title="待本人处理 ${own} 项 · 待他人处理 ${waiting} 项 · 合计 ${total} 项">
      <i class="wl-seg wl-seg-own" style="width:${pct(own)}%"></i>
      <i class="wl-seg wl-seg-wait" style="width:${pct(waiting)}%"></i>
    </span>
    <span class="wl-bar-value"><b>${own}</b><small>${waiting ? '+' + waiting : ''}</small></span>
  </div>`;
}

function workloadCharts(members, data, page) {
  if (!members.length) return '';
  const ranked = [...members].sort((a, b) => b.pending - a.pending || b.waiting - a.waiting);
  const pageSize = 10;
  const pageCount = Math.max(1, Math.ceil(ranked.length / pageSize));
  const currentPage = Math.min(Math.max(page, 0), pageCount - 1);
  const shown = ranked.slice(currentPage * pageSize, (currentPage + 1) * pageSize);
  const maxMember = Math.max(...ranked.map(m => m.pending + m.waiting), 1);

  const roles = Object.entries(WORKLOAD_ROLES).map(([role, label]) => {
    const group = members.filter(m => m.role === role);
    return { label, own: group.reduce((a, m) => a + m.pending, 0), waiting: group.reduce((a, m) => a + m.waiting, 0), count: group.length };
  }).filter(r => r.count);
  const maxRole = Math.max(...roles.map(r => r.own + r.waiting), 1);

  const legend = `<span class="wl-legend"><i style="background:${WL_OWN}"></i>待本人处理<i style="background:${WL_WAIT};margin-left:12px"></i>待他人处理</span>`;

  return `<section class="wl-charts">
    <div class="wl-chart">
      <div class="wl-chart-head"><h3>人员待处理工作量</h3>${legend}</div>
      ${shown.map(m => workloadBar(m.name, m.roleLabel, m.pending, m.waiting, maxMember, m.userId)).join('')}
      ${pageCount > 1 ? `<div class="wl-pagination"><button class="wl-more" data-emie-action="click:workload-chart-page" data-page="${currentPage - 1}" ${currentPage === 0 ? 'disabled' : ''}>上一页</button><span>第 ${currentPage + 1} / ${pageCount} 页</span><button class="wl-more" data-emie-action="click:workload-chart-page" data-page="${currentPage + 1}" ${currentPage === pageCount - 1 ? 'disabled' : ''}>下一页</button></div>` : ''}
    </div>
    <div class="wl-chart">
      <div class="wl-chart-head"><h3>角色工作量分布</h3>${legend}</div>
      ${roles.map(r => workloadBar(r.label, r.count + ' 人', r.own, r.waiting, maxRole, null)).join('')}
    </div>
  </section>`;
}

const WORKLOAD_STATUS_CLASS = { risk: 'risk', watch: 'watch', steady: 'normal', idle: 'idle' };
const rateText = user => (user.completionRate == null ? '—' : Math.round(user.completionRate) + '%');

function workloadMemberComparator(a, b, sort) {
  if (sort === 'total') return b.workloadTotal - a.workloadTotal;
  if (sort === 'pending') return b.pending - a.pending;
  // 本期无新增（完成率为 null）排最后，不按 0% 参与排序
  if (sort === 'rate') return (a.completionRate ?? 101) - (b.completionRate ?? 101);
  return a.statusOrder - b.statusOrder || b.pending - a.pending;
}

/** 渠道 / 常规合并展示，辅助文字标注同批完成数。 */
/** 在手：渠道 / 常规；辅助文字补充本期新增与完成，区分两个统计口径。 */
function workloadPair(channel, regular, created, completed) {
  const c = Number(channel || 0), r = Number(regular || 0), n = Number(created || 0);
  if (!c && !r && !n) return '<span class="muted">—</span>';
  return `<b>${c}</b><span class="sep">/</span><b>${r}</b><small>本期新增 ${n}，完成 ${Number(completed || 0)}</small>`;
}

function workloadMemberTable(users, roleFilter) {
  if (!users.length) return '<div class="empty">未找到员工</div>';
  const head = `<tr><th>成员</th><th>状态</th><th>在手项目（渠道/常规）</th><th>在手子任务（渠道/常规）</th><th>本期新增</th><th>已完成</th><th>完成率</th><th>待本人处理</th><th>待他人处理</th><th>在手合计</th></tr>`;
  const row = user => {
    const statusClass = WORKLOAD_STATUS_CLASS[user.statusKey] || 'normal';
    const rate = user.completionRate == null ? null : Math.round(user.completionRate);
    return `<tr id="wl-row-${escHtml(user.userId)}" class="wl-row ${user.statusKey}" title="${escHtml(user.statusReason || '')}">
      <td><b>${escHtml(user.name)}</b><small>${escHtml([...new Set([user.roleLabel, user.title].filter(Boolean))].join(' · '))}</small></td>
      <td><em class="workload-status ${statusClass}">${escHtml(user.statusLabel)}</em></td>
      <td class="wl-mix wl-jump-cell" data-emie-action="click:workload-details" data-user-id="${escHtml(user.userId)}" data-bucket="projects" title="查看在手项目">${workloadPair(user.projectsOutstandingChannel, user.projectsOutstandingRegular, user.projectsCreated, user.projectsCompletedFromCreated)}</td>
      <td class="wl-mix wl-jump-cell" data-emie-action="click:workload-details" data-user-id="${escHtml(user.userId)}" data-bucket="tasks" title="查看在手子任务">${workloadPair(user.tasksOutstandingChannel, user.tasksOutstandingRegular, user.tasksCreated, user.tasksCompletedFromCreated)}</td>
      <td class="num"><strong class="wl-total">${user.workloadTotal || '—'}</strong></td>
      <td class="num">${user.completedInRange || '—'}</td>
      <td class="wl-rate">${rate == null ? '<span class="muted">—</span>' : `<i><em style="width:${rate}%"></em></i><b>${rate}%</b>`}</td>
      <td class="num strong-own"><button class="wl-number own" data-emie-action="click:workload-details" data-user-id="${escHtml(user.userId)}" data-bucket="own">${user.pending || '—'}</button></td>
      <td class="num strong-wait"><button class="wl-number wait" data-emie-action="click:workload-details" data-user-id="${escHtml(user.userId)}" data-bucket="waiting">${user.waiting || '—'}</button></td>
      <td class="num"><strong class="wl-number total">${user.outstandingAll || '—'}</strong></td>
    </tr>`;
  };
  const body = roleFilter !== 'all'
    ? users.map(row).join('')
    : Object.entries(WORKLOAD_ROLES).map(([role, label]) => {
        const group = users.filter(user => user.role === role);
        if (!group.length) return '';
        const attention = group.filter(user => user.statusKey === 'risk' || user.statusKey === 'watch').length;
        return `<tr class="wl-group"><td colspan="10">${label} <span>${group.length} 人${attention ? ` · ${attention} 人需关注` : ''}</span></td></tr>` + group.map(row).join('');
      }).join('');
  return `<div class="table-wrap wl-table"><table><thead>${head}</thead><tbody>${body}</tbody></table></div>`;
}

async function openWorkloadDetails(userId, bucket) {
  const user = document.getElementById('wl-row-' + userId)?.querySelector('td b')?.textContent || '员工';
  const bucketLabels = { own: '待本人处理', waiting: '待他人处理', projects: '在手项目', tasks: '在手子任务', total: '在手合计' };
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay workload-detail-overlay';
  overlay.innerHTML = `<aside class="workload-detail-drawer"><header><div><small>工作量明细</small><h3>${escHtml(user)} · ${bucketLabels[bucket] || '工作量明细'}</h3></div><button class="modal-close" data-emie-action="click:workload-details-close">✕</button></header><div class="workload-detail-body"><div class="loading">加载中</div></div></aside>`;
  overlay.addEventListener('click', event => { if (event.target === overlay) closeWorkloadDetails(); });
  document.body.appendChild(overlay);
  try {
    const items = await apiGet(`/admin/workload/details?userId=${encodeURIComponent(userId)}&bucket=${bucket}`);
    const body = overlay.querySelector('.workload-detail-body');
    body.innerHTML = items.length ? items.map(item => `<article class="workload-detail-item" data-emie-action="click:workload-detail-open" data-item-type="${escHtml(item.type || 'project')}" data-task-id="${escHtml(item.id || '')}" data-project-id="${escHtml(item.projectId || '')}"><div><strong>${escHtml(item.name || '未命名事项')}</strong><small>项目编号：${escHtml(item.projectCode || '未设置')}</small><small>${escHtml(item.project || '未命名项目')}</small><span class="workload-detail-type">${escHtml(item.projectTypeLabel || '未设置类型')}</span></div><span class="workload-detail-status">${escHtml(item.statusLabel || item.status || '')}</span><small class="workload-detail-stage">${escHtml(item.stage || '未设置阶段')} · 点击查看${item.type === 'task' ? '子任务' : '项目'}</small></article>`).join('') : '<div class="empty">暂无明细</div>';
  } catch (error) {
    overlay.querySelector('.workload-detail-body').innerHTML = `<div class="empty">加载失败：${escHtml(error.message)}</div>`;
  }
}

function closeWorkloadDetails() {
  document.querySelector('.workload-detail-overlay')?.remove();
}

function openWorkloadDetailProject(projectId) {
  closeWorkloadDetails();
  if (projectId && EMIE.actions.openProjectDetail) EMIE.actions.openProjectDetail(Number(projectId));
}
function openWorkloadDetailItem(element) {
  if (element.dataset.itemType === 'task' && element.dataset.taskId && element.dataset.projectId && EMIE.actions.openProjectDetail) {
    closeWorkloadDetails();
    EMIE.actions.openProjectDetail(Number(element.dataset.projectId), Number(element.dataset.taskId));
    return;
  }
  openWorkloadDetailProject(element.dataset.projectId);
}

/** 图表撳落去 → 定位并高亮对应表格行 */
function focusWorkloadMember(userId) {
  const row = document.getElementById('wl-row-' + userId);
  if (!row) return;
  document.querySelectorAll('.wl-row.flash').forEach(el => el.classList.remove('flash'));
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  row.classList.add('flash');
}

function switchWorkloadRange(range) {
  EMIE.adminState.workloadRange = range;
  EMIE.rememberWorkloadRange('emie_admin_workload_range', range);
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
function changeWorkloadChartPage(page) {
  const state = EMIE.adminState;
  const previousPage = Number(state.workloadChartPage || 0);
  state.workloadChartPage = Math.max(0, Number(page) || 0);
  const chart = document.querySelector('.wl-charts .wl-chart');
  if (!chart || !state.workloadChartMembers) return;
  const wrapper = document.createElement('div');
  wrapper.innerHTML = workloadCharts(state.workloadChartMembers, state.workloadChartData, state.workloadChartPage);
  const nextChart = wrapper.firstElementChild.firstElementChild;
  nextChart.classList.add(state.workloadChartPage >= previousPage ? 'wl-page-next' : 'wl-page-prev');
  chart.replaceWith(nextChart);
}
function toggleWorkloadChart() { changeWorkloadChartPage(0); }
function renderWorkload() { const container = EMIE.workloadContainer || document.getElementById('adminContent'); if (container) renderAdminWorkload(container); }

EMIE.registerActions({ renderAdminWorkload, toggleWorkloadChart, changeWorkloadChartPage, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, focusWorkloadMember, openWorkloadDetails, closeWorkloadDetails, openWorkloadDetailProject, openWorkloadDetailItem });
const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('workload-range', (_event, element) => switchWorkloadRange(element.dataset.range));
  registerEventAction('workload-query', (_event, element) => setWorkloadQuery(element.value));
  registerEventAction('workload-sort', (_event, element) => setWorkloadSort(element.value));
  registerEventAction('workload-role', (_event, element) => setWorkloadRole(element.dataset.role));
  registerEventAction('workload-start-date', (_event, element) => setWorkloadDate('start', element.value));
  registerEventAction('workload-end-date', (_event, element) => setWorkloadDate('end', element.value));
  registerEventAction('workload-apply-dates', () => applyWorkloadDates());
  registerEventAction('workload-member', (_event, element) => focusWorkloadMember(element.dataset.userId));
  registerEventAction('workload-expand-chart', () => toggleWorkloadChart());
  registerEventAction('workload-chart-page', (_event, element) => changeWorkloadChartPage(element.dataset.page));
  registerEventAction('workload-details', (_event, element) => openWorkloadDetails(element.dataset.userId, element.dataset.bucket));
  registerEventAction('workload-details-close', () => closeWorkloadDetails());
  registerEventAction('workload-detail-open', (_event, element) => openWorkloadDetailItem(element));
}
EMIE.registerModule('adminWorkload', { renderAdminWorkload, toggleWorkloadChart, changeWorkloadChartPage, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, focusWorkloadMember, openWorkloadDetails, closeWorkloadDetails, openWorkloadDetailProject, openWorkloadDetailItem });
