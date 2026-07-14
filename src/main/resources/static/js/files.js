const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const fmtSize = (...args) => EMIE.actions.fmtSize(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const normalizeFileUrl = (...args) => EMIE.actions.normalizeFileUrl(...args);
const storedNameFromFile = (...args) => EMIE.actions.storedNameFromFile(...args);
const isPreviewableFile = (...args) => EMIE.actions.isPreviewableFile(...args);

// EMIE 文件交互：PDF/PPT 预览与下载操作
// ==================== PDF / PPT / PPTX 在线预览 ====================


function closeFilePreview() {
  EMIE.fileState.previewSequence++;
  EMIE.fileState.currentPreview = null;
  document.getElementById('filePreviewOverlay')?.remove();
}

function retryFilePreview() {
  const current = EMIE.fileState.currentPreview;
  if (!current) return;
  openFilePreview(current.fileUrl, current.fileName, current.fileSize, true);
}

function authenticatedFileUrl(url) {
  const token = localStorage.getItem('design_pm_token') || '';
  return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
}

async function openFilePreview(fileUrl, fileName, fileSize, retry = false) {
  if (!isPreviewableFile(fileName)) {
    showDownloadOptions(fileUrl, fileName, fileSize);
    return;
  }

  closeFilePreview();
  const requestSequence = ++EMIE.fileState.previewSequence;
  const normalizedUrl = normalizeFileUrl(fileUrl);
  const storedName = storedNameFromFile(normalizedUrl);
  EMIE.fileState.currentPreview = { fileUrl: normalizedUrl, fileName, fileSize, storedName };

  const overlay = document.createElement('div');
  overlay.id = 'filePreviewOverlay';
  overlay.className = 'modal-overlay file-preview-overlay';
  overlay.onclick = event => { if (event.target === overlay) closeFilePreview(); };
  overlay.innerHTML = `
    <div class="file-preview-dialog" role="dialog" aria-modal="true" aria-label="文件预览">
      <div class="file-preview-header">
        <div class="file-preview-title-wrap">
          <span class="file-preview-icon">📄</span>
          <div><div class="file-preview-title">${escHtml(fileName || storedName)}</div><div class="file-preview-meta">${fileSize ? fmtSize(fileSize) + ' · ' : ''}${escHtml((fileName || storedName).split('.').pop()?.toUpperCase() || '')}</div></div>
        </div>
        <button class="file-preview-close" data-emie-onclick="closeFilePreview()" aria-label="关闭预览">✕</button>
      </div>
      <div class="file-preview-body" id="filePreviewBody">
        <div class="file-preview-state"><div class="file-preview-spinner"></div><p>正在准备文件预览…</p></div>
      </div>
      <div class="file-preview-footer">
        <span class="file-preview-tip">PPT/PPTX 以静态幻灯片形式预览</span>
        <div class="file-preview-footer-actions">
          <button class="btn btn-outline btn-sm" id="openPreviewWindowBtn" data-emie-onclick="openPreviewInNewWindow()" disabled>↗ 新窗口打开</button>
          <button class="btn btn-primary btn-sm" data-emie-onclick="doDirectDownload('${escJsString(authenticatedFileUrl(normalizedUrl))}')">⬇ 下载原文件</button>
        </div>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  if (!storedName) {
    showFilePreviewError('无法识别文件地址，请下载后查看', false);
    return;
  }

  try {
    for (let attempt = 0; attempt < 120; attempt++) {
      if (requestSequence !== EMIE.fileState.previewSequence || !document.getElementById('filePreviewOverlay')) return;
      const retryQuery = retry && attempt === 0 ? '?retry=true' : '';
      const status = await apiGet('/files/preview/status/' + encodeURIComponent(storedName) + retryQuery);
      if (status.status === 'ready' && status.previewUrl) {
        showFilePreviewFrame(status.previewUrl, fileName || storedName);
        return;
      }
      if (status.status === 'failed' || status.status === 'unsupported') {
        showFilePreviewError(status.message || '文件预览生成失败', status.status === 'failed');
        return;
      }
      const body = document.getElementById('filePreviewBody');
      if (body) {
        body.innerHTML = `<div class="file-preview-state"><div class="file-preview-spinner"></div><p>${escHtml(status.message || '正在生成演示文稿预览…')}</p><small>首次预览需要转换，完成后会自动打开</small></div>`;
      }
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
    showFilePreviewError('预览任务仍在排队，请稍后重试或直接下载', true);
  } catch (error) {
    if (requestSequence === EMIE.fileState.previewSequence) {
      showFilePreviewError(error.message || '文件预览加载失败', true);
    }
  }
}

function showFilePreviewFrame(previewUrl, fileName) {
  const body = document.getElementById('filePreviewBody');
  if (!body) return;
  const iframe = document.createElement('iframe');
  iframe.className = 'file-preview-frame';
  iframe.title = fileName || '文件预览';
  iframe.src = authenticatedFileUrl(previewUrl);
  iframe.setAttribute('allowfullscreen', '');
  body.replaceChildren(iframe);
  if (EMIE.fileState.currentPreview) EMIE.fileState.currentPreview.previewUrl = previewUrl;
  const openButton = document.getElementById('openPreviewWindowBtn');
  if (openButton) openButton.disabled = false;
}

function showFilePreviewError(message, canRetry) {
  const body = document.getElementById('filePreviewBody');
  if (!body) return;
  body.innerHTML = `<div class="file-preview-state error"><div class="file-preview-error-icon">⚠️</div><p>${escHtml(message)}</p>${canRetry ? '<button class="btn btn-outline btn-sm" data-emie-onclick="retryFilePreview()">重新生成预览</button>' : ''}</div>`;
}

function openPreviewInNewWindow() {
  const previewUrl = EMIE.fileState.currentPreview?.previewUrl;
  if (!previewUrl) return;
  window.open(authenticatedFileUrl(previewUrl), '_blank', 'noopener');
}

// ==================== 文件下载选项 ====================

/** 显示文件下载选项面板（直接下载 / 复制链接） */
function showDownloadOptions(fileUrl, fileName, fileSize) {
  // 移除已有面板
  document.getElementById('downloadOptionPanel')?.remove();

  function fmtSize(bytes) {
    if (!bytes) return '';
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  const ext = fileName ? fileName.split('.').pop().toUpperCase() : '';
  const canPreview = isPreviewableFile(fileName);
  const token = localStorage.getItem('design_pm_token') || '';
  const fullUrl = (fileUrl.startsWith('http') ? fileUrl : window.location.origin + fileUrl)
    + (fileUrl.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);

  const panel = document.createElement('div');
  panel.id = 'downloadOptionPanel';
  panel.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;display:flex;align-items:center;justify-content:center;';
  panel.innerHTML = `
    <div data-emie-onclick="closeDownloadOptions()" style="position:absolute;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.3);"></div>
    <div style="position:relative;background:#fff;border-radius:16px;width:420px;max-width:90vw;box-shadow:0 8px 40px rgba(0,0,0,0.15);overflow:hidden;">
      <div style="padding:24px 24px 0;">
        <div style="display:flex;align-items:flex-start;gap:12px;margin-bottom:20px;">
          <div style="width:48px;height:48px;border-radius:12px;background:#E6F1FB;display:flex;align-items:center;justify-content:center;font-size:22px;flex-shrink:0;">📄</div>
          <div style="min-width:0;flex:1;">
            <p style="font-weight:600;font-size:14px;color:#1f2937;margin:0 0 4px 0;word-break:break-all;line-height:1.4;">${escHtml(fileName || '')}</p>
            <p style="font-size:12px;color:#6b7280;margin:0;">${fileSize ? fmtSize(fileSize) + ' · ' : ''}${ext}</p>
          </div>
          <button data-emie-onclick="closeDownloadOptions()" style="background:none;border:none;cursor:pointer;font-size:18px;color:#9ca3af;padding:4px;line-height:1;">✕</button>
        </div>
      </div>
      <div style="padding:0 24px 24px;">
        <div style="display:grid;grid-template-columns:repeat(${canPreview ? 3 : 2},1fr);gap:10px;margin-bottom:16px;">
          ${canPreview ? `<button data-emie-onclick="closeDownloadOptions();openFilePreview('${escJsString(fileUrl)}','${escJsString(fileName)}',${fileSize || 0});"
            style="display:flex;align-items:center;justify-content:center;gap:6px;padding:12px 6px;border-radius:10px;border:1px solid #c7d2fe;background:#eef2ff;cursor:pointer;font-size:13px;color:#3730a3;transition:background 0.15s;">
            <span style="font-size:18px;">👁</span> 在线预览
          </button>` : ''}
          <button data-emie-onclick="doDirectDownload('${escJsString(fullUrl)}');closeDownloadOptions();"
            style="display:flex;align-items:center;justify-content:center;gap:6px;padding:12px 6px;border-radius:10px;border:1px solid #e5e7eb;background:#fff;cursor:pointer;font-size:13px;color:#1f2937;transition:background 0.15s;"
            onmouseenter="this.style.background='#f9fafb'" onmouseleave="this.style.background='#fff'">
            <span style="font-size:18px;">⬇️</span> 直接下载
          </button>
          <button data-emie-onclick="doCopyDownloadLink('${escJsString(fullUrl)}', this);"
            style="display:flex;align-items:center;justify-content:center;gap:6px;padding:12px 6px;border-radius:10px;border:1px solid #e5e7eb;background:#fff;cursor:pointer;font-size:13px;color:#1f2937;transition:background 0.15s;"
            onmouseenter="this.style.background='#f9fafb'" onmouseleave="this.style.background='#fff'">
            <span style="font-size:18px;">🔗</span> <span id="copyBtnLabel">复制下载地址</span>
          </button>
        </div>
        <div style="padding:10px 14px;background:#f9fafb;border-radius:10px;font-size:12px;color:#9ca3af;display:flex;align-items:center;gap:8px;">
          <span style="flex-shrink:0;">🔗</span>
          <span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" title="${escHtml(fullUrl)}">${escHtml(fullUrl)}</span>
          <button data-emie-onclick="copyUrlOnly('${escJsString(fullUrl)}', this)" style="background:none;border:none;cursor:pointer;font-size:12px;color:#3370FF;padding:2px 6px;border-radius:4px;flex-shrink:0;">复制</button>
        </div>
      </div>
    </div>`;
  document.body.appendChild(panel);
}

function closeDownloadOptions() {
  document.getElementById('downloadOptionPanel')?.remove();
}

/** 直接下载：添加 ?download=true 参数触发浏览器保存 */
function doDirectDownload(url) {
  const link = document.createElement('a');
  link.href = url + (url.includes('?') ? '&' : '?') + 'download=true';
  link.target = '_blank';
  link.rel = 'noopener';
  link.click();
}

/** 给 URL 追加 ?download=true 参数 */
function appendDownloadParam(url) {
  return url + (url.includes('?') ? '&' : '?') + 'download=true';
}

/** 复制下载链接（自动带上 ?download=true，粘贴到浏览器直接触发下载） */
async function doCopyDownloadLink(url, btn) {
  const dlUrl = appendDownloadParam(url);
  try {
    await navigator.clipboard.writeText(dlUrl);
    const label = btn.querySelector('#copyBtnLabel') || btn.querySelector('span:last-child');
    if (label) {
      const orig = label.textContent;
      label.textContent = '✅ 已复制';
      setTimeout(() => label.textContent = orig, 2000);
    }
  } catch(e) {
    // fallback
    const ta = document.createElement('textarea');
    ta.value = dlUrl;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    const label = btn.querySelector('#copyBtnLabel') || btn.querySelector('span:last-child');
    if (label) {
      const orig = label.textContent;
      label.textContent = '✅ 已复制';
      setTimeout(() => label.textContent = orig, 2000);
    }
  }
}

/** 复制带下载参数的链接（底部栏） */
async function copyUrlOnly(url, btn) {
  const dlUrl = appendDownloadParam(url);
  try {
    await navigator.clipboard.writeText(dlUrl);
    btn.textContent = '✅';
    setTimeout(() => btn.textContent = '复制', 2000);
  } catch(e) {
    const ta = document.createElement('textarea');
    ta.value = dlUrl;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    btn.textContent = '✅';
    setTimeout(() => btn.textContent = '复制', 2000);
  }
}

/** 生成文件操作按钮 HTML（用于嵌入到列表/卡片中） */
function renderFileActions(fileUrl, fileName, fileSize) {
  const fullUrl = fileUrl.startsWith('http') ? fileUrl : window.location.origin + fileUrl;
  return `<button class="btn btn-outline btn-sm" data-emie-onclick="showDownloadOptions('${escJsString(fullUrl)}','${escJsString(fileName || '')}',${fileSize || 0})" title="下载选项">⬇️</button>`;
}


EMIE.registerActions({
  closeFilePreview,
  retryFilePreview,
  authenticatedFileUrl,
  openFilePreview,
  showFilePreviewFrame,
  showFilePreviewError,
  openPreviewInNewWindow,
  showDownloadOptions,
  closeDownloadOptions,
  doDirectDownload,
  appendDownloadParam,
  doCopyDownloadLink,
  copyUrlOnly,
  renderFileActions,
});

EMIE.registerModule('files', {
  authenticatedFileUrl,
  openFilePreview,
  closeFilePreview,
  retryFilePreview,
  openPreviewInNewWindow,
  showDownloadOptions,
  closeDownloadOptions,
  doDirectDownload,
  doCopyDownloadLink,
  copyUrlOnly,
  renderFileActions,
});
