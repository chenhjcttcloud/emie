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
  let orders = EMIE.state.cache.orders;
  if (!orders || !orders.length) {
    const participating = role === 'designer' || role === 'supplychain' ? '&participating=true' : '';
    orders = await apiGet(`/projects?role=${role}&userId=${uid}${participating}`);
    EMIE.state.cache.orders = orders;
  }
  let title = '全部项目';
  if (type === 'channel_custom') { orders = orders.filter(o => o.type === 'channel_custom'); title = '📦 渠道定制单'; }
  else if (type === 'regular') { orders = orders.filter(o => o.type === 'regular'); title = '🏭 公司常规品'; }
  else title = '📋 全部项目';

  EMIE.state.cache.orders = orders;
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
        <option value="in_progress">进行中</option>
        <option value="completed">已完成</option>
        <option value="completed_pending_score">待评分</option>
        <option value="pending_planner">待企划接单</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索编号/描述..." data-emie-oninput="filterProjectList()" style="min-width:180px;" id="searchInput">
      <input type="date" class="form-input" id="filterDateStart" style="min-width:130px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:12px;">~</span>
      <input type="date" class="form-input" id="filterDateEnd" style="min-width:130px;" title="结束日期">
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
  else if (currentFilter === 'completed') filtered = filtered.filter(o => o.status === 'completed');
  else if (currentFilter === 'completed_pending_score') filtered = filtered.filter(o => o.status === 'completed_pending_score');
  else if (currentFilter === 'pending_planner') filtered = filtered.filter(o => o.status === 'pending_planner');

  // 搜索
  const q = document.getElementById('searchInput')?.value?.toLowerCase();
  if (q) filtered = filtered.filter(o => String(o.id).includes(q) || (o.productRequirements || '').toLowerCase().includes(q));

  // 日期范围筛选（按 deadline）
  const dateStart = document.getElementById('filterDateStart')?.value;
  const dateEnd = document.getElementById('filterDateEnd')?.value;
  if (dateStart || dateEnd) {
    filtered = filtered.filter(o => {
      if (!o.deadline) return !dateStart && !dateEnd;
      if (dateStart && o.deadline < dateStart) return false;
      if (dateEnd && o.deadline > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('projectListContainer');
  if (c) c.innerHTML = renderProjectTable(filtered);
}

function resetProjectFilters() {
  const statusEl = document.getElementById('projectStatusFilter');
  const searchEl = document.getElementById('searchInput');
  const dateStartEl = document.getElementById('filterDateStart');
  const dateEndEl = document.getElementById('filterDateEnd');
  if (statusEl) statusEl.value = 'all';
  if (searchEl) searchEl.value = '';
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
        <option value="in_progress">进行中</option>
        <option value="planner_accepted">待添加子任务</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索项目编号/描述..." data-emie-oninput="filterTaskProjects()" style="min-width:180px;" id="taskProjectSearch">
      <input type="date" class="form-input" id="taskProjectDateStart" style="min-width:130px;" title="要求日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="taskProjectDateEnd" style="min-width:130px;" title="要求日期止">
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
  if (q) list = list.filter(o => String(o.id).includes(q) || (o.productRequirements || '').toLowerCase().includes(q));
  if (dateStart || dateEnd) {
    list = list.filter(o => {
      if (!o.deadline) return !dateStart && !dateEnd;
      if (dateStart && o.deadline < dateStart) return false;
      if (dateEnd && o.deadline > dateEnd) return false;
      return true;
    });
  }
  const c = document.getElementById('taskProjectContainer');
  if (c) c.innerHTML = renderTaskProjectTable(list);
}

function resetTaskProjectFilters() {
  const filterEl = document.getElementById('taskProjectFilter');
  const searchEl = document.getElementById('taskProjectSearch');
  const dateStartEl = document.getElementById('taskProjectDateStart');
  const dateEndEl = document.getElementById('taskProjectDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterTaskProjects();
}

// ==================== 待评分页面 ====================

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
});

EMIE.registerModule('dashboardLists', {
  renderOrderList,
  filterProjectList,
  resetProjectFilters,
  renderMyTasks,
  filterTaskProjects,
  resetTaskProjectFilters,
});
