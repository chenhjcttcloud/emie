const EMIE = window.EMIE;
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

async function renderAdminLogs(container) {
  container.innerHTML = `
    <div class="admin-log-filter-panel"><div class="admin-log-filter-title"><span>⌕</span><div><strong>日志筛选</strong><small>选择日期范围查询系统操作记录</small></div></div><div class="admin-log-filter-fields">
      <label><span>开始日期</span><input type="date" class="form-input" id="logStartDate" title="开始日期"></label>
      <i>至</i>
      <label><span>结束日期</span><input type="date" class="form-input" id="logEndDate" title="结束日期"></label>
      <button class="btn btn-primary btn-sm" data-emie-onclick="queryAdminLogs()">查询日志</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetAdminLogs()">重置</button>
    </div></div>
    <div id="logContainer"><div class="loading">加载中</div></div>
  `;
  EMIE.adminState.logPage = 0;
  await loadAdminLogs();
}

async function queryAdminLogs() {
  EMIE.adminState.logPage = 0;
  await loadAdminLogs();
}

async function resetAdminLogs() {
  const start = document.getElementById('logStartDate');
  const end = document.getElementById('logEndDate');
  if (start) start.value = '';
  if (end) end.value = '';
  await queryAdminLogs();
}

async function loadAdminLogs() {
  const container = document.getElementById('logContainer');
  if (!container) return;
  const requestId = (EMIE.adminState.logRequestId || 0) + 1;
  EMIE.adminState.logRequestId = requestId;
  container.innerHTML = '<div class="loading">加载中</div>';
  try {
    const startDate = document.getElementById('logStartDate')?.value || '';
    const endDate = document.getElementById('logEndDate')?.value || '';
    let url = '/system/logs';
    const params = [];
    if (startDate) params.push('startDate=' + startDate);
    if (endDate) params.push('endDate=' + endDate);
    params.push('page=' + (EMIE.adminState.logPage || 0));
    // 每页少量展示，避免日志表格内部滚动时把页面内容顶出视口。
    params.push('size=8');
    if (params.length) url += '?' + params.join('&');
    const pageResult = await apiGet(url);
    if (requestId !== EMIE.adminState.logRequestId) return;
    const logs = pageResult.items || [];
    if (!logs.length) {
      container.innerHTML = '<div class="empty"><div class="empty-icon">📭</div><p>暂无日志记录</p></div>';
      return;
    }
    container.innerHTML = '<div class="card admin-log-card"><div class="admin-log-card-head"><div><strong>操作记录</strong><span>共 ' + pageResult.total + ' 条记录</span></div><small>第 ' + (pageResult.page + 1) + ' 页</small></div>' +
      '<div class="admin-log-table-wrap"><table><thead><tr>' +
      '<th style="width:60px;">#</th><th style="width:150px;">时间</th><th style="width:60px;">角色</th>' +
      '<th style="width:80px;">操作人</th><th>操作内容</th><th style="width:80px;">关联项目</th></tr></thead><tbody>' +
      logs.map(l => {
        const rl = {sales:'销售',planner:'企划',designer:'设计师',supplychain:'供应链',admin:'管理员'};
        const rn = rl[l.role] || l.role;
        const pl = l.projectId ? '<a href="javascript:void(0)" data-emie-onclick="openProjectDetail(' + l.projectId + ')" style="color:var(--primary);text-decoration:none;">#' + l.projectId + '</a>' : '-';
        return '<tr><td style="color:var(--gray-400);">' + l.id + '</td><td style="white-space:nowrap;font-size:12px;">' +
          escHtml(l.time || '-') + '</td><td><span class="admin-log-role role-' + escHtml(l.role || '') + '">' + escHtml(rn || '-') + '</span></td><td><strong>' +
          escHtml(l.username || '-') + '</strong></td><td class="admin-log-action">' + escHtml(l.action || '-') + '</td><td>' + pl + '</td></tr>';
      }).join('') + '</tbody></table></div>' + (pageResult.totalPages > 1 ? `<div class="admin-log-pagination"><span>第 ${pageResult.page + 1} / ${pageResult.totalPages} 页</span><div><button class="btn btn-outline btn-sm" ${pageResult.page <= 0 ? 'disabled' : ''} data-emie-onclick="changeAdminLogPage(${pageResult.page - 1})">上一页</button><span class="admin-log-jump">跳至 <input id="adminLogPageJump" type="number" min="1" max="${pageResult.totalPages}" value="${pageResult.page + 1}" aria-label="日志页码"> 页</span><button class="btn btn-outline btn-sm" data-emie-onclick="jumpAdminLogPage()">跳转</button><button class="btn btn-outline btn-sm" ${pageResult.page >= pageResult.totalPages - 1 ? 'disabled' : ''} data-emie-onclick="changeAdminLogPage(${pageResult.page + 1})">下一页</button></div></div>` : '') + '</div>';
  } catch (e) {
    if (requestId !== EMIE.adminState.logRequestId) return;
    container.innerHTML = '<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ' + escHtml(e.message || '未知错误') + '</p></div>';
  }
}

