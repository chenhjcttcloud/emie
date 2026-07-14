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
              <option value="">永不过期</option>
            </select>
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
    const expiresIn = expiresEl.value ? parseInt(expiresEl.value) : null;
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
