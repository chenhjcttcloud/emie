const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const uploadFile = (...args) => EMIE.actions.uploadFile(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const fmtSize = (...args) => EMIE.actions.fmtSize(...args);
const handleFileUpload = (...args) => EMIE.actions.handleFileUpload(...args);
const renderFileList = (...args) => EMIE.actions.renderFileList(...args);

function fileLabel(file) { return file?.originalName || file?.name || file?.fileName || file?.storedName || '文件'; }
function fileJson(file) { return JSON.stringify(file || {}); }
function authenticatedFileUrl(url) { return EMIE.actions.authenticatedFileUrl ? EMIE.actions.authenticatedFileUrl(url) : url; }
function materialIp(m) { return [m.ipName, ...(Array.isArray(m.ipSubOptions) ? m.ipSubOptions : [])].filter(Boolean).join(' / ') || '暂无'; }
function materialProjectLink(m, compact = false) {
  if (!m.projectId) return '<span class="material-project-empty">暂无关联项目</span>';
  const code = m.projectCode || `#${m.projectId}`;
  const label = compact ? `项目 ${code}` : `关联项目 ${code}`;
  return `<a href="javascript:void(0)" class="material-project-link" data-emie-onclick="openProjectDetail(${Number(m.projectId)})" title="点击查看关联项目">${label}</a>`;
}
function canPick(m) { return !m.selected && !m.projectId && ['sales','planner','admin'].includes(EMIE.state.currentRole); }
function isOwnMaterial(m) { return EMIE.state.currentRole === 'designer' && String(m.creatorId || m.authorId || '') === String(EMIE.state.currentUserId || EMIE.state.authUser?.userId || ''); }
function parseMaterialJson(value, fallback) {
  if (!value) return fallback;
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch (_) { return fallback; }
}
function normalizeMaterial(raw) {
  const m = { ...raw };
  m.description = m.description || m.productDescription || '';
  m.authorName = m.authorName || m.creatorName || m.designerName || '';
  m.ipSubOptions = Array.isArray(m.ipSubOptions) ? m.ipSubOptions : parseMaterialJson(m.ipSubOptionsJson, []);
  m.files = Array.isArray(m.files) ? m.files : parseMaterialJson(m.materialFilesJson, []);
  if (!Array.isArray(m.files)) m.files = m.files ? [m.files] : [];
  m.referenceImages = Array.isArray(m.referenceImages) ? m.referenceImages : parseMaterialJson(m.referenceImagesJson, []);
  if (!Array.isArray(m.referenceImages)) m.referenceImages = m.referenceImages ? [m.referenceImages] : [];
  m.planFile = m.planFile || m.planningPpt || parseMaterialJson(m.proposalPptJson, null);
  if (Array.isArray(m.planFile)) m.planFile = m.planFile[0] || null;
  return m;
}

async function loadMaterials() {
  const data = await apiGet('/materials');
  const items = Array.isArray(data) ? data : (data.items || data.content || []);
  return items.map(normalizeMaterial);
}

function renderMaterialCard(m) {
  const chosen = !!(m.selected || m.projectId || m.status === 'selected');
  const preview = authenticatedFileUrl(m.coverUrl || m.previewUrl || m.thumbnailUrl || m.referenceImages[0]?.downloadUrl || m.referenceImages[0]?.url);
  return `<article class="material-card ${chosen ? 'material-card-selected' : ''}" tabindex="0" data-emie-onclick="openMaterialDetail(${m.id})" aria-label="查看素材 ${escHtml(m.title || '')}">
    <div class="material-cover">${preview ? `<img src="${escHtml(preview)}" alt="${escHtml(m.title || '素材预览')}" loading="lazy">` : '<span aria-hidden="true">🎨</span>'}<span class="material-selected-badge ${chosen ? 'is-claimed' : 'is-unclaimed'}">${chosen ? '已被认领' : '未被认领'}</span></div>
    <div class="material-card-body"><h3>${escHtml(m.title || '未命名素材')}</h3><div class="material-meta">${escHtml(materialIp(m))}</div><p>${escHtml(m.description || '暂无产品说明')}</p><div class="material-card-foot"><span>作者：${escHtml(m.authorName || m.designerName || '-')}</span><span>${chosen ? `认领：${escHtml(m.selectedByName || m.selectedBy || '已认领')}` : '待认领'}</span></div><div class="material-project-row">${materialProjectLink(m, true)}</div></div>
  </article>`;
}

async function openMaterialDetail(id) {
  const m = normalizeMaterial(await apiGet(`/materials/${id}`));
  const chosen = !!(m.selected || m.projectId || m.status === 'selected');
  const files = Array.isArray(m.files) ? m.files : (Array.isArray(m.attachments) ? m.attachments : []);
  const referenceImages = Array.isArray(m.referenceImages) ? m.referenceImages : [];
  const ppt = m.planFile || m.planningPpt;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialDetailModal';
  overlay.innerHTML = `<div class="modal modal-lg"><div class="modal-header"><div class="modal-title">${escHtml(m.title || '素材详情')}</div><button class="modal-close" data-emie-onclick="closeMaterialDetail()">✕</button></div><div class="modal-body">
    ${chosen ? '<div class="material-lock-note">🔒 该素材已被选中，其他用户无法再次立项，但仍可查看资料。</div>' : ''}
    <div class="material-detail-summary"><div><div class="detail-label">IP</div><div class="detail-value">${escHtml(materialIp(m))}</div></div><div><div class="detail-label">创意作者</div><div class="detail-value">${escHtml(m.authorName || m.designerName || '暂无')}</div></div><div><div class="detail-label">认领人</div><div class="detail-value">${chosen ? escHtml(m.selectedByName || m.selectedBy || '已认领') : '待认领'}</div></div><div><div class="detail-label">关联项目</div><div class="detail-value">${materialProjectLink(m)}</div></div></div>
    <section class="material-detail-section"><h3>产品说明</h3><div class="material-detail-section-content material-description">${m.description ? escHtml(m.description) : '<span class="material-empty">暂无</span>'}</div></section>
    <section class="material-detail-section"><h3>🖼️ 参考图片</h3><div class="material-detail-section-content">${referenceImages.length ? `<div class="material-reference-images">${referenceImages.map(image => { const url = authenticatedFileUrl(image.downloadUrl || image.url || '#'); return `<a href="${escHtml(url)}" target="_blank" rel="noopener"><img src="${escHtml(url)}" alt="${escHtml(fileLabel(image))}"></a>`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    <section class="material-detail-section"><h3>素材文件</h3><div class="material-detail-section-content">${files.length ? `<div class="material-file-list">${files.map(f => { const url = authenticatedFileUrl(f.downloadUrl || f.url || '#'); return `<a class="material-file" href="${escHtml(url)}" target="_blank" rel="noopener">📎 ${escHtml(fileLabel(f))}<small>${f.size ? fmtSize(f.size) : ''}</small></a>`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    ${ppt ? `<section class="material-detail-section"><h3>策划案 PPT</h3><div class="material-detail-section-content"><a class="material-file" href="${escHtml(authenticatedFileUrl(ppt.downloadUrl || ppt.url || '#'))}" target="_blank" rel="noopener">📊 ${escHtml(fileLabel(ppt))}</a></div></section>` : ''}
    </div><div class="modal-footer">${canPick(m) ? `${EMIE.state.currentRole === 'admin' ? `<label class="form-label" style="margin:0 8px 0 0;">负责企划</label><select id="materialPlannerSelect" class="form-input" style="width:180px;display:inline-block;"><option value="">请选择</option>${Object.values(EMIE.state.users || {}).filter(u => u.role === 'planner' || u.roles?.includes?.('planner')).map(u => `<option value="${escHtml(u.id || u.userId)}">${escHtml(u.name || u.displayName || u.id)}</option>`).join('')}</select>` : ''}<button class="btn btn-primary" data-emie-onclick="selectMaterial(${m.id})">${EMIE.state.currentRole === 'sales' ? '选中并创建渠道定制单' : '选中并创建公司常规品'}</button>` : ''}${isOwnMaterial(m) && !chosen ? `<span class="material-owner-actions"><button class="btn btn-outline" data-emie-onclick="openMaterialEdit(${m.id})">编辑</button><button class="btn btn-danger" data-emie-onclick="deleteMaterial(${m.id})">删除</button><button class="btn btn-warning" data-emie-onclick="unpublishMaterial(${m.id})">下架</button></span>` : ''}<button class="btn btn-outline" data-emie-onclick="closeMaterialDetail()">关闭</button></div></div>`;
  document.body.appendChild(overlay);
}
function closeMaterialDetail() { document.getElementById('materialDetailModal')?.remove(); }
async function deleteMaterial(id) { if (!window.confirm('确认删除这件作品吗？删除后不可恢复。')) return; try { await apiDelete(`/materials/${id}`); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('删除失败：' + e.message); } }
async function unpublishMaterial(id) { if (!window.confirm('确认下架这件作品吗？下架后其他人将无法认领。')) return; try { await apiPost(`/materials/${id}/withdraw`); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('下架失败：' + e.message); } }
async function selectMaterial(id) { try { const plannerId = document.getElementById('materialPlannerSelect')?.value; if (EMIE.state.currentRole === 'admin' && !plannerId) return EMIE.actions.showSystemAlert('请选择负责的产品企划'); await apiPost(`/materials/${id}/select`, { role: EMIE.state.currentRole, plannerId: plannerId || undefined }); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('选材失败：' + e.message); } }

async function openMaterialEdit(id) {
  try { const m = normalizeMaterial(await apiGet(`/materials/${id}`)); if (!isOwnMaterial(m) || m.selected || m.projectId) return EMIE.actions.showSystemAlert('已认领作品不能修改'); renderMaterialUploadModal(m); } catch (e) { EMIE.actions.showSystemAlert('加载作品失败：' + e.message); }
}
function renderMaterialUploadModal(existing = null) {
  const ips = EMIE.state.ipOptions || [];
  EMIE.projectState.materialRefImages = [];
  EMIE.projectState.materialAttachments = [];
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialUploadModal';
  overlay.innerHTML = `<div class="modal modal-lg"><div class="modal-header"><div class="modal-title">${existing ? '编辑作品' : '发布新素材'}</div><button class="modal-close" data-emie-onclick="closeMaterialUpload()">✕</button></div><form id="materialUploadForm" class="modal-body" data-material-id="${existing?.id || ''}">
    <div class="form-group"><label class="form-label">标题 <span class="required-mark">*</span></label><input class="form-input" name="title" required maxlength="120"></div>
    <div class="form-group"><label class="form-label">IP <span class="required-mark">*</span></label><select class="form-select" id="materialIpName" name="ipName" required data-emie-onchange="onMaterialIpChange(this)"><option value="">请选择 IP</option>${ips.map(i => `<option value="${escHtml(i.name || i.value || i)}">${escHtml(i.name || i.label || i.value || i)}</option>`).join('')}</select></div>
    <div class="form-group" id="materialIpSubGroup" style="display:none"><label class="form-label">二级IP</label><div class="chip-group" id="materialIpSubChips"></div><input type="hidden" name="ipSubOptions" id="materialIpSubOptions" value="[]"><div class="form-hint" id="materialIpSubHint"></div></div>
    <div class="form-group"><label class="form-label">产品说明 <span class="required-mark">*</span></label><textarea class="form-textarea" name="description" required rows="5" placeholder="说明产品创意、适用场景和设计亮点"></textarea></div>
    <div class="form-group material-upload-group"><label class="form-label">🖼️ 参考图片 <span class="required-mark">*</span></label><div class="upload-area" data-emie-onclick="document.getElementById('materialRefImageInput').click()"><div>📁 拖拽图片到此处，或点击选择图片</div><input class="form-input" type="file" id="materialRefImageInput" multiple accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-onchange="handleMaterialRefImages(this)"></div><div class="file-list" id="materialRefImageList"></div></div>
    <div class="form-group material-upload-group"><label class="form-label">📎 附件</label><div class="upload-area" data-emie-onclick="document.getElementById('materialAttachmentInput').click()"><div>📁 拖拽文件到此处，或点击选择文件</div><input class="form-input" type="file" id="materialAttachmentInput" multiple accept="${escHtml(EMIE.fileAccept.attachment)}" style="display:none" data-emie-onchange="handleMaterialAttachments(this)"></div><div class="file-list" id="materialAttachmentList"></div></div>
    <div id="materialUploadError" class="form-error" hidden></div>
  </form><div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeMaterialUpload()">取消</button><button class="btn btn-primary" data-emie-onclick="submitMaterialUpload()">立即发布</button></div></div>`;
  document.body.appendChild(overlay); EMIE.installUploadHints?.(overlay);
  if (existing) { const form = document.getElementById('materialUploadForm'); form.elements.title.value = existing.title || ''; form.elements.ipName.value = existing.ipName || ''; form.elements.description.value = existing.description || ''; EMIE.projectState.materialRefImages = Array.isArray(existing.referenceImages) ? existing.referenceImages.slice() : parseMaterialJson(existing.referenceImagesJson, []); EMIE.projectState.materialAttachments = Array.isArray(existing.files) ? existing.files.slice() : parseMaterialJson(existing.materialFilesJson, []); renderFileList(EMIE.projectState.materialRefImages, '参考图片'); renderFileList(EMIE.projectState.materialAttachments, '附件'); onMaterialIpChange(form.elements.ipName); const sub = parseMaterialJson(existing.ipSubOptionsJson || existing.ipSubOptions, []); document.getElementById('materialIpSubOptions').value = JSON.stringify(sub); document.querySelectorAll('#materialIpSubChips .chip').forEach(chip => { if (sub.includes(chip.dataset.value)) chip.classList.add('selected'); }); }
}
function closeMaterialUpload() { document.getElementById('materialUploadModal')?.remove(); }
function handleMaterialRefImages(input) { handleFileUpload(input, EMIE.projectState.materialRefImages, 6, '参考图片', true); }
function handleMaterialAttachments(input) { handleFileUpload(input, EMIE.projectState.materialAttachments, 5, '附件', false); }
function onMaterialIpChange(select) { const ip = (EMIE.state.ipOptions || []).find(item => item.name === select.value); const options = parseMaterialJson(ip?.subOptionsJson, []); const group = document.getElementById('materialIpSubGroup'); const chips = document.getElementById('materialIpSubChips'); const input = document.getElementById('materialIpSubOptions'); const hint = document.getElementById('materialIpSubHint'); if (!group || !chips || !input || !hint) return; input.value = '[]'; chips.innerHTML = ''; if (!options.length) { group.style.display = 'none'; return; } group.style.display = ''; group.dataset.selectionMode = ip.subOptionSelectionMode === 'single' ? 'single' : 'multiple'; hint.textContent = group.dataset.selectionMode === 'single' ? '请选择一个二级 IP' : '可多选'; chips.innerHTML = options.map(v => `<span class="chip" data-value="${escHtml(v)}" data-emie-onclick="toggleMaterialIpSubOption(this)">${escHtml(v)}</span>`).join(''); }
function toggleMaterialIpSubOption(el) { const group = document.getElementById('materialIpSubGroup'); if (group?.dataset.selectionMode === 'single') document.querySelectorAll('#materialIpSubChips .chip').forEach(x => x.classList.remove('selected')); el.classList.toggle('selected'); document.getElementById('materialIpSubOptions').value = JSON.stringify([...document.querySelectorAll('#materialIpSubChips .chip.selected')].map(x => x.dataset.value)); }
async function submitMaterialUpload() { const form = document.getElementById('materialUploadForm'); if (!form.reportValidity()) return; if (EMIE.projectState.uploadingCount > 0) return EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); if (!form.dataset.materialId && !EMIE.projectState.materialRefImages.length) return EMIE.actions.showSystemAlert('请至少上传一张参考图片'); const btn = document.querySelector('#materialUploadModal .btn-primary'); btn.disabled = true; try { const fd = new FormData(form); const body = { title: fd.get('title'), ipName: fd.get('ipName'), ipSubOptions: fd.get('ipSubOptions'), description: fd.get('description'), filesJson: JSON.stringify(EMIE.projectState.materialAttachments), referenceImagesJson: JSON.stringify(EMIE.projectState.materialRefImages) }; if (form.dataset.materialId) await apiPut(`/materials/${form.dataset.materialId}`, body); else await apiPost('/materials', body); closeMaterialUpload(); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { const error = document.getElementById('materialUploadError'); error.hidden = false; error.textContent = e.message; btn.disabled = false; } }

function materialStatus(m) { return m.selected || m.projectId || m.status === 'selected' ? 'selected' : 'available'; }

function renderMaterialGrid(items) {
  const grid = document.getElementById('materialGrid');
  if (!grid) return;
  grid.innerHTML = items.length
    ? items.map(renderMaterialCard).join('')
    : '<div class="market-empty"><div class="market-empty-icon">✦</div><h3>暂无匹配素材</h3><p>试试调整筛选条件或搜索关键词</p></div>';
}

function setMaterialStatusFilter(status) {
  const input = document.getElementById('materialStatusFilter');
  if (input) input.value = status;
  filterMaterials();
}

function resetMaterialFilters() {
  const search = document.getElementById('materialSearch');
  const status = document.getElementById('materialStatusFilter');
  const ip = document.getElementById('materialIpFilter');
  if (search) search.value = '';
  if (status) status.value = 'all';
  if (ip) ip.value = '';
  filterMaterials();
}

async function renderMaterialMarket(main) {
  const role = EMIE.state.currentRole;
  main.innerHTML = `<section class="market-hero"><div class="market-hero-copy"><span class="market-eyebrow">EMIE CREATIVE MARKET</span><h1>让好创意，被看见</h1><p>发现团队里的灵感宝藏，把优秀设计快速变成产品。</p><div class="market-hero-actions">${role === 'designer' ? '<button class="btn market-primary-btn" data-emie-onclick="renderMaterialUploadModal()">＋ 发布我的创意</button>' : '<span class="market-hint">浏览灵感，寻找下一个爆款</span>'}</div></div><div class="market-hero-art"><span>✦</span><span>◇</span><span>✧</span></div></section><div class="market-toolbar-row"><div class="market-filter-card"><div class="market-stats"><button type="button" data-emie-onclick="setMaterialStatusFilter('all')"><strong id="marketTotal">—</strong><span>全部素材</span></button><button type="button" data-emie-onclick="setMaterialStatusFilter('available')"><strong id="marketAvailable">—</strong><span>可选创意</span></button></div><div class="market-filter-fields"><label><span>状态</span><select id="materialStatusFilter" class="form-select" data-emie-onchange="filterMaterials()"><option value="all">全部</option><option value="available">可选</option><option value="selected">已选中</option></select></label><label><span>IP</span><select id="materialIpFilter" class="form-select" data-emie-onchange="filterMaterials()"><option value="">全部 IP</option></select></label><button class="btn btn-outline btn-sm market-filter-reset" data-emie-onclick="resetMaterialFilters()">重置</button></div></div><div class="material-toolbar"><div class="market-search-wrap"><span>⌕</span><input id="materialSearch" class="form-input" placeholder="搜索标题、IP、作者或产品说明" data-emie-oninput="filterMaterials()"><button class="btn market-search-btn" data-emie-onclick="filterMaterials()">搜索</button></div></div></div><div id="materialGrid" class="material-grid"><div class="market-empty"><div class="market-empty-icon">✦</div><h3>正在寻找灵感…</h3><p>素材广场马上为你呈现最新创意</p></div></div>`;
  document.querySelector('.market-filter-card')?.insertAdjacentHTML('afterbegin', '<div class="market-filter-intro"><span>⌘</span><div><strong>筛选素材</strong><small>按状态与 IP 快速定位</small></div></div>');
  document.querySelector('.market-search-btn')?.remove();
  const items = await loadMaterials();
  EMIE.materialState.items = items;
  document.getElementById('marketTotal').textContent = items.length;
  document.getElementById('marketAvailable').textContent = items.filter(m => materialStatus(m) === 'available').length;
  const ipFilter = document.getElementById('materialIpFilter');
  if (ipFilter) ipFilter.insertAdjacentHTML('beforeend', [...new Set(items.map(m => m.ipName).filter(Boolean))].sort().map(ip => `<option value="${escHtml(ip)}">${escHtml(ip)}</option>`).join(''));
  filterMaterials();
}

function filterMaterials() {
  const q = (document.getElementById('materialSearch')?.value || '').trim().toLowerCase();
  const status = document.getElementById('materialStatusFilter')?.value || 'all';
  const ip = document.getElementById('materialIpFilter')?.value || '';
  const items = (EMIE.materialState.items || []).filter(m => {
    const matchesKeyword = `${m.title} ${m.description} ${materialIp(m)} ${m.authorName}`.toLowerCase().includes(q);
    return matchesKeyword && (status === 'all' || materialStatus(m) === status) && (!ip || m.ipName === ip);
  });
  renderMaterialGrid(items);
}

EMIE.registerActions({ renderMaterialMarket, openMaterialDetail, closeMaterialDetail, selectMaterial, renderMaterialUploadModal, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, resetMaterialFilters, onMaterialIpChange, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments });
EMIE.registerModule('materialMarket', { renderMaterialMarket, openMaterialDetail, closeMaterialDetail, selectMaterial, renderMaterialUploadModal, openMaterialEdit, deleteMaterial, unpublishMaterial, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, resetMaterialFilters, onMaterialIpChange, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments });
