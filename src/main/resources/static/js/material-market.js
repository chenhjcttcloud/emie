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
const MATERIAL_CATEGORY_LABELS = { id: 'ID', visual: '视觉', graphic: '平面' };
function materialCategory(m) { return MATERIAL_CATEGORY_LABELS[m.category] || '视觉'; }
function materialProjectLinks(m, compact = false) {
  const records = Array.isArray(m.adoptions) && m.adoptions.length ? m.adoptions : (m.projectId ? [{ projectId: m.projectId, projectCode: m.projectCode, adoptionType: m.adoptionType }] : []);
  if (!records.length) return '<span class="material-project-empty">暂无成立项目</span>';
  return `<span class="material-project-links">${records.map((record, index) => { const code = record.projectCode || `#${record.projectId}`; const type = record.adoptionType === 'design' ? '设计采纳' : '直接采纳'; const label = compact ? `${type} ${code}` : `${type}成立项目 ${code}`; return `<a href="javascript:void(0)" class="material-project-link" data-emie-action="click:market-open-project" data-project-id="${Number(record.projectId)}" title="点击查看成立项目">${escHtml(label)}</a>`; }).join(compact ? ' · ' : '<br>')}</span>`;
}
function adoptionUsed(m, type) { return (m.adoptions || []).some(record => record.adoptionType === type) || (!m.adoptions?.length && m.projectId && (m.adoptionType || 'direct') === type); }
function canPick(m) { return m.status !== 'withdrawn' && ['sales','planner','admin'].includes(EMIE.state.currentRole) && (!adoptionUsed(m, 'direct') || !adoptionUsed(m, 'design')); }
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
  m.category = m.category || 'visual';
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
  const adoptionCount = Array.isArray(m.adoptions) ? m.adoptions.length : (m.projectId ? 1 : 0);
  const chosen = adoptionCount > 0;
  const preview = authenticatedFileUrl(m.coverUrl || m.previewUrl || m.thumbnailUrl || m.referenceImages[0]?.downloadUrl || m.referenceImages[0]?.url);
  return `<article class="material-card ${chosen ? 'material-card-selected' : ''}" tabindex="0" data-emie-action="click:market-detail" data-material-id="${m.id}" aria-label="查看素材 ${escHtml(m.title || '')}">
    <div class="material-cover">${preview ? `<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(preview)}" alt="${escHtml(m.title || '素材预览')}" loading="lazy">` : '<span aria-hidden="true">🎨</span>'}<span class="material-selected-badge ${chosen ? 'is-claimed' : 'is-unclaimed'}">${chosen ? `已采纳 ${adoptionCount} 次` : '待采纳'}</span></div>
    <div class="material-card-body"><h3>${escHtml(m.title || '未命名素材')}</h3><div class="material-meta-row"><span class="material-category-badge">${escHtml(materialCategory(m))}</span><span class="material-meta">${escHtml(materialIp(m))}</span></div><p>${escHtml(m.description || '暂无产品说明')}</p><div class="material-card-foot"><span>作者：${escHtml(m.authorName || m.designerName || '-')}</span><span>${chosen ? `已成立 ${adoptionCount} 个项目` : '等待采纳'}</span></div><div class="material-card-actions">${isOwnMaterial(m) ? `<span class="material-like-own" title="不能给自己的作品点赞">♥ ${Number(m.likeCount || 0)}</span>` : `<button type="button" class="material-like-btn ${m.likedByCurrentUser ? 'is-liked' : ''}" data-emie-action="click:market-like" data-material-id="${m.id}" aria-label="${m.likedByCurrentUser ? '取消点赞' : '点赞'}">♥ <span>${Number(m.likeCount || 0)}</span></button>`}${materialProjectLinks(m, true)}</div></div>
  </article>`;
}

async function openMaterialDetail(id) {
  const m = normalizeMaterial(await apiGet(`/materials/${id}`));
  const adoptionCount = Array.isArray(m.adoptions) ? m.adoptions.length : (m.projectId ? 1 : 0);
  const chosen = adoptionCount > 0;
  const files = Array.isArray(m.files) ? m.files : (Array.isArray(m.attachments) ? m.attachments : []);
  const referenceImages = Array.isArray(m.referenceImages) ? m.referenceImages : [];
  const ppt = m.planFile || m.planningPpt;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialDetailModal';
  overlay.innerHTML = `<div class="modal modal-lg"><div class="modal-header"><div class="modal-title">${escHtml(m.title || '素材详情')}</div><button class="modal-close" data-emie-action="click:market-close-detail">✕</button></div><div class="modal-body">
    ${chosen ? `<div class="material-lock-note">✓ 该素材已被采纳 ${adoptionCount} 次，仍可继续采纳、查看资料和点赞。</div>` : ''}
    <div class="material-detail-summary"><div><div class="detail-label">分类</div><div class="detail-value">${escHtml(materialCategory(m))}</div></div><div><div class="detail-label">IP</div><div class="detail-value">${escHtml(materialIp(m))}</div></div><div><div class="detail-label">设计师</div><div class="detail-value">${escHtml(m.authorName || m.designerName || '暂无')}</div></div><div><div class="detail-label">采纳情况</div><div class="detail-value">${chosen ? `已采纳 ${adoptionCount} 次` : '待采纳'}</div></div><div><div class="detail-label">全部成立项目</div><div class="detail-value">${materialProjectLinks(m)}</div></div></div>
    <section class="material-detail-section"><h3>产品说明</h3><div class="material-detail-section-content material-description">${m.description ? escHtml(m.description) : '<span class="material-empty">暂无</span>'}</div></section>
    <section class="material-detail-section"><h3>🖼️ 参考图片</h3><div class="material-detail-section-content">${referenceImages.length ? `<div class="material-reference-images">${referenceImages.map(image => { const url = authenticatedFileUrl(image.downloadUrl || image.url || '#'); return `<a href="${escHtml(url)}" target="_blank" rel="noopener"><img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(url)}" alt="${escHtml(fileLabel(image))}"></a>`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    <section class="material-detail-section"><h3>素材文件</h3><div class="material-detail-section-content">${files.length ? `<div class="material-file-list">${files.map(f => { const url = authenticatedFileUrl(f.downloadUrl || f.url || '#'); return `<a class="material-file" href="${escHtml(url)}" target="_blank" rel="noopener">📎 ${escHtml(fileLabel(f))}<small>${f.size ? fmtSize(f.size) : ''}</small></a>`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    ${ppt ? `<section class="material-detail-section"><h3>策划案 PPT</h3><div class="material-detail-section-content"><a class="material-file" href="${escHtml(authenticatedFileUrl(ppt.downloadUrl || ppt.url || '#'))}" target="_blank" rel="noopener">📊 ${escHtml(fileLabel(ppt))}</a></div></section>` : ''}
    </div><div class="modal-footer">${canPick(m) ? `<button class="btn btn-outline" data-emie-action="click:market-adopt" data-adoption-type="design" data-material-id="${m.id}" ${adoptionUsed(m, 'design') ? 'disabled' : ''}>${adoptionUsed(m, 'design') ? '设计采纳 · 已完成' : '设计采纳'}</button><button class="btn btn-primary" data-emie-action="click:market-adopt" data-adoption-type="direct" data-material-id="${m.id}" ${adoptionUsed(m, 'direct') ? 'disabled' : ''}>${adoptionUsed(m, 'direct') ? '直接采纳 · 已完成' : '直接采纳'}</button>` : ''}${isOwnMaterial(m) && !chosen ? `<span class="material-owner-actions"><button class="btn btn-outline" data-emie-action="click:market-edit" data-material-id="${m.id}">编辑</button><button class="btn btn-danger" data-emie-action="click:market-delete" data-material-id="${m.id}">删除</button><button class="btn btn-warning" data-emie-action="click:market-unpublish" data-material-id="${m.id}">下架</button></span>` : ''}<button class="btn btn-outline" data-emie-action="click:market-close-detail">关闭</button></div></div>`;
  document.body.appendChild(overlay);
}
function closeMaterialDetail() { document.getElementById('materialDetailModal')?.remove(); }
async function deleteMaterial(id) { if (!await EMIE.actions.showSystemConfirm('确认删除这件作品吗？删除后不可恢复。')) return; try { await apiDelete(`/materials/${id}`); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('删除失败：' + e.message); } }
async function unpublishMaterial(id) { if (!await EMIE.actions.showSystemConfirm('确认下架这件作品吗？下架后其他人将无法采纳。')) return; try { await apiPost(`/materials/${id}/withdraw`); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('下架失败：' + e.message); } }
async function adoptMaterial(id, adoptionType) { try { const label = adoptionType === 'design' ? '设计采纳' : '直接采纳'; if (!await EMIE.actions.showSystemConfirm(`确认${label}并成立项目吗？采纳后不可更改。`)) return; await apiPost(`/materials/${id}/adopt`, { adoptionType }); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { EMIE.actions.showSystemAlert('采纳失败：' + e.message); } }
async function toggleMaterialLike(id, sourceButton = null) { try { const result = await apiPost(`/materials/${id}/like`); const buttons = document.querySelectorAll(`.material-like-btn[data-material-id="${id}"]`); buttons.forEach(button => { button.classList.toggle('is-liked', Boolean(result.liked)); button.setAttribute('aria-label', result.liked ? '取消点赞' : '点赞'); const count = button.querySelector('span'); if (count) count.textContent = Number(result.likeCount || 0); else button.innerHTML = `♥ <span>${Number(result.likeCount || 0)}</span>`; button.classList.remove('like-pop'); void button.offsetWidth; button.classList.add('like-pop'); }); const burst = document.createElement('span'); burst.className = 'material-like-burst'; burst.textContent = result.liked ? '♥' : '♡'; (sourceButton || buttons[0])?.appendChild(burst); setTimeout(() => burst.remove(), 700); } catch (e) { EMIE.actions.showSystemAlert('点赞失败：' + e.message); } }

async function openMaterialEdit(id) {
  try { const m = normalizeMaterial(await apiGet(`/materials/${id}`)); if (!isOwnMaterial(m) || m.selected || m.projectId) return EMIE.actions.showSystemAlert('已采纳作品不能修改'); renderMaterialUploadModal(m); } catch (e) { EMIE.actions.showSystemAlert('加载作品失败：' + e.message); }
}
function renderMaterialUploadModal(existing = null) {
  const ips = EMIE.state.ipOptions || [];
  EMIE.projectState.materialRefImages = [];
  EMIE.projectState.materialAttachments = [];
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialUploadModal';
  overlay.innerHTML = `<div class="modal modal-lg"><div class="modal-header"><div class="modal-title">${existing ? '编辑作品' : '发布新素材'}</div><button class="modal-close" data-emie-action="click:market-close-upload">✕</button></div><form id="materialUploadForm" class="modal-body" data-material-id="${existing?.id || ''}">
    <div class="form-group"><label class="form-label">标题 <span class="required-mark">*</span></label><input class="form-input" name="title" required maxlength="120"></div>
    <div class="form-group"><label class="form-label">作品分类 <span class="required-mark">*</span></label><div class="material-category-options"><label><input type="radio" name="category" value="id" required><span>ID</span></label><label><input type="radio" name="category" value="visual" required><span>视觉</span></label><label><input type="radio" name="category" value="graphic" required><span>平面</span></label></div></div>
    <div class="form-group"><label class="form-label">IP <span class="required-mark">*</span></label><select class="form-select" id="materialIpName" name="ipName" required data-emie-action="change:market-ip-change"><option value="">请选择 IP</option>${ips.map(i => `<option value="${escHtml(i.name || i.value || i)}">${escHtml(i.name || i.label || i.value || i)}</option>`).join('')}</select></div>
    <div class="form-group" id="materialIpSubGroup" style="display:none"><label class="form-label">二级IP</label><div class="chip-group" id="materialIpSubChips"></div><input type="hidden" name="ipSubOptions" id="materialIpSubOptions" value="[]"><div class="form-hint" id="materialIpSubHint"></div></div>
    <div class="form-group"><label class="form-label">产品说明 <span class="required-mark">*</span></label><textarea class="form-textarea" name="description" required rows="5" placeholder="说明产品创意、适用场景和设计亮点"></textarea></div>
    <div class="form-group material-upload-group"><label class="form-label">🖼️ 参考图片 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-ref-input"><div>📁 拖拽图片到此处，或点击选择图片</div><input class="form-input" type="file" id="materialRefImageInput" multiple accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-action="change:market-ref-images"></div><div class="file-list" id="materialRefImageList"></div></div>
    <div class="form-group material-upload-group"><label class="form-label">📎 附件</label><div class="upload-area" data-emie-action="click:market-attachment-input"><div>📁 拖拽文件到此处，或点击选择文件</div><input class="form-input" type="file" id="materialAttachmentInput" multiple accept="${escHtml(EMIE.fileAccept.attachment)}" style="display:none" data-emie-action="change:market-attachments"></div><div class="file-list" id="materialAttachmentList"></div></div>
    <div id="materialUploadError" class="form-error" hidden></div>
  </form><div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:market-close-upload">取消</button><button class="btn btn-primary" data-emie-action="click:market-submit-upload">立即发布</button></div></div>`;
  document.body.appendChild(overlay); EMIE.installUploadHints?.(overlay);
  const form = document.getElementById('materialUploadForm');
  const selectedCategory = existing?.category || 'visual';
  const categoryInput = form.querySelector(`input[name="category"][value="${selectedCategory}"]`);
  if (categoryInput) categoryInput.checked = true;
  if (existing) { form.elements.title.value = existing.title || ''; form.elements.ipName.value = existing.ipName || ''; form.elements.description.value = existing.description || ''; EMIE.projectState.materialRefImages = Array.isArray(existing.referenceImages) ? existing.referenceImages.slice() : parseMaterialJson(existing.referenceImagesJson, []); EMIE.projectState.materialAttachments = Array.isArray(existing.files) ? existing.files.slice() : parseMaterialJson(existing.materialFilesJson, []); renderFileList(EMIE.projectState.materialRefImages, '参考图片'); renderFileList(EMIE.projectState.materialAttachments, '附件'); onMaterialIpChange(form.elements.ipName); const sub = parseMaterialJson(existing.ipSubOptionsJson || existing.ipSubOptions, []); document.getElementById('materialIpSubOptions').value = JSON.stringify(sub); document.querySelectorAll('#materialIpSubChips .chip').forEach(chip => { if (sub.includes(chip.dataset.value)) chip.classList.add('selected'); }); }
}
function closeMaterialUpload() { document.getElementById('materialUploadModal')?.remove(); }
function handleMaterialRefImages(input) { handleFileUpload(input, EMIE.projectState.materialRefImages, 6, '参考图片', true); }
function handleMaterialAttachments(input) { handleFileUpload(input, EMIE.projectState.materialAttachments, 5, '附件', false); }
function onMaterialIpChange(select) { const ip = (EMIE.state.ipOptions || []).find(item => item.name === select.value); const options = parseMaterialJson(ip?.subOptionsJson, []); const group = document.getElementById('materialIpSubGroup'); const chips = document.getElementById('materialIpSubChips'); const input = document.getElementById('materialIpSubOptions'); const hint = document.getElementById('materialIpSubHint'); if (!group || !chips || !input || !hint) return; input.value = '[]'; chips.innerHTML = ''; if (!options.length) { group.style.display = 'none'; return; } group.style.display = ''; group.dataset.selectionMode = ip.subOptionSelectionMode === 'single' ? 'single' : 'multiple'; hint.textContent = group.dataset.selectionMode === 'single' ? '请选择一个二级 IP' : '可多选'; chips.innerHTML = options.map(v => `<span class="chip" data-value="${escHtml(v)}" data-emie-action="click:market-ip-sub-toggle">${escHtml(v)}</span>`).join(''); }
function toggleMaterialIpSubOption(el) { const group = document.getElementById('materialIpSubGroup'); if (group?.dataset.selectionMode === 'single') document.querySelectorAll('#materialIpSubChips .chip').forEach(x => x.classList.remove('selected')); el.classList.toggle('selected'); document.getElementById('materialIpSubOptions').value = JSON.stringify([...document.querySelectorAll('#materialIpSubChips .chip.selected')].map(x => x.dataset.value)); }
async function submitMaterialUpload() { const form = document.getElementById('materialUploadForm'); if (!form.reportValidity()) return; if (EMIE.projectState.uploadingCount > 0) return EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); if (!form.dataset.materialId && !EMIE.projectState.materialRefImages.length) return EMIE.actions.showSystemAlert('请至少上传一张参考图片'); const btn = document.querySelector('#materialUploadModal .btn-primary'); btn.disabled = true; try { const fd = new FormData(form); const body = { title: fd.get('title'), category: fd.get('category'), ipName: fd.get('ipName'), ipSubOptions: fd.get('ipSubOptions'), description: fd.get('description'), filesJson: JSON.stringify(EMIE.projectState.materialAttachments), referenceImagesJson: JSON.stringify(EMIE.projectState.materialRefImages) }; if (form.dataset.materialId) await apiPut(`/materials/${form.dataset.materialId}`, body); else await apiPost('/materials', body); closeMaterialUpload(); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { const error = document.getElementById('materialUploadError'); error.hidden = false; error.textContent = e.message; btn.disabled = false; } }

