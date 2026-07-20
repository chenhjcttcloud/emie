const EMIE = window.EMIE;
const render = (...args) => EMIE.actions.render(...args);

// ==================== 侧边栏 ====================
function renderSidebar() {
  const navs = [];

  if (EMIE.state.currentRole === 'sales') {
    // 销售：工作台、全部项目、渠道定制单、公司常规品、待评分
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'other-tasks', icon: '🧭', label: '其他子任务', badge: '' });
    navs.push({ view: 'scoring', icon: '⭐', label: '评分', badge: 'badgeScoring' });
  } else if (EMIE.state.currentRole === 'admin') {
    // 管理员：工作台 + 系统管理
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'tasks', icon: '📌', label: '我的子任务', badge: 'badgeMyTasks' });
    navs.push({ view: 'other-tasks', icon: '🧭', label: '其他子任务', badge: '' });
    navs.push({ view: 'scoring', icon: '⭐', label: '评分', badge: 'badgeScoring' });
    navs.push({ view: 'admin', icon: '⚙️', label: '系统管理', badge: '' });
  } else {
    // 其他角色：显示全部导航
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'tasks', icon: '📌', label: '我的子任务', badge: 'badgeMyTasks' });
    navs.push({ view: 'other-tasks', icon: '🧭', label: '其他子任务', badge: '' });
    navs.push({ view: 'scoring', icon: '⭐', label: '评分', badge: 'badgeScoring' });
  }

  const sidebar = document.getElementById('sidebarContainer');
  if (!sidebar) return;

  sidebar.innerHTML = navs.map(n => `
    <button class="nav-item ${n.view === EMIE.state.currentView ? 'active' : ''}" data-emie-onclick="navigate('${n.view}')">
      <span class="nav-icon">${n.icon}</span>${n.label}
      ${n.badge ? `<span class="nav-badge" id="${n.badge}">0</span>` : ''}
    </button>
    ${n.view === 'tasks' && n.view === EMIE.state.currentView ? `<div class="nav-submenu">
      <button class="nav-subitem ${EMIE.state.taskBucket !== 'pending' && EMIE.state.taskBucket !== 'completed' ? 'active' : ''}" data-emie-onclick="navigateTaskBucket('all')">全部子任务</button>
      <button class="nav-subitem ${EMIE.state.taskBucket === 'pending' ? 'active' : ''}" data-emie-onclick="navigateTaskBucket('pending')">待处理子任务</button>
      <button class="nav-subitem ${EMIE.state.taskBucket === 'completed' ? 'active' : ''}" data-emie-onclick="navigateTaskBucket('completed')">已完成子任务</button>
    </div>` : ''}
  `).join('');
}

// ==================== 导航 ====================
function navigate(view) {
  EMIE.state.currentView = view;
  if (view !== 'tasks') EMIE.state.taskBucket = 'all';
  // 保存当前浏览页面（刷新后恢复）
  localStorage.setItem('design_pm_lastView', view);
  renderSidebar();
  render();
  closeMobileSidebar();
  // 移动端切换页面后回顶部（延迟确保渲染完成）
  setTimeout(() => {
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
  }, 50);
}

function navigateTaskBucket(bucket) {
  EMIE.state.currentView = 'tasks';
  EMIE.state.taskBucket = ['pending', 'completed'].includes(bucket) ? bucket : 'all';
  localStorage.setItem('design_pm_taskBucket', EMIE.state.taskBucket);
  renderSidebar();
  render();
  closeMobileSidebar();
}

// ==================== 移动端侧栏 ====================
function toggleMobileSidebar() {
  const sidebar = document.getElementById('sidebarContainer');
  const overlay = document.getElementById('sidebarOverlay');
  const button = document.getElementById('hamburgerBtn');
  if (!sidebar || !overlay) return;
  const open = !sidebar.classList.contains('open');
  sidebar.classList.toggle('open', open);
  overlay.classList.toggle('open', open);
  button?.setAttribute('aria-expanded', String(open));
  document.body.classList.toggle('mobile-sidebar-open', open);
}

function closeMobileSidebar() {
  const sidebar = document.getElementById('sidebarContainer');
  const overlay = document.getElementById('sidebarOverlay');
  if (!sidebar || !overlay) return;
  sidebar.classList.remove('open');
  overlay.classList.remove('open');
  document.getElementById('hamburgerBtn')?.setAttribute('aria-expanded', 'false');
  document.body.classList.remove('mobile-sidebar-open');
}

document.addEventListener('keydown', function(event) {
  if (event.key === 'Escape') closeMobileSidebar();
});


EMIE.registerActions({
  renderSidebar,
  navigate,
  navigateTaskBucket,
  toggleMobileSidebar,
  closeMobileSidebar,
});

EMIE.registerModule('coreShell', {
  renderSidebar,
  navigate,
  toggleMobileSidebar,
  closeMobileSidebar,
});
