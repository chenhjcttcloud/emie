const EMIE = window.EMIE;
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);

// ===== Admin: 文件存储 =====
async function renderAdminFileStorage(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const [stats, archived] = await Promise.all([
      apiGet('/admin/files/stats'),
      apiGet('/admin/files/archived'),
    ]);

    function fmt(s) {
      if (!s) return '0 B';
      if (s >= 1073741824) return (s / 1073741824).toFixed(1) + ' GB';
      if (s >= 1048576) return (s / 1048576).toFixed(1) + ' MB';
      if (s >= 1024) return (s / 1024).toFixed(0) + ' KB';
      return s + ' B';
    }

    const diskPercent = stats.diskTotalBytes ? ((stats.diskUsedBytes / stats.diskTotalBytes) * 100).toFixed(0) : 0;

    container.innerHTML = `
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:16px;">
        <div class="admin-stat-card">
          <div class="admin-stat-icon">💾</div>
          <div class="admin-stat-value">${fmt(stats.localSizeBytes)}</div>
          <div class="admin-stat-label">本地文件</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📦</div>
          <div class="admin-stat-value">${stats.archivedCount || 0} 个</div>
          <div class="admin-stat-label">已归档</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">🗄️</div>
          <div class="admin-stat-value">${fmt(stats.diskUsedBytes)} / ${fmt(stats.diskTotalBytes)}</div>
          <div class="admin-stat-label">磁盘使用</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">${stats.nasEnabled ? '✅' : '❌'}</div>
          <div class="admin-stat-value">${stats.nasHost || '未配置'}</div>
          <div class="admin-stat-label">NAS 状态</div>
        </div>
      </div>
      <div style="margin-bottom:16px;">
        <div style="background:var(--gray-200);border-radius:8px;height:12px;overflow:hidden;">
          <div style="background:${diskPercent > 80 ? 'var(--danger)' : diskPercent > 60 ? '#EF9F27' : '#639922'};width:${diskPercent}%;height:100%;border-radius:8px;transition:width 0.3s;"></div>
        </div>
        <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--gray-500);margin-top:4px;">
          <span>已用 ${diskPercent}%</span>
          <span>剩余 ${fmt(stats.diskFreeBytes)}</span>
        </div>
      </div>
      <div style="display:flex;gap:8px;margin-bottom:16px;">
        <button class="btn btn-primary" data-emie-onclick="manualArchive()">📤 立即归档</button>
      </div>
      <div class="card">
        <div class="card-header" style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <h3 style="font-size:15px;font-weight:600;">📦 已归档文件</h3>
          <span style="font-size:12px;color:var(--gray-500);">共 ${archived.length} 个</span>
        </div>
        <div style="overflow-x:auto;">
          <table style="width:100%;border-collapse:collapse;font-size:13px;">
            <thead><tr>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">原始文件名</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">原始大小</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">压缩后</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">归档时间</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">操作</th>
            </tr></thead>
            <tbody>
              ${archived.length === 0 ? '<tr><td colspan="5" style="text-align:center;padding:24px;color:var(--gray-400);">暂无归档文件</td></tr>' :
                archived.map(f => '<tr>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + escHtml(f.originalName) + '">' + escHtml(f.originalName) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + fmt(f.fileSize) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + fmt(f.archiveSize) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (f.archivedAt ? f.archivedAt.substring(0,16) : '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><button class="btn btn-outline btn-sm" data-emie-onclick="restoreArchivedFile(' + f.id + ')">恢复本地</button></td>' +
                '</tr>').join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  } catch(e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${escHtml(e.message || '未知错误')}</p></div>`;
  }
}

async function manualArchive() {
  if (!confirm('确定要手动归档过期文件吗？超过 90 天的文件将被压缩并推送到 NAS。')) return;
  try {
    const r = await apiPost('/admin/files/archive', {});
    showAdminToast('✅ 归档完成: 成功 ' + (r.success || 0) + ' 个, 失败 ' + (r.fail || 0) + ' 个', r.fail > 0 ? 'warning' : 'success');
    await renderAdminFileStorage(document.getElementById('adminContent'));
  } catch(e) {
    window.EMIE.actions.showSystemAlert('归档失败: ' + e.message);
  }
}

async function restoreArchivedFile(fileId) {
  if (!confirm('确定要从 NAS 恢复此文件到本地吗？')) return;
  try {
    await apiPost('/admin/files/restore/' + fileId, {});
    showAdminToast('✅ 文件已恢复', 'success');
    await renderAdminFileStorage(document.getElementById('adminContent'));
  } catch(e) {
    window.EMIE.actions.showSystemAlert('恢复失败: ' + e.message);
  }
}



EMIE.registerActions({
  renderAdminFileStorage,
  manualArchive,
  restoreArchivedFile,
});

EMIE.registerModule('adminStorage', {
  renderAdminFileStorage,
  manualArchive,
  restoreArchivedFile,
});