function materialStatus(m) { if (m.status === 'withdrawn') return 'withdrawn'; return (Array.isArray(m.adoptions) && m.adoptions.length) || m.selected || m.projectId || m.status === 'selected' ? 'selected' : 'available'; }

function renderMaterialGrid(items) {
  const grid = document.getElementById('materialGrid');
  if (!grid) return;
  grid.innerHTML = items.length
    ? items.map(renderMaterialCard).join('')
    : '<div class="market-empty"><div class="market-empty-icon">✦</div><h3>暂无匹配素材</h3><p>试试调整筛选条件或搜索关键词</p></div>';
}

function setMaterialStatusFilter(status) {
  const input = document.getElementById('materialAdoptionFilter');
  if (input) input.value = status;
  document.querySelectorAll('[data-market-adoption-filter]').forEach(button => button.classList.toggle('is-active', button.dataset.marketAdoptionFilter === status));
  filterMaterials();
}

function setMaterialCategoryFilter(category) {
  const input = document.getElementById('materialCategoryFilter');
  if (input) input.value = category;
  document.querySelectorAll('[data-market-category-filter]').forEach(button => button.classList.toggle('is-active', button.dataset.marketCategoryFilter === category));
  filterMaterials();
}

function resetMaterialFilters() {
  const search = document.getElementById('materialSearch');
  const status = document.getElementById('materialAdoptionFilter');
  const category = document.getElementById('materialCategoryFilter');
  const ip = document.getElementById('materialIpFilter');
  if (search) search.value = '';
  if (status) status.value = 'all';
  if (category) category.value = 'all';
  if (ip) ip.value = '';
  document.querySelectorAll('[data-market-adoption-filter],[data-market-category-filter]').forEach(button => button.classList.toggle('is-active', button.dataset.marketAdoptionFilter === 'all' || button.dataset.marketCategoryFilter === 'all'));
  filterMaterials();
}

