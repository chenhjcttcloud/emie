const EMIE = window.EMIE;
const render = (...args) => EMIE.actions.render(...args);
const hasPermission = (...args) => EMIE.actions.hasPermission(...args);

const NAV_DEFINITIONS = [
  { view: 'dashboard', icon: '📊', label: '工作台', permission: 'page.dashboard.view' },
  { view: 'orders', icon: '📋', label: '全部项目', permission: 'page.projects.view' },
  { view: 'channel', icon: '📦', label: '渠道定制单', permission: 'page.projects.channel.view' },
  { view: 'regular', icon: '🏭', label: '公司常规品', permission: 'page.projects.regular.view' },
  { view: 'design-needs', icon: '🎨', label: '设计/送审需求', permission: 'page.design_requirements.view' },
  { view: 'tasks', icon: '📌', label: '我的子任务', permission: 'page.subtasks.mine.view' },
  { view: 'other-tasks', icon: '🧭', label: '其他子任务', permission: 'page.subtasks.department.view' },
  { view: 'scoring', icon: '⭐', label: '评分', permission: 'page.scoring.view' },
  { view: 'admin', icon: '⚙️', label: '系统管理', permission: 'page.admin.view' },
];

function canNavigateTo(view) {
  const nav = NAV_DEFINITIONS.find(item => item.view === view);
  if (!nav || !hasPermission(nav.permission)) return false;
  return view !== 'other-tasks' || canViewOtherTasksNav();
}

// ==================== 侧边栏 ====================
function canViewOtherTasksNav() {
  if (EMIE.state.currentRole === 'admin') return true;
  if (EMIE.state.currentRole === 'planner') return true;
  if (!['sales', 'planner'].includes(EMIE.state.currentRole)) return false;
  const uid = EMIE.state.currentUserId;
  return (EMIE.state.departments || []).some(d =>
    d.active !== false && d.headUserId === uid && d.role === EMIE.state.currentRole
  );
}

function renderSidebar() {
  const navs = NAV_DEFINITIONS
    .filter(item => canNavigateTo(item.view))
    .map(item => ({ ...item, badge: '' }));
  if (!canNavigateTo(EMIE.state.currentView)) {
    EMIE.state.currentView = canNavigateTo('dashboard') ? 'dashboard' : navs[0]?.view || 'dashboard';
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
  if (!canNavigateTo(view)) return;
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
  canNavigateTo,
});

EMIE.registerModule('coreShell', {
  renderSidebar,
  navigate,
  toggleMobileSidebar,
  closeMobileSidebar,
});
