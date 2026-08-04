const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const getCurrentUserName = (...args) => EMIE.actions.getCurrentUserName(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const swrFetch = (...args) => EMIE.actions.swrFetch(...args);
const navigate = (...args) => EMIE.actions.navigate(...args);
const navigateTaskBucket = (...args) => EMIE.actions.navigateTaskBucket(...args);
const getProjectStatusInfo = (...args) => EMIE.actions.getProjectStatusInfo(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const renderScore = (...args) => EMIE.actions.renderScore(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const refreshNavigationBadges = (...args) => EMIE.actions.refreshNavigationBadges(...args);
let plannerBoardScope = 'mine';
let plannerScopeUserId = '';

async function updateBadges(role, uid) {
  return refreshNavigationBadges();
}

// ==================== 工作台 ====================
async function renderDashboard(main, role, uid) {
  if (role === 'planner' && plannerScopeUserId !== uid) {
    plannerScopeUserId = uid;
    plannerBoardScope = 'mine';
  }
  // 使用 SWR 缓存 + 聚合端点（1次API替代8次）
  const scopeParam = role === 'planner' ? plannerBoardScope : 'mine';
  const cacheKey = `dashboard_${role}_${uid}_${scopeParam}`;
  const data = await swrFetch(cacheKey,
    () => apiGet(`/dashboard/full?role=${role}&userId=${uid}&scope=${scopeParam}&includeRoleStatus=false`),
    30000
  );

  const orders = data.orders || [];
  const stats = data.stats || {};
  EMIE.state.departments = data.departments || [];

  // 更新全局缓存
  EMIE.state.cache.orders = orders;

  // 工作台只展示可继续推进的项目；终止/待终止/已完成项目进入项目历史，不占用行动视图。
  const activeOrders = orders.filter(o => !['terminated', 'pending_terminate', 'completed'].includes(String(o.status || '').toLowerCase()) && (o.actionableTaskCount || 0) > 0);
  const channel = activeOrders.filter(o => o.type === 'channel_custom');
  const regular = activeOrders.filter(o => o.type === 'regular');

  const myDept = EMIE.state.departments.find(d => d.headUserId === uid);
  const executionRole = ['designer', 'supplychain', 'promotion'].includes(EMIE.state.currentRole);
  const rolePanelsHtml = `<div id="dashboardRoleStatus" class="dashboard-role-status-loading">正在加载状态面板…</div>`;

  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">📊 工作台 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">— ${EMIE.state.currentRole === 'planner' ? `<select class="form-input" style="display:inline-block;width:auto;min-width:130px;padding:4px 28px 4px 8px;font-size:13px;vertical-align:middle;"><option value="mine" ${plannerBoardScope === 'mine' ? 'selected' : ''}>${escHtml(EMIE.state.authUser?.name || getCurrentUserName())}</option><option value="all" ${plannerBoardScope === 'all' ? 'selected' : ''}>全部产品企划</option></select>` : `${escHtml(EMIE.state.authUser?.name || getCurrentUserName())}（${roleLabel(EMIE.state.currentRole)}）`}</span></h2>
    ${!executionRole ? `<div class="stats-grid dashboard-stats-row">
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigate('orders')"><div class="stat-icon blue">📁</div><div><div class="stat-value">${stats.totalProjects}</div><div class="stat-label">${EMIE.state.currentRole === 'admin' ? '全部项目' : '我的项目'}</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigate('channel')"><div class="stat-icon blue">📦</div><div><div class="stat-value">${stats.channelProjects}</div><div class="stat-label">渠道定制单</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigate('regular')"><div class="stat-icon green">🏭</div><div><div class="stat-value">${stats.regularProjects}</div><div class="stat-label">公司常规品</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigate('orders')"><div class="stat-icon yellow">🔄</div><div><div class="stat-value">${stats.inProgress}</div><div class="stat-label">进行中项目</div></div></div>
    </div>
    <div class="stats-grid dashboard-stats-row">
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigateTaskBucket('all')"><div class="stat-icon blue">📌</div><div><div class="stat-value">${stats.allTasks ?? 0}</div><div class="stat-label">子任务总数</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigateTaskBucket('pending')"><div class="stat-icon yellow">⏳</div><div><div class="stat-value">${stats.pendingTasks}</div><div class="stat-label">待处理子任务</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigateTaskBucket('completed')"><div class="stat-icon green">✅</div><div><div class="stat-value">${stats.approvedTasks}</div><div class="stat-label">已完成子任务</div></div></div>
      <div class="stat-card" style="cursor:pointer" data-emie-onclick="navigate('scoring')"><div class="stat-icon yellow">⭐</div><div><div class="stat-value">${stats.pendingScore}</div><div class="stat-label">待评分</div></div></div>
    </div>` : ''}
    ${rolePanelsHtml}
    ${!executionRole && orders.length === 0 ? `<div class="empty"><div class="empty-icon">📭</div><p>暂无您负责的项目</p></div>` : ''}
    ${executionRole ? '<div id="dashboardExecutionTasks"><div class="empty" style="padding:24px;"><p>正在加载关联子任务…</p></div></div>' : renderProjectSummary(channel, '📦 渠道定制单') + renderProjectSummary(regular, '🏭 公司常规品')}
    <div id="dashboardWorkloadSection"></div>
  `;
  const plannerScopeSelect = main.querySelector('select.form-input');
  if (plannerScopeSelect) plannerScopeSelect.addEventListener('change', e => changePlannerBoardScope(e.target.value));
  // 仅 admin 可见工作量概览
  if (EMIE.state.currentRole === 'admin') {
    loadDashboardWorkloadSection();
  }
  loadDashboardRoleStatus(role, uid, myDept);
  if (executionRole) loadDashboardExecutionTasks(uid, EMIE.state.currentRole);
}

async function loadDashboardExecutionTasks(uid, role) {
  const container = document.getElementById('dashboardExecutionTasks');
  if (!container) return;
  try {
    const rows = await apiGet('/projects/my-subtasks');
    const tasks = (rows || []).filter(t => {
      const roleMatches = role === 'designer'
        ? (!t.assigneeRole || t.assigneeRole === 'designer')
        : t.assigneeRole === role;
      const projectIsActive = !['completed', 'pending_terminate', 'terminated']
        .includes(String(t.projectStatus || '').toLowerCase());
      return roleMatches && (t.designerId === uid || t.relation === 'assignee')
        && projectIsActive
        && ['pending', 'accepted', 'rejected'].includes(t.status);
    });
    const statusOrder = { rejected: 0, pending: 1, accepted: 2 };
    tasks.sort((a, b) => (statusOrder[a.status] ?? 9) - (statusOrder[b.status] ?? 9)
      || String(a.plannedDate || '9999-12-31').localeCompare(String(b.plannedDate || '9999-12-31')));
    EMIE.dashboardState.designerTaskCache = tasks;
    if (!tasks.length) {
      container.innerHTML = '<div class="empty"><div class="empty-icon">🎉</div><p>当前没有需要处理的关联子任务</p></div>';
      return;
    }
    const renderGroup = (groupTasks, title) => {
      if (!groupTasks.length) return '';
      const display = groupTasks.slice(0, 5);
      return `
        <div class="type-section">
          <div class="card" style="padding:0;">
            <div style="padding:20px 20px 0;">
              <div class="type-section-title">${title} <span class="count">共 ${groupTasks.length} 个</span></div>
            </div>
            <div style="padding:0 20px 20px;">
              <div class="table-wrap"><table>
                <thead><tr><th>子任务</th><th>所属项目</th><th>发布人</th><th>要求完成</th><th>状态</th><th>驳回意见</th><th>操作</th></tr></thead>
                <tbody>${display.map(t => {
                  const statusInfo = getTaskStatusInfo(t.status);
                  if (String(role || '').toLowerCase().replace(/[-_]/g, '') === 'supplychain'
                      && ['accepted', 'rejected'].includes(t.status)) {
                    statusInfo.label = '进行中';
                    statusInfo.icon = '🔧';
                  }
                  return `
                    <tr style="cursor:pointer;" data-emie-onclick="openPublishedSubTaskDetail(${t.id})">
                      <td><strong>${escHtml(t.name || '-')}</strong><div style="font-size:11px;color:var(--gray-400);margin-top:2px;">#${t.id}</div></td>
                      <td>${escHtml(t.projectName || '未命名项目')}</td>
                      <td>${escHtml(t.publisherName || '-')}</td>
                      <td>${formatDate(t.plannedDate)}</td>
                      <td><span class="badge ${statusInfo.cls}">${statusInfo.icon} ${statusInfo.label}</span></td>
                      <td style="max-width:260px;white-space:normal;">${t.status === 'rejected' ? escHtml(t.reviewComments || '待查看详情') : '—'}</td>
                      <td><button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();openPublishedSubTaskDetail(${t.id})">查看</button></td>
                    </tr>`;
                }).join('')}
                </tbody>
              </table></div>
              ${groupTasks.length > 5 ? '<div style="text-align:center;margin-top:8px;"><button class="btn btn-outline btn-sm" data-emie-onclick="navigate(\'tasks\')">查看全部关联子任务 →</button></div>' : ''}
            </div>
          </div>
        </div>`;
    };
    const pendingTasks = tasks.filter(t => t.status === 'pending');
    const designingTasks = tasks.filter(t => ['accepted', 'rejected'].includes(t.status));
    const activeTitle = String(role || '').toLowerCase().replace(/[-_]/g, '') === 'supplychain' ? '🔧 进行中' : '🎨 设计中';
    container.innerHTML = renderGroup(pendingTasks, '📥 待接单')
      + renderGroup(designingTasks, activeTitle);
  } catch (error) {
    container.innerHTML = `<div class="empty"><p>关联子任务加载失败：${escHtml(error.message)}</p></div>`;
  }
}

async function loadDashboardRoleStatus(role, uid, myDept) {
  const container = document.getElementById('dashboardRoleStatus');
  if (!container) return;
  try {
    const response = await apiGet('/dashboard/role-status?scope=' + (EMIE.state.currentRole === 'planner' ? plannerBoardScope : 'all'));
    const roleStatus = response || {};
    let html = '';
    if (EMIE.state.currentRole === 'admin') {
      html = renderRolePanelFromData(roleStatus.sales || {}, 'sales')
        + renderRolePanelFromData(roleStatus.planner || {}, 'planner')
        + renderRolePanelFromData(roleStatus.supplychain || {}, 'supplychain')
        + renderRolePanelFromData(roleStatus.designer || {}, 'designer');
    } else if (EMIE.state.currentRole === 'planner') {
      // 企划状态看板统一展示全部产品企划，包含部门负责人和当前用户。
      html = renderRolePanelFromData(roleStatus.planner || {}, 'planner')
        + renderRolePanelFromData(roleStatus.designer || {}, 'designer')
        + renderRolePanelFromData(roleStatus.supplychain || {}, 'supplychain');
    } else if (myDept) {
      html = renderRolePanelFromData(roleStatus[myDept.role] || {}, myDept.role, myDept.id, uid);
    }
  container.innerHTML = html;
  container.querySelectorAll('[data-user-card-id]').forEach(card => {
    card.addEventListener('click', () => showUserTasksPopup(card.dataset.userCardId, card.dataset.userCardName || '', card.dataset.userCardRole || ''));
  });
  } catch (error) {
    container.innerHTML = '<div class="empty" style="padding:20px;"><p>状态面板暂时无法加载</p></div>';
  }
}

async function changePlannerBoardScope(scope) {
  const nextScope = scope === 'all' ? 'all' : 'mine';
  if (nextScope === plannerBoardScope && document.querySelector('#dashboardRoleStatus')) return;
  plannerBoardScope = nextScope;
  const main = document.querySelector('main');
  if (main) {
    main.innerHTML = '<div class="loading">正在更新企划范围…</div>';
    await renderDashboard(main, EMIE.state.currentRole, getCurrentUserId());
  }
}

/** 在 dashboard 底部加载工作量看板 */
async function loadDashboardWorkloadSection() {
  const container = document.getElementById('dashboardWorkloadSection');
  if (!container) return;
  try {
    const data = await apiGet('/admin/workload/timeline?range=' + EMIE.dashboardState.workloadRange);
    const summary = data._summary || {};

    const rangeOpts = [
      { k: 'day', l: '今日' }, { k: 'week', l: '本周' }, { k: 'month', l: '本月' },
      { k: 'quarter', l: '本季度' }, { k: 'half-year', l: '本半年' }, { k: 'year', l: '本年度' }
    ];

    const isWorker = r => r === 'designer' || r === 'supplychain';
    const roleOrder = ['sales', 'planner', 'designer', 'supplychain'];

    let html = `<div style="margin-top:24px;border-top:2px solid var(--gray-200);padding-top:20px;">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:16px;font-weight:600;color:#1f2937;">📊 工作量概览</span>
        <span style="font-size:12px;color:var(--gray-400);margin-left:4px;">时间范围:</span>
        ${rangeOpts.map(o => `
          <button data-emie-onclick="switchDashWorkload('${o.k}')"
            style="padding:4px 12px;border-radius:6px;border:${o.k === EMIE.dashboardState.workloadRange ? '2px solid #3370FF' : '1px solid var(--gray-200)'};
            background:${o.k === EMIE.dashboardState.workloadRange ? '#E6F1FB' : '#fff'};
            color:${o.k === EMIE.dashboardState.workloadRange ? '#1E40AF' : '#374151'};
            font-size:12px;cursor:pointer;">${o.l}</button>
        `).join('')}
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:16px;">
        <div class="stat-card" style="cursor:default;"><div class="stat-icon blue">📁</div><div><div class="stat-value">${summary.totalProjectsCreated}</div><div class="stat-label">新建项目</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon green">✅</div><div><div class="stat-value">${summary.totalProjectsCompleted}</div><div class="stat-label">完成项目</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon blue">📌</div><div><div class="stat-value">${summary.totalTasksAssigned}</div><div class="stat-label">新分任务</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon green">✅</div><div><div class="stat-value">${summary.totalTasksCompleted}</div><div class="stat-label">完成任务</div></div></div>
      </div>`;

    for (const role of roleOrder) {
      const r = data[role];
      if (!r || !r.users || r.users.length === 0) continue;
      const w = isWorker(role);
      html += `<div class="card" style="margin-bottom:12px;">
        <div style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <div><span style="font-size:15px;margin-right:4px;">${r.icon||'👤'}</span><strong style="font-size:14px;">${r.label||role}</strong>
          <span style="font-size:12px;color:var(--gray-400);margin-left:6px;">${r.totalUsers} 人</span></div>
        </div>
        <div style="display:flex;padding:6px 16px;font-size:11px;color:var(--gray-400);border-bottom:1px solid var(--gray-100);">
          <div style="min-width:120px;">姓名</div>
          <div style="flex:1;display:flex;gap:12px;"><span style="width:50px;">${w?'新分配':'新建'}</span><span style="width:50px;">完成</span><span style="width:50px;">完成率</span></div>
        </div>`;
      for (const u of r.users) {
        const cr = u.created || u.assigned || 0;
        const cp = u.completed || 0;
        const rate = cr > 0 ? Math.round((cp / cr) * 100) + '%' : '-';
        html += `<div style="display:flex;align-items:center;padding:8px 16px;border-bottom:1px solid var(--gray-100);">
          <div style="min-width:120px;flex-shrink:0;">
            <div style="font-size:13px;font-weight:500;color:#1f2937;">${escHtml(u.name)}</div>
          </div>
          <div style="flex:1;display:flex;gap:12px;align-items:center;">
            <span style="width:50px;font-size:13px;font-weight:600;">${cr}</span>
            <span style="width:50px;font-size:13px;font-weight:600;color:#065F46;">${cp}</span>
            <span style="width:50px;font-size:12px;color:${rate === '-' ? 'var(--gray-400)' : '#374151'};">${rate}</span>
          </div>
          <div style="flex:1;max-width:100px;background:var(--gray-200);border-radius:4px;height:6px;overflow:hidden;">
            <div style="background:#639922;width:${cr > 0 ? Math.min(100, (cp / cr) * 100) : 0}%;height:100%;border-radius:4px;"></div>
          </div>
        </div>`;
      }
      html += '</div>';
    }
    html += '</div>';
    container.innerHTML = html;
  } catch(e) { /* silently ignore - workload section is optional */ }
}

function switchDashWorkload(range) {
  EMIE.dashboardState.workloadRange = range;
  loadDashboardWorkloadSection();
}

/** 通用角色状态面板（支持按部门分组）
 *  @param {string} role - 角色名
 *  @param {number|null} deptId - 可选，部门ID，只显示该部门成员
 *  @param {string|null} excludeUserId - 可选，排除某个用户ID（如部门负责人不显示自己）
 */
async function renderRolePanel(role, deptId, excludeUserId) {
  const roleEmoji = { sales: '💼', planner: '📋', supplychain: '🛒', designer: '👥' };
  const roleLabel_ = { sales: '销售', planner: '产品企划', supplychain: '供应链', designer: '设计师' };
  try {
    const status = await apiGet(`/projects/role-status?role=${role}`);
    let users = Object.values(status);

    // 排除指定用户（如部门负责人不显示自己）
    if (excludeUserId) {
      users = users.filter(u => u.id !== excludeUserId);
    }

    // 获取最新的用户数据（包含部门分配信息）
    let allUsersFlat;
    if (deptId || EMIE.state.departments.length > 0) {
      try {
        const freshUsers = await apiGet('/users');
        allUsersFlat = Object.values(freshUsers).flat();
        EMIE.state.users = freshUsers; // 更新全局缓存
      } catch(e) {
        allUsersFlat = Object.values(EMIE.state.users).flat();
      }
    } else {
      allUsersFlat = Object.values(EMIE.state.users).flat();
    }
    // 如果指定了部门ID，只保留该部门的用户
    if (deptId) {
      users = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return userObj && String(userObj.departmentId) === String(deptId);
      });
    }
    const busy = users.filter(u => u.busy);
    const idle = users.filter(u => !u.busy);

    // 按部门分组（如果有组织架构）
    const roleDepts = EMIE.state.departments.filter(d => d.role === role && d.active);
    let bodyHtml = '';
    if (roleDepts.length > 0) {
      // 找出所有未分配部门的用户
      const unknownUsers = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return !userObj || !userObj.departmentId;
      });
      // 有部门：按部门分组展示
      for (const dept of roleDepts) {
        const deptUsers = users.filter(u => {
          const userObj = allUsersFlat.find(us => us.userId === u.id);
          return userObj && String(userObj.departmentId) === String(dept.id);
        });
        if (deptUsers.length > 0) {
          bodyHtml += `
            <div style="margin-bottom:12px;">
              <div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;padding:0 4px;">
                🏢 ${escHtml(dept.name)}
                ${dept.headUserId ? `<span style="font-weight:400;font-size:12px;color:var(--gray-400);">（负责人：${(() => { const h = users.find(u => u.id === dept.headUserId); return h ? h.name : '—'; })()})</span>` : ''}
              </div>
              <div class="designer-grid">${deptUsers.map(u => renderUserCard(u, role)).join('')}</div>
            </div>`;
        }
      }
      // 尾部显示未分配部门的人员
      if (unknownUsers.length > 0) {
        bodyHtml += `
          <div style="margin-bottom:12px;">
            <div style="font-size:13px;font-weight:600;color:var(--gray-400);margin-bottom:8px;padding:0 4px;">📋 未分配部门</div>
            <div class="designer-grid">${unknownUsers.map(u => renderUserCard(u, role)).join('')}</div>
          </div>`;
      }
    } else {
      // 无部门：平铺展示
      bodyHtml = `<div class="designer-grid">${users.map(u => renderUserCard(u, role)).join('')}</div>`;
    }

    return `<div class="designer-status-panel">
      <div class="card-header">
        <div class="card-title">${roleEmoji[role] || '👤'} ${roleLabel_[role] || role}状态看板</div>
        <div style="display:flex;gap:12px;font-size:13px;">
          <span><span class="badge badge-busy" style="margin-right:4px;">🟡</span>忙碌 ${busy.length}人</span>
          <span><span class="badge badge-idle" style="margin-right:4px;">🟢</span>空闲 ${idle.length}人</span>
        </div>
      </div>
      ${bodyHtml}
    </div>`;
  } catch (e) {
    return '';
  }
}

/** 从预取数据渲染角色面板（替代 API 调用） */
function renderRolePanelFromData(statusData, role, deptId, excludeUserId) {
  const roleEmoji = { sales: '💼', planner: '📋', supplychain: '🛒', designer: '👥' };
  const roleLabel_ = { sales: '销售', planner: '产品企划', supplychain: '供应链', designer: '设计师' };
  let users = Object.values(statusData);

  // 过滤部门
  if (deptId) {
    const allUsers = Object.values(EMIE.state.users).flat();
    users = users.filter(u => {
      const userObj = allUsers.find(us => us.userId === u.id);
      return userObj && String(userObj.departmentId) === String(deptId);
    });
  }
  // 排除自己
  if (excludeUserId) {
    users = users.filter(u => u.id !== excludeUserId);
  }

  const busy = users.filter(u => u.busy);
  const idle = users.filter(u => !u.busy);

  // 按部门分组
  const roleDepts = EMIE.state.departments.filter(d => d.role === role && d.active);
  let bodyHtml = '';
  if (roleDepts.length > 0) {
    const allUsersFlat = Object.values(EMIE.state.users).flat();
    const unknownUsers = users.filter(u => {
      const userObj = allUsersFlat.find(us => us.userId === u.id);
      return !userObj || !userObj.departmentId;
    });
    for (const dept of roleDepts) {
      const deptUsers = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return userObj && String(userObj.departmentId) === String(dept.id);
      });
      if (deptUsers.length > 0) {
        bodyHtml += `<div style="margin-bottom:12px;">
          <div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;padding:0 4px;">
            🏢 ${escHtml(dept.name)}
            ${dept.headUserId ? `<span style="font-weight:400;font-size:12px;color:var(--gray-400);">（负责人：${(() => { const h = users.find(u => u.id === dept.headUserId); return h ? h.name : '—'; })()})</span>` : ''}
          </div>
          <div class="designer-grid">${deptUsers.map(u => renderUserCard(u, role)).join('')}</div>
        </div>`;
      }
    }
    if (unknownUsers.length > 0) {
      bodyHtml += `<div style="margin-bottom:12px;">
        <div style="font-size:13px;font-weight:600;color:var(--gray-400);margin-bottom:8px;padding:0 4px;">📋 未分配部门</div>
        <div class="designer-grid">${unknownUsers.map(u => renderUserCard(u, role)).join('')}</div>
      </div>`;
    }
  } else {
    bodyHtml = `<div class="designer-grid">${users.map(u => renderUserCard(u, role)).join('')}</div>`;
  }

  return `<div class="designer-status-panel">
    <div class="card-header">
      <div class="card-title">${roleEmoji[role] || '👤'} ${roleLabel_[role] || role}状态看板</div>
      <div style="display:flex;gap:12px;font-size:13px;">
        <span><span class="badge badge-busy" style="margin-right:4px;">🟡</span>忙碌 ${busy.length}人</span>
        <span><span class="badge badge-idle" style="margin-right:4px;">🟢</span>空闲 ${idle.length}人</span>
      </div>
    </div>
    ${bodyHtml}
  </div>`;
}

/** 渲染单个用户卡片 */
function renderUserCard(u, cardRole) {
  // 忙碌的用户整个卡片可点击，弹出任务列表
  const clickAttr = u.busy ? `data-user-card-id="${escHtml(u.id)}" data-user-card-name="${escHtml(u.name || '')}" data-user-card-role="${escHtml(cardRole || '')}" style="cursor:pointer;"` : '';
  return `<div class="designer-card ${u.busy ? 'busy' : 'idle'}" ${clickAttr}>
    <div class="designer-avatar">${u.name.charAt(0)}</div>
    <div class="designer-info">
      <div class="designer-name">${escHtml(u.name || '')}</div>
      <div class="designer-title">${escHtml(displayText(u.title, '未设置职级'))}</div>
      <div class="designer-tasks">
        ${u.busy
          ? (u.activeTasks
            ? `进行中：${u.activeTasks.length}个子任务`
            : `进行中：${u.activeProjects.length}个项目`)
          : `🟢 空闲`}
      </div>
    </div>
  </div>`;
}

/** 弹出用户任务/项目列表 */
function showUserTasksPopup(userId, userName, cardRole) {
  if (document.getElementById('userTasksPopup')) return;
  // 从页面上的角色状态数据中获取该用户的任务列表
  // 重新拉取角色状态数据
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'userTasksPopup';
  modal.onclick = function(e) { if (e.target === this) closeM('userTasksPopup'); };
  modal.innerHTML = `
    <div class="modal" style="max-width:500px;">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📋 ${escHtml(userName)} 的进行中任务</div></div><button class="modal-close" data-emie-onclick="closeM('userTasksPopup')">✕</button></div>
      <div class="modal-body" id="userTasksPopupBody">
        <div style="text-align:center;padding:20px;color:var(--gray-400);">加载中...</div>
      </div>
    </div>`;
  document.body.appendChild(modal);
  loadUserTasksPopup(userId, cardRole);
}

async function loadUserTasksPopup(userId, cardRole) {
  try {
    // 从 EMIE.state.users 缓存中找到该用户的角色
    const allUsers = Object.values(EMIE.state.users).flat();
    const userInfo = allUsers.find(u => u.userId === userId);
    let role = userInfo ? userInfo.role : 'designer';

    const data = await apiGet(`/projects/role-status?role=${role}`);
    const userData = Object.values(data).find(u => u.id === userId);
    if (!userData) {
      document.getElementById('userTasksPopupBody').innerHTML = '<div style="text-align:center;padding:20px;color:var(--gray-500);">暂无数据</div>';
      return;
    }
    const tasks = userData.activeTasks || [];
    const projects = cardRole === 'planner' ? [] : (userData.activeProjects || []);
    const body = document.getElementById('userTasksPopupBody');
    if (tasks.length === 0 && projects.length === 0) {
      body.innerHTML = '<div style="text-align:center;padding:20px;color:var(--success);">🟢 当前空闲，无进行中的任务</div>';
      return;
    }
    let html = '';
    if (tasks.length > 0) {
      html += `<div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;">子任务（${tasks.length}）</div>
        <div style="display:flex;flex-direction:column;gap:6px;">`;
      tasks.forEach(t => {
        const statusLabels = { pending: '⏳ 待接单', accepted: '🔄 进行中', rejected: '↩️ 已驳回', delivered: '📤 已交付' };
        html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;cursor:pointer;" data-emie-onclick="closeM('userTasksPopup');openProjectDetail(${t.projectId})">
          <span style="font-size:13px;">${escHtml(t.name)}</span>
          <span style="font-size:11px;color:var(--gray-500);">${statusLabels[t.status] || t.status}</span>
        </div>`;
      });
      html += `</div>`;
    }
    if (projects.length > 0) {
      html += `<div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-top:12px;margin-bottom:8px;">项目（${projects.length}）</div>
        <div style="display:flex;flex-direction:column;gap:6px;">`;
      projects.forEach(p => {
        html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;cursor:pointer;" data-emie-onclick="closeM('userTasksPopup');openProjectDetail(${p.id})">
          <span style="font-size:13px;">${escHtml(p.name)}</span>
          <span style="font-size:11px;color:var(--gray-500);">${p.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</span>
        </div>`;
      });
      html += `</div>`;
    }
    body.innerHTML = html;
  } catch(e) {
    document.getElementById('userTasksPopupBody').innerHTML = `<div style="text-align:center;padding:20px;color:var(--danger);">加载失败: ${escHtml(e.message || '未知错误')}</div>`;
  }
}

function renderProjectSummary(projects, title) {
  if (projects.length === 0) return '';
  const display = projects.slice(0, 5);
  return `
    <div class="type-section">
      <div class="card" style="padding:0;">
        <div style="padding:20px 20px 0 20px;">
          <div class="type-section-title">${title} <span class="count">共 ${projects.length} 个</span></div>
        </div>
        <div style="padding:0 20px 20px 20px;">
          <div class="table-wrap"><table>
        <thead><tr><th>项目编号</th><th>产品名称</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>子任务数</th><th>进度</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>${display.map(o => {
          const st = getProjectStatusInfo(o.status);
          return `<tr style="cursor:pointer;">
            <td><strong>${escHtml(o.projectCode || ('#' + o.id))}</strong></td>
            <td>${escHtml(displayText(o.productName, '未设置'))}</td>
            <td>${o.salesName || '-'}</td>
            <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
            <td>${o.productCategory || '-'}</td>
            <td>${o.targetMarket ? (() => { try { return JSON.parse(o.targetMarket).join('/'); } catch(e) { return o.targetMarket; } })() : '-'}</td>
            <td>${o.taskCount}（完成${o.approvedTaskCount}）</td>
            <td><div class="progress-bar" style="width:80px;"><div class="progress-fill" style="width:${o.progressPercent}%;"></div></div></td>
            <td>${renderScore(o.score)}</td>
            <td>${formatDate(o.deadline)}</td>
            <td><span class="badge ${st.cls}">${st.label}</span></td>
            <td><button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();openProjectDetail(${o.id})">查看</button></td>
          </tr>`;
        }).join('')}</tbody>
      </table></div>
      ${projects.length > 5 ? `<div style="text-align:center;margin-top:8px;"><button class="btn btn-outline btn-sm" data-emie-onclick="navigate('${title.includes('渠道') ? 'channel' : 'regular'}')">查看全部 →</button></div>` : ''}
      </div></div></div>`;
}

// ==================== 项目列表 ====================

EMIE.registerActions({
  changePlannerBoardScope,
  updateBadges,
  renderDashboard,
  loadDashboardWorkloadSection,
  switchDashWorkload,
  renderRolePanel,
  renderRolePanelFromData,
  renderUserCard,
  showUserTasksPopup,
  loadUserTasksPopup,
  renderProjectSummary,
});

EMIE.registerModule('dashboardHome', {
  changePlannerBoardScope,
  updateBadges,
  renderDashboard,
  loadDashboardWorkloadSection,
  switchDashWorkload,
  renderRolePanel,
  renderUserCard,
  showUserTasksPopup,
  loadUserTasksPopup,
  renderProjectSummary,
});
