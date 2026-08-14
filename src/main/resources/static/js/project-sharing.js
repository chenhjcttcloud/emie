const EMIE = window.EMIE;
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

// ==================== 分享链接 ====================

async function shareProject(projectId) {
  document.getElementById('shareModal')?.remove();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'shareModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:520px;">
      <div class="modal-header">
        <div class="modal-title">🔗 分享此项目</div>
      </div>
      <div class="modal-body">
        <div id="shareForm">
          <div class="form-group">
            <label class="form-label">过期时间</label>
            <select class="form-select" id="shareExpires">
              <option value="3600">1 小时后</option>
              <option value="86400">24 小时后</option>
              <option value="604800" selected>7 天后</option>
              <option value="2592000">30 天后</option>
              <option value="5184000">60 天后</option>
              <option value="custom">自定义时间</option>
            </select>
            <input type="datetime-local" class="form-input" id="shareCustomExpires" style="display:none;margin-top:8px;">
            <div style="font-size:12px;color:var(--gray-400);margin-top:5px;">最长有效期为60天</div>
          </div>
          <div class="form-group">
            <label class="form-label">访问密码（可选）</label>
            <input type="text" class="form-input" id="sharePassword" placeholder="留空则无需密码" style="text-align:center;">
          </div>
          <div id="shareCreateArea">
            <button class="btn btn-primary btn-lg" data-emie-onclick="doCreateShareLink('project', ${projectId})" style="width:100%;justify-content:center;">生成分享链接</button>
          </div>
          <div id="shareResultArea" style="display:none;">
            <div class="form-group">
              <label class="form-label">分享链接</label>
              <div style="display:flex;gap:8px;">
                <input type="text" class="form-input" id="shareUrl" readonly style="text-align:center;flex:1;background:#f9fafb;">
                <button class="btn btn-primary" data-emie-onclick="copyShareUrl()">复制</button>
              </div>
            </div>
            <div id="shareStatus" style="font-size:13px;color:var(--gray-500);text-align:center;margin-top:8px;"></div>
          </div>
        </div>
        <div id="shareLoading" style="display:none;text-align:center;padding:40px;color:var(--gray-400);">生成中...</div>
        <div id="shareError" style="color:var(--danger);font-size:13px;text-align:center;margin-top:12px;display:none;"></div>
      </div>
      <div class="modal-footer" style="justify-content:center;">
        <button class="btn btn-outline" data-emie-onclick="closeM('shareModal')">关闭</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  document.getElementById('shareExpires')?.addEventListener('change', (event) => {
    const custom = document.getElementById('shareCustomExpires');
    if (!custom) return;
    custom.style.display = event.target.value === 'custom' ? '' : 'none';
    if (event.target.value === 'custom') {
      const max = new Date(Date.now() + 60 * 24 * 60 * 60 * 1000);
      custom.min = new Date(Date.now() + 60 * 1000).toISOString().slice(0, 16);
      custom.max = max.toISOString().slice(0, 16);
    }
  });
}

async function doCreateShareLink(targetType, targetId) {
  const expiresEl = document.getElementById('shareExpires');
  const passwordEl = document.getElementById('sharePassword');
  const errEl = document.getElementById('shareError');
  const loadingEl = document.getElementById('shareLoading');
  const formEl = document.getElementById('shareForm');
  const createArea = document.getElementById('shareCreateArea');
  const resultArea = document.getElementById('shareResultArea');
  const urlInput = document.getElementById('shareUrl');
  const statusEl = document.getElementById('shareStatus');

  errEl.style.display = 'none';
  loadingEl.style.display = '';
  createArea.style.display = 'none';
  resultArea.style.display = 'none';

  try {
    let expiresIn;
    if (expiresEl.value === 'custom') {
      const custom = document.getElementById('shareCustomExpires')?.value;
      if (!custom) throw new Error('请选择自定义过期时间');
      expiresIn = Math.floor((new Date(custom).getTime() - Date.now()) / 1000);
    } else {
      expiresIn = parseInt(expiresEl.value, 10);
    }
    if (!Number.isFinite(expiresIn) || expiresIn <= 0 || expiresIn > 60 * 24 * 60 * 60) {
      throw new Error('分享链接有效期必须在1秒到60天之间');
    }
    const password = passwordEl.value.trim() || null;
    const result = await apiPost('/share', { targetType, targetId, expiresIn, password });
    const fullUrl = window.location.origin + result.url;
    urlInput.value = fullUrl;
    resultArea.style.display = '';
    try {
      await navigator.clipboard.writeText(fullUrl);
      statusEl.innerHTML = '✅ 已复制到剪贴板';
    } catch(e) {
      statusEl.innerHTML = '';
    }
  } catch(e) {
    errEl.textContent = e.message || '生成失败';
    errEl.style.display = '';
    createArea.style.display = '';
  } finally {
    loadingEl.style.display = 'none';
  }
}

function copyShareUrl() {
  const input = document.getElementById('shareUrl');
  input.select();
  document.execCommand('copy');
  const status = document.getElementById('shareStatus');
  status.innerHTML = '✅ 已复制到剪贴板';
}


EMIE.registerActions({
  shareProject,
  doCreateShareLink,
  copyShareUrl,
});

EMIE.registerModule('projectSharing', {
  shareProject,
  doCreateShareLink,
  copyShareUrl,
});
