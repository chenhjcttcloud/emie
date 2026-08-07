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
const renderDepartmentTasks = (...args) => EMIE.actions.renderDepartmentTasks(...args);
const renderScoringView = (...args) => EMIE.actions.renderScoringView(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const renderProjectDetailContent = (...args) => EMIE.actions.renderProjectDetailContent(...args);
const renderProjectActions = (...args) => EMIE.actions.renderProjectActions(...args);
const pauseProject = (...args) => EMIE.actions.pauseProject(...args);
const resumeProject = (...args) => EMIE.actions.resumeProject(...args);
const plannerAcceptFromList = (...args) => EMIE.actions.plannerAcceptFromList(...args);
const clearSWRCache = (...args) => EMIE.actions.clearSWRCache(...args);

// EMIE 工作台：主渲染、状态看板、项目列表、任务列表与待评分视图
/** 左侧导航只允许由此函数写入，避免页面缓存、详情操作和工作台互相覆盖数据。 */
async function refreshNavigationBadges() {
  const badgeStats = await apiGet('/projects/badge-stats');
  const values = {
    badgeTotal: badgeStats.totalCount,
    badgeChannel: badgeStats.channelCount,
    badgeRegular: badgeStats.regularCount,
    badgeMyTasks: badgeStats.myTaskCount,
    badgeScoring: badgeStats.pendingScoreCount,
  };
  Object.entries(values).forEach(([id, value]) => {
    const element = document.getElementById(id);
    if (element) element.textContent = Number.isFinite(Number(value)) ? String(value) : '0';
  });
  return badgeStats;
}

/** 操作后增量刷新（轻量版：不重拉全量列表） */
async function refreshAfterMutation(pid) {
  // 清除所有缓存
  EMIE.state.cache.orders = [];
  clearSWRCache();

  // 并发：更新徽章 + 刷新视图
  await Promise.all([
    refreshNavigationBadges().catch(() => {}),

    // 按当前视图刷新
    (async () => {
      if (['orders', 'channel', 'regular', 'design-needs', 'tasks', 'scoring'].includes(EMIE.state.currentView)) {
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
    // 无论当前在哪个列表页，只要项目详情弹窗仍打开，就用服务端最新数据重绘。
    (async () => {
      if (!pid || !document.getElementById('projectDetailModal')) return;
      try {
        const detail = await apiGet(`/projects/${pid}`);
        const body = document.querySelector('#projectDetailModal .modal-body');
        const footer = document.getElementById('detailActions');
        if (body) body.innerHTML = renderProjectDetailContent(detail);
        if (footer) footer.innerHTML = renderProjectActions(detail);
      } catch (e) {
        console.warn('刷新项目详情失败', e);
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
function renderProjectRow(o, compact = false, showType = true) {
  const fallbackStatus = getProjectStatusInfo(o.status);
  // 独立业务类型可返回自己的状态文案（如设计需求 draft=待设计交付），
  // 不应再被通用项目状态字典覆盖。
  const st = {
    label: o.statusLabel || fallbackStatus.label,
    cls: o.statusCls || fallbackStatus.cls,
  };
  const rowOpen = `openListItemDetail('${o.type}',${o.id})`;
  if (compact) {
    const market = o.targetMarket ? (() => { try { return JSON.parse(o.targetMarket).join('/'); } catch(e) { return o.targetMarket; } })() : '-';
    return `<tr style="cursor:pointer;" tabindex="0" data-emie-onclick="${rowOpen}" data-emie-onkeydown="if(event.target===event.currentTarget&&(event.key==='Enter'||event.key===' ')){event.preventDefault();${rowOpen}}">
      <td><strong>#${o.id}</strong></td>
      ${showType ? `<td>${o.type === 'channel_custom' ? '📦 渠道' : '🏭 常规'}</td>` : ''}
      <td class="project-name-cell" title="${escHtml(displayText(o.productName, '未设置'))}">${escHtml(displayText(o.productName, '未设置'))}</td>
      <td><div>${escHtml(o.salesName || '-')}</div><div class="project-muted">${escHtml(o.plannerName || '未指定')}</div></td>
      <td><div>${escHtml(o.productCategory || '-')}</div><div class="project-muted">${escHtml(market)} · ${escHtml(o.priceRange || '-')}</div></td>
      <td>${o.approvedTaskCount}/${o.taskCount}<span class="project-muted"> · ${renderScore(o.score)}</span></td>
      <td>${formatDate(o.deadline)}</td>
      <td><span class="badge ${st.cls}">${st.label}</span></td>
      <td class="project-action-cell"><button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();${rowOpen}">查看</button></td>
    </tr>`;
  }
  return `<tr style="cursor:pointer;" tabindex="0" data-emie-onclick="${rowOpen}" data-emie-onkeydown="if(event.target===event.currentTarget&&(event.key==='Enter'||event.key===' ')){event.preventDefault();${rowOpen}}">
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
      <button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();${rowOpen}">查看</button>
      ${o.status === 'pending_planner' && EMIE.state.currentRole === 'planner' ? `<button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();plannerAcceptFromList(${o.id})" style="color:var(--success);border-color:var(--success);margin-left:4px;">接单</button>` : ''}
      ${['planner_accepted','in_progress','paused'].includes(o.status) ? `<button class="btn btn-outline btn-sm" data-emie-onclick="event.stopPropagation();${o.status === 'paused' ? `resumeProject(${o.id})` : `pauseProject(${o.id})`}" style="font-size:11px;margin-left:4px;color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};border-color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};">${o.status === 'paused' ? '▶ 继续' : '⏸ 暂停'}</button>` : ''}
    </td>
  </tr>`;
}

async function render() {
  const main = document.getElementById('mainContent');
  const renderId = (EMIE.state.renderId || 0) + 1;
  EMIE.state.renderId = renderId;
  const view = EMIE.state.currentView;
  showLoading(main);

  // 页面导航时不清空缓存（依赖 mutation 操作清空），导航切换无需重新拉取
  // 首次加载或缓存为空时才拉取

  try {
    const role = EMIE.state.currentRole;
    const uid = getCurrentUserId();

    // 三个项目列表使用服务端分页；进入时不再预加载全量项目。
    const isPagedProjectList = ['orders', 'channel', 'regular', 'design-needs'].includes(view);
    let orders = EMIE.state.cache.orders;
    if (!isPagedProjectList && (!orders || !orders.length)) {
      try { orders = await apiGet(`/projects?role=${role}&userId=${uid}`); } catch(e) {}
      EMIE.state.cache.orders = orders || [];
    }
    orders = orders || [];

    if (EMIE.state.renderId !== renderId) return;
    if (view === 'dashboard') {
      await renderDashboard(main, role, uid);
    } else if (view === 'orders') {
      await renderOrderList(main, null, role, uid, '', '/projects/page', renderId);
    } else if (view === 'channel') {
      await renderOrderList(main, 'channel_custom', role, uid, '', '/projects/page', renderId);
    } else if (view === 'regular') {
      await renderOrderList(main, 'regular', role, uid, '', '/projects/page', renderId);
    } else if (view === 'design-needs') {
      await renderOrderList(main, 'design_requirement', role, uid, '🎨 设计/送审需求', '/design-requirements/page', renderId);
    } else if (view === 'tasks') {
      await renderMyTasks(main, role, uid, EMIE.state.taskBucket || 'all');
    } else if (view === 'other-tasks') {
      await renderDepartmentTasks(main, role, uid, EMIE.state.taskBucket || 'all');
    } else if (view === 'scoring') {
      await renderScoringView(main, role, uid);
    } else if (view === 'admin' || view === 'logs') {
      await renderAdmin(main, role, uid);
    }

    // 侧栏每次重建后重新请求唯一统计源，页面缓存不得参与徽章计算。
    if (EMIE.state.renderId === renderId) await refreshNavigationBadges();
  } catch (e) {
    console.error('渲染错误:', e);
    main.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

// ==================== 徽章更新 ====================

EMIE.registerActions({
  refreshNavigationBadges,
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
