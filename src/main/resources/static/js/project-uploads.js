const EMIE = window.EMIE;
const uploadFile = (...args) => EMIE.actions.uploadFile(...args);
const fmtSize = (...args) => EMIE.actions.fmtSize(...args);
const formModified = (...args) => EMIE.actions.formModified(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const showDownloadOptions = (...args) => EMIE.actions.showDownloadOptions(...args);
const renderAttachmentActions = (...args) => EMIE.actions.renderAttachmentActions(...args);

let activePreviewImage = null;
const presentationImagePayloadCache = new Map();
const presentationImagePayloadCacheLimit = 4;

function createPresentationImageCanvas(image) {
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, image.naturalWidth);
  canvas.height = Math.max(1, image.naturalHeight);
  canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
  return canvas;
}

function canvasToPngBlob(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('图片转换失败')), 'image/png');
  });
}

async function getPresentationClipboardImage(image) {
  const payload = await getPresentationImagePayload(image);
  const originalBlob = payload.blob;
  const originalType = (originalBlob.type || '').toLowerCase();
  const canWriteOriginal = originalType === 'image/png'
    || (originalType && typeof ClipboardItem.supports === 'function' && ClipboardItem.supports(originalType));
  if (canWriteOriginal) {
    return { type: originalType, blob: originalBlob };
  }

  // 浏览器通常只保证支持 PNG；非 PNG 图片才进行等尺寸转换。
  // 不再缩放图片，避免改变像素尺寸；PNG 原图会走上面的直写路径以保留 DPI 等元数据。
  return { type: 'image/png', blob: await canvasToPngBlob(createPresentationImageCanvas(image)) };
}

function presentationImageSource(image) {
  return image.dataset.fullSrc || image.currentSrc || image.src;
}

