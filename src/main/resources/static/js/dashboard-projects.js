const EMIE = window.EMIE;
const renderAdmin = (...args) => EMIE.actions.renderAdmin(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const getProjectStatusInfo = (...args) => EMIE.actions.getProjectStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const renderScore = (...args) => EMIE.actions.renderScore(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const showLoading = (...args) => EMIE.actions.showLoading(...args);
const renderDashboard = (...args) => EMIE.actions.renderDashboard(...args);
const renderOrderList = (...args) => EMIE.actions.renderOrderList(...args);
const renderProjectTable = (...args) => EMIE.actions.renderProjectTable(...args);
const renderMyTasks = (...args) => EMIE.actions.renderMyTasks(...args);
const renderScoringView = (...args) => EMIE.actions.renderScoringView(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const renderProjectDetailContent = (...args) => EMIE.actions.renderProjectDetailContent(...args);
const renderProjectActions = (...args) => EMIE.actions.renderProjectActions(...args);
const pauseProject = (...args) => EMIE.actions.pauseProject(...args);
const resumeProject = (...args) => EMIE.actions.resumeProject(...args);
const plannerAcceptFromList = (...args) => EMIE.actions.plannerAcceptFromList(...args);

// EMIE 工作台：主渲染、状态看板、项目列表、任务列表与待评分视图
/** 操作后增量刷新（轻量版：不重拉全量列表） */
async function refreshAfterMutation(pid) {
  // 清除所有缓存
  EMIE.state.cache.orders = [];
  Object.keys(SWR_CACHE).forEach(k => delete SWR_CACHE[k]);

  // 并发：更新徽章 + 刷新视图
  await Promise.all([
    // 更新侧边栏徽章
    (async () => {
      try {
        const badgeStats = await apiGet(`/projects/badge-stats?role=${EMIE.state.currentRole}&userId=${getCurrentUserId()}`);
        const elScoring = document.getElementById('badgeScoring');
        if (elScoring) elScoring.textContent = badgeStats.pendingScoreCount || 0;
        const elMyTasks = document.getElementById('badgeMyTasks');
        if (elMyTasks) elMyTasks.textContent = badgeStats.myTaskCount || 0;
        const badgeTotal = document.getElementById('badgeTotal');
        if (badgeTotal) badgeTotal.textContent = badgeStats.totalCount || 0;
        const badgeChannel = document.getElementById('badgeChannel');
        if (badgeChannel) badgeChannel.textContent = badgeStats.channelCount || 0;
        const badgeRegular = document.getElementById('badgeRegular');
        if (badgeRegular) badgeRegular.textContent = badgeStats.regularCount || 0;
      } catch(e) {}
    })(),

    // 按当前视图刷新
    (async () => {
      if (EMIE.state.currentView === 'tasks' || EMIE.state.currentView === 'scoring') {
        await render();
      } else if (pid) {
        try {
          const detail = await apiGet(`/projects/${pid}`);
          updateProjectRow(pid, detail);
          if (document.getElementById('projectDetailModal')) {
            const body = document.querySelector('#projectDetailModal .modal-body');
            const footer = document.getElementById('detailActions');
            if (body) body.innerHTML = renderProjectDetailContent(detail);
            if (footer) footer.innerHTML = renderProjectActions(detail);
          }
        } catch(e) {}
      }
    })(),
  ]);
}

/** 更新列表中单个项目的行数据 */
function updateProjectRow(pid, detail) {
  const container = document.getElementById('projectListContainer');
  if (!container) return;
  // 从缓存中找到对应的项目数据并更新
  if (EMIE.state.cache.currentFilterData) {
    const idx = EMIE.state.cache.currentFilterData.findIndex(o => o.id === pid);
    if (idx >= 0) {
      // 用最新的 detail 数据合并到列表缓存中
      const st = getProjectStatusInfo(detail.status);
      EMIE.state.cache.currentFilterData[idx] = {
        ...EMIE.state.cache.currentFilterData[idx],
        status: detail.status,
        statusLabel: st.label,
        statusCls: st.cls,
        taskCount: detail.tasks?.length || 0,
        approvedTaskCount: detail.tasks?.filter(t => t.status === 'completed' || t.status === 'approved' || t.status === 'sales_approved' || t.status === 'admin_approved').length || 0,
        progressPercent: detail.progressPercent || 0,
        plannerName: detail.plannerName,
        salesName: detail.salesName,
      };
      // 如果当前表格可见，只替换对应行
      const rows = container.querySelectorAll('tbody tr');
      const targetRow = Array.from(rows).find(r => r.cells[0]?.textContent?.trim() === `#${pid}`);
      if (targetRow) {
        // 只是重新渲染这一行
        const newRowHtml = renderProjectRow(EMIE.state.cache.currentFilterData[idx]);
        targetRow.outerHTML = newRowHtml;
      } else {
        // 如果找不到行，整表重绘（兜底）
        container.innerHTML = renderProjectTable(EMIE.state.cache.currentFilterData);
      }
    }
  }
}

/** 渲染单行项目 */
function renderProjectRow(o) {
  const st = getProjectStatusInfo(o.status);
  return `<tr style="cursor:pointer;">
    <td><strong>#${o.id}</strong></td>
    <td style="font-size:12px;">${o.type === 'channel_custom' ? '📦 渠道' : '🏭 常规'}</td>
    <td>${escHtml(displayText(o.productName, '未设置'))}</td>
    <td>${o.salesName || '-'}</td>
    <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
    <td>${o.productCategory || '-'}</td>
    <td>${o.targetMarket ? (() => { try { return JSON.parse(o.targetMarket).join('/'); } catch(e) { return o.targetMarket; } })() : '-'}</td>
    <td style="font-size:12px;">${o.priceRange || '-'}</td>
    <td style="font-size:12px;">${o.approvedTaskCount}/${o.taskCount}</td>
    <td style="font-size:12px;">${renderScore(o.score)}</td>
    <td style="font-size:12px;">${formatDate(o.deadline)}</td>
    <td><span class="badge ${st.cls}" style="font-size:11px;">${st.label}</span></td>
    <td style="white-space:nowrap;">
      <button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();openProjectDetail(${o.id})">查看</button>
      ${o.status === 'pending_planner' && EMIE.state.currentRole === 'planner' ? `<button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();plannerAcceptFromList(${o.id})" style="color:var(--success);border-color:var(--success);margin-left:4px;">接单</button>` : ''}
      ${['planner_accepted','in_progress','paused'].includes(o.status) ? `<button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();${o.status === 'paused' ? `resumeProject(${o.id})` : `pauseProject(${o.id})`}" style="font-size:11px;margin-left:4px;color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};border-color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};">${o.status === 'paused' ? '▶ 继续' : '⏸ 暂停'}</button>` : ''}
    </td>
  </tr>`;
}

async function render() {
  const main = document.getElementById('mainContent');
  showLoading(main);

  // 页面导航时不清空缓存（依赖 mutation 操作清空），导航切换无需重新拉取
  // 首次加载或缓存为空时才拉取

  try {
    const role = EMIE.state.currentRole;
    const uid = getCurrentUserId();

    // 预加载项目列表（使用缓存避免重复请求）
    let orders = EMIE.state.cache.orders;
    if (!orders || !orders.length) {
      try { orders = await apiGet(`/projects?role=${role}&userId=${uid}`); } catch(e) {}
      EMIE.state.cache.orders = orders || [];
    }

    // 更新基础徽章（销售角色没有 total/regular 徽章，需要判空）
    const elTotal = document.getElementById('badgeTotal');
    if (elTotal) elTotal.textContent = orders.length;
    const elChannel = document.getElementById('badgeChannel');
    if (elChannel) elChannel.textContent = orders.filter(x => x.type === 'channel_custom').length;
    const elRegular = document.getElementById('badgeRegular');
    if (elRegular) elRegular.textContent = orders.filter(x => x.type === 'regular').length;

    if (EMIE.state.currentView === 'dashboard') {
      await renderDashboard(main, role, uid);
    } else if (EMIE.state.currentView === 'orders') {
      await renderOrderList(main, null, role, uid);
    } else if (EMIE.state.currentView === 'channel') {
      await renderOrderList(main, 'channel_custom', role, uid);
    } else if (EMIE.state.currentView === 'regular') {
      await renderOrderList(main, 'regular', role, uid);
    } else if (EMIE.state.currentView === 'tasks') {
      await renderMyTasks(main, role, uid);
    } else if (EMIE.state.currentView === 'scoring') {
      await renderScoringView(main, role, uid);
    } else if (EMIE.state.currentView === 'admin' || EMIE.state.currentView === 'logs') {
      await renderAdmin(main, role, uid);
    }

    // 计算徽章（独立 try/catch，不影响主渲染）
    try {
      let pendingScoreCount = 0;
      let myTaskCount = 0;
      // 使用批量徽章统计 API，避免 N 次详情查询
      const badgeStats = await apiGet(`/projects/badge-stats?role=${role}&userId=${uid}`);
      pendingScoreCount = badgeStats.pendingScoreCount || 0;
      myTaskCount = badgeStats.myTaskCount || 0;

      // 我的子任务（企划/管理员：进行中的项目数）
      if (role !== 'sales' && role !== 'designer' && role !== 'supplychain') {
        myTaskCount = orders.filter(o =>
          o.status === 'in_progress' || o.status === 'planner_accepted'
        ).length;
      }
      const elScoring = document.getElementById('badgeScoring');
      if (elScoring) elScoring.textContent = pendingScoreCount;
      if (document.getElementById('badgeMyTasks')) {
        document.getElementById('badgeMyTasks').textContent = myTaskCount;
      }
    } catch (e) {
      console.error('徽章计算错误:', e);
    }
  } catch (e) {
    console.error('渲染错误:', e);
    main.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

// ==================== 徽章更新 ====================

EMIE.registerActions({
  refreshAfterMutation,
  updateProjectRow,
  renderProjectRow,
  render,
});

EMIE.registerModule('dashboardProjects', {
  refreshAfterMutation,
  updateProjectRow,
  renderProjectRow,
  render,
});
