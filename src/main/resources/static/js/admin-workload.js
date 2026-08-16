const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// ===== Admin: 工作量 =====

async function renderAdminWorkload(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const data = await apiGet('/admin/workload/timeline?range=' + EMIE.adminState.workloadRange);
    const summary = data._summary || {};
    const rangeLabel = summary.rangeLabel || '本月';
    const rangeOptions = [
      { key: 'day', label: '今日', icon: '☀️' },
      { key: 'week', label: '本周', icon: '📅' },
      { key: 'month', label: '本月', icon: '📆' },
      { key: 'quarter', label: '本季度', icon: '🗓️' },
      { key: 'half-year', label: '本半年', icon: '📋' },
      { key: 'year', label: '本年度', icon: '📊' },
    ];

    container.innerHTML = `
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:500;color:#374151;">📊 工作量看板</span>
        <span style="font-size:12px;color:var(--gray-400);margin-right:4px;">时间范围:</span>
        ${rangeOptions.map(o => `
          <button data-emie-onclick="switchWorkloadRange('${o.key}')"
            style="padding:5px 14px;border-radius:8px;border:${o.key === EMIE.adminState.workloadRange ? '2px solid #3370FF' : '1px solid var(--gray-200)'};
            background:${o.key === EMIE.adminState.workloadRange ? '#E6F1FB' : '#fff'};
            color:${o.key === EMIE.adminState.workloadRange ? '#1E40AF' : '#374151'};
            font-size:13px;cursor:pointer;white-space:nowrap;">${o.icon} ${o.label}</button>
        `).join('')}
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:20px;">
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📁</div>
          <div class="admin-stat-value">${summary.totalProjectsCreated || 0}</div>
          <div class="admin-stat-label">新建项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">✅</div>
          <div class="admin-stat-value">${summary.totalProjectsCompleted || 0}</div>
          <div class="admin-stat-label">完成项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📝</div>
          <div class="admin-stat-value">${summary.totalTasksAssigned || 0}</div>
          <div class="admin-stat-label">新分配子任务</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">✅</div>
          <div class="admin-stat-value">${summary.totalTasksCompleted || 0}</div>
          <div class="admin-stat-label">完成任务</div>
        </div>
      </div>`;

    const roleOrder = ['sales', 'planner', 'designer', 'supplychain'];

    for (const role of roleOrder) {
      const r = data[role];
      if (!r || !r.users || r.users.length === 0) continue;

      let html = '<div class="card workload-role-card" style="margin-bottom:16px;">';
      html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:14px 16px;border-bottom:1px solid var(--gray-200);">
        <div><span style="font-size:16px;margin-right:6px;">${r.icon || '👤'}</span><strong style="font-size:15px;">${r.label || role}</strong>
        <span style="font-size:12px;color:var(--gray-500);margin-left:8px;">${r.totalUsers} 人</span></div></div>`;

      // 表头
      const isWorker = (role === 'designer' || role === 'supplychain');
      const createdLabel = isWorker ? '分配子任务' : '新建项目';
      const completedLabel = isWorker ? '完成子任务' : '完成项目';
      html += `<div class="workload-table-scroll"><div style="display:flex;padding:8px 16px;font-size:11px;color:var(--gray-500);border-bottom:1px solid var(--gray-100);min-width:680px;">
        <div style="min-width:140px;">姓名</div>
        <div style="flex:0 0 280px;display:flex;gap:16px;">
          <span style="width:60px;">${createdLabel}</span>
          <span style="width:60px;">${completedLabel}</span>
          <span style="width:60px;">完成率</span>
        </div>
      </div>`;

      html += '<div class="workload-user-list">';

      for (const u of r.users) {
        const created = u.created || u.assigned || 0;
        const completed = u.completed || 0;
        const rate = created > 0 ? Math.round((completed / created) * 100) + '%' : '-';

        html += `<div style="display:flex;align-items:center;padding:10px 16px;border-bottom:1px solid var(--gray-100);">
          <div style="min-width:140px;flex-shrink:0;">
            <div style="font-size:13px;font-weight:500;color:#1f2937;">${escHtml(u.name)}</div>
          </div>
          <div style="flex:0 0 280px;display:flex;gap:16px;align-items:center;">
            <span style="width:60px;font-size:13px;font-weight:600;color:#374151;">${created}</span>
            <span style="width:60px;font-size:13px;font-weight:600;color:#065F46;">${completed}</span>
            <span style="width:60px;font-size:12px;color:${rate === '-' ? 'var(--gray-400)' : '#374151'};">${rate}</span>
          </div>
          <!-- 进度条 -->
          <div style="flex:0 0 180px;margin-left:auto;background:var(--gray-200);border-radius:6px;height:8px;overflow:hidden;">
            <div style="background:${created > 0 ? '#639922' : '#e5e7eb'};width:${created > 0 ? Math.min(100, (completed / created) * 100) : 0}%;height:100%;border-radius:6px;transition:width 0.3s;"></div>
          </div>
        </div>`;
      }
      html += '</div></div>';
      html += '</div>';
      container.innerHTML += html;
    }
  } catch (e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${escHtml(e.message)}</p></div>`;
  }
}

function switchWorkloadRange(range) {
  EMIE.adminState.workloadRange = range;
  const container = document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}


EMIE.registerActions({
  renderAdminWorkload,
  switchWorkloadRange,
});

EMIE.registerModule('adminWorkload', {
  renderAdminWorkload,
  switchWorkloadRange,
});