function presentationImageFileName(image, mimeType) {
  const extensionByType = {
    'image/png': '.png',
    'image/jpeg': '.jpg',
    'image/gif': '.gif',
    'image/webp': '.webp',
    'image/bmp': '.bmp'
  };
  const extension = extensionByType[mimeType] || '.png';
  let name = (image.alt || image.title || 'EMIE原图').trim()
    .replace(/[\\/:*?"<>|]+/g, '_');
  if (!name) name = 'EMIE原图';
  if (!/\.(png|jpe?g|gif|webp|bmp)$/i.test(name)) name += extension;
  return name;
}

function getPresentationImagePayload(image) {
  const source = presentationImageSource(image);
  let pending = presentationImagePayloadCache.get(source);
  if (pending) return pending;

  while (presentationImagePayloadCache.size >= presentationImagePayloadCacheLimit) {
    presentationImagePayloadCache.delete(presentationImagePayloadCache.keys().next().value);
  }
  pending = fetch(source, {
    credentials: 'same-origin',
    cache: 'force-cache'
  }).then(async response => {
    if (!response.ok) throw new Error('原图读取失败');
    const blob = await response.blob();
    const type = (blob.type || 'image/png').toLowerCase();
    return {
      source,
      blob,
      type,
      fileName: presentationImageFileName(image, type)
    };
  }).catch(error => {
    presentationImagePayloadCache.delete(source);
    throw error;
  });
  presentationImagePayloadCache.set(source, pending);
  return pending;
}

function preparePresentationImageDrag(image) {
  if (image._emiePresentationDragPayload) return;
  getPresentationImagePayload(image).then(payload => {
    if (!image.isConnected || presentationImageSource(image) !== payload.source) return;
    image._emiePresentationDragPayload = payload;
    image.draggable = true;
    image.style.cursor = 'grab';
  }).catch(() => {
    image.draggable = true;
    image.style.cursor = 'grab';
  });
}

function showImageCopyFeedback(image, text) {
  const feedback = image.closest('.modal-overlay')?.querySelector('.ppt-copy-feedback');
  if (!feedback) return;
  feedback.textContent = text;
  window.setTimeout(() => {
    if (feedback.isConnected && feedback.textContent === text) feedback.textContent = '100%';
  }, 1200);
}

// EMIE 项目域：上传、项目详情、子任务工作流、评分与分享
// ==================== 图片预览（Lightbox + 滚轮缩放 + 拖拽平移）====================
function previewImage(src, name) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.style.cssText = 'z-index:300;background:rgba(0,0,0,.85);overflow:hidden;';
  overlay.onclick = (e) => { if (e.target === overlay) { cleanup(); overlay.remove(); } };

  let scale = 1;
  const minScale = 0.25;
  const maxScale = 10;
  let isDragging = false;
  let startX = 0, startY = 0;
  let panX = 0, panY = 0;

  const imgWrap = document.createElement('div');
  imgWrap.style.cssText = 'width:100%;height:100%;display:flex;align-items:center;justify-content:center;cursor:grab;user-select:none;overflow:hidden;';
  imgWrap.onmousedown = function(e) {
    if (scale <= 1) return;
    isDragging = true;
    startX = e.clientX - panX;
    startY = e.clientY - panY;
    imgWrap.style.cursor = 'grabbing';
    e.preventDefault();
  };

  const img = document.createElement('img');
  img.src = src;
  img.className = 'ppt-drag-image';
  img.dataset.fullSrc = src;
  img.alt = name || '预览';
  img.draggable = true;
  img.style.cssText = 'max-width:90vw;max-height:90vh;border-radius:8px;box-shadow:0 4px 30px rgba(0,0,0,.5);transition:transform .15s ease;transform:scale(1);pointer-events:auto;user-select:auto;';
  activePreviewImage = img;

  imgWrap.appendChild(img);

  const zoomLabel = document.createElement('div');
  zoomLabel.className = 'ppt-copy-feedback';
  zoomLabel.style.cssText = 'position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.6);color:#fff;padding:6px 16px;border-radius:20px;font-size:13px;z-index:310;pointer-events:none;user-select:none;';
  zoomLabel.textContent = '100%';

  overlay.onwheel = function(e) {
    e.preventDefault();
    const delta = e.deltaY > 0 ? -0.1 : 0.1;
    scale = Math.min(maxScale, Math.max(minScale, scale + delta));
    img.style.transform = 'scale(' + scale + ')';
    imgWrap.style.cursor = scale > 1 ? 'grab' : 'default';
    zoomLabel.textContent = Math.round(scale * 100) + '%';
    // 还原到 1x 时复位位置
    if (scale <= 1) { panX = 0; panY = 0; img.style.marginLeft = '0'; img.style.marginTop = '0'; }
  };

  // 全局鼠标移动和释放（防止拖出图片区域时丢失事件）
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);

  function onMove(e) {
    if (!isDragging) return;
    panX = e.clientX - startX;
    panY = e.clientY - startY;
    img.style.marginLeft = panX + 'px';
    img.style.marginTop = panY + 'px';
  }

  function onUp() {
    isDragging = false;
    imgWrap.style.cursor = scale > 1 ? 'grab' : 'default';
  }

  const closeBtn = document.createElement('button');
  closeBtn.innerHTML = '✕';
  closeBtn.onclick = function() { cleanup(); overlay.remove(); };
  closeBtn.style.cssText = 'position:fixed;top:20px;left:20px;width:40px;height:40px;border-radius:12px;border:none;background:rgba(255,255,255,.9);color:#333;font-size:20px;cursor:pointer;z-index:310;box-shadow:0 2px 12px rgba(0,0,0,.3);';


  function cleanup() {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    if (activePreviewImage === img) activePreviewImage = null;
  }

  overlay.appendChild(closeBtn);
  overlay.appendChild(imgWrap);
  overlay.appendChild(zoomLabel);
  document.body.appendChild(overlay);
  preparePresentationImageDrag(img);
}

// 全局点击图片预览委托
document.addEventListener('click', function(e) {
  const img = e.target.closest('.img-clickable');
  if (img) {
    previewImage(img.dataset.fullSrc || img.src, img.alt || img.title || '');
  }
});

// 缩略图生成失败时自动回退到已授权原图，避免详情卡片出现空白；只回退一次防止错误循环。
document.addEventListener('error', function(e) {
  const img = e.target.closest?.('img.img-clickable');
  if (!img || img.dataset.fullFallback === 'true' || !img.dataset.fullSrc) return;
  img.dataset.fullFallback = 'true';
  img.src = img.dataset.fullSrc;
}, true);

