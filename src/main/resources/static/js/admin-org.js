const EMIE = window.EMIE;
const switchAdminTab = (...args) => EMIE.actions.switchAdminTab(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const isModalOpen = (...args) => EMIE.actions.isModalOpen(...args);
const submitGuard = (...args) => EMIE.actions.submitGuard(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

function adminOrgRoleClass(role) {
  const raw = String(role || '').trim().toLowerCase();
  if (raw === '供应链' || raw === 'supply' || raw === 'supply_chain' || raw === 'supply-chain') return 'supplychain';
  if (raw === '管理员') return 'admin';
  const normalized = raw.replace(/[^a-z0-9]/g, '');
  return normalized === 'supplychain' ? 'supplychain' : normalized;
}

function adminOrgRoleKey(role) {
  return adminOrgRoleClass(role);
}

async function refreshOrgData() {
  try {
    const [users, depts] = await Promise.all([
      apiGet('/users'),
      apiGet('/departments'),
    ]);
    EMIE.state.users = users;
    EMIE.state.departments = depts;
  } catch(e) {}
}

  // ==================== 管理员：组织架构管理 ====================
async function renderAdminOrg(container) {
  const [depts, usersData, roles] = await Promise.all([
    apiGet('/departments'),
    apiGet('/users'),
    apiGet('/admin/roles'),
  ]);
  // 同步全局数据，避免组织架构操作继续使用进入页面前的旧列表。
  EMIE.state.departments = depts;
  EMIE.state.users = usersData;
  // 展平所有用户
  const allUsers = Object.values(usersData).flat();
  const roleLabels = Object.fromEntries(roles.map(r => [r.name, r.displayName || r.name]));

  // 确保部门负责人也计入部门成员（即使 departmentId 未同步）
  for (const d of depts) {
    if (d.headUserId) {
      const headUser = allUsers.find(u => u.userId === d.headUserId);
      if (headUser && !headUser.departmentId) {
        headUser.departmentId = String(d.id);
      }
    }
  }

  container.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h3 style="font-size:16px;margin:0;">🏢 组织架构</h3>
      <button class="btn btn-primary btn-sm" data-emie-onclick="openCreateDeptModal()">➕ 新建部门</button>
    </div>
    ${depts.length === 0 ? `<div class="empty"><div class="empty-icon">🏢</div><p>暂无部门，点击上方按钮创建</p></div>` : `
    <div style="display:flex;flex-direction:column;gap:12px;">
      ${depts.map(d => {
        const headUser = allUsers.find(u => u.userId === d.headUserId);
        const members = allUsers.filter(u => u.departmentId === String(d.id));
        const roleLabel_ = roleLabels[d.role] || d.role;
        return `<div class="card" style="padding:16px;">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
            <div>
              <strong style="font-size:15px;">${escHtml(d.name)}</strong>
              <span class="admin-user-role-badge role-${adminOrgRoleClass(d.role)}" style="margin-left:8px;font-size:11px;">${escHtml(roleLabel_)}</span>
              ${!d.active ? `<span style="color:var(--danger);font-size:12px;margin-left:8px;">⛔ 已停用</span>` : ''}
            </div>
            <div style="display:flex;gap:6px;">
              <button class="btn btn-outline btn-sm" data-emie-onclick="editDept(${JSON.stringify(d).replace(/"/g,"'")})">✏️ 编辑</button>
              <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" data-emie-onclick="deleteDept(${d.id})">🗑️ 删除</button>
            </div>
          </div>
          <div style="font-size:13px;color:var(--gray-500);margin-bottom:8px;">
            ${headUser ? `👤 部门负责人：<strong>${escHtml(headUser.name)}</strong>` : `<span style="color:var(--warning);">⚠️ 未设置负责人</span>`}
            ｜ 👥 ${members.length} 人
          </div>
          ${members.length > 0 ? `<div style="display:flex;flex-wrap:wrap;gap:6px;">
            ${members.map(u => {
              const titleLevelLabel = u.titleLevel === '2' ? '（负责人）' : u.titleLevel === '1' ? '（组长）' : '';
              const isHeadUser = d.headUserId === u.userId;
              return `<span style="display:inline-flex;align-items:center;gap:4px;padding:4px 10px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:12px;">
                ${escHtml(u.name)}${titleLevelLabel}
                ${isHeadUser ? '<span style="color:var(--gray-300);font-size:11px;" title="部门负责人不可移出">🔒</span>' : `<span style="cursor:pointer;color:var(--gray-400);font-size:11px;" data-emie-onclick="removeUserFromDept('${u.userId}')" title="移出部门">✕</span>`}
              </span>`;
            }).join('')}
          </div>` : '<div style="font-size:12px;color:var(--gray-400);">暂无成员</div>'}
        </div>`;
      }).join('')}
    </div>`}
    <div class="card" style="padding:16px;margin-top:16px;">
      <h4 style="margin:0 0 8px 0;font-size:14px;">未分配部门的用户</h4>
      ${allUsers.filter(u => !u.departmentId).map(u =>
        `<span style="display:inline-flex;align-items:center;gap:4px;padding:4px 10px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:12px;margin:3px;">
          ${escHtml(u.name)}（${escHtml(roleLabels[u.role] || u.role)}）
          <span style="cursor:pointer;color:var(--primary);font-size:11px;" data-emie-onclick="openAssignUserDept('${u.userId}')" title="分配到部门">📂</span>
        </span>`
      ).join('') || '<span style="font-size:12px;color:var(--gray-400);">全部已分配</span>'}
    </div>
  `;
}

async function openCreateDeptModal() {
  if (isModalOpen()) return;
  const [roleData, usersData] = await Promise.all([
    apiGet('/admin/roles'),
    apiGet('/users'),
  ]);
  const roles = roleData.filter(r => r.name !== 'pending');
  const allUsers = Object.values(usersData).flat();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'createDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('createDeptModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">➕ 新建部门</div></div></div>
      <div class="modal-body">
        <form id="createDeptForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 部门名称</label>
            <input type="text" class="form-input" name="name" placeholder="如：设计一部" required>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 关联角色</label>
            <select class="form-select" name="role" id="deptRoleSelect">
              ${roles.map(r => `<option value="${escHtml(r.name)}">${escHtml(r.displayName || r.name)}</option>`).join('')}
            </select>
          </div>
          <div class="form-group"><label class="form-label">部门负责人</label>
            <select class="form-select" name="headUserId" id="deptHeadSelect">
              <option value="">未设置</option>
            </select>
          </div>
        </form>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('createDeptModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitCreateDept())">确认创建</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  // 根据选择角色动态更新负责人选项
  document.getElementById('deptRoleSelect').onchange = function() {
    const role = this.value;
    const sel = document.getElementById('deptHeadSelect');
    const filtered = [
      ...allUsers.filter(u => adminOrgRoleKey(u.role) === adminOrgRoleKey(role)),
      ...allUsers.filter(u => adminOrgRoleKey(u.role) === 'admin'),
    ];
    sel.innerHTML = '<option value="">未设置</option>' +
      filtered.filter(u => adminOrgRoleKey(u.role) !== 'admin').map(u => `<option value="${escHtml(u.userId)}">${escHtml(u.name)}</option>`).join('') +
      (filtered.some(u => adminOrgRoleKey(u.role) === 'admin')
        ? '<optgroup label="管理员">' + filtered.filter(u => adminOrgRoleKey(u.role) === 'admin').map(u => `<option value="${escHtml(u.userId)}">${escHtml(u.name)}</option>`).join('') + '</optgroup>'
        : '');
  };
  document.getElementById('deptRoleSelect').dispatchEvent(new Event('change'));
}

async function submitCreateDept() {
  const fd = new FormData(document.getElementById('createDeptForm'));
  const data = Object.fromEntries(fd.entries());
  if (!data.name) return alert('请填写部门名称');
  await apiPost('/departments', data);
  closeM('createDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function editDept(d) {
  if (isModalOpen()) return;
  const [roleData, usersData] = await Promise.all([
    apiGet('/admin/roles'),
    apiGet('/users'),
  ]);
  const roles = roleData.filter(r => r.name !== 'pending');
  if (d.role && !roles.some(r => r.name === d.role)) {
    roles.push({ name: d.role, displayName: d.role });
  }
  const allUsers = Object.values(usersData).flat();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'editDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('editDeptModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑部门</div></div></div>
      <div class="modal-body">
        <form id="editDeptForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 部门名称</label>
            <input type="text" class="form-input" name="name" value="${escHtml(d.name)}" required>
          </div>
          <div class="form-group"><label class="form-label">关联角色</label>
            <select class="form-select" name="role" id="editDeptRoleSelect">
              ${roles.map(r => `<option value="${escHtml(r.name)}" ${r.name === d.role ? 'selected' : ''}>${escHtml(r.displayName || r.name)}</option>`).join('')}
            </select>
          </div>
          <div class="form-group"><label class="form-label">部门负责人</label>
            <select class="form-select" name="headUserId" id="editDeptHeadSelect"></select>
          </div>
          <div class="form-group"><label class="form-label">状态</label>
            <select class="form-select" name="active">
              <option value="true" ${d.active ? 'selected' : ''}>启用</option>
              <option value="false" ${!d.active ? 'selected' : ''}>停用</option>
            </select>
          </div>
        </form>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('editDeptModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitEditDept(${d.id}))">保存</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  const updateHeadSelect = () => {
    const role = document.getElementById('editDeptRoleSelect').value;
    const sel = document.getElementById('editDeptHeadSelect');
    const candidates = [
        ...allUsers.filter(u => adminOrgRoleKey(u.role) === adminOrgRoleKey(role)),
        ...allUsers.filter(u => adminOrgRoleKey(u.role) === 'admin'),
      ];
    sel.innerHTML = '<option value="">未设置</option>' +
      candidates.filter(u => adminOrgRoleKey(u.role) !== 'admin').map(u =>
        `<option value="${escHtml(u.userId)}" ${u.userId === d.headUserId ? 'selected' : ''}>${escHtml(u.name)}</option>`
      ).join('') +
      (candidates.some(u => adminOrgRoleKey(u.role) === 'admin')
        ? '<optgroup label="管理员">' + candidates.filter(u => adminOrgRoleKey(u.role) === 'admin').map(u =>
          `<option value="${escHtml(u.userId)}" ${u.userId === d.headUserId ? 'selected' : ''}>${escHtml(u.name)}</option>`
        ).join('') + '</optgroup>'
        : '');
  };
  document.getElementById('editDeptRoleSelect').onchange = updateHeadSelect;
  updateHeadSelect();
}

async function submitEditDept(id) {
  const fd = new FormData(document.getElementById('editDeptForm'));
  const data = Object.fromEntries(fd.entries());
  data.active = data.active === 'true';
  await apiPut(`/departments/${id}`, data);
  closeM('editDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function deleteDept(id) {
  if (!confirm('确定删除该部门？')) return;
  await apiDelete(`/departments/${id}`);
  await refreshOrgData();
  switchAdminTab('org');
}

async function openAssignUserDept(userId) {
  if (isModalOpen()) return;
  const [depts, usersData] = await Promise.all([
    apiGet('/departments'),
    apiGet('/users'),
  ]);
  const allUsers = Object.values(usersData).flat();
  const user = allUsers.find(u => u.userId === userId);
  if (!user) return;
  // 按角色过滤可选部门
  const roleDepts = depts.filter(d => d.role === user.role && d.active);
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'assignDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('assignDeptModal')">✕</button>
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📂 分配部门：${escHtml(user.name)}</div></div></div>
      <div class="modal-body">
        ${roleDepts.length === 0 ? `<p style="color:var(--gray-500)">没有可分配的 ${user.role} 部门，请先创建</p>` : `
        <div style="display:flex;flex-direction:column;gap:8px;">
          ${roleDepts.map(d => `
            <label style="display:flex;align-items:center;gap:8px;padding:10px 14px;border:1px solid var(--gray-200);border-radius:8px;cursor:pointer;">
              <input type="radio" name="deptId" value="${d.id}">
              <div><strong>${escHtml(d.name)}</strong><div style="font-size:12px;color:var(--gray-500);">负责人：${allUsers.find(u2 => u2.userId === d.headUserId)?.name || '未设置'}</div></div>
            </label>
          `).join('')}
        </div>`}
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('assignDeptModal')">取消</button>
        ${roleDepts.length > 0 ? `<button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitAssignDept('${userId}'))">确认分配</button>` : ''}
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitAssignDept(userId) {
  const sel = document.querySelector('input[name="deptId"]:checked');
  if (!sel) return alert('请选择一个部门');
  await apiPut(`/users/org/${userId}`, { departmentId: parseInt(sel.value) });
  closeM('assignDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function removeUserFromDept(userId) {
  // 检查是否为部门负责人，负责人不能移出
  const isHead = EMIE.state.departments.some(d => d.headUserId === userId);
  if (isHead) {
    alert('该用户是部门负责人，无法直接移出。请先更换部门负责人或删除部门。');
    return;
  }
  if (!confirm('确定将该用户移出部门？')) return;
  await apiPut(`/users/org/${userId}`, { departmentId: null });
  await refreshOrgData();
  switchAdminTab('org');
}


EMIE.registerActions({
  refreshOrgData,
  renderAdminOrg,
  openCreateDeptModal,
  submitCreateDept,
  editDept,
  submitEditDept,
  deleteDept,
  openAssignUserDept,
  submitAssignDept,
  removeUserFromDept,
});

EMIE.registerModule('adminOrg', {
  refreshOrgData,
  renderAdminOrg,
  openCreateDeptModal,
  submitCreateDept,
  editDept,
  submitEditDept,
  deleteDept,
  openAssignUserDept,
  submitAssignDept,
  removeUserFromDept,
});
