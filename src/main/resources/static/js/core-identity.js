const EMIE = window.EMIE;
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const renderSidebar = (...args) => EMIE.actions.renderSidebar(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const render = (...args) => EMIE.actions.render(...args);

// ==================== 用户视角切换（天花板版） ====================
const ROLE_LABELS = { admin: '管理员', sales: '销售', planner: '企划', designer: '设计师', supplychain: '供应链' };
const ROLE_COLORS = { admin: 'admin', sales: 'sales', planner: 'planner', designer: 'designer', supplychain: 'supplychain' };
let _roleSwitcherRenderToken = 0;

async function renderRoleSwitcher() {
  const headerRight = document.querySelector('.header-right');
  const renderToken = ++_roleSwitcherRenderToken;
  // 仅 admin 显示切换
  if (!EMIE.state.originalUser || (EMIE.state.originalUser.role !== 'admin')) {
    document.getElementById('identitySwitcher')?.remove();
    return;
  }
  // 刷新用户列表，确保新增/角色变更及时同步
  try { EMIE.state.users = await apiGet('/users'); } catch(e) {}
  // 丢弃旧的异步渲染，避免多个请求各自插入一个切换器。
  if (renderToken !== _roleSwitcherRenderToken) return;
  if (!EMIE.state.users || Object.keys(EMIE.state.users).length === 0) {
    document.getElementById('identitySwitcher')?.remove();
    return;
  }

  document.getElementById('identitySwitcher')?.remove();

  // 构建所有用户列表
  const allUsers = [];
  const roleOrder = ['sales', 'planner', 'designer', 'supplychain', 'admin'];
  for (const role of roleOrder) {
    const users = EMIE.state.users[role];
    if (!users || users.length === 0) continue;
    for (const u of users) {
      allUsers.push({ userId: u.userId, name: u.name, role });
    }
  }

  const isSwitched = EMIE.state.currentUserId !== EMIE.state.originalUser.userId;
  const initial = EMIE.state.authUser.name.charAt(0);
  const roleKey = ROLE_COLORS[EMIE.state.authUser.role] || 'admin';

  const container = document.createElement('div');
  container.id = 'identitySwitcher';
  container.className = 'identity-switcher';
  container.innerHTML = `
    <button class="identity-trigger" data-emie-onclick="toggleIdentityPanel(event)" aria-label="切换用户视角" title="切换用户视角">
      <span class="identity-avatar role-${roleKey}">${initial}</span>
      <span class="identity-info">
        <span class="identity-name">${EMIE.state.authUser.name}</span>
        <span class="identity-role-tag">${ROLE_LABELS[EMIE.state.authUser.role] || EMIE.state.authUser.role}</span>
      </span>
      <svg class="identity-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
    </button>
    <div class="identity-panel" id="identityPanel">
      <div class="identity-search">
        <svg class="identity-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
        <input type="text" id="identitySearchInput" placeholder="搜索用户..." data-emie-oninput="filterUsers(this.value)" autocomplete="off">
      </div>
      <div class="identity-current">
        <span>当前身份</span>
        <span class="identity-current-line"></span>
      </div>
      <div class="identity-list" id="identityUserList">
        ${renderUserList(allUsers)}
      </div>
      ${isSwitched ? `
      <div style="padding:4px 10px 10px;">
        <button class="identity-back-btn" data-emie-onclick="switchToUser('${EMIE.state.originalUser.userId}')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
          返回我的视角
        </button>
      </div>` : ''}
    </div>
  `;

  const userDisplay = document.getElementById('userDisplay');
  if (userDisplay && userDisplay.nextSibling) {
    headerRight.insertBefore(container, userDisplay.nextSibling);
  } else {
    headerRight.appendChild(container);
  }
}

function renderUserList(users) {
  const groups = {};
  for (const u of users) {
    if (!groups[u.role]) groups[u.role] = [];
    groups[u.role].push(u);
  }
  const roleOrder = ['sales', 'planner', 'designer', 'supplychain', 'admin'];
  let html = '';
  for (const role of roleOrder) {
    const group = groups[role];
    if (!group || group.length === 0) continue;
    const label = ROLE_LABELS[role] || role;
    const dotClass = 'g-' + (ROLE_COLORS[role] || role);
    html += `<div class="identity-group" data-role="${role}">
      <div class="identity-group-label">
        <span class="identity-group-dot ${dotClass}"></span>${label}
      </div>`;
    for (const u of group) {
      const isActive = u.userId === EMIE.state.currentUserId;
      const avatarInitial = u.name.charAt(0);
      const uClass = 'u-' + (ROLE_COLORS[u.role] || u.role);
      const rClass = 'r-' + (ROLE_COLORS[u.role] || u.role);
      html += `<div class="identity-user${isActive ? ' active' : ''}" data-userid="${u.userId}" data-emie-onclick="switchToUser('${u.userId}')">
        <span class="identity-user-avatar ${uClass}">${avatarInitial}</span>
        <span class="identity-user-info">
          <div class="identity-user-name">${escHtml(u.name)}</div>
          <div class="identity-user-id">${escHtml(u.userId)}</div>
        </span>
        <span class="identity-user-role ${rClass}">${label}</span>
        <svg class="identity-user-check${isActive ? ' show' : ''}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
      </div>`;
    }
    html += '</div>';
  }
  return html || '<div class="identity-empty">暂无用户</div>';
}

async function switchToUser(targetUserId) {
  if (!targetUserId || targetUserId === EMIE.state.currentUserId) return;
  closeIdentityPanel();
  try {
    const viewBeforeSwitch = EMIE.state.currentView || 'dashboard';
    const result = await apiPost('/auth/impersonate', { userId: targetUserId });
    EMIE.state.authUser = { userId: result.userId, name: result.name, role: result.role, title: result.title };
    EMIE.state.currentRole = result.role;
    EMIE.state.currentUserId = result.userId;
    document.getElementById('userDisplay').textContent = `${EMIE.state.authUser.name}（${roleLabel(EMIE.state.authUser.role)}）`;
    await renderRoleSwitcher();
    // 角色切换后保留当前业务页面，避免先渲染旧角色再被重置到工作台。
    EMIE.state.currentView = viewBeforeSwitch;
    localStorage.setItem('design_pm_lastView', EMIE.state.currentView);
    renderSidebar();
    EMIE.state.cache.orders = [];
    await render();
  } catch (e) {
    alert(e.message || '切换失败');
  }
}

async function toggleIdentityPanel(event) {
  event.stopPropagation();
  let panel = document.getElementById('identityPanel');
  if (!panel) return;
  const isOpen = panel.classList.contains('open');
  if (isOpen) {
    closeIdentityPanel();
  } else {
    // 管理员可能在其他页面/窗口新增用户或修改角色，打开面板时重新拉取。
    if (EMIE.state.originalUser?.role === 'admin') {
      try {
        await renderRoleSwitcher();
        panel = document.getElementById('identityPanel');
      } catch (e) {
        console.warn('刷新用户切换列表失败', e);
      }
    }
    if (!panel) return;
    const chevron = document.querySelector('.identity-chevron');
    panel.classList.add('open');
    if (chevron) chevron.classList.add('open');
    // 清除搜索
    const input = document.getElementById('identitySearchInput');
    if (input) { input.value = ''; filterUsers(''); }
    // 点击外部关闭
    setTimeout(() => document.addEventListener('click', closeIdentityPanel, { once: true }), 0);
  }
}

function closeIdentityPanel() {
  const panel = document.getElementById('identityPanel');
  const chevron = document.querySelector('.identity-chevron');
  if (panel) panel.classList.remove('open');
  if (chevron) chevron.classList.remove('open');
}

function filterUsers(query) {
  clearTimeout(filterUsers._timer);
  filterUsers._timer = setTimeout(() => applyFilterUsers(query), 80);
}

function applyFilterUsers(query) {
  const q = query.toLowerCase().trim();
  const items = document.querySelectorAll('.identity-user');
  const groups = document.querySelectorAll('.identity-group');
  let hasVisible = false;
  items.forEach(item => {
    const name = item.querySelector('.identity-user-name')?.textContent.toLowerCase() || '';
    const id = item.querySelector('.identity-user-id')?.textContent.toLowerCase() || '';
    const match = !q || name.includes(q) || id.includes(q);
    item.classList.toggle('hidden', !match);
    if (match) hasVisible = true;
  });
  groups.forEach(g => {
    const visible = [...g.querySelectorAll('.identity-user')].some(el => !el.classList.contains('hidden'));
    g.classList.toggle('hidden', !visible);
  });
}

function getCurrentUserName() {
  // EMIE.state.currentUserId 使用的是业务 userId（如 admin_liu），而不是数据库数字主键 id。
  // 优先使用当前会话，避免工作台错误显示该角色列表中的第一个用户。
  if (EMIE.state.authUser?.userId === EMIE.state.currentUserId && EMIE.state.authUser.name) {
    return EMIE.state.authUser.name;
  }
  if (EMIE.state.currentUserId && EMIE.state.users[EMIE.state.currentRole]) {
    const u = EMIE.state.users[EMIE.state.currentRole].find(x => x.userId === EMIE.state.currentUserId);
    if (u) return u.name;
  }
  return EMIE.state.authUser?.name || EMIE.state.users[EMIE.state.currentRole]?.[0]?.name || '未知';
}

function getCurrentUserId() {
  if (EMIE.state.currentUserId) return EMIE.state.currentUserId;
  return EMIE.state.users[EMIE.state.currentRole]?.[0]?.userId || '';
}

function getUserName(id) {
  for (const arr of Object.values(EMIE.state.users)) {
    const u = arr.find(x => x.userId === id);
    if (u) return u.name;
  }
  return id || '未知';
}


EMIE.registerActions({
  renderRoleSwitcher,
  renderUserList,
  switchToUser,
  toggleIdentityPanel,
  closeIdentityPanel,
  filterUsers,
  applyFilterUsers,
  getCurrentUserName,
  getCurrentUserId,
  getUserName,
});

EMIE.registerModule('coreIdentity', {
  renderRoleSwitcher,
  renderUserList,
  switchToUser,
  toggleIdentityPanel,
  closeIdentityPanel,
  filterUsers,
  getCurrentUserName,
  getCurrentUserId,
  getUserName,
});