async function renderMaterialMarket(main) {
  const role = EMIE.state.currentRole;
  main.innerHTML = `<section class="market-hero"><div class="market-hero-copy"><span class="market-eyebrow">EMIE CREATIVE MARKET</span><h1>让好创意，被看见</h1><p>发现团队里的灵感宝藏，把优秀设计快速变成产品。</p><div class="market-hero-actions">${role === 'designer' ? '<button class="btn market-primary-btn" data-emie-action="click:market-upload-modal">＋ 发布我的创意</button>' : '<span class="market-hint">浏览灵感，寻找下一个爆款</span>'}</div></div><div class="market-hero-art"><span>✦</span><span>◇</span><span>✧</span></div></section><div class="market-filter-tabs"><input type="hidden" id="materialAdoptionFilter" value="all"><input type="hidden" id="materialCategoryFilter" value="all"><div class="market-filter-tab-row"><span>采纳方式</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-adoption-filter" data-market-adoption-filter="all">全部</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="available">待采纳</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="design">设计采纳</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="direct">直接采纳</button></div></div><div class="market-filter-tab-row"><span>作品分类</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-category-filter" data-market-category-filter="all">全部分类</button><button data-emie-action="click:market-category-filter" data-market-category-filter="id">ID</button><button data-emie-action="click:market-category-filter" data-market-category-filter="visual">视觉</button><button data-emie-action="click:market-category-filter" data-market-category-filter="graphic">平面</button></div></div></div><div class="market-toolbar-row"><div class="market-filter-card"><div class="market-stats"><button type="button" data-emie-action="click:market-status-all"><strong id="marketTotal">—</strong><span>全部素材</span></button><button type="button" data-emie-action="click:market-status-available"><strong id="marketAvailable">—</strong><span>待采纳创意</span></button></div><div class="market-filter-fields"><label><span>IP</span><select id="materialIpFilter" class="form-select" data-emie-action="change:market-filter"><option value="">全部 IP</option></select></label><button class="btn btn-outline btn-sm market-filter-reset" data-emie-action="click:market-reset-filters">重置</button></div></div><div class="material-toolbar"><div class="market-search-wrap"><span>⌕</span><input id="materialSearch" class="form-input" placeholder="搜索标题、分类、IP、作者或产品说明" data-emie-action="input:market-filter"><button class="btn market-search-btn" data-emie-action="click:market-filter">搜索</button></div></div></div><div id="materialGrid" class="material-grid"><div class="market-empty"><div class="market-empty-icon">✦</div><h3>正在寻找灵感…</h3><p>素材广场马上为你呈现最新创意</p></div></div>`;
  document.querySelector('.market-filter-card')?.insertAdjacentHTML('afterbegin', '<div class="market-filter-intro"><span>⌘</span><div><strong>筛选素材</strong><small>按 IP 快速定位</small></div></div>');
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
  const adoption = document.getElementById('materialAdoptionFilter')?.value || 'all';
  const category = document.getElementById('materialCategoryFilter')?.value || 'all';
  const ip = document.getElementById('materialIpFilter')?.value || '';
  const items = (EMIE.materialState.items || []).filter(m => {
    const matchesKeyword = `${m.title} ${m.description} ${materialCategory(m)} ${materialIp(m)} ${m.authorName}`.toLowerCase().includes(q);
    const matchesAdoption = adoption === 'all' || (adoption === 'available' ? materialStatus(m) === 'available' : adoptionUsed(m, adoption));
    return matchesKeyword && matchesAdoption && (category === 'all' || m.category === category) && (!ip || m.ipName === ip);
  });
  renderMaterialGrid(items);
}

