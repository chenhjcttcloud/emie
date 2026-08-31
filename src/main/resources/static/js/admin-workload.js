const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// ===== Admin: 工作量 =====

async function renderAdminWorkload(container) {
  EMIE.workloadContainer = container;
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const customStart = EMIE.adminState.workloadStartDate || '';
    const customEnd = EMIE.adminState.workloadEndDate || '';
    const customQuery = EMIE.adminState.workloadRange === 'custom' && customStart && customEnd
      ? `&startDate=${encodeURIComponent(customStart)}&endDate=${encodeURIComponent(customEnd)}` : '';
    const data = await apiGet('/admin/workload/timeline?range=' + EMIE.adminState.workloadRange + customQuery);
    const summary = data._summary || {};
    const rangeLabel = summary.rangeLabel || '今日';
    const rangeOptions = [
      { key: 'day', label: '今日' }, { key: 'week', label: '本周' },
      { key: 'month', label: '本月' }, { key: 'quarter', label: '本季度' },
      { key: 'half-year', label: '本半年' }, { key: 'year', label: '本年度' },
      { key: 'all', label: '总览' }, { key: 'custom', label: '自定义日期' },
    ];

    const workloadQuery = EMIE.adminState.workloadQuery || '';
    const workloadSort = EMIE.adminState.workloadSort || 'default';
    container.innerHTML = `
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:500;color:#374151;">工作量看板</span>
        <span style="font-size:12px;color:var(--gray-400);margin-right:4px;">时间范围:</span>
        ${rangeOptions.map(o => `
          <button data-emie-action="click:workload-range" data-range="${o.key}"
            style="padding:5px 14px;border-radius:8px;border:${o.key === EMIE.adminState.workloadRange ? '2px solid #3370FF' : '1px solid var(--gray-200)'};
            background:${o.key === EMIE.adminState.workloadRange ? '#E6F1FB' : '#fff'};
            color:${o.key === EMIE.adminState.workloadRange ? '#1E40AF' : '#374151'};
            font-size:13px;cursor:pointer;white-space:nowrap;">${o.label}</button>
        `).join('')}
      </div>
      <div style="display:${EMIE.adminState.workloadRange === 'custom' ? 'flex' : 'none'};align-items:center;gap:8px;margin:-6px 0 16px;padding:12px 14px;border:1px solid #BFDBFE;border-radius:10px;background:#F8FBFF;flex-wrap:wrap;">
        <span style="font-size:12px;color:#475569;">统计日期</span>
        <input type="date" class="form-input" style="width:160px;" value="${escHtml(customStart)}" data-emie-action="change:workload-start-date" aria-label="开始日期">
        <span style="color:var(--gray-400);">至</span>
        <input type="date" class="form-input" style="width:160px;" value="${escHtml(customEnd)}" data-emie-action="change:workload-end-date" aria-label="结束日期">
        <button class="btn btn-primary btn-sm" data-emie-action="click:workload-apply-dates">查询</button>
        <span style="font-size:12px;color:var(--gray-400);">包含开始和结束当天</span>
      </div>
      <div style="display:flex;gap:10px;align-items:center;margin-bottom:16px;flex-wrap:wrap;">
        <input class="form-input" style="width:220px;" placeholder="搜索员工姓名" value="${escHtml(workloadQuery)}" data-emie-action="input:workload-query">
        <select class="form-select" style="width:160px;" data-emie-action="change:workload-sort">
          <option value="default" ${workloadSort === 'default' ? 'selected' : ''}>默认排序</option>
          <option value="total" ${workloadSort === 'total' ? 'selected' : ''}>按总量排序</option>
          <option value="pending" ${workloadSort === 'pending' ? 'selected' : ''}>按未完成排序</option>
          <option value="rate" ${workloadSort === 'rate' ? 'selected' : ''}>按完成率排序</option>
        </select>
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:20px;">
        <div class="admin-stat-card">
          <div class="admin-stat-value">${summary.totalProjectsCreated || 0}</div>
          <div class="admin-stat-label">新建项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-value">${summary.totalProjectsCompleted || 0}</div>
          <div class="admin-stat-label">完成项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-value">${summary.totalTasksAssigned || 0}</div>
          <div class="admin-stat-label">新分配子任务</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-value">${summary.totalTasksCompleted || 0}</div>
          <div class="admin-stat-label">完成任务</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-value">${Math.max(0, (summary.totalProjectsCreated || 0) - (summary.totalProjectsCompleted || 0))}</div>
          <div class="admin-stat-label">未完成项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-value">${Math.max(0, (summary.totalTasksAssigned || 0) - (summary.totalTasksCompleted || 0))}</div>
          <div class="admin-stat-label">未完成子任务</div>
        </div>
      </div>`;

    const roleOrder = ['sales', 'planner', 'designer', 'supplychain'];

    for (const role of roleOrder) {
      const r = data[role];
      if (!r || !r.users || r.users.length === 0) continue;

      let html = '<div class="card workload-role-card" style="margin-bottom:16px;">';
      const roleTone = { sales: ['#2563EB', '#EFF6FF'], planner: ['#D97706', '#FFFBEB'], designer: ['#7C3AED', '#F5F3FF'], supplychain: ['#0F766E', '#F0FDFA'] }[role] || ['#475569', '#F8FAFC'];
      const roleTotal = r.users.reduce((sum, u) => sum + Number(u.created || u.assigned || 0), 0);
      const roleDone = r.users.reduce((sum, u) => sum + Number(u.completed || 0), 0);
      const roleChannel = r.users.reduce((sum, u) => sum + Number(u.channelCustomProjects || 0), 0);
      const roleRegular = r.users.reduce((sum, u) => sum + Number(u.regularProjects || 0), 0);
      const completedRoleChannel = r.users.reduce((sum, u) => sum + Number(u.completedChannelProjects || 0), 0);
      const completedRoleRegular = r.users.reduce((sum, u) => sum + Number(u.completedRegularProjects || 0), 0);
      const isWorker = (role === 'designer' || role === 'supplychain');
      html += `<div style="display:grid;grid-template-columns:120px repeat(5,minmax(0,1fr)) 184px;gap:16px;align-items:center;padding:14px 16px;border-top:3px solid ${roleTone[0]};border-bottom:1px solid var(--gray-200);background:linear-gradient(90deg,${roleTone[1]},#fff 55%);">
        <div style="display:flex;align-items:center;gap:10px;"><div><strong style="font-size:15px;color:#1f2937;">${r.label || role}</strong><div style="font-size:11px;color:#94a3b8;margin-top:2px;">成员工作量分布 · ${r.totalUsers}人</div></div></div><div style="grid-column:2 / span 5;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:16px;align-items:center;font-size:12px;color:#64748B;text-align:center;"><span>总量 <strong style="color:#2563EB;font-size:15px;">${roleTotal}</strong></span><span>${isWorker ? '渠道定制任务' : '渠道定制项目'} <strong style="color:#7C3AED;font-size:15px;">${roleChannel}</strong></span><span>${isWorker ? '常规品任务' : '公司常规品项目'} <strong style="color:#B45309;font-size:15px;">${roleRegular}</strong></span><span style="white-space:nowrap;">完成 <strong style="color:#047857;font-size:15px;">${roleDone}</strong><small style="font-size:11px;color:#64748B;">（${isWorker ? '渠道定制任务' : '渠道定制项目'}：${completedRoleChannel}个、${isWorker ? '常规品任务' : '公司常规品项目'}：${completedRoleRegular}个）</small></span><span></span></div></div>`;
      const roleUsers = (r.users || []).filter(u => !workloadQuery || String(u.name || '').toLowerCase().includes(workloadQuery.toLowerCase()));
      roleUsers.sort((a, b) => {
        if (workloadSort === 'total') return Number(b.created || b.assigned || 0) - Number(a.created || a.assigned || 0);
        if (workloadSort === 'pending') return (Number(b.created || b.assigned || 0) - Number(b.completed || 0)) - (Number(a.created || a.assigned || 0) - Number(a.completed || 0));
        if (workloadSort === 'rate') return (Number(b.completed || 0) / Math.max(1, Number(b.created || b.assigned || 0))) - (Number(a.completed || 0) / Math.max(1, Number(a.created || a.assigned || 0)));
        return 0;
      });
      if (!roleUsers.length) continue;
      const roleSummary = roleUsers.reduce((sum, u) => ({
        created: sum.created + Number(u.created || u.assigned || 0),
        completed: sum.completed + Number(u.completed || 0),
        channel: sum.channel + Number(u.channelCustomProjects || 0),
        regular: sum.regular + Number(u.regularProjects || 0),
      }), { created: 0, completed: 0, channel: 0, regular: 0 });
      const roleRate = roleSummary.created > 0 ? Math.round(roleSummary.completed / roleSummary.created * 100) + '%' : '-';
      const rolePending = Math.max(0, roleSummary.created - roleSummary.completed);
      html += `<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;padding:12px 16px;background:#fafbff;border-bottom:1px solid var(--gray-100);">
        ${[
          [isWorker ? '分配子任务' : '新建项目', roleSummary.created, '#2563EB'],
          [isWorker ? '完成子任务' : '完成项目', roleSummary.completed, '#047857'],
          [isWorker ? '未完成子任务' : '未完成项目', rolePending, '#D97706'],
          ['完成率', roleRate, '#374151'],
          ['渠道定制', roleSummary.channel, '#7C3AED'],
          ['公司常规品', roleSummary.regular, '#B45309'],
        ].map(([label, value, color]) => `<div style="padding:8px 10px;border:1px solid var(--gray-200);border-radius:8px;background:#fff;">
          <div style="font-size:11px;color:var(--gray-500);">${label}</div><div style="font-size:18px;font-weight:700;color:${color};margin-top:2px;">${value}</div>
        </div>`).join('')}
      </div>`;

      // 表头
      const createdLabel = isWorker ? '分配子任务' : '新建项目';
      const completedLabel = isWorker ? '完成子任务' : '完成项目';
      const projectColumns = (role === 'planner' || role === 'sales') ? `
          <span style="flex:1;color:#7C3AED;font-weight:600;">渠道定制</span>
          <span style="flex:1;color:#B45309;font-weight:600;">公司常规品</span>` : '';
      const taskColumns = (role === 'designer' || role === 'supplychain') ? `
          <span style="flex:1;color:#7C3AED;font-weight:600;">渠道定制任务</span>
          <span style="flex:1;color:#B45309;font-weight:600;">常规品任务</span>` : '';
      html += `<div class="workload-table-scroll"><div style="display:grid;grid-template-columns:120px repeat(5,minmax(0,1fr)) 184px;gap:16px;padding:8px 16px;font-size:11px;color:var(--gray-500);border-bottom:1px solid var(--gray-100);min-width:1000px;">
        <div>姓名</div>
        <div style="grid-column:2 / span 5;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:16px;text-align:center;">
          <span style="color:#2563EB;font-weight:600;">${createdLabel}</span>
          ${projectColumns || taskColumns}
          <span style="color:#047857;font-weight:600;">${completedLabel}</span>
          <span style="color:#475569;font-weight:600;">完成率</span>
        </div>
      </div>`;

      html += '<div class="workload-user-list">';

      for (const u of r.users) {
        const created = u.created || u.assigned || 0;
        const completed = u.completed || 0;
        const rate = created > 0 ? Math.round((completed / created) * 100) + '%' : '-';
        const categoryValues = (role === 'planner' || role === 'sales') ? `
            <span style="flex:1;font-size:13px;font-weight:700;color:#6D28D9;background:#F5F3FF;border-radius:6px;padding:3px 8px;text-align:center;">${u.channelCustomProjects || 0}</span>
            <span style="flex:1;font-size:13px;font-weight:700;color:#B45309;background:#FFFBEB;border-radius:6px;padding:3px 8px;text-align:center;">${u.regularProjects || 0}</span>` : (role === 'designer' || role === 'supplychain') ? `
            <span style="flex:1;font-size:13px;font-weight:700;color:#6D28D9;background:#F5F3FF;border-radius:6px;padding:3px 8px;text-align:center;">${u.channelCustomProjects || 0}</span>
            <span style="flex:1;font-size:13px;font-weight:700;color:#B45309;background:#FFFBEB;border-radius:6px;padding:3px 8px;text-align:center;">${u.regularProjects || 0}</span>` : '';

        html += `<div style="display:grid;grid-template-columns:120px repeat(5,minmax(0,1fr)) 184px;gap:16px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--gray-100);min-width:1000px;">
          <div>
            <div style="font-size:13px;font-weight:500;color:#1f2937;">${escHtml(u.name)}</div>
          </div>
          <div style="grid-column:2 / span 5;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:16px;align-items:center;text-align:center;">
            <span style="font-size:13px;font-weight:700;color:#2563EB;background:#EFF6FF;border-radius:6px;padding:3px 8px;">${created}</span>
            ${categoryValues}
            <span style="font-size:13px;font-weight:700;color:#047857;background:#ECFDF5;border-radius:6px;padding:3px 8px;white-space:nowrap;">${completed}<small style="font-size:10px;font-weight:500;color:#64748B;margin-left:4px;">（${isWorker ? '渠道定制任务' : '渠道定制项目'}：${u.completedChannelProjects || 0}个、${isWorker ? '常规品任务' : '公司常规品项目'}：${u.completedRegularProjects || 0}个）</small></span>
            <span style="font-size:12px;font-weight:600;color:${rate === '-' ? 'var(--gray-400)' : '#475569'};">${rate}</span>
          </div>
          <!-- 进度条 -->
          <div style="background:var(--gray-200);border-radius:6px;height:8px;overflow:hidden;">
            <div style="background:${created > 0 ? '#639922' : '#e5e7eb'};width:${created > 0 ? Math.min(100, (completed / created) * 100) : 0}%;height:100%;border-radius:6px;transition:width 0.3s;"></div>
          </div>
        </div>`;
      }
      html += '</div></div>';
      html += '</div>';
      container.innerHTML += html;
    }
  } catch (e) {
    container.innerHTML = `<div class="empty"><p>加载失败: ${escHtml(e.message)}</p></div>`;
  }
}

function switchWorkloadRange(range) {
  EMIE.adminState.workloadRange = range;
  if (range === 'custom') {
    const today = new Date();
    const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
    const formatDate = date => date.toLocaleDateString('en-CA');
    EMIE.adminState.workloadStartDate ||= formatDate(monthStart);
    EMIE.adminState.workloadEndDate ||= formatDate(today);
  }
  const container = EMIE.workloadContainer || document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}

function setWorkloadDate(kind, value) {
  if (kind === 'start') EMIE.adminState.workloadStartDate = value;
  if (kind === 'end') EMIE.adminState.workloadEndDate = value;
}

function applyWorkloadDates() {
  const start = EMIE.adminState.workloadStartDate;
  const end = EMIE.adminState.workloadEndDate;
  if (!start || !end) return EMIE.actions.showSystemAlert('请选择开始日期和结束日期');
  if (start > end) return EMIE.actions.showSystemAlert('结束日期不能早于开始日期');
  const container = EMIE.workloadContainer || document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}

function setWorkloadQuery(value) {
  EMIE.adminState.workloadQuery = value || '';
  const container = EMIE.workloadContainer || document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}

function setWorkloadSort(value) {
  EMIE.adminState.workloadSort = value || 'default';
  const container = EMIE.workloadContainer || document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}


EMIE.registerActions({
  renderAdminWorkload,
  switchWorkloadRange,
  setWorkloadQuery,
  setWorkloadSort,
  setWorkloadDate,
  applyWorkloadDates,
});

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('workload-range', (_event, element) => switchWorkloadRange(element.dataset.range));
  registerEventAction('workload-query', (_event, element) => setWorkloadQuery(element.value));
  registerEventAction('workload-sort', (_event, element) => setWorkloadSort(element.value));
  registerEventAction('workload-start-date', (_event, element) => setWorkloadDate('start', element.value));
  registerEventAction('workload-end-date', (_event, element) => setWorkloadDate('end', element.value));
  registerEventAction('workload-apply-dates', () => applyWorkloadDates());
}

EMIE.registerModule('adminWorkload', {
  renderAdminWorkload,
  switchWorkloadRange,
  setWorkloadQuery,
  setWorkloadSort,
  setWorkloadDate,
  applyWorkloadDates,
});
