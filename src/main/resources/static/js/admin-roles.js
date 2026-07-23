const EMIE = window.EMIE;
const renderAdminContent = (...args) => EMIE.actions.renderAdminContent(...args);
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const isModalOpen = (...args) => EMIE.actions.isModalOpen(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const renderRoleSwitcher = (...args) => EMIE.actions.renderRoleSwitcher(...args);

// ===== Admin: 角色管理 =====
async function renderAdminRoles(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  let [roles, permDefs] = await Promise.all([
    apiGet('/admin/roles').catch(() => []),
    apiGet('/admin/permission-defs').catch(() => []),
  ]);

  // 按组归类权限
  const groups = {};
  permDefs.forEach(p => {
    if (!groups[p.group]) groups[p.group] = [];
    groups[p.group].push(p);
  });

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>🔐 角色管理 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">共 ${roles.length} 个角色</span></h3>
        <button class="btn btn-sm btn-primary" data-emie-onclick="openCreateRoleModal()">➕ 新建角色</button>
      </div>
      <div class="config-card-body">
        <div class="table-wrap">
          <table class="admin-user-table">
            <thead>
              <tr>
                <th style="width:40px;">#</th>
                <th>角色标识</th>
                <th>显示名称</th>
                <th>描述</th>
                <th>权限数</th>
                <th>类型</th>
                <th style="width:160px;">操作</th>
              </tr>
            </thead>
            <tbody>
              ${roles.map((r, i) => `
                <tr>
                  <td style="color:var(--gray-400);">${i + 1}</td>
                  <td><strong>${escHtml(r.name)}</strong></td>
                  <td>${escHtml(r.displayName)}</td>
                  <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escHtml(r.description || '-')}</td>
                  <td><span class="badge badge-progress">${r.permissions.length}</span></td>
                  <td>${r.isSystem ? '<span class="admin-user-role-badge role-admin">系统</span>' : '<span class="admin-user-role-badge role-sales">自定义</span>'}</td>
                  <td>
                    <div class="admin-user-actions">
                      <button class="btn-edit-user" data-emie-onclick="openEditRoleModal(${r.id}, '${escHtml(r.name)}', '${escHtml(r.displayName)}', '${escHtml(r.description || '')}', ${JSON.stringify(r.permissions).replace(/"/g, "'")})">✏️ 编辑</button>
                      ${!r.isSystem ? `<button class="btn-delete" data-emie-onclick="confirmDeleteRole(${r.id}, '${escHtml(r.displayName)}')">🗑️ 删除</button>` : ''}
                    </div>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
        ${roles.length === 0 ? '<div class="empty"><div class="empty-icon">📭</div><p>暂无角色数据</p></div>' : ''}
      </div>
    </div>`;
}

// ===== 新建角色 =====
function openCreateRoleModal() {
  loadPermDefsAndOpenModal(null);
}

function openEditRoleModal(id, name, displayName, description, permissions) {
  loadPermDefsAndOpenModal({ id, name, displayName, description, permissions });
}

async function loadPermDefsAndOpenModal(editData) {
  if (isModalOpen()) return;
  const permDefs = await apiGet('/admin/permission-defs').catch(() => []);
  const groups = {};
  permDefs.forEach(p => {
    if (!groups[p.group]) groups[p.group] = [];
    groups[p.group].push(p);
  });

  const isEdit = editData !== null && editData.id != null;
  const selectedPerms = isEdit ? (editData.permissions || []) : [];

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'roleFormModal';
  modal.innerHTML = `
    <div class="modal modal-lg">
      <div class="modal-header"><button class="modal-close" data-emie-onclick="closeM('roleFormModal')">✕</button><div class="modal-header-left"><div class="modal-title">${isEdit ? '✏️ 编辑角色' : '➕ 新建角色'}</div></div></div>
      <div class="modal-body">
        <div id="roleFormError" style="color:var(--danger);font-size:13px;display:none;margin-bottom:12px;text-align:center;padding:8px;background:var(--danger-light);border-radius:6px;"></div>
        ${!isEdit ? `
        <div class="form-group">
          <label class="form-label"><span class="required">*</span> 角色标识</label>
          <input type="text" class="form-input" id="roleNameInput" placeholder="英文标识，如 senior_designer" value="">
          <div class="form-hint">唯一标识符，创建后不可修改。仅支持英文/数字/下划线</div>
        </div>` : `
        <div class="form-group">
          <label class="form-label">角色标识</label>
          <div style="padding:9px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:13px;color:var(--gray-600);"><strong>${escHtml(editData.name)}</strong></div>
        </div>`}
        <div class="form-row">
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 显示名称</label>
            <input type="text" class="form-input" id="roleDisplayNameInput" placeholder="如 高级设计师" value="${isEdit ? escHtml(editData.displayName) : ''}">
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input type="text" class="form-input" id="roleDescInput" placeholder="角色说明" value="${isEdit ? escHtml(editData.description) : ''}">
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">权限分配</label>
        </div>
        ${Object.entries(groups).map(([group, perms]) => `
          <div style="margin-bottom:16px;">
            <div style="font-size:13px;font-weight:600;color:var(--gray-700);margin-bottom:8px;padding-bottom:4px;border-bottom:1px solid var(--gray-200);">${group}</div>
            <div class="checkbox-group">
              ${perms.map(p => `
                <label class="checkbox-item ${selectedPerms.includes(p.key) ? 'checked' : ''}">
                  <input type="checkbox" name="perm" value="${p.key}" ${selectedPerms.includes(p.key) ? 'checked' : ''} data-emie-onchange="this.closest('.checkbox-item').classList.toggle('checked')">
                  <span>${p.label}</span>
                </label>
              `).join('')}
            </div>
          </div>
        `).join('')}
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('roleFormModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="${isEdit ? `submitEditRole(${editData.id})` : 'submitCreateRole()'}">${isEdit ? '💾 保存修改' : '✅ 创建角色'}</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitCreateRole() {
  const errEl = document.getElementById('roleFormError');
  errEl.style.display = 'none';

  const name = document.getElementById('roleNameInput').value.trim();
  const displayName = document.getElementById('roleDisplayNameInput').value.trim();
  const description = document.getElementById('roleDescInput').value.trim();

  if (!name || !/^[a-zA-Z0-9_]+$/.test(name)) {
    errEl.textContent = '角色标识仅支持英文/数字/下划线'; errEl.style.display = ''; return;
  }
  if (!displayName) { errEl.textContent = '显示名称不能为空'; errEl.style.display = ''; return; }

  const perms = Array.from(document.querySelectorAll('input[name="perm"]:checked')).map(el => el.value);

  try {
    await apiPost('/admin/roles', { name, displayName, description, permissions: perms });
    showAdminToast('✅ 角色已创建', 'success');
    closeM('roleFormModal');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message; errEl.style.display = '';
  }
}

async function submitEditRole(roleId) {
  const errEl = document.getElementById('roleFormError');
  errEl.style.display = 'none';

  const displayName = document.getElementById('roleDisplayNameInput').value.trim();
  const description = document.getElementById('roleDescInput').value.trim();
  if (!displayName) { errEl.textContent = '显示名称不能为空'; errEl.style.display = ''; return; }

  const perms = Array.from(document.querySelectorAll('input[name="perm"]:checked')).map(el => el.value);

  try {
    await apiPut(`/admin/roles/${roleId}`, { displayName, description, permissions: perms });
    showAdminToast('✅ 角色已更新', 'success');
    closeM('roleFormModal');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message; errEl.style.display = '';
  }
}

function confirmDeleteRole(roleId, displayName) {
  if (!confirm(`⚠️ 确定要删除角色「${displayName}」吗？\n删除后该角色下所有用户将失去对应权限。`)) return;
  submitDeleteRole(roleId);
}

async function submitDeleteRole(roleId) {
  try {
    await apiDelete(`/admin/roles/${roleId}`);
    showAdminToast('✅ 角色已删除', 'success');
    await renderRoleSwitcher();
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 删除失败: ' + e.message, 'error');
  }
}


EMIE.registerActions({
  renderAdminRoles,
  openCreateRoleModal,
  openEditRoleModal,
  loadPermDefsAndOpenModal,
  submitCreateRole,
  submitEditRole,
  confirmDeleteRole,
  submitDeleteRole,
});

EMIE.registerModule('adminRoles', {
  renderAdminRoles,
  openCreateRoleModal,
  openEditRoleModal,
  submitCreateRole,
  submitEditRole,
  confirmDeleteRole,
});
