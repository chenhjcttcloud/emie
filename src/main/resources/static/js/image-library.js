const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const uploadFile = (...args) => EMIE.actions.uploadFile(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const getUserName = (...args) => EMIE.actions.getUserName(...args);
let pendingImages = [];
let editingItemId = null;

function parseJson(raw, fallback = []) { try { return raw ? JSON.parse(raw) : fallback; } catch { return fallback; } }
function resourceUrl(url) { const token = localStorage.getItem('design_pm_token'); return token ? `${url}${url.includes('?') ? '&' : '?'}authToken=${encodeURIComponent(token)}` : url; }

function openUploadDrawer(existing = null) {
  editingItemId = existing?.id || null;
  pendingImages = (existing?.images || []).map(image => ({ uploaded: image, preview: resourceUrl(image.url || `/api/files/download/${image.storedName}`) }));
  const ips = (EMIE.state.ipOptions || []).filter(ip => ip.active !== false);
  const overlay = document.createElement('div'); overlay.id = 'imageLibraryUploadDrawer'; overlay.className = 'modal-overlay modal-detail-drawer';
  overlay.innerHTML = `<div class="modal"><div class="modal-header"><div><div class="modal-title">${existing ? '编辑图档' : '上传图档'}</div><div class="form-hint">完善图档资料，最多上传 3 张图片</div></div><button class="modal-close" data-emie-action="click:image-library-close-upload">✕</button></div><div class="modal-body image-library-form">
    <div class="form-group"><label class="form-label">图片名称 <span class="required-mark">*</span></label><input id="imageLibraryName" class="form-input" maxlength="160" placeholder="请输入图片名称"></div>
    <div class="form-group"><label class="form-label">IP <span class="required-mark">*</span></label><select id="imageLibraryIp" class="form-select" data-emie-action="change:image-library-ip-change"><option value="">请选择 IP</option>${ips.map(ip => `<option value="${escHtml(ip.name)}">${escHtml(ip.name)}</option>`).join('')}</select></div>
    <div class="form-group" id="imageLibrarySubGroup" hidden><label class="form-label">二级 IP</label><div id="imageLibrarySubOptions" class="chip-group"></div><div id="imageLibrarySubHint" class="form-hint"></div></div>
    <div class="form-group"><label class="form-label">上传文件 <span class="required-mark">*</span> <small id="imageLibraryImageCount">0/3</small></label><label class="image-library-dropzone"><span class="image-library-drop-icon">＋</span><strong>选择图片或 AI 文件</strong><small>支持 JPG、PNG、WebP、AI，合计最多 3 个</small><input id="imageLibraryFiles" type="file" accept="image/*,.ai" multiple hidden></label><div id="imageLibraryImagePreviews" class="image-library-upload-previews"></div></div>
    <div class="form-group"><label class="form-label">备注</label><textarea id="imageLibraryNotes" class="form-textarea" maxlength="1000" rows="4" placeholder="补充图片用途、设计说明等信息（选填）"></textarea></div>
    <div id="imageLibraryFormError" class="form-error" hidden></div>
  </div><div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:image-library-close-upload">取消</button><button id="imageLibrarySubmit" class="btn btn-primary" data-emie-action="click:image-library-submit">确认上传</button></div></div>`;
  overlay.addEventListener('click', e => { if (e.target === overlay) closeUploadDrawer(); }); document.body.appendChild(overlay);
  document.getElementById('imageLibraryFiles').addEventListener('change', e => selectImages(e.target.files));
  if (existing) {
    document.getElementById('imageLibraryName').value = existing.name || ''; document.getElementById('imageLibraryIp').value = existing.ipName || ''; document.getElementById('imageLibraryNotes').value = existing.notes || ''; updateSubOptions();
    document.querySelectorAll('#imageLibrarySubOptions .chip').forEach(chip => chip.classList.toggle('selected', (existing.subOptions || []).includes(chip.dataset.value))); renderPendingImages();
  }
  document.getElementById('imageLibraryName').focus();
}
function closeUploadDrawer() { pendingImages.filter(item => item.file).forEach(item => URL.revokeObjectURL(item.preview)); pendingImages = []; editingItemId = null; document.getElementById('imageLibraryUploadDrawer')?.remove(); }
function updateSubOptions() {
  const ip = (EMIE.state.ipOptions || []).find(item => item.name === document.getElementById('imageLibraryIp')?.value);
  const options = parseJson(ip?.subOptionsJson, []), group = document.getElementById('imageLibrarySubGroup'), box = document.getElementById('imageLibrarySubOptions');
  group.hidden = !options.length; box.innerHTML = options.map(value => `<button type="button" class="chip" data-emie-action="click:image-library-sub-toggle" data-value="${escHtml(value)}">${escHtml(value)}</button>`).join('');
  document.getElementById('imageLibrarySubHint').textContent = ip?.subOptionSelectionMode === 'single' ? '请选择一个二级 IP' : '可多选'; group.dataset.mode = ip?.subOptionSelectionMode === 'single' ? 'single' : 'multiple';
}
function toggleSubOption(button) { const group = document.getElementById('imageLibrarySubGroup'); if (group.dataset.mode === 'single') group.querySelectorAll('.chip').forEach(chip => chip.classList.remove('selected')); button.classList.toggle('selected'); }
function isAiFile(fileOrRecord) { return String(fileOrRecord?.name || '').toLowerCase().endsWith('.ai'); }
function selectImages(files) { for (const file of Array.from(files)) { if (pendingImages.length >= 3) break; if (file.type.startsWith('image/') || isAiFile(file)) pendingImages.push({ file, preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : '' }); } document.getElementById('imageLibraryFiles').value = ''; renderPendingImages(); }
function removePendingImage(index) { if (pendingImages[index].file) URL.revokeObjectURL(pendingImages[index].preview); pendingImages.splice(index, 1); renderPendingImages(); }
function renderPendingImages() { document.getElementById('imageLibraryImageCount').textContent = `${pendingImages.length}/3`; document.getElementById('imageLibraryImagePreviews').innerHTML = pendingImages.map((item, index) => { const file = item.file || item.uploaded; return `<div>${isAiFile(file) ? `<span class="image-library-ai-preview"><b>Ai</b><small>${escHtml(file.name || 'AI 文件')}</small></span>` : `<img src="${item.preview}" alt="待上传图片">`}<button type="button" data-emie-action="click:image-library-remove-image" data-index="${index}" aria-label="移除文件">✕</button></div>`; }).join(''); }
function showFormError(message) { const error = document.getElementById('imageLibraryFormError'); error.hidden = false; error.textContent = message; }

async function submitUpload() {
  const name = document.getElementById('imageLibraryName').value.trim(), ipName = document.getElementById('imageLibraryIp').value, submit = document.getElementById('imageLibrarySubmit');
  if (!name) return showFormError('请输入图片名称'); if (!ipName) return showFormError('请选择 IP'); if (!pendingImages.length) return showFormError('请至少选择一张图片');
  submit.disabled = true; submit.textContent = '正在上传…'; document.getElementById('imageLibraryFormError').hidden = true;
  try {
    const images = []; for (const item of pendingImages) images.push(item.uploaded || await uploadFile(item.file));
    const subOptions = [...document.querySelectorAll('#imageLibrarySubOptions .chip.selected')].map(chip => chip.dataset.value);
    const body = { name, ipName, subOptions, notes: document.getElementById('imageLibraryNotes').value.trim(), images };
    if (editingItemId) await apiPut(`/image-library/${editingItemId}`, body); else await apiPost('/image-library', body);
    closeUploadDrawer(); await renderImageLibrary(document.getElementById('mainContent'));
  } catch (e) { showFormError(e.message || '上传失败'); submit.disabled = false; submit.textContent = '确认上传'; }
}

async function renderImageLibrary(main) {
  const canUpload = ['admin', 'planner'].includes(EMIE.state.currentRole);
  main.innerHTML = `<section class="image-library-hero"><div><span class="image-library-kicker">PRODUCT ASSET LIBRARY</span><h1>图档库</h1><p>沉淀产品视觉资产，让每一张好图都能被快速找到。</p></div><div class="image-library-hero-art"><span>✦</span><span>◌</span><span>✧</span></div></section><section class="image-library-toolbar"><div class="image-library-stats"><div><strong id="imageLibraryTotal">—</strong><span>全部图档</span></div><div><strong id="imageLibraryToday">—</strong><span>今日新增</span></div></div><div class="image-library-actions">${canUpload ? '<button class="btn btn-primary image-library-upload" data-emie-action="click:image-library-open-upload">＋ 上传图档</button>' : ''}<label class="image-library-search">⌕<input id="imageLibrarySearch" placeholder="搜索名称、IP 或企划…"></label></div></section><div id="imageLibraryGrid" class="image-library-grid"><div class="image-library-loading"><span></span>正在整理图档…</div></div>`;
  const grid = document.getElementById('imageLibraryGrid');
  try {
    const items = await apiGet('/image-library'); document.getElementById('imageLibraryTotal').textContent = items.length; document.getElementById('imageLibraryToday').textContent = items.filter(item => String(item.createdAt || '').slice(0, 10) === new Date().toISOString().slice(0, 10)).length;
    const renderItems = list => { grid.innerHTML = list.length ? list.map(item => {
      const image = item.images?.[0] || {}, ai = isAiFile(image), thumb = image.storedName ? `/api/files/thumbnail/${image.storedName}` : image.url;
      const tags = [item.ipName, ...(item.subOptions || [])].filter(Boolean);
      const cover = ai ? `<span class="image-library-ai-cover"><b>Ai</b><small>Adobe Illustrator</small><img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(resourceUrl(thumb))}" alt="${escHtml(item.name)}" loading="lazy"></span>` : `<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(resourceUrl(thumb))}" alt="${escHtml(item.name)}" loading="lazy">`;
      const manageActions = canUpload ? `<button data-emie-action="click:image-library-edit" data-item-id="${item.id}">编辑</button><button class="danger" data-emie-action="click:image-library-delete" data-item-id="${item.id}">删除</button>` : '';
      return `<article class="image-library-card"><a href="#" data-emie-action="click:image-library-preview-cover" data-item-id="${item.id}">${cover}<span class="image-library-view">${ai ? '查看预览' : '点击放大'}</span>${item.images.length > 1 ? `<b class="image-library-count">${item.images.length} 个</b>` : ''}</a><div class="image-library-card-body"><div title="${escHtml(item.name)}">${escHtml(item.name)}</div><p>${tags.map(tag => `<span>${escHtml(tag)}</span>`).join('')}</p><small>产品企划：${escHtml(getUserName(item.ownerUserId) || item.ownerUserId || '未知')}</small><div class="image-library-card-actions"><button class="download" data-emie-action="click:image-library-open-item" data-item-id="${item.id}">下载</button>${manageActions}</div></div></article>`;
    }).join('') : '<div class="image-library-empty"><div class="image-library-empty-icon">🖼️</div><h3>还没有图档</h3><p>上传产品图片，建立团队共享的视觉资产库</p></div>'; };
    EMIE.imageLibraryItems = items; renderItems(items); document.getElementById('imageLibrarySearch').addEventListener('input', e => { const q = e.target.value.trim().toLowerCase(); renderItems(items.filter(item => `${item.name} ${item.ipName} ${(item.subOptions || []).join(' ')} ${getUserName(item.ownerUserId)}`.toLowerCase().includes(q))); });
  } catch (e) { grid.innerHTML = `<div class="empty-state">加载失败：${escHtml(e.message)}</div>`; }
}
function openItem(id) {
  const item = (EMIE.imageLibraryItems || []).find(entry => entry.id === id); if (!item?.images?.length) return;
  document.getElementById('imageLibraryDetailDrawer')?.remove();
  const overlay = document.createElement('div'); overlay.id = 'imageLibraryDetailDrawer'; overlay.className = 'modal-overlay modal-detail-drawer';
  overlay.innerHTML = `<div class="modal"><div class="modal-header"><div><div class="modal-title">${escHtml(item.name)}</div><div class="form-hint">${escHtml([item.ipName, ...(item.subOptions || [])].filter(Boolean).join(' / '))}</div></div><button class="modal-close" data-emie-action="click:image-library-close-detail">✕</button></div><div class="modal-body"><div class="image-library-detail-meta"><span>产品企划：${escHtml(getUserName(item.ownerUserId) || item.ownerUserId || '未知')}</span>${item.notes ? `<p>${escHtml(item.notes)}</p>` : ''}</div><div class="image-library-file-grid">${item.images.map((file, index) => { const ai = isAiFile(file), url = resourceUrl(file.url || `/api/files/download/${file.storedName}`), thumb = `/api/files/thumbnail/${file.storedName}`; return `<article>${ai ? '<div class="image-library-detail-ai">Ai</div>' : `<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(resourceUrl(thumb))}" alt="${escHtml(file.name || item.name)}">`}<div><strong title="${escHtml(file.name || '')}">${escHtml(file.name || `文件 ${index + 1}`)}</strong><span>${ai ? 'Adobe Illustrator' : '图片文件'}</span></div><footer>${!ai ? `<button class="btn btn-outline btn-sm" data-emie-action="click:image-library-preview-file" data-item-id="${item.id}" data-file-index="${index}">预览</button>` : ''}<button class="btn btn-primary btn-sm" data-emie-action="click:image-library-download-file" data-item-id="${item.id}" data-file-index="${index}">下载</button></footer></article>`; }).join('')}</div></div><div class="modal-footer">${item.images.length > 1 ? `<button class="btn btn-primary" data-emie-action="click:image-library-download-all" data-item-id="${item.id}">⬇ 下载全部（ZIP）</button>` : ''}<button class="btn btn-outline" data-emie-action="click:image-library-close-detail">关闭</button></div></div>`;
  overlay.addEventListener('click', e => { if (e.target === overlay) closeItemDetail(); }); document.body.appendChild(overlay);
}
function closeItemDetail() { document.getElementById('imageLibraryDetailDrawer')?.remove(); }
function previewCover(id) {
  const item = (EMIE.imageLibraryItems || []).find(entry => entry.id === id); if (!item?.images?.length) return;
  const file = item.images.find(entry => !isAiFile(entry)) || item.images[0];
  const url = isAiFile(file) ? `/api/files/thumbnail/${file.storedName}` : (file.url || `/api/files/download/${file.storedName}`);
  EMIE.actions.previewImage(resourceUrl(url), file.name || item.name);
}
function itemFile(id, index) { return (EMIE.imageLibraryItems || []).find(item => item.id === id)?.images?.[index]; }
function previewItemFile(id, index) { const file = itemFile(id, index); if (!file || isAiFile(file)) return; EMIE.actions.previewImage(resourceUrl(file.url || `/api/files/download/${file.storedName}`), file.name || '图档预览'); }
function downloadItemFile(id, index) { const file = itemFile(id, index); if (!file) return; EMIE.actions.doDirectDownload(resourceUrl(file.url || `/api/files/download/${file.storedName}`), file.name || '图档文件'); }
async function downloadAllFiles(id, button) {
  button.disabled = true; const oldText = button.textContent; button.textContent = '正在打包…';
  try {
    const token = localStorage.getItem('design_pm_token'), response = await fetch(`/api/image-library/${id}/download-all`, { headers: token ? {'X-Auth-Token': token} : {}, credentials: 'same-origin' });
    if (!response.ok) throw new Error('下载全部失败');
    const blobUrl = URL.createObjectURL(await response.blob()), link = document.createElement('a'); link.href = blobUrl;
    const item = (EMIE.imageLibraryItems || []).find(entry => entry.id === id); link.download = `${item?.name || '图档'}.zip`; link.click(); setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
  } catch (e) { EMIE.actions.showSystemAlert(e.message); } finally { button.disabled = false; button.textContent = oldText; }
}
function editItem(id) { const item = (EMIE.imageLibraryItems || []).find(entry => entry.id === id); if (item) openUploadDrawer(item); }
async function deleteItem(id) { const item = (EMIE.imageLibraryItems || []).find(entry => entry.id === id); if (!item || !await EMIE.actions.showSystemConfirm(`确认删除图档“${item.name}”吗？`)) return; try { await apiDelete(`/image-library/${id}`); await renderImageLibrary(document.getElementById('mainContent')); } catch (e) { EMIE.actions.showSystemAlert('删除失败：' + e.message); } }

EMIE.registerActions({ renderImageLibrary, openUploadDrawer, closeUploadDrawer, updateSubOptions, toggleSubOption, removePendingImage, submitUpload, openItem, closeItemDetail, previewCover, previewItemFile, downloadItemFile, downloadAllFiles, editItem, deleteItem });
const register = EMIE.actions.registerEventAction;
register?.('image-library-open-upload', () => openUploadDrawer()); register?.('image-library-close-upload', closeUploadDrawer); register?.('image-library-ip-change', updateSubOptions); register?.('image-library-sub-toggle', (_e, el) => toggleSubOption(el)); register?.('image-library-remove-image', (_e, el) => removePendingImage(Number(el.dataset.index))); register?.('image-library-submit', submitUpload); register?.('image-library-open-item', (e, el) => { e.preventDefault(); openItem(Number(el.dataset.itemId)); });
register?.('image-library-edit', (e, el) => { e.stopPropagation(); editItem(Number(el.dataset.itemId)); }); register?.('image-library-delete', (e, el) => { e.stopPropagation(); deleteItem(Number(el.dataset.itemId)); });
register?.('image-library-close-detail', closeItemDetail); register?.('image-library-preview-file', (_e, el) => previewItemFile(Number(el.dataset.itemId), Number(el.dataset.fileIndex))); register?.('image-library-download-file', (_e, el) => downloadItemFile(Number(el.dataset.itemId), Number(el.dataset.fileIndex))); register?.('image-library-download-all', (_e, el) => downloadAllFiles(Number(el.dataset.itemId), el));
register?.('image-library-preview-cover', (e, el) => { e.preventDefault(); previewCover(Number(el.dataset.itemId)); });
EMIE.registerModule('imageLibrary', { renderImageLibrary });