// 放大图激活时，⌘C / Ctrl+C 直接写入原始图片字节。
// PNG 不再经过 Canvas 重编码，从而保留原图 DPI，避免 PowerPoint/WPS 按错误 DPI 放大。
document.addEventListener('keydown', async function(e) {
  if (!(e.metaKey || e.ctrlKey) || e.altKey || e.key.toLowerCase() !== 'c') return;
  const image = activePreviewImage;
  if (!image || !image.isConnected || !image.complete || !image.naturalWidth || !image.naturalHeight) return;

  if (!window.isSecureContext || !window.ClipboardItem || !navigator.clipboard?.write) {
    showImageCopyFeedback(image, '当前地址请右键复制图像');
    return;
  }

  e.preventDefault();
  e.stopImmediatePropagation();
  try {
    const clipboardImage = await getPresentationClipboardImage(image);
    await navigator.clipboard.write([
      new ClipboardItem({ [clipboardImage.type]: clipboardImage.blob })
    ]);
    showImageCopyFeedback(image, '已复制原始图片');
  } catch (_) {
    // 不再选中页面中的图片或调用 execCommand，避免污染随后浏览器右键复制的剪贴板格式。
    showImageCopyFeedback(image, '快捷键复制失败，请右键复制图像');
  }
}, true);

// 鼠标停留时提前读取原图；dragstart 必须同步写入 DataTransfer，不能在拖动开始后再异步 fetch。
document.addEventListener('pointerover', function(e) {
  const image = e.target.closest?.('img.img-clickable, img.ppt-drag-image');
  if (image) preparePresentationImageDrag(image);
});

// 向外部应用同时提供真实 File、Chrome DownloadURL 和标准 URL/HTML 降级格式。
// PowerPoint/WPS 优先接收原始文件 Blob，可保留原图像素、DPI 和宽高比。
document.addEventListener('dragstart', function(e) {
  const image = e.target.closest?.('img.img-clickable, img.ppt-drag-image');
  if (!image || !e.dataTransfer || !image.complete) return;
  const payload = image._emiePresentationDragPayload;
  if (!payload) {
    e.preventDefault();
    preparePresentationImageDrag(image);
    showImageCopyFeedback(image, '原图准备中，请稍后拖拽');
    return;
  }

  try {
    e.dataTransfer.effectAllowed = 'copy';
    e.dataTransfer.clearData();
    const file = new File([payload.blob], payload.fileName, {
      type: payload.type,
      lastModified: Date.now()
    });
    e.dataTransfer.items?.add(file);
    e.dataTransfer.setData('DownloadURL', `${payload.type}:${payload.fileName}:${payload.source}`);
    e.dataTransfer.setData('text/uri-list', payload.source);
    e.dataTransfer.setData('text/plain', payload.source);
    e.dataTransfer.setData('text/html', `<img src="${payload.source}" alt="${escHtml(payload.fileName)}">`);
    e.dataTransfer.setDragImage(
      image,
      Math.min(40, Math.max(1, image.clientWidth / 2)),
      Math.min(40, Math.max(1, image.clientHeight / 2))
    );
  } catch (_) {
    showImageCopyFeedback(image, '当前浏览器无法拖出原图，请使用复制');
  }
});

// ==================== 文件上传工具（multipart 流式 + 进度条）====================

function showProgressBar(containerId, fileName) {
  const c = document.getElementById(containerId);
  if (!c) return;
  const el = document.createElement('div');
  el.className = 'upload-progress';
  el.id = 'prog_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
  el.style.cssText = 'margin-top:6px;background:var(--gray-100);border-radius:6px;overflow:hidden;font-size:11px;';
  el.innerHTML = `<div style="display:flex;justify-content:space-between;padding:2px 8px;color:var(--gray-500);">
    <span>⏳ ${fileName}</span><span class="prog-pct">0%</span>
  </div><div style="height:4px;background:var(--gray-200);border-radius:2px;margin:0 8px 6px;">
    <div class="prog-bar" style="width:0%;height:100%;background:var(--primary);border-radius:2px;transition:width .3s;"></div>
  </div>`;
  c.parentNode.insertBefore(el, c.nextSibling);
  return el.id;
}