async function changeAdminLogPage(page) {
  if (page < 0) return;
  EMIE.adminState.logPage = page;
  await loadAdminLogs();
}

async function jumpAdminLogPage() {
  const input = document.getElementById('adminLogPageJump');
  const totalPages = Number(input?.max || 1);
  const requested = Number.parseInt(input?.value, 10);
  if (!Number.isFinite(requested)) return;
  const page = Math.min(Math.max(requested, 1), totalPages) - 1;
  EMIE.adminState.logPage = page;
  await loadAdminLogs();
}

// ===== Admin: 分享管理 =====
async function renderAdminShares(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const shares = await apiGet('/share/admin/all');
    if (!shares || shares.length === 0) {
      container.innerHTML = `<div class="empty"><div class="empty-icon">🔗</div><p>暂无分享链接</p></div>`;
      return;
    }
    const statusLabel = s => ({ active: '有效', expired: '已过期', revoked: '已收回' }[s] || s);
    const statusCls = s => ({ active: 'badge-completed', expired: 'badge-pending', revoked: 'badge-rejected' }[s] || '');
    const typeLabel = t => ({ project: '项目', sub_task: '子任务' }[t] || t);

    container.innerHTML = `
      <div class="card">
        <div class="card-header" style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <h3 style="font-size:15px;font-weight:600;">🔗 全部分享链接</h3>
          <span style="font-size:12px;color:var(--gray-500);">共 ${shares.length} 条</span>
        </div>
        <div style="overflow-x:auto;">
          <table style="width:100%;border-collapse:collapse;font-size:13px;">
            <thead><tr>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">ID</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">类型</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">目标</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">创建人</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">创建时间</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">过期</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">状态</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">访问</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">操作</th>
            </tr></thead>
            <tbody>
              ${shares.map(s => {
                const isActive = s.status === 'active';
                return '<tr>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + s.id + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + typeLabel(s.targetType) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><a href="javascript:void(0)" data-emie-onclick="openProjectDetail(' + s.targetId + ')" style="color:var(--primary);text-decoration:none;">#' + s.targetId + '</a></td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + (s.createdByName || s.createdBy || '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.createdAt ? s.createdAt.substring(0,16) : '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.expiresAt ? s.expiresAt.substring(0,10) : '永不过期') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><span class="badge ' + statusCls(s.status) + '">' + statusLabel(s.status) + '</span></td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.viewCount || 0) + ' 次</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);white-space:nowrap;">' +
                    (isActive
                      ? '<button class="btn btn-outline btn-sm" data-emie-onclick="adminEditShare(' + s.id + ')" style="margin-right:4px;">编辑</button>' +
                        '<button class="btn btn-danger btn-sm" data-emie-onclick="adminRevokeShare(' + s.id + ')">收回</button>'
                      : '<span style="font-size:12px;color:var(--gray-400);">-</span>') +
                  '</td></tr>';
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  } catch (e) {
      container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${escHtml(e.message || '未知错误')}</p></div>`;
  }
}

