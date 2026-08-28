const EMIE = window.EMIE;
const renderAdminContent = (...args) => EMIE.actions.renderAdminContent(...args);
const renderRoleSwitcher = (...args) => EMIE.actions.renderRoleSwitcher(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const isModalOpen = (...args) => EMIE.actions.isModalOpen(...args);
const submitGuard = (...args) => EMIE.actions.submitGuard(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

const adminUserRoleLabels = { pending: '待分配', sales: '销售', planner: '产品企划', designer: '设计师', supplychain: '供应链', promotion: '产品推广', admin: '管理员' };

function adminUserRoleKey(role) {
  const raw = String(role || '').trim().toLowerCase();
  if (raw === '供应链' || raw === 'supply' || raw === 'supply_chain' || raw === 'supply-chain') return 'supplychain';
  if (raw === '管理员') return 'admin';
  if (raw === '销售') return 'sales';
  if (raw === '产品企划') return 'planner';
  if (raw === '设计师') return 'designer';
  if (raw === '产品推广') return 'promotion';
  return raw.replace(/[^a-z0-9]/g, '');
}

function adminUserRoleClass(role) {
  return adminUserRoleKey(role);
}

function adminUserStatusLabel(status) {
  if (status === 'pending') return '⏳ 待分配';
  if (status === 'disabled') return '❌ 停用';
  return '✅ 启用';
}

// ===== Admin: 用户管理 =====
async function renderAdminUsers(container, page = 0, filters = {}) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  let users = [], roles = [];
  let pageResult = { items: [], page, size: 30, total: 0, totalPages: 0 };
  try {
    const params = new URLSearchParams({ page: String(page), size: '30' });
    Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
    pageResult = await apiGet('/admin/users/page?' + params);
    users = pageResult.items || [];
    EMIE.state.adminUsers = users;
    EMIE.adminUserPage = { ...pageResult, container, page, filters };
    [roles] = await Promise.all([
      apiGet('/admin/roles'),
    ]);
  } catch(e) { /* ignore */ }
  roles.forEach(r => { if (!adminUserRoleLabels[adminUserRoleKey(r.name)]) adminUserRoleLabels[adminUserRoleKey(r.name)] = r.displayName || r.name; });
  const assignableRoles = roles.filter(r => r.name !== 'pending');

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>👥 用户管理 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">共 ${EMIE.adminUserPage?.total ?? users.length} 人</span></h3>
        <div style="display:flex;gap:8px;">
          <button class="btn btn-sm btn-outline" data-emie-action="click:users-refresh">🔄 刷新</button>
        </div>
      </div>
      <div class="config-card-body">
        <div class="admin-user-filters">
          <input type="text" id="userSearchInput" placeholder="🔍 搜索用户ID/姓名..." data-emie-action="input:users-filter" style="flex:1;max-width:300px;">
          <select id="userRoleFilter" data-emie-action="change:users-filter">
            <option value="">全部角色</option>
            <option value="pending">待分配</option>
            ${assignableRoles.map(r => `<option value="${escHtml(r.name)}">${escHtml(r.displayName || r.name)}</option>`).join('')}
          </select>
          <select id="userStatusFilter" data-emie-action="change:users-filter">
            <option value="">全部状态</option>
            <option value="pending">待分配</option>
            <option value="active">启用</option>
            <option value="disabled">停用</option>
          </select>
          <span class="admin-user-count" id="userCountDisplay">当前页 ${users.length} 人</span>
        </div>
        <div class="table-wrap">
          <table class="admin-user-table">
            <thead>
              <tr>
                <th style="width:40px;">#</th>
                <th>用户ID</th>
                <th>姓名</th>
                <th>角色</th>
                <th>状态</th>
                <th>手机号</th>
                <th>邮箱</th>
                <th style="width:280px;">操作</th>
              </tr>
            </thead>
            <tbody id="adminUserTableBody">
              ${users.map((u, i) => `
                <tr data-user-id="${escHtml(u.userId)}" data-role="${adminUserRoleKey(u.role)}" data-name="${escHtml(u.name)}" data-status="${u.status || 'active'}">
                  <td style="color:var(--gray-400);">${i + 1}</td>
                  <td><strong>${escHtml(u.userId)}</strong></td>
                  <td>${escHtml(u.name)}</td>
                  <td><span class="admin-user-role-badge role-${adminUserRoleClass(u.role)}">${adminUserRoleLabels[adminUserRoleKey(u.role)] || u.role}</span></td>
                  <td><span class="admin-user-status-badge status-${u.status || 'active'}">${adminUserStatusLabel(u.status)}</span></td>
                  <td>${escHtml(u.phone || '-')}</td>
                  <td>${escHtml(u.email || '-')}</td>
                  <td>
                    <div class="admin-user-actions">
                      <button class="btn-edit-user" data-emie-action="click:users-edit" data-user-id="${u.id}">✏️ 编辑</button>
                      <button class="btn-edit-role" data-emie-action="click:users-change-role" data-user-id="${u.id}" data-user-role="${escHtml(escJsString(u.role || ''))}" data-user-name="${escHtml(escJsString(u.name || ''))}">${u.status === 'pending' ? '✅ 分配角色' : '🔄 角色'}</button>
                      ${u.status === 'pending' ? '' : `<button class="btn-reset-pwd" data-emie-action="click:users-reset-password" data-user-id="${u.id}" data-user-name="${escHtml(escJsString(u.name || ''))}">🔑 密码</button>`}
                      ${u.userId !== EMIE.state.authUser.userId
                        ? `<button class="btn-status" data-emie-action="click:users-toggle-status" data-user-id="${u.id}" data-user-name="${escHtml(escJsString(u.name || ''))}" data-user-status="${escHtml(escJsString(u.status || 'active'))}">${(u.status === 'disabled') ? '✅ 启用' : '⛔ 停用'}</button>`
                        : ''}
                      ${u.userId !== EMIE.state.authUser.userId ? `<button class="btn-delete" data-emie-action="click:users-delete" data-user-id="${u.id}" data-user-name="${escHtml(escJsString(u.name || ''))}">🗑️ 删除</button>` : ''}
                    </div>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
          ${users.length === 0 ? '<div class="empty"><div class="empty-icon">📭</div><p>暂无用户数据</p></div>' : ''}
          ${pageResult.totalPages > 1 ? `<div class="project-pagination"><span>显示第 ${pageResult.page + 1} / ${pageResult.totalPages} 页，共 ${pageResult.total} 人</span><div><button class="btn btn-outline btn-sm" ${page <= 0 ? 'disabled' : ''} data-emie-action="click:users-page" data-page="${page - 1}">上一页</button><button class="btn btn-outline btn-sm" ${page >= pageResult.totalPages - 1 ? 'disabled' : ''} data-emie-action="click:users-page" data-page="${page + 1}">下一页</button></div></div>` : ''}
      </div>
    </div>`;
}

function filterAdminUsers() {
  clearTimeout(filterAdminUsers._timer);
  filterAdminUsers._timer = setTimeout(applyFilterAdminUsers, 250);
}

async function applyFilterAdminUsers() {
  const search = (document.getElementById('userSearchInput').value || '').toLowerCase();
  const roleFilter = document.getElementById('userRoleFilter').value;
  const statusFilter = document.getElementById('userStatusFilter').value;
  const state = EMIE.adminUserPage;
  if (!state?.container) return;
  await renderAdminUsers(state.container, 0, { keyword: search, role: roleFilter, status: statusFilter });
}

async function changeAdminUserPage(page) {
  const state = EMIE.adminUserPage;
  if (!state?.container || page < 0 || page >= state.totalPages) return;
  await renderAdminUsers(state.container, page, state.filters || {});
}

// ===== Admin: 编辑用户弹窗 =====
function openEditUserModal(userData) {
  if (isModalOpen()) return;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'editUserModal';
  modal.innerHTML = `
    <div class="modal modal-lg">
      <div class="modal-header"><button class="modal-close" data-emie-action="click:users-close-edit">✕</button><div class="modal-header-left"><div class="modal-title">✏️ 编辑用户：${escHtml(userData.name || userData.userId)}</div></div></div>
      <div class="modal-body">
        <div id="editUserError" style="color:var(--danger);font-size:13px;display:none;margin-bottom:12px;text-align:center;padding:8px;background:var(--danger-light);border-radius:6px;"></div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 用户ID</label>
            <input type="text" class="form-input" id="editUserId" value="${escHtml(userData.userId)}" placeholder="登录用ID" required>
          </div>
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 姓名</label>
            <input type="text" class="form-input" id="editUserName" value="${escHtml(userData.name || '')}" placeholder="真实姓名" required>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">手机号</label>
            <input type="tel" class="form-input" id="editUserPhone" value="${escHtml(userData.phone || '')}" placeholder="手机号">
          </div>
          <div class="form-group">
            <label class="form-label">邮箱</label>
            <input type="email" class="form-input" id="editUserEmail" value="${escHtml(userData.email || '')}" placeholder="邮箱地址">
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">新密码</label>
            <input type="password" class="form-input" id="editUserPassword" placeholder="留空则不修改密码" autocomplete="new-password">
            <div class="form-hint">留空保持原密码，填入新密码则自动加密存储</div>
          </div>
          <div class="form-group">
            <label class="form-label">确认新密码</label>
            <input type="password" class="form-input" id="editUserPasswordConfirm" placeholder="再次输入新密码" autocomplete="new-password">
          </div>
        </div>
        <div style="background:var(--gray-50);padding:12px 16px;border-radius:8px;margin-top:8px;">
          <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
            <span style="font-size:13px;color:var(--gray-500);">当前角色：<span class="admin-user-role-badge role-${adminUserRoleClass(userData.role)}">${escHtml(adminUserRoleLabels[adminUserRoleKey(userData.role)] || userData.role)}</span></span>
            <span style="font-size:13px;color:var(--gray-500);">当前状态：<span class="admin-user-status-badge status-${userData.status || 'active'}">${adminUserStatusLabel(userData.status)}</span></span>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-action="click:users-close-edit">取消</button>
        <button class="btn btn-primary" data-emie-action="click:users-submit-edit" data-user-id="${userData.id}">💾 保存修改</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitEditUser(userId) {
  const errEl = document.getElementById('editUserError');
  errEl.style.display = 'none';

  const newUserId = document.getElementById('editUserId').value.trim();
  const name = document.getElementById('editUserName').value.trim();
  const phone = document.getElementById('editUserPhone').value.trim();
  const email = document.getElementById('editUserEmail').value.trim();
  const password = document.getElementById('editUserPassword').value;
  const passwordConfirm = document.getElementById('editUserPasswordConfirm').value;

  // 校验
  if (!newUserId) { errEl.textContent = '用户ID不能为空'; errEl.style.display = ''; return; }
  if (!name) { errEl.textContent = '姓名不能为空'; errEl.style.display = ''; return; }

  if (password) {
    if (password.length < 6) { errEl.textContent = '密码至少6位'; errEl.style.display = ''; return; }
    if (password !== passwordConfirm) { errEl.textContent = '两次密码不一致'; errEl.style.display = ''; return; }
  }

  const body = { userId: newUserId, name, phone, email };
  if (password) body.password = password;

  try {
    await apiPut(`/admin/users/${userId}`, body);
    showAdminToast('✅ 用户资料已更新', 'success');
    closeM('editUserModal');
    EMIE.state.users = await apiGet('/users');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message;
    errEl.style.display = '';
  }
}

// ===== Admin: 停用/启用账号 =====
async function toggleUserStatus(userId, userName, currentStatus) {
  const action = currentStatus === 'pending' ? '拒绝' : (currentStatus === 'disabled' ? '启用' : '停用');
  if (!await EMIE.actions.showSystemConfirm(`⚠️ 确定要${action}用户「${userName}」吗？\n${action === '停用' ? '停用后该用户将无法登录系统。' : '启用后该用户可以正常登录。'}`)) return;

  try {
    const result = await apiPut(`/admin/users/${userId}/toggle-status`, {});
    showAdminToast(`✅ 用户「${userName}」已${result.status === 'active' ? '启用' : '停用'}`, 'success');
    EMIE.state.users = await apiGet('/users');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 操作失败: ' + e.message, 'error');
  }
}

async function refreshUserList() {
  EMIE.state.users = await apiGet('/users');
  await renderRoleSwitcher();
  await renderAdminContent();
}

async function openChangeRoleModal(userId, existingRole, userName) {
  if (isModalOpen()) return;
  const roles = await apiGet('/admin/roles').catch(() => []);
  const roleOptions = Object.fromEntries(roles.map(r => [r.name, r.displayName]));
  const needsAssignment = existingRole === 'pending';
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'changeRoleModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><button class="modal-close" data-emie-action="click:users-close-role">✕</button><div class="modal-header-left"><div class="modal-title">${needsAssignment ? '✅ 分配角色' : '✏️ 修改角色'}：${escHtml(userName)}</div></div></div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">当前角色</label>
          <div><span class="admin-user-role-badge role-${adminUserRoleClass(existingRole)}">${escHtml(adminUserRoleLabels[adminUserRoleKey(existingRole)] || existingRole)}</span></div>
        </div>
        <div class="form-group">
          <label class="form-label">新角色</label>
          <select class="form-select" id="newRoleSelect">
            ${needsAssignment ? '<option value="" selected disabled>请选择角色</option>' : ''}
            ${Object.entries(roleOptions).map(([k, v]) =>
              `<option value="${k}" ${k === existingRole ? 'selected' : ''}>${v}</option>`
            ).join('')}
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-action="click:users-close-role">取消</button>
        <button class="btn btn-primary" data-emie-action="click:users-submit-role" data-user-id="${userId}">确认修改</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitChangeRole(userId) {
  const newRole = document.getElementById('newRoleSelect').value;
  if (!newRole) {
    showAdminToast('请选择要分配的角色', 'warning');
    return;
  }
  try {
    const result = await apiPut(`/admin/users/${userId}/role`, { role: newRole });
    showAdminToast(result.status === 'active' ? '✅ 角色已分配，账号已启用' : '✅ 角色已更新', 'success');
    closeM('changeRoleModal');
    EMIE.state.users = await apiGet('/users');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 更新失败: ' + e.message, 'error');
  }
}

function openResetPwdModal(userId, userName) {
  if (isModalOpen()) return;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'resetPwdModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><button class="modal-close" data-emie-action="click:users-close-pwd">✕</button><div class="modal-header-left"><div class="modal-title">🔑 重置密码：${escHtml(userName)}</div></div></div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">新密码</label>
          <input type="password" class="form-input" id="newPwdInput" placeholder="输入新密码（至少6位）" minlength="6" style="text-align:center;">
        </div>
        <div class="form-group">
          <label class="form-label">确认密码</label>
          <input type="password" class="form-input" id="confirmPwdInput" placeholder="再次输入新密码" style="text-align:center;">
        </div>
        <div id="pwdError" style="color:var(--danger);font-size:13px;display:none;text-align:center;"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-action="click:users-close-pwd">取消</button>
        <button class="btn btn-warning" data-emie-action="click:users-submit-pwd" data-user-id="${userId}">确认重置</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitResetPwd(userId) {
  const pwd = document.getElementById('newPwdInput').value;
  const confirm = document.getElementById('confirmPwdInput').value;
  const errEl = document.getElementById('pwdError');
  if (!pwd || pwd.length < 6) {
    errEl.textContent = '密码至少6位';
    errEl.style.display = '';
    return;
  }
  if (pwd !== confirm) {
    errEl.textContent = '两次密码不一致';
    errEl.style.display = '';
    return;
  }
  errEl.style.display = 'none';
  try {
    await apiPut(`/admin/users/${userId}/reset-password`, { password: pwd });
    showAdminToast('✅ 密码已重置', 'success');
    closeM('resetPwdModal');
  } catch (e) {
    showAdminToast('❌ 重置失败: ' + e.message, 'error');
  }
}

async function confirmDeleteUser(userId, userName) {
  if (!await EMIE.actions.showSystemConfirm(`⚠️ 确定要删除用户「${userName}」吗？\n此操作不可恢复！`)) return;
  submitDeleteUser(userId);
}

async function submitDeleteUser(userId) {
  try {
    await apiDelete(`/admin/users/${userId}`);
    showAdminToast('✅ 用户已删除', 'success');
    EMIE.state.users = await apiGet('/users');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 删除失败: ' + e.message, 'error');
  }
}

// Toast 通知
function showAdminToast(message, type = 'success') {
  const existing = document.querySelector('.admin-toast');
  if (existing) existing.remove();
  const toast = document.createElement('div');
  toast.className = 'admin-toast ' + type;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transition = 'opacity .3s';
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}


EMIE.registerActions({
  renderAdminUsers,
  filterAdminUsers,
  applyFilterAdminUsers,
  changeAdminUserPage,
  openEditUserModal,
  submitEditUser,
  toggleUserStatus,
  refreshUserList,
  openChangeRoleModal,
  submitChangeRole,
  openResetPwdModal,
  submitResetPwd,
  confirmDeleteUser,
  submitDeleteUser,
  showAdminToast,
});

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('users-refresh', () => refreshUserList());
  registerEventAction('users-filter', () => filterAdminUsers());
  registerEventAction('users-edit', (_event, el) => openEditUserModal(EMIE.state.adminUsers?.find?.(u => Number(u.id) === Number(el.dataset.userId))));
  registerEventAction('users-change-role', (_event, el) => openChangeRoleModal(Number(el.dataset.userId), el.dataset.userRole || '', el.dataset.userName || ''));
  registerEventAction('users-reset-password', (_event, el) => openResetPwdModal(Number(el.dataset.userId), el.dataset.userName || ''));
  registerEventAction('users-toggle-status', (_event, el) => toggleUserStatus(Number(el.dataset.userId), el.dataset.userName || '', el.dataset.userStatus || 'active'));
  registerEventAction('users-delete', (_event, el) => confirmDeleteUser(Number(el.dataset.userId), el.dataset.userName || ''));
  registerEventAction('users-page', (_event, el) => changeAdminUserPage(Number(el.dataset.page)));
  registerEventAction('users-close-edit', () => closeM('editUserModal'));
  registerEventAction('users-close-role', () => closeM('changeRoleModal'));
  registerEventAction('users-close-pwd', () => closeM('resetPwdModal'));
  registerEventAction('users-submit-edit', (_event, el) => submitGuard(el, () => submitEditUser(Number(el.dataset.userId))));
  registerEventAction('users-submit-role', (_event, el) => submitGuard(el, () => submitChangeRole(Number(el.dataset.userId))));
  registerEventAction('users-submit-pwd', (_event, el) => submitGuard(el, () => submitResetPwd(Number(el.dataset.userId))));
}

EMIE.registerModule('adminUsers', {
  renderAdminUsers,
  filterAdminUsers,
  changeAdminUserPage,
  openEditUserModal,
  submitEditUser,
  toggleUserStatus,
  refreshUserList,
  openChangeRoleModal,
  submitChangeRole,
  openResetPwdModal,
  submitResetPwd,
  confirmDeleteUser,
  showAdminToast,
});
