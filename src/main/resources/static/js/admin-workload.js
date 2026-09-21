const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// 销售唔计入工作量（佢哋发起嘅项目由企划承担执行）
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
    const summary = data._summary || {};
    const members = Object.entries(WORKLOAD_ROLES).flatMap(([role, roleLabel]) =>
      (data[role]?.users || []).map(user => ({ ...user, role, roleLabel, ...workloadMemberMetrics(user) })));
    const query = String(state.workloadQuery || '').toLowerCase();
    const visible = members.filter(user => !query || String(user.name || '').toLowerCase().includes(query));
    const roleFilter = state.workloadRole || 'all';
    const filtered = visible.filter(user => roleFilter === 'all' || user.role === roleFilter);
    filtered.sort((a, b) => workloadMemberComparator(a, b, state.workloadSort || 'attention'));
    const workload = filtered.reduce((sum, user) => sum + user.workloadTotal, 0);
    const completed = filtered.reduce((sum, user) => sum + user.completedTotal, 0);
    const outstanding = filtered.reduce((sum, user) => sum + user.pending, 0);
    const waiting = filtered.reduce((sum, user) => sum + user.waiting, 0);
    const splitAvailable = summary.outstandingSplitAvailable !== false;
    const orphan = summary.inactiveOwnerTasks || {};
    const unassigned = summary.unassignedTasks || {};
    const completedInRange = filtered.reduce((sum, user) => sum + user.completedInRange, 0);
    const attention = filtered.filter(user => user.statusKey === 'risk' || user.statusKey === 'watch').length;
    const points = filtered.reduce((sum, user) => sum + Number(user.performancePoints || 0), 0);
    const performanceMonth = summary.performanceMonth;
    state.workloadPerformanceMonth = performanceMonth;

    container.innerHTML = `
      <section class="workload-page-head"><div><p class="workload-kicker">TEAM WORKLOAD</p><h2>员工工作量</h2><p>${escHtml(summary.rangeLabel || '当前范围')} · 先判断团队，再查看员工明细</p></div><div class="workload-range"><span>时间范围</span>${WORKLOAD_RANGES.map(option => `<button class="workload-range-btn ${option.key === state.workloadRange ? 'active' : ''}" data-emie-action="click:workload-range" data-range="${option.key}">${option.label}</button>`).join('')}</div></section>
      <div class="workload-custom-range" style="display:${state.workloadRange === 'custom' ? 'flex' : 'none'};"><input type="date" class="form-input" value="${escHtml(state.workloadStartDate || '')}" data-emie-action="change:workload-start-date" aria-label="开始日期"><span>至</span><input type="date" class="form-input" value="${escHtml(state.workloadEndDate || '')}" data-emie-action="change:workload-end-date" aria-label="结束日期"><button class="btn btn-primary btn-sm" data-emie-action="click:workload-apply-dates">查询</button></div>
      <div class="workload-toolbar"><input class="form-input" placeholder="搜索员工姓名" value="${escHtml(state.workloadQuery || '')}" data-emie-action="input:workload-query"><select class="form-select" data-emie-action="change:workload-sort"><option value="attention" ${(state.workloadSort || 'attention') === 'attention' ? 'selected' : ''}>优先关注</option><option value="total" ${state.workloadSort === 'total' ? 'selected' : ''}>按总量排序</option><option value="pending" ${state.workloadSort === 'pending' ? 'selected' : ''}>按未完成排序</option><option value="rate" ${state.workloadSort === 'rate' ? 'selected' : ''}>按完成率排序</option></select></div>
      <div class="workload-role-filter"><span>员工角色</span><button class="${roleFilter === 'all' ? 'active' : ''}" data-emie-action="click:workload-role" data-role="all">全部</button>${Object.entries(WORKLOAD_ROLES).map(([role, label]) => `<button class="${roleFilter === role ? 'active' : ''}" data-emie-action="click:workload-role" data-role="${role}">${label}<small>${(data[role]?.users || []).length}</small></button>`).join('')}</div>
      <section class="workload-summary-grid"><div><small>统计员工</small><strong>${filtered.length}</strong><span>${attention ? `其中 ${attention} 人需关注` : '当前筛选范围'}</span></div><div><small>本期新增工作量</small><strong>${workload}</strong><span>项目、任务及需求</span></div><div><small>本期新增完成率</small><strong>${workload ? Math.round(completed / workload * 100) + '%' : '—'}</strong><span>新增 ${workload} 项，已完成 ${completed} 项</span></div>${splitAvailable
        ? `<div><small>自己要做</small><strong>${outstanding}</strong><span>等接单 / 做紧 / 要返工</span></div><div><small>等他人处理</small><strong>${waiting}</strong><span>已交付，等验收或评分</span></div>`
        : `<div><small>在手未完成</small><strong>${outstanding + waiting}</strong><span>历史区间不拆分责任</span></div>`}<div><small>本期完成</small><strong>${completedInRange}</strong><span>含往期遗留，不做分子</span></div><div><small>绩效积分</small><strong>${performanceMonth ? points : '—'}</strong><span>${performanceMonth ? `${performanceMonth} 归属月` : '仅完整自然月展示'}</span></div></section>
      ${workloadCharts(filtered, data, state.workloadChartExpanded)}
      <details class="workload-provenance"><summary>口径说明</summary><span>${escHtml(summary.completionRateRule || '')}</span><span>${escHtml(summary.attentionRule || '')}</span><span>汇总为各成员数字相加：同一件子任务会分别计入派发的产品企划与承接的设计师，属「人次」口径。</span><span>${escHtml(summary.performanceSource || '')}</span></details>
      ${Number(unassigned.outstanding || 0) || Number(unassigned.projectsOutstanding || 0) || Number(orphan.outstanding || 0) ? `<div class="workload-orphan-strip">
        ${Number(unassigned.outstanding || 0) ? `<span>⚠️ 子任务未分配负责人：<b>${unassigned.outstanding}</b> 项未完成</span>` : ''}
        ${Number(unassigned.projectsOutstanding || 0) ? `<span>📋 项目未指派企划：<b>${unassigned.projectsOutstanding}</b> 个等接单</span>` : ''}
        ${Number(orphan.outstanding || 0) ? `<span>👤 已停用 / 离职遗留：<b>${orphan.outstanding}</b> 项未完成${(orphan.owners || []).length ? ` · ${escHtml((orphan.owners || []).join('、'))}` : ''}</span>` : ''}
        <span>这些工作不属于任何在册成员，不计入上方人头统计。</span>
      </div>` : ''}
      <section class="workload-member-panel"><div class="workload-member-head"><div><h3>员工工作量明细</h3><p>${escHtml(summary.rangeLabel || '')} · 全部数据一览，点击图表可定位员工。</p></div><span>${filtered.length} 位员工</span></div>
      ${workloadMemberTable(filtered, roleFilter, performanceMonth)}</section>`;
  } catch (error) {
    container.innerHTML = `<div class="empty"><p>加载失败: ${escHtml(error.message)}</p></div>`;
  }
}

