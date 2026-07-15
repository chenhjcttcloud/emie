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
  // 使用缓存的项目列表
  let allOrders = EMIE.state.cache.orders;
  if (!allOrders || !allOrders.length) {
    const participating = role === 'designer' || role === 'supplychain' ? '&participating=true' : '';
    allOrders = await apiGet(`/projects?role=${role}&userId=${uid}${participating}`);
    EMIE.state.cache.orders = allOrders;
  }
  let orders = [...allOrders];
  let title = '全部项目';
  if (type === 'channel_custom') { orders = orders.filter(o => o.type === 'channel_custom'); title = '📦 渠道定制单'; }
  else if (type === 'regular') { orders = orders.filter(o => o.type === 'regular'); title = '🏭 公司常规品'; }
  else title = '📋 全部项目';

  // 保存当前列表的完整数据用于筛选
  EMIE.state.cache.currentFilterData = [...orders];

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h2 style="font-size:20px;">${title} <span style="font-size:13px;color:var(--gray-400);font-weight:400;">（${orders.length} 个）</span></h2>
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
    <div class="card" id="projectListContainer">${renderProjectTable(orders)}</div>
  `;
}

function renderProjectTable(orders) {
  if (!orders.length) return `<div class="empty" style="padding:40px;"><div class="empty-icon">📭</div><p>暂无项目</p></div>`;
  return `<div class="table-wrap"><table>
    <thead><tr><th>编号</th><th>类型</th><th>产品名称</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>价格</th><th>子任务</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
    <tbody>${orders.map(o => renderProjectRow(o)).join('')}</tbody>
  </table></div>`;
}

function filterProjectList() {
  clearTimeout(filterProjectList._timer);
  filterProjectList._timer = setTimeout(applyFilterProjectList, 100);
}

function applyFilterProjectList() {
  let filtered = EMIE.state.cache.currentFilterData || [...EMIE.state.cache.orders];

  // 状态筛选
  const currentFilter = document.getElementById('projectStatusFilter')?.value || 'all';
  if (currentFilter === 'in_progress') filtered = filtered.filter(o => o.status === 'in_progress' || o.status === 'planner_accepted');
  else if (currentFilter === 'draft') filtered = filtered.filter(o => o.status === 'draft');
  else if (currentFilter === 'paused') filtered = filtered.filter(o => o.status === 'paused');
  else if (currentFilter === 'completed') filtered = filtered.filter(o => o.status === 'completed');
  else if (currentFilter === 'completed_pending_score') filtered = filtered.filter(o => o.status === 'completed_pending_score');
  else if (currentFilter === 'pending_planner') filtered = filtered.filter(o => o.status === 'pending_planner');
  else if (currentFilter === 'pending_terminate') filtered = filtered.filter(o => o.status === 'pending_terminate');
  else if (currentFilter === 'terminated') filtered = filtered.filter(o => o.status === 'terminated');

  const category = document.getElementById('projectCategoryFilter')?.value || 'all';
  if (category !== 'all') filtered = filtered.filter(o => o.productCategory === category);

  const market = document.getElementById('projectMarketFilter')?.value || 'all';
  if (market !== 'all') filtered = filtered.filter(o => normalizeFilterText(o.targetMarket).includes(normalizeFilterText(market)));

  // 关键字覆盖项目编号、名称、需求、人员、类目、市场和价格等可见字段
  const q = document.getElementById('searchInput')?.value || '';
  if (q) filtered = filtered.filter(o => projectMatchesKeyword(o, q));

  // 日期范围筛选（按 deadline）
  const dateStart = document.getElementById('filterDateStart')?.value;
  const dateEnd = document.getElementById('filterDateEnd')?.value;
  filtered = filtered.filter(o => isDateInRange(o.deadline, dateStart, dateEnd));

  const c = document.getElementById('projectListContainer');
  if (c) c.innerHTML = renderProjectTable(filtered);
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
  filterProjectList();
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
  filterProjectList,
  resetProjectFilters,
  renderMyTasks,
  filterTaskProjects,
  resetTaskProjectFilters,
});