function updateProgress(progId, pct) {
  const el = document.getElementById(progId);
  if (!el) return;
  el.querySelector('.prog-pct').textContent = pct + '%';
  el.querySelector('.prog-bar').style.width = pct + '%';
  if (pct >= 100) {
    el.querySelector('span:first-child').textContent = '✅ ' + el.querySelector('span:first-child').textContent.slice(2);
  }
}

function removeProgress(progId) {
  const el = document.getElementById(progId);
  if (el) el.remove();
}

async function handleFileUpload(input, list, maxCount, typeLabel, isImage) {
  const files = input?.files || input || [];
  if (list.length + files.length > maxCount) {
    alert(typeLabel + '最多上传' + maxCount + '个');
    if (input?.value !== undefined) input.value = '';
    return;
  }
  const maxBytes = 200 * 1024 * 1024;
  const blocked = ['.sql', '.sh', '.bat', '.cmd', '.exe', '.dll', '.so', '.jar', '.war', '.php', '.asp', '.jsp', '.py', '.vbs', '.ps1', '.msi', '.reg', '.scr'];

  for (const f of files) {
    const ext = '.' + f.name.split('.').pop()?.toLowerCase();
    if (blocked.includes(ext)) { alert('不允许上传 ' + ext + ' 文件'); continue; }
    if (f.size > maxBytes) { alert('文件 ' + f.name + ' 超过大小限制'); continue; }

    const suffix = typeLabel.includes('交付') ? 'Deliver' : 'Create';
    const containerId = isImage ? (suffix + (isImage ? 'RefImageList' : 'AttachmentList')) : (suffix + (isImage ? 'RefImageList' : 'AttachmentList'));
    const barId = showProgressBar(
      isImage ? (suffix === 'Create' ? 'createRefImageList' : 'deliverImageList')
              : (suffix === 'Create' ? 'createAttachmentList' : 'deliverAttachmentList'),
      f.name
    );
    EMIE.projectState.uploadingCount++;

    try {
      const result = await uploadFile(f, (pct) => updateProgress(barId, pct));
      list.push({ name: result.name, url: result.url, size: result.size, storedName: result.storedName });
      renderFileList(list, typeLabel);
      formModified();
      setTimeout(() => removeProgress(barId), 1500);
    } catch (e) {
      removeProgress(barId);
      alert('上传失败: ' + f.name + ' - ' + e.message);
    }
    EMIE.projectState.uploadingCount--;
  }
  if (input?.value !== undefined) input.value = '';
}

function renderFileList(list, typeLabel) {
  const isImage = typeLabel.includes('参考图片');
  const context = resolveFileListContext(list, isImage);
  const containerId = context.containerId;
  const c = document.getElementById(containerId);
  if (!c) return;
  if (!list.length) { c.innerHTML = ''; return; }

  // 为图片 URL 追加 token（<img> 标签无法发送 X-Auth-Token 头）
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => u + (u.includes('?') ? '&' : '?') + 'token=' + token;
  const canRenderAsImage = file => /\.(png|jpe?g|gif|webp|bmp)$/i.test(file?.name || file?.url || '');

  if (isImage) {
    c.innerHTML = `<div class="image-preview">${list.map((img, i) => canRenderAsImage(img)
      ? `<div style="position:relative;display:inline-block;">
        <img src="${escHtml(authUrl(img.url))}" alt="${escHtml(img.name)}" class="img-clickable" draggable="true" loading="lazy" decoding="async" style="width:180px;height:180px;object-fit:contain;border-radius:6px;border:1px solid var(--gray-200);cursor:grab;background:#fff;">
        <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escJsString(img.url)}','${escJsString(img.name)}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:20px;height:20px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
        <button style="position:absolute;top:-6px;right:-6px;width:20px;height:20px;border-radius:50%;border:none;background:var(--danger);color:#fff;font-size:12px;cursor:pointer;display:flex;align-items:center;justify-content:center;" data-emie-onclick="removeFileItem('${context.listKey}',${i})">✕</button>
      </div>`
      : `<div class="file-item" style="width:100%;"><span class="file-item-name">📐 ${escHtml(img.name)}</span><span style="font-size:11px;color:var(--gray-400);">${fmtSize(img.size)}</span>${renderAttachmentActions(img, true)}<button class="remove-file" data-emie-onclick="removeFileItem('${context.listKey}',${i})">✕</button></div>`).join('')}</div>`;
  } else {
    c.innerHTML = list.map((f, i) =>
      `<div class="file-item"><span class="file-item-name">📎 ${escHtml(f.name)}</span><span style="font-size:11px;color:var(--gray-400);">${fmtSize(f.size)}</span>${renderAttachmentActions(f, true)}<button class="remove-file" data-emie-onclick="removeFileItem('${context.listKey}',${i})">✕</button></div>`
    ).join('');
  }
}