// 口径全部由后端计好：workloadCreated / workloadCompletedFromCreated 系同一批嘢，
// completedInRange（本期完成，含往期遗留）同 outstandingTotal（期末在手）系另外两个指标。
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

// 图表：两个系列 —— 自己要做（靛蓝）/ 等他人（青绿）。已过 dataviz 调色校验：
// CVD ΔE 22.1、正常视觉 ΔE 27.1、对比度达标；红/橙留畀状态标签，唔做数据系列。
const WL_OWN = '#4f46e5';
const WL_WAIT = '#0d9488';

function workloadBar(label, sub, own, waiting, max, onclick) {
  const total = own + waiting;
  const pct = v => (max ? Math.max(v / max * 100, v > 0 ? 1.5 : 0) : 0);
  return `<div class="wl-bar-row"${onclick ? ` data-emie-action="click:workload-member" data-user-id="${escHtml(onclick)}" role="button" tabindex="0"` : ''}>
    <span class="wl-bar-label"><b>${escHtml(label)}</b>${sub ? `<small>${escHtml(sub)}</small>` : ''}</span>
    <span class="wl-bar-track" title="自己要做 ${own} 项 · 等他人 ${waiting} 项 · 合计 ${total} 项">
      <i class="wl-seg wl-seg-own" style="width:${pct(own)}%"></i>
      <i class="wl-seg wl-seg-wait" style="width:${pct(waiting)}%"></i>
    </span>
    <span class="wl-bar-value"><b>${own}</b><small>${waiting ? '+' + waiting : ''}</small></span>
  </div>`;
}

function workloadCharts(members, data, expanded) {
  if (!members.length) return '';
  const ranked = [...members].sort((a, b) => b.pending - a.pending || b.waiting - a.waiting);
  const shown = expanded ? ranked : ranked.slice(0, 10);
  const maxMember = Math.max(...ranked.map(m => m.pending + m.waiting), 1);

  const roles = Object.entries(WORKLOAD_ROLES).map(([role, label]) => {
    const group = members.filter(m => m.role === role);
    return { label, own: group.reduce((a, m) => a + m.pending, 0), waiting: group.reduce((a, m) => a + m.waiting, 0), count: group.length };
  }).filter(r => r.count);
  const maxRole = Math.max(...roles.map(r => r.own + r.waiting), 1);

  const legend = `<span class="wl-legend"><i style="background:${WL_OWN}"></i>自己要做<i style="background:${WL_WAIT};margin-left:12px"></i>等他人</span>`;

  return `<section class="wl-charts">
    <div class="wl-chart">
      <div class="wl-chart-head"><h3>谁手上积压最多</h3>${legend}</div>
      ${shown.map(m => workloadBar(m.name, m.roleLabel, m.pending, m.waiting, maxMember, m.userId)).join('')}
      ${ranked.length > 10 ? `<button class="wl-more" data-emie-action="click:workload-expand-chart">${expanded ? '收起' : `展开全部 ${ranked.length} 人`}</button>` : ''}
    </div>
    <div class="wl-chart">
      <div class="wl-chart-head"><h3>按角色分布</h3>${legend}</div>
      ${roles.map(r => workloadBar(r.label, r.count + ' 人', r.own, r.waiting, maxRole, null)).join('')}
    </div>
  </section>`;
}

const WORKLOAD_STATUS_CLASS = { risk: 'risk', watch: 'watch', steady: 'normal', idle: 'idle' };
const rateText = user => (user.completionRate == null ? '—' : Math.round(user.completionRate) + '%');

function workloadMemberComparator(a, b, sort) {
  if (sort === 'total') return b.workloadTotal - a.workloadTotal;
  if (sort === 'pending') return b.pending - a.pending;
  // 本期无新增（完成率 null）排最后，唔好当 0% 顶上去
  if (sort === 'rate') return (a.completionRate ?? 101) - (b.completionRate ?? 101);
  return a.statusOrder - b.statusOrder || b.pending - a.pending;
}

/** 渠道 / 常规 一格搞掂，细字标同批完成数 */
/** 在手：渠道 / 常规；细字补返本期新增同完成，两个口径分开讲清楚 */
function workloadPair(channel, regular, created, completed) {
  const c = Number(channel || 0), r = Number(regular || 0), n = Number(created || 0);
  if (!c && !r && !n) return '<span class="muted">—</span>';
  return `<b>${c}</b><span class="sep">/</span><b>${r}</b><small>本期新增 ${n}，完成 ${Number(completed || 0)}</small>`;
}

/** 子任务难度占比：标准→复杂→重大 单色由浅到深（顺序型数据用顺序色阶） */
function workloadDifficulty(user) {
  const parts = [
    { key: 'standard', label: '标准', n: Number(user.outstandingStandard || 0), color: '#c7d2fe' },
    { key: 'complex', label: '复杂', n: Number(user.outstandingComplex || 0), color: '#818cf8' },
    { key: 'major', label: '重大', n: Number(user.outstandingMajor || 0), color: '#4338ca' },
    { key: 'unset', label: '未设置', n: Number(user.outstandingUnset || 0), color: '#e2e8f0' },
  ].filter(part => part.n > 0);
  const total = parts.reduce((sum, part) => sum + part.n, 0);
  if (!total) return '<span class="muted">—</span>';
  const tip = parts.map(part => `${part.label} ${part.n} 项（${Math.round(part.n / total * 100)}%）`).join(' · ');
  return `<span class="wl-diff" title="${escHtml(tip)}">
    <i>${parts.map(part => `<em style="width:${part.n / total * 100}%;background:${part.color}"></em>`).join('')}</i>
    <small>${parts.map(part => `${part.label} ${Math.round(part.n / total * 100)}%`).join(' · ')}</small>
  </span>`;
}