async function adminEditShare(id) {
  document.getElementById('shareEditModal')?.remove();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'shareEditModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:420px;">
      <div class="modal-header">
        <div class="modal-title">✏️ 编辑分享链接 #${id}</div>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">过期时间</label>
          <select class="form-select" id="editShareExpires">
            <option value="3600">1 小时后</option>
            <option value="86400">24 小时后</option>
            <option value="604800" selected>7 天后</option>
            <option value="2592000">30 天后</option>
            <option value="5184000">60 天后</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">访问密码</label>
          <input type="text" class="form-input" id="editSharePassword" placeholder="留空不修改，输入新密码覆盖" style="text-align:center;">
          <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">留空 = 不修改密码 / 清空输入框内容并保存 = 清除密码（新密码需至少 6 位且不能全为数字）</div>
        </div>
        <div id="editShareError" style="color:var(--danger);font-size:13px;text-align:center;margin-top:12px;display:none;"></div>
      </div>
      <div class="modal-footer" style="justify-content:center;">
        <button class="btn btn-primary" data-emie-onclick="doAdminUpdateShare(${id})">💾 保存</button>
        <button class="btn btn-outline" data-emie-onclick="closeM('shareEditModal')">取消</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  // 跟踪密码框是否被用户主动修改：区分「未修改 → null」与「清空 → ""」，与后端语义对齐
  const pwdInput = document.getElementById('editSharePassword');
  if (pwdInput) {
    pwdInput.addEventListener('input', () => { pwdInput.dataset.passwordTouched = '1'; });
  }
}

async function doAdminUpdateShare(id) {
  const expiresEl = document.getElementById('editShareExpires');
  const passwordEl = document.getElementById('editSharePassword');
  const errEl = document.getElementById('editShareError');
  errEl.style.display = 'none';

  const expiresVal = expiresEl.value;
  const expiresIn = parseInt(expiresVal, 10);
  // 与后端语义对齐：null=不修改密码、空串""=清除密码；仅用户主动动过密码框才可能触发清除
  const password = passwordEl.value;
  const passwordTouched = passwordEl.dataset.passwordTouched === '1';
  let passwordPayload;
  if (!passwordTouched) {
    passwordPayload = null; // 用户未修改密码
  } else if (password === '') {
    passwordPayload = ''; // 用户清空输入框 → 清除密码
  } else {
    passwordPayload = password; // 输入新密码
  }
  // 与后端规则一致：非空时必须至少 6 位且不能全为数字（提交前校验，避免后端 400）
  if (password && (password.length < 6 || /^\d+$/.test(password))) {
    errEl.textContent = '访问密码至少 6 位且不能全为数字';
    errEl.style.display = '';
    return;
  }

  try {
    await apiPut('/share/admin/' + id, { expiresIn, password: passwordPayload });
    showAdminToast('✅ 更新成功', 'success');
    closeM('shareEditModal');
    await renderAdminShares(document.getElementById('adminContent'));
  } catch(e) {
    errEl.textContent = e.message || '更新失败';
    errEl.style.display = '';
  }
}

async function adminRevokeShare(id) {
  if (!confirm('确定要收回此分享链接吗？收回后原链接将无法访问。')) return;
  try {
    const r = await apiPost('/share/admin/' + id + '/revoke', {});
    showAdminToast('✅ ' + (r.message || '已收回'), 'success');
    await renderAdminShares(document.getElementById('adminContent'));
  } catch(e) {
    window.EMIE.actions.showSystemAlert('操作失败: ' + e.message);
  }
}


EMIE.registerActions({
  renderAdminLogs,
  loadAdminLogs,
  queryAdminLogs,
  resetAdminLogs,
  changeAdminLogPage,
  renderAdminShares,
  adminEditShare,
  doAdminUpdateShare,
  adminRevokeShare,
});

EMIE.registerModule('adminAudit', {
  renderAdminLogs,
  loadAdminLogs,
  queryAdminLogs,
  resetAdminLogs,
  changeAdminLogPage,
  renderAdminShares,
  adminEditShare,
  doAdminUpdateShare,
  adminRevokeShare,
});