function resolveFileListContext(list, isImage) {
  if (list === EMIE.projectState.createRefImages) return { listKey: 'createRef', containerId: 'createRefImageList' };
  if (list === EMIE.projectState.createAttachments) return { listKey: 'createAttachment', containerId: 'createAttachmentList' };
  if (list === EMIE.projectState.deliverImages) return { listKey: 'deliverImage', containerId: 'deliverImageList' };
  if (list === EMIE.projectState.deliverAttachments) return { listKey: 'deliverAttachment', containerId: 'deliverAttachmentList' };
  if (list === EMIE.projectState.subTaskRefImages) return { listKey: 'subTaskRef', containerId: 'createRefImageList' };
  if (list === EMIE.projectState.subTaskAttachments) return { listKey: 'subTaskAttachment', containerId: 'createAttachmentList' };
  if (list === EMIE.projectState.editTaskRefImages) return { listKey: 'editTaskRef', containerId: 'createRefImageList' };
  if (list === EMIE.projectState.editTaskAttachments) return { listKey: 'editTaskAttachment', containerId: 'createAttachmentList' };
  if (list === EMIE.projectState.editProjectRefImages) return { listKey: 'editProjectRef', containerId: 'editProjectRefImageList' };
  if (list === EMIE.projectState.editProjectAttachments) return { listKey: 'editProjectAttachment', containerId: 'editProjectAttachmentList' };
  if (list === EMIE.projectState.rejectionImages) return { listKey: 'rejectionImage', containerId: 'rejectImageList' };
  if (list === EMIE.projectState.rejectionAttachments) return { listKey: 'rejectionAttachment', containerId: 'rejectAttachmentList' };
  return {
    listKey: isImage ? 'createRef' : 'createAttachment',
    containerId: isImage ? 'createRefImageList' : 'createAttachmentList'
  };
}

function removeFileItem(listKey, idx) {
  const contexts = {
    createRef: [EMIE.projectState.createRefImages, '参考图片'],
    createAttachment: [EMIE.projectState.createAttachments, '附件'],
    deliverImage: [EMIE.projectState.deliverImages, '交付参考图片'],
    deliverAttachment: [EMIE.projectState.deliverAttachments, '交付附件'],
    subTaskRef: [EMIE.projectState.subTaskRefImages, '参考图片'],
    subTaskAttachment: [EMIE.projectState.subTaskAttachments, '附件'],
    editTaskRef: [EMIE.projectState.editTaskRefImages, '编辑参考图片'],
    editTaskAttachment: [EMIE.projectState.editTaskAttachments, '编辑附件'],
    editProjectRef: [EMIE.projectState.editProjectRefImages, '编辑项目参考图片'],
    editProjectAttachment: [EMIE.projectState.editProjectAttachments, '编辑项目附件'],
    rejectionImage: [EMIE.projectState.rejectionImages, '驳回参考图'],
    rejectionAttachment: [EMIE.projectState.rejectionAttachments, '驳回附件']
  };
  const context = contexts[listKey];
  if (!context) return;
  const [list, typeLabel] = context;
  if (idx >= 0 && idx < list.length) {
    list.splice(idx, 1);
  }
  renderFileList(list, typeLabel);
}