EMIE.registerActions({ renderMaterialMarket, openMaterialDetail, closeMaterialDetail, adoptMaterial, toggleMaterialLike, renderMaterialUploadModal, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, setMaterialCategoryFilter, resetMaterialFilters, onMaterialIpChange, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments });
const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('market-open-project', (_event, el) => EMIE.actions.openProjectDetail(Number(el.dataset.projectId)));
  registerEventAction('market-detail', (_event, el) => openMaterialDetail(Number(el.dataset.materialId)));
  registerEventAction('market-close-detail', () => closeMaterialDetail());
  registerEventAction('market-adopt', (_event, el) => adoptMaterial(Number(el.dataset.materialId), el.dataset.adoptionType));
  registerEventAction('market-like', (event, el) => { event.stopPropagation(); toggleMaterialLike(Number(el.dataset.materialId), el); });
  registerEventAction('market-edit', (_event, el) => openMaterialEdit(Number(el.dataset.materialId)));
  registerEventAction('market-delete', (_event, el) => deleteMaterial(Number(el.dataset.materialId)));
  registerEventAction('market-unpublish', (_event, el) => unpublishMaterial(Number(el.dataset.materialId)));
  registerEventAction('market-close-upload', () => closeMaterialUpload());
  registerEventAction('market-submit-upload', () => submitMaterialUpload());
  registerEventAction('market-ip-change', (_event, el) => onMaterialIpChange(el));
  registerEventAction('market-ref-input', () => document.getElementById('materialRefImageInput')?.click());
  registerEventAction('market-attachment-input', () => document.getElementById('materialAttachmentInput')?.click());
  registerEventAction('market-ref-images', (_event, el) => handleMaterialRefImages(el));
  registerEventAction('market-attachments', (_event, el) => handleMaterialAttachments(el));
  registerEventAction('market-ip-sub-toggle', (_event, el) => toggleMaterialIpSubOption(el));
  registerEventAction('market-upload-modal', () => renderMaterialUploadModal());
  registerEventAction('market-status-all', () => setMaterialStatusFilter('all'));
  registerEventAction('market-status-available', () => setMaterialStatusFilter('available'));
  registerEventAction('market-adoption-filter', (_event, el) => setMaterialStatusFilter(el.dataset.marketAdoptionFilter));
  registerEventAction('market-category-filter', (_event, el) => setMaterialCategoryFilter(el.dataset.marketCategoryFilter));
  registerEventAction('market-filter', () => filterMaterials());
  registerEventAction('market-reset-filters', () => resetMaterialFilters());
}
EMIE.registerModule('materialMarket', { renderMaterialMarket, openMaterialDetail, closeMaterialDetail, adoptMaterial, toggleMaterialLike, renderMaterialUploadModal, openMaterialEdit, deleteMaterial, unpublishMaterial, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, setMaterialCategoryFilter, resetMaterialFilters, onMaterialIpChange, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments });
