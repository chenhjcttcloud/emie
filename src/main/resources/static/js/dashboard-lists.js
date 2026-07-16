const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const getProjectStatusInfo = (...args) => EMIE.actions.getProjectStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const renderDesignerTasks = (...args) => EMIE.actions.renderDesignerTasks(...args);
const renderProjectRow = (...args) => EMIE.actions.renderProjectRow(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const openCreateProject = (...args) => EMIE.actions.openCreateProject(...args);

async function renderOrderList(main, type, role, uid) {
  let title = '全部项目';
  if (type === 'channel_custom') title = '📦 渠道定制单';
  else if (type === 'regular') title = '🏭 公司常规品';
  else title = '📋 全部项目';

  const participating = role === 'designer' || role === 'supplychain';
  const state = EMIE.projectListState = { type, role, uid, participating, page: 0, total: 0, totalPages: 0, filters: {}, loading: false };
  main.innerHTML = `<div class="project-query-loading"><span class="project-query-spinner"></span>正在查询项目…</div>`;
  const result = await loadProjectListPage(0);

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h2 style="font-size:20px;">${title} <span id="projectListCount" style="font-size:13px;color:var(--gray-400);font-weight:400;">（${result.total} 个）</span></h2>
      <div style="display:flex;gap:8px;">
        ${EMIE.state.currentRole === 'sales' && type === 'channel_custom' ? `<button class="btn btn-primary" data-emie-onclick="openCreateProject('channel_custom')">➕ 新建渠道定制项目</button>` : ''}
        ${EMIE.state.currentRole === 'planner' && type === 'regular' ? `<button class="btn btn-primary" data-emie-onclick="openCreateProject('regular')">➕ 新建常规品设计项目</button>` : ''}
      </div>
    </div>
    <div class="filter-bar" style="margin-bottom:16px;">
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectStatusFilter">
        <option value="all">全部状态</option>
        <option value="draft">草稿</option>
        <option value="in_progress">进行中</option>
        <option value="paused">已暂停</option>
        <option value="completed">已完成</option>
        <option value="completed_pending_score">待评分</option>
        <option value="pending_planner">待企划接单</option>
        <option value="pending_terminate">终止确认中</option>
        <option value="terminated">已终止</option>
      </select>
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectCategoryFilter">
        <option value="all">全部类目</option>
        ${(EMIE.state.categories || []).map(c => `<option value="${escHtml(c.name)}">${escHtml(c.name)}</option>`).join('')}
      </select>
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectMarketFilter">
        <option value="all">全部市场</option>
        <option value="国内">国内</option>
        <option value="海外">海外</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索编号/描述..." data-emie-oninput="filterProjectList()" style="min-width:180px;" id="searchInput">
      <input type="date" class="form-input" id="filterDateStart" data-emie-onchange="filterProjectList()" style="min-width:130px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:12px;">~</span>
      <input type="date" class="form-input" id="filterDateEnd" data-emie-onchange="filterProjectList()" style="min-width:130px;" title="结束日期">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterProjectList()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetProjectFilters()">↺ 重置</button>
    </div>
    <div class="card project-list-card" id="projectListContainer">${renderProjectTable(result.items, { compact: true, showType: !type })}</div>
  `;
}

async function loadProjectListPage(page) {
  const state = EMIE.projectListState;
  if (!state) return { items: [], total: 0, totalPages: 0, page: 0 };
  const params = new URLSearchParams({ page: String(page), size: '15' });
  if (state.type) params.set('type', state.type);
  if (state.participating) params.set('participating', 'true');
  Object.entries(state.filters || {}).forEach(([key, value]) => {
    if (value) params.set(key, value);
  });
  const result = await apiGet(`/projects/page?${params}`);
  state.total = result.total || 0;
  state.totalPages = result.totalPages || 0;
  return result;
}

function renderProjectTable(orders, options = {}) {
  if (!orders.length) return `<div class="empty" style="padding:40px;"><div class="empty-icon">📭</div><p>暂无项目</p></div>`;
  const { compact = false, showType = true } = options;
  const headers = compact
    ? `<th class="col-id">编号</th>${showType ? '<th>类型</th>' : ''}<th class="col-name">产品名称</th><th>需求方 / 企划</th><th>类目 / 市场</th><th>进度</th><th>截止</th><th>状态</th><th class="col-action">操作</th>`
    : '<th>编号</th><th>类型</th><th>产品名称</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>价格</th><th>子任务</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th>';
  return `<div class="table-wrap project-table-wrap"><table class="${compact ? 'project-list-table' : ''}">
    <thead><tr>${headers}</tr></thead>
    <tbody>${orders.map(o => renderProjectRow(o, compact, showType)).join('')}</tbody>
  </table></div>${compact ? renderProjectPagination(orders.length) : ''}`;
}

function renderProjectPagination(currentCount) {
  const state = EMIE.projectListState || {};
  const total = state.total;
  const pages = state.totalPages;
  const page = state.page || 0;
  const from = total ? page * 15 + 1 : 0;
  const to = total ? page * 15 + currentCount : 0;
  return `<div class="project-pagination"><span>显示 ${from}-${to} / ${total} · 共 ${pages} 页</span><div><button class="btn btn-outline btn-sm" ${page <= 0 ? 'disabled' : ''} data-emie-onclick="changeProjectListPage(${page - 1})">上一页</button><span class="project-page-indicator">${pages ? `${page + 1} / ${pages}` : '0 / 0'}</span><button class="btn btn-outline btn-sm" ${page >= pages - 1 ? 'disabled' : ''} data-emie-onclick="changeProjectListPage(${page + 1})">下一页</button><span class="project-page-jump">跳至 <input id="projectPageJumpInput" type="number" min="1" max="${Math.max(pages, 1)}" value="${pages ? page + 1 : 1}" ${pages ? '' : 'disabled'}> 页</span><button class="btn btn-outline btn-sm" ${pages ? '' : 'disabled'} data-emie-onclick="jumpProjectListPage()">跳转</button></div></div>`;
}

function renderProjectListLoading() {
  return `<div class="project-query-loading"><span class="project-query-spinner"></span>正在查询项目…</div>`;
}

async function changeProjectListPage(page) {
  const state = EMIE.projectListState;
  if (!state || page < 0 || state.loading) return;
  const container = document.getElementById('projectListContainer');
  state.loading = true;
  if (container) container.innerHTML = renderProjectListLoading();
  try {
    const result = await loadProjectListPage(page);
    state.page = result.page ?? page;
    if (container) container.innerHTML = renderProjectTable(result.items || [], { compact: true, showType: !state.type });
    const count = document.getElementById('projectListCount');
    if (count) count.textContent = `（${result.total || 0} 个）`;
  } catch (e) {
    if (container) container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>查询失败：${escHtml(e.message)}</p></div>`;
  } finally {
    state.loading = false;
  }
}

function jumpProjectListPage() {
  const state = EMIE.projectListState;
  const input = document.getElementById('projectPageJumpInput');
  const pages = state?.totalPages;
  const requested = Number.parseInt(input?.value, 10);
  if (!Number.isInteger(requested) || requested < 1 || requested > pages) {
    alert(`请输入 1 至 ${pages || 1} 的页码`);
    return;
  }
  changeProjectListPage(requested - 1);
}

function filterProjectList() {
  clearTimeout(filterProjectList._timer);
  filterProjectList._timer = setTimeout(applyFilterProjectList, 100);
}

async function applyFilterProjectList() {
  const state = EMIE.projectListState;
  if (!state) return;
  const status = document.getElementById('projectStatusFilter')?.value || 'all';
  const category = document.getElementById('projectCategoryFilter')?.value || 'all';
  const market = document.getElementById('projectMarketFilter')?.value || 'all';
  state.filters = {
    ...(status !== 'all' ? { status } : {}),
    ...(category !== 'all' ? { category } : {}),
    ...(market !== 'all' ? { market } : {}),
    ...(document.getElementById('searchInput')?.value?.trim() ? { keyword: document.getElementById('searchInput').value.trim() } : {}),
    ...(document.getElementById('filterDateStart')?.value ? { deadlineStart: document.getElementById('filterDateStart').value } : {}),
    ...(document.getElementById('filterDateEnd')?.value ? { deadlineEnd: document.getElementById('filterDateEnd').value } : {}),
  };
  state.page = 0;
  await changeProjectListPage(0);
}

function resetProjectFilters() {
  const statusEl = document.getElementById('projectStatusFilter');
  const searchEl = document.getElementById('searchInput');
  const dateStartEl = document.getElementById('filterDateStart');
  const dateEndEl = document.getElementById('filterDateEnd');
  if (statusEl) statusEl.value = 'all';
  const categoryEl = document.getElementById('projectCategoryFilter');
  const marketEl = document.getElementById('projectMarketFilter');
  if (searchEl) searchEl.value = '';
  if (categoryEl) categoryEl.value = 'all';
  if (marketEl) marketEl.value = 'all';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  if (EMIE.projectListState) EMIE.projectListState.filters = {};
  changeProjectListPage(0);
}

// ==================== 我的子任务（企划派发任务界面） ====================
async function renderMyTasks(main, role, uid) {
  if (role === 'designer' || role === 'supplychain' || role === 'planner') {
    // 设计师/供应链/企划: 展示分配给自己的子任务卡片
    await renderDesignerTasks(main, uid);
    return;
  }

  // 其他角色: 展示项目列表，方便查看和添加子任务
  let orders = await apiGet(`/projects?role=${role}&userId=${uid}`);
  // 只显示进行中的项目（需要有子任务操作的项目）
  if (role !== 'admin') {
    orders = orders.filter(o =>
      o.status === 'in_progress' || o.status === 'planner_accepted'
    );
  }

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">📌 子任务管理 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${orders.length} 个项目)</span></h2>
    </div>
    <div class="filter-bar">
      <select class="form-select" data-emie-onchange="filterTaskProjects()" style="min-width:120px;" id="taskProjectFilter">
        <option value="all">全部项目</option>
        <option value="draft">草稿</option>
        <option value="paused">已暂停</option>
        <option value="in_progress">进行中</option>
        <option value="planner_accepted">待添加子任务</option>
        <option value="completed_pending_score">待评分</option>
        <option value="completed">已完成</option>
        <option value="pending_terminate">终止确认中</option>
        <option value="terminated">已终止</option>
      </select>
      <select class="form-select" data-emie-onchange="filterTaskProjects()" style="min-width:120px;" id="taskProjectTypeFilter">
        <option value="all">全部类型</option>
        <option value="channel_custom">渠道定制单</option>
        <option value="regular">公司常规品</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索项目编号/描述..." data-emie-oninput="filterTaskProjects()" style="min-width:180px;" id="taskProjectSearch">
      <input type="date" class="form-input" id="taskProjectDateStart" data-emie-onchange="filterTaskProjects()" style="min-width:130px;" title="要求日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="taskProjectDateEnd" data-emie-onchange="filterTaskProjects()" style="min-width:130px;" title="要求日期止">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterTaskProjects()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetTaskProjectFilters()">↺ 重置</button>
    </div>
    <div id="taskProjectContainer">${renderTaskProjectTable(orders)}</div>
  `;
  EMIE.dashboardState.taskProjectsCache = orders;
}

function renderTaskProjectTable(orders) {
  if (!orders.length) return `<div class="empty"><div class="empty-icon">📭</div><p>${EMIE.state.currentRole === 'admin' ? '暂无项目' : '暂无进行中的项目'}</p></div>`;
  return `<div class="card"><div class="table-wrap"><table>
    <thead><tr>
      <th>项目编号</th>
      <th>类型</th>
      <th>产品要求</th>
      <th>产品企划</th>
      <th>子任务</th>
      <th>要求时间</th>
      <th>状态</th>
      <th>操作</th>
    </tr></thead>
    <tbody>${orders.map(o => {
      const st = getProjectStatusInfo(o.status);
      return `<tr>
        <td><strong>#${o.id}</strong></td>
        <td>${o.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escHtml(o.productRequirements || '')}">${escHtml(o.productRequirements || '-')}</td>
        <td>${o.plannerName ? escHtml(o.plannerName) : '<span style="color:var(--gray-400);">未指定</span>'}</td>
        <td>${o.approvedTaskCount}/${o.taskCount}</td>
        <td>${formatDate(o.deadline)}</td>
        <td><span class="badge ${st.cls}">${st.label}</span></td>
        <td>
          <button class="btn btn-primary btn-sm" data-emie-onclick="openProjectDetail(${o.id})">📋 管理子任务</button>
          <button class="btn btn-outline btn-sm" data-emie-onclick="openProjectDetail(${o.id})">查看</button>
        </td>
      </tr>`;
    }).join('')}</tbody>
  </table></div></div>`;
}

function filterTaskProjects() {
  clearTimeout(filterTaskProjects._timer);
  filterTaskProjects._timer = setTimeout(applyFilterTaskProjects, 100);
}

function applyFilterTaskProjects() {
  const filter = document.getElementById('taskProjectFilter')?.value || 'all';
  const q = document.getElementById('taskProjectSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('taskProjectDateStart')?.value;
  const dateEnd = document.getElementById('taskProjectDateEnd')?.value;
  let list = EMIE.dashboardState.taskProjectsCache || [];
  if (filter !== 'all') list = list.filter(o => o.status === filter);
  const type = document.getElementById('taskProjectTypeFilter')?.value || 'all';
  if (type !== 'all') list = list.filter(o => o.type === type);
  if (q) list = list.filter(o => projectMatchesKeyword(o, q));
  list = list.filter(o => isDateInRange(o.deadline, dateStart, dateEnd));
  const c = document.getElementById('taskProjectContainer');
  if (c) c.innerHTML = renderTaskProjectTable(list);
}

function resetTaskProjectFilters() {
  const filterEl = document.getElementById('taskProjectFilter');
  const typeEl = document.getElementById('taskProjectTypeFilter');
  const searchEl = document.getElementById('taskProjectSearch');
  const dateStartEl = document.getElementById('taskProjectDateStart');
  const dateEndEl = document.getElementById('taskProjectDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (typeEl) typeEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterTaskProjects();
}

// ==================== 待评分页面 ====================

function normalizeFilterText(value) {
  if (Array.isArray(value)) return value.join(' ')
    .toLocaleLowerCase();
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) return parsed.join(' ').toLocaleLowerCase();
    } catch (e) { /* 普通文本 */ }
  }
  return String(value ?? '').toLocaleLowerCase();
}

function matchesSearchText(query, ...values) {
  const normalizedQuery = normalizeFilterText(query).trim();
  if (!normalizedQuery) return true;
  return values.some(value => normalizeFilterText(value).includes(normalizedQuery));
}

function projectMatchesKeyword(project, query) {
  return matchesSearchText(query,
    project.id, project.type, project.status, project.productName,
    project.productRequirements, project.salesName, project.plannerName,
    project.productCategory, project.targetMarket, project.complianceItems,
    project.priceRange, project.ipName
  );
}

function isDateInRange(value, start, end) {
  if (!start && !end) return true;
  const date = String(value || '').slice(0, 10);
  if (!date) return false;
  return (!start || date >= start) && (!end || date <= end);
}

EMIE.registerActions({
  renderOrderList,
  renderProjectTable,
  changeProjectListPage,
  jumpProjectListPage,
  filterProjectList,
  applyFilterProjectList,
  resetProjectFilters,
  renderMyTasks,
  renderTaskProjectTable,
  filterTaskProjects,
  applyFilterTaskProjects,
  resetTaskProjectFilters,
  normalizeFilterText,
  matchesSearchText,
  projectMatchesKeyword,
  isDateInRange,
});

EMIE.registerModule('dashboardLists', {
  renderOrderList,
  changeProjectListPage,
  jumpProjectListPage,
  filterProjectList,
  resetProjectFilters,
  renderMyTasks,
  filterTaskProjects,
  resetTaskProjectFilters,
});