function handleCreateRefImages(input) { handleFileUpload(input, EMIE.projectState.createRefImages, 6, '参考图片', true); }
function handleCreateAttachments(input) { handleFileUpload(input, EMIE.projectState.createAttachments, 5, '附件', false); }
function handleDeliverImages(input) { handleFileUpload(input, EMIE.projectState.deliverImages, 6, '交付参考图片', true); }
function handleDeliverAttachments(input) { handleFileUpload(input, EMIE.projectState.deliverAttachments, 5, '交付附件', false); }

// 全局拖拽上传：所有 .upload-area 根据其隐藏 input 自动复用现有上传逻辑。
function handleUploadDrop(input, files) {
  const id = input?.id || '';
  const config = {
    createRefImageInput: [EMIE.projectState.createRefImages, 6, '参考图片', true],
    createAttachmentInput: [EMIE.projectState.createAttachments, 5, '附件', false],
    subTaskRefImageInput: [EMIE.projectState.subTaskRefImages, 6, '参考图片', true],
    subTaskAttachmentInput: [EMIE.projectState.subTaskAttachments, 9, '附件', false],
    editRefImageInput: [EMIE.projectState.editTaskRefImages, 6, '编辑参考图片', true],
    editAttachmentInput: [EMIE.projectState.editTaskAttachments, 9, '编辑附件', false],
    editProjectRefImageInput: [EMIE.projectState.editProjectRefImages, 6, '编辑项目参考图片', true],
    editProjectAttachmentInput: [EMIE.projectState.editProjectAttachments, 5, '编辑项目附件', false],
    deliverImageInput: [EMIE.projectState.deliverImages, 6, '交付参考图片', true],
    deliverAttachmentInput: [EMIE.projectState.deliverAttachments, 5, '交付附件', false],
  }[id];
  if (config) handleFileUpload(files, ...config);
}

document.addEventListener('dragover', event => {
  const zone = event.target.closest?.('.upload-area');
  if (!zone) return;
  event.preventDefault();
  zone.classList.add('drag-over');
  event.dataTransfer.dropEffect = 'copy';
});
document.addEventListener('dragleave', event => {
  const zone = event.target.closest?.('.upload-area');
  if (zone && !zone.contains(event.relatedTarget)) zone.classList.remove('drag-over');
});
document.addEventListener('drop', event => {
  const zone = event.target.closest?.('.upload-area');
  if (!zone) return;
  event.preventDefault();
  zone.classList.remove('drag-over');
  const input = zone.querySelector('input[type="file"]');
  if (input && event.dataTransfer?.files?.length) handleUploadDrop(input, event.dataTransfer.files);
});

// 剪贴板图片上传：从飞书、微信或截图工具复制图片后，鼠标移入上传框并直接粘贴。
// 仅处理剪贴板中的真实图片 Blob，不读取或上传剪贴板文本，避免影响原有输入框。
let activeUploadZone = null;
document.addEventListener('mouseover', event => {
  const zone = event.target.closest?.('.upload-area');
  if (zone) activeUploadZone = zone;
});
document.addEventListener('paste', event => {
  const zone = event.target.closest?.('.upload-area') || activeUploadZone;
  const item = Array.from(event.clipboardData?.items || []).find(entry => entry.kind === 'file' && /^image\//i.test(entry.type));
  if (!zone || !item) return;
  const input = zone.querySelector('input[type="file"]');
  const blob = item.getAsFile();
  if (!input || !blob) return;
  event.preventDefault();
  const ext = (blob.type.split('/')[1] || 'png').replace('jpeg', 'jpg');
  const file = new File([blob], `粘贴图片-${Date.now()}.${ext}`, { type: blob.type, lastModified: Date.now() });
  handleUploadDrop(input, [file]);
});


EMIE.registerActions({
  previewImage,
  showProgressBar,
  updateProgress,
  removeProgress,
  handleFileUpload,
  renderFileList,
  resolveFileListContext,
  removeFileItem,
  handleCreateRefImages,
  handleCreateAttachments,
  handleDeliverImages,
  handleDeliverAttachments,
  handleUploadDrop,
});

EMIE.registerModule('projectUploads', {
  previewImage,
  handleFileUpload,
  renderFileList,
  removeFileItem,
  handleCreateRefImages,
  handleCreateAttachments,
  handleDeliverImages,
  handleDeliverAttachments,
  handleUploadDrop,
});