function workloadMemberTable(users, roleFilter, performanceMonth) {
  if (!users.length) return '<div class="empty">未找到员工</div>';
  const head = `<tr><th>成员</th><th>状态</th><th>在手项目 渠/常</th><th>在手子任务 渠/常</th><th>在手难度占比</th><th class="num">本期新增</th><th class="num">已完成</th><th>完成率</th><th class="num">自己要做</th><th class="num">等他人</th><th class="num">在手合计</th><th class="num">积分</th></tr>`;
  const row = user => {
    const statusClass = WORKLOAD_STATUS_CLASS[user.statusKey] || 'normal';
    const rate = user.completionRate == null ? null : Math.round(user.completionRate);
    return `<tr id="wl-row-${escHtml(user.userId)}" class="wl-row ${user.statusKey}" title="${escHtml(user.statusReason || '')}">
      <td><b>${escHtml(user.name)}</b><small>${escHtml([...new Set([user.roleLabel, user.title].filter(Boolean))].join(' · '))}</small></td>
      <td><em class="workload-status ${statusClass}">${escHtml(user.statusLabel)}</em></td>
      <td class="wl-mix">${workloadPair(user.projectsOutstandingChannel, user.projectsOutstandingRegular, user.projectsCreated, user.projectsCompletedFromCreated)}</td>
      <td class="wl-mix">${workloadPair(user.tasksOutstandingChannel, user.tasksOutstandingRegular, user.tasksCreated, user.tasksCompletedFromCreated)}</td>
      <td>${workloadDifficulty(user)}</td>
      <td class="num">${user.workloadTotal || '—'}</td>
      <td class="num">${user.completedInRange || '—'}</td>
      <td class="wl-rate">${rate == null ? '<span class="muted">—</span>' : `<i><em style="width:${rate}%"></em></i><b>${rate}%</b>`}</td>
      <td class="num strong-own">${user.pending || '—'}</td>
      <td class="num strong-wait">${user.waiting || '—'}</td>
      <td class="num">${user.outstandingAll || '—'}</td>
      <td class="num">${performanceMonth ? Number(user.performancePoints || 0) : '—'}</td>
    </tr>`;
  };
  const body = roleFilter !== 'all'
    ? users.map(row).join('')
    : Object.entries(WORKLOAD_ROLES).map(([role, label]) => {
        const group = users.filter(user => user.role === role);
        if (!group.length) return '';
        const attention = group.filter(user => user.statusKey === 'risk' || user.statusKey === 'watch').length;
        return `<tr class="wl-group"><td colspan="12">${label} <span>${group.length} 人${attention ? ` · ${attention} 人需关注` : ''}</span></td></tr>` + group.map(row).join('');
      }).join('');
  return `<div class="table-wrap wl-table"><table><thead>${head}</thead><tbody>${body}</tbody></table></div>`;
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
function toggleWorkloadChart() { EMIE.adminState.workloadChartExpanded = !EMIE.adminState.workloadChartExpanded; renderWorkload(); }
function renderWorkload() { const container = EMIE.workloadContainer || document.getElementById('adminContent'); if (container) renderAdminWorkload(container); }

EMIE.registerActions({ renderAdminWorkload, toggleWorkloadChart, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, focusWorkloadMember });
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
}
EMIE.registerModule('adminWorkload', { renderAdminWorkload, toggleWorkloadChart, switchWorkloadRange, setWorkloadQuery, setWorkloadSort, setWorkloadRole, setWorkloadDate, applyWorkloadDates, focusWorkloadMember });
