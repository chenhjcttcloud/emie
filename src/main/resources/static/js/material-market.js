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
let marketBannerTimer;
let marketPostType = 'all';
let packagingFlatImage = [];
let packagingFoldedImage = [];

const FREE_CATEGORY_LABELS = { product_concept: '产品概念', packaging_concept: '产品包装概念', brainstorming: '集思广益' };

function fileLabel(file) { return file?.originalName || file?.name || file?.fileName || file?.storedName || '文件'; }
function fileJson(file) { return JSON.stringify(file || {}); }
function authenticatedFileUrl(url) { return EMIE.actions.authenticatedFileUrl ? EMIE.actions.authenticatedFileUrl(url) : url; }
function materialIp(m) { return [m.ipName, ...(Array.isArray(m.ipSubOptions) ? m.ipSubOptions : [])].filter(Boolean).join(' / ') || '暂无'; }
const MATERIAL_CATEGORY_LABELS = { id: 'ID', visual: '视觉', graphic: '平面' };
const MATERIAL_DESC_PLACEHOLDER = {
  '否': '请描述产品的设计初衷、灵感来源，以及从想法到方案的构思过程。',
  '是': '请说明 AI 主要参与了哪部分设计，并附上 AI 生图提示词（prompt）。'
};
function currentMaterialAi() { return document.querySelector('#materialUploadForm input[name="aiAssisted"]:checked')?.value || '否'; }
function onMaterialAiToggle() { const ta = document.getElementById('materialDescription'); if (ta) ta.placeholder = MATERIAL_DESC_PLACEHOLDER[currentMaterialAi()] || MATERIAL_DESC_PLACEHOLDER['否']; }
function materialCategory(m) { return MATERIAL_CATEGORY_LABELS[m.category] || '视觉'; }
function materialProjectLinks(m, compact = false) {
  const records = Array.isArray(m.adoptions) && m.adoptions.length ? m.adoptions : (m.projectId ? [{ projectId: m.projectId, projectCode: m.projectCode, adoptionType: m.adoptionType }] : []);
  if (!records.length) return '<span class="material-project-empty">暂无成立项目</span>';
  return `<span class="material-project-links">${records.map((record, index) => { const code = record.projectCode || `#${record.projectId}`; const type = record.adoptionType === 'design' ? '设计采纳' : '直接采纳'; const label = compact ? `${type} ${code}` : `${type}成立项目 ${code}`; return `<a href="javascript:void(0)" class="material-project-link" data-emie-action="click:market-open-project" data-project-id="${Number(record.projectId)}" title="点击查看成立项目">${escHtml(label)}</a>`; }).join(compact ? ' · ' : '<br>')}</span>`;
}
function adoptionUsed(m, type) { return (m.adoptions || []).some(record => record.adoptionType === type) || (!m.adoptions?.length && m.projectId && (m.adoptionType || 'direct') === type); }
function canPick(m) { return m.status !== 'withdrawn' && ['sales','planner','admin'].includes(EMIE.state.currentRole); }
function isOwnMaterial(m) { return String(m.creatorId || m.authorId || '') === String(EMIE.state.currentUserId || EMIE.state.authUser?.userId || ''); }
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
  const free = m.postType === 'free';
  const preview = authenticatedFileUrl(m.coverUrl || m.previewUrl || m.thumbnailUrl || m.referenceImages[0]?.downloadUrl || m.referenceImages[0]?.url);
  const adoptionFlags = free ? '' : `<div class="material-adoption-flags"><span class="${adoptionUsed(m, 'design') ? 'is-active' : ''}">设计采纳</span><span class="${adoptionUsed(m, 'direct') ? 'is-active' : ''}">直接采纳</span></div>`;
  return `<article class="material-card" tabindex="0" data-emie-action="click:market-detail" data-material-id="${m.id}" aria-label="查看素材 ${escHtml(m.title || '')}">
    <div class="material-cover">${preview ? `<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(preview)}" alt="${escHtml(m.title || '素材预览')}" loading="lazy">` : '<span aria-hidden="true">🎨</span>'}<span class="material-selected-badge ${free ? 'is-free' : 'is-idea'}">${free ? '自由单' : '创意单'}</span></div>
    <div class="material-card-body"><h3>${escHtml(m.title || '未命名素材')}</h3><div class="material-meta-row"><span class="material-category-badge">${escHtml(free ? FREE_CATEGORY_LABELS[m.freeCategory] || '自由单' : materialCategory(m))}</span>${free ? '' : `<span class="material-meta">${escHtml(materialIp(m))}</span>`}</div><p>${escHtml(m.description || '暂无设计构思')}</p>${adoptionFlags}<div class="material-card-foot"><span>作者：${escHtml(m.authorName || m.designerName || '-')}</span></div><div class="material-card-actions">${isOwnMaterial(m) ? `<span class="material-like-own" title="不能给自己的作品点赞">♥ ${Number(m.likeCount || 0)}</span>` : `<button type="button" class="material-like-btn ${m.likedByCurrentUser ? 'is-liked' : ''}" data-emie-action="click:market-like" data-material-id="${m.id}" aria-label="${m.likedByCurrentUser ? '取消点赞' : '点赞'}">♥ <span>${Number(m.likeCount || 0)}</span></button>`}${free ? `<span class="material-like-hint">点赞表示认可</span>` : materialProjectLinks(m, true)}</div></div>
  </article>`;
}

async function openMaterialDetail(id) {
  const m = normalizeMaterial(await apiGet(`/materials/${id}`));
  if (m.postType === 'free') return openFreeMaterialDetail(m);
  const adoptionCount = Array.isArray(m.adoptions) ? m.adoptions.length : (m.projectId ? 1 : 0);
  const chosen = adoptionCount > 0;
  const files = Array.isArray(m.files) ? m.files : (Array.isArray(m.attachments) ? m.attachments : []);
  const referenceImages = Array.isArray(m.referenceImages) ? m.referenceImages : [];
  const ppt = m.planFile || m.planningPpt;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialDetailModal';
  overlay.innerHTML = `<div class="modal modal-lg market-detail-modal is-idea"><div class="modal-header"><div class="material-detail-heading"><span class="material-detail-type">创意单</span><div class="modal-title">${escHtml(m.title || '素材详情')}</div><p>设计师作品 · 可发起采纳</p></div><button class="modal-close" data-emie-action="click:market-close-detail">✕</button></div><div class="modal-body">
    ${chosen ? `<div class="material-lock-note">✓ 该素材已被采纳 ${adoptionCount} 次，仍可继续采纳、查看资料和点赞。</div>` : ''}
    <div class="material-detail-summary"><div><div class="detail-label">分类</div><div class="detail-value">${escHtml(materialCategory(m))}</div></div><div><div class="detail-label">IP</div><div class="detail-value">${escHtml(materialIp(m))}</div></div><div><div class="detail-label">设计师</div><div class="detail-value">${escHtml(m.authorName || m.designerName || '暂无')}</div></div><div><div class="detail-label">采纳情况</div><div class="detail-value">${chosen ? `已采纳 ${adoptionCount} 次` : '待采纳'}</div></div><div><div class="detail-label">全部成立项目</div><div class="detail-value">${materialProjectLinks(m)}</div></div></div>
    <section class="material-detail-section"><h3>设计构思</h3><div class="material-detail-section-content"><div class="material-ai-note">AI 参与制图：${m.aiAssisted ? '是' : '否'}</div><div class="material-description">${m.description ? escHtml(m.description) : '<span class="material-empty">暂无</span>'}</div></div></section>
    <section class="material-detail-section"><h3>🖼️ 参考图片</h3><div class="material-detail-section-content">${referenceImages.length ? `<div class="material-reference-images image-preview">${referenceImages.map(image => { const url = authenticatedFileUrl(image.downloadUrl || image.url || '#'); return `<img class="img-clickable" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(url)}" data-full-src="${escHtml(url)}" alt="${escHtml(fileLabel(image))}" title="点击放大">`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    <section class="material-detail-section"><h3>素材文件</h3><div class="material-detail-section-content">${files.length ? `<div class="material-file-list">${files.map(f => { const url = authenticatedFileUrl(f.downloadUrl || f.url || '#'); return `<a class="material-file" href="${escHtml(url)}" target="_blank" rel="noopener">📎 ${escHtml(fileLabel(f))}<small>${f.size ? fmtSize(f.size) : ''}</small></a>`; }).join('')}</div>` : '<span class="material-empty">暂无</span>'}</div></section>
    ${ppt ? `<section class="material-detail-section"><h3>策划案 PPT</h3><div class="material-detail-section-content"><a class="material-file" href="${escHtml(authenticatedFileUrl(ppt.downloadUrl || ppt.url || '#'))}" target="_blank" rel="noopener">📊 ${escHtml(fileLabel(ppt))}</a></div></section>` : ''}
    </div><div class="modal-footer">${canPick(m) && ['planner','admin'].includes(EMIE.state.currentRole) ? `<button class="btn btn-outline" data-emie-action="click:market-adopt" data-adoption-type="design" data-material-id="${m.id}" ${adoptionUsed(m, 'design') ? 'disabled' : ''}>${adoptionUsed(m, 'design') ? '设计采纳 · 已完成' : '设计采纳'}</button>` : ''}${canPick(m) && ['sales','admin'].includes(EMIE.state.currentRole) ? `<button class="btn btn-primary" data-emie-action="click:market-adopt" data-adoption-type="direct" data-material-id="${m.id}" ${adoptionUsed(m, 'direct') ? 'disabled' : ''}>${adoptionUsed(m, 'direct') ? '直接采纳 · 已完成' : '直接采纳'}</button>` : ''}${isOwnMaterial(m) && !chosen ? `<span class="material-owner-actions"><button class="btn btn-outline" data-emie-action="click:market-edit" data-material-id="${m.id}">编辑</button><button class="btn btn-danger" data-emie-action="click:market-delete" data-material-id="${m.id}">删除</button><button class="btn btn-warning" data-emie-action="click:market-unpublish" data-material-id="${m.id}">下架</button></span>` : ''}<button class="btn btn-outline" data-emie-action="click:market-close-detail">关闭</button></div></div>`;
  document.body.appendChild(overlay);
}
function openFreeMaterialDetail(m) {
  const content = parseMaterialJson(m.freeContentJson, {});
  const refs = m.referenceImages || [];
  const files = m.files || [];
  const sections = [
    ['灵感来源', content.inspirationSource],
    ['预估成本', content.estimatedCost],
    ['配置和产品特性', content.configurationFeatures],
    ['类型素材', content.materialType]
  ].filter(([, value]) => value);
  const packagingLabels = ['包装展开图', '折叠效果图'];
  const images = refs.map((file, i) => { const url = authenticatedFileUrl(file.downloadUrl || file.url || '#'); const label = m.freeCategory === 'packaging_concept' ? packagingLabels[i] || '包装图片' : '设计平面图 / 渲染图'; return `<div><img class="img-clickable" src="${escHtml(url)}" data-full-src="${escHtml(url)}" alt="${escHtml(label)}" title="点击放大" loading="lazy"><span>${escHtml(label)}</span></div>`; }).join('');
  const links = content.relatedLinks ? content.relatedLinks.split(/\s+/).map(url => `<a class="material-file" href="${escHtml(url)}" target="_blank" rel="noopener">🔗 ${escHtml(url)}</a>`).join('') : '';
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialDetailModal';
  overlay.innerHTML = `<div class="modal modal-lg market-detail-modal is-free"><div class="modal-header"><div class="material-detail-heading"><span class="material-detail-type">自由单</span><div class="modal-title">${escHtml(m.title)}</div><p>灵感分享 · 点赞表示认可</p></div><button class="modal-close" data-emie-action="click:market-close-detail">✕</button></div><div class="modal-body"><div class="material-detail-summary"><div><div class="detail-label">自由单类型</div><div class="detail-value">${escHtml(FREE_CATEGORY_LABELS[m.freeCategory] || '自由单')}</div></div><div><div class="detail-label">发布者</div><div class="detail-value">${escHtml(m.authorName || '-')}</div></div><div><div class="detail-label">点赞</div><div class="detail-value">${Number(m.likeCount || 0)} 人认可</div></div></div>${sections.map(([label, value]) => `<section class="material-detail-section"><h3>${escHtml(label)}</h3><div class="material-detail-section-content"><div class="material-description">${escHtml(value)}</div></div></section>`).join('')}${images ? `<section class="material-detail-section"><h3>${m.freeCategory === 'packaging_concept' ? '包装图示' : '设计平面图 / 渲染图'}</h3><div class="material-detail-section-content"><div class="material-reference-images image-preview">${images}</div></div></section>` : ''}${files.length ? `<section class="material-detail-section"><h3>分享素材</h3><div class="material-detail-section-content"><div class="material-file-list">${files.map(file => `<a class="material-file" href="${escHtml(authenticatedFileUrl(file.downloadUrl || file.url || '#'))}" target="_blank" rel="noopener">📎 ${escHtml(fileLabel(file))}</a>`).join('')}</div></div></section>` : ''}${links ? `<section class="material-detail-section"><h3>相关链接</h3><div class="material-detail-section-content"><div class="material-file-list">${links}</div></div></section>` : ''}</div><div class="modal-footer">${isOwnMaterial(m) ? `<button class="btn btn-danger" data-emie-action="click:market-delete" data-material-id="${m.id}">删除</button>` : `<button class="btn btn-outline material-like-btn ${m.likedByCurrentUser ? 'is-liked' : ''}" data-emie-action="click:market-like" data-material-id="${m.id}">${m.likedByCurrentUser ? '♥ 已点赞' : '♡ 点赞'} · ${Number(m.likeCount || 0)}</button>`}<button class="btn btn-outline" data-emie-action="click:market-close-detail">关闭</button></div></div>`;
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
    <div class="form-group"><label class="form-label">是否 AI 参与制图 <span class="required-mark">*</span></label><div class="material-category-options"><label><input type="radio" name="aiAssisted" value="否" required data-emie-action="change:market-ai-toggle"><span>否</span></label><label><input type="radio" name="aiAssisted" value="是" required data-emie-action="change:market-ai-toggle"><span>是</span></label></div></div>
    <div class="form-group"><label class="form-label">设计构思 <span class="required-mark">*</span></label><textarea class="form-textarea" id="materialDescription" name="description" required rows="5" placeholder="${MATERIAL_DESC_PLACEHOLDER['否']}"></textarea></div>
    <div class="form-group material-upload-group"><label class="form-label">🖼️ 参考图片 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-ref-input"><div>📁 拖拽图片到此处，或点击选择图片</div><input class="form-input" type="file" id="materialRefImageInput" multiple accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-action="change:market-ref-images"></div><div class="file-list" id="materialRefImageList"></div></div>
    <div class="form-group material-upload-group"><label class="form-label">📎 附件</label><div class="upload-area" data-emie-action="click:market-attachment-input"><div>📁 拖拽文件到此处，或点击选择文件</div><input class="form-input" type="file" id="materialAttachmentInput" multiple accept="${escHtml(EMIE.fileAccept.attachment)}" style="display:none" data-emie-action="change:market-attachments"></div><div class="file-list" id="materialAttachmentList"></div></div>
    <div id="materialUploadError" class="form-error" hidden></div>
  </form><div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:market-close-upload">取消</button><button class="btn btn-primary" data-emie-action="click:market-submit-upload">立即发布</button></div></div>`;
  document.body.appendChild(overlay); EMIE.installUploadHints?.(overlay);
  const form = document.getElementById('materialUploadForm');
  const selectedCategory = existing?.category || 'visual';
  const categoryInput = form.querySelector(`input[name="category"][value="${selectedCategory}"]`);
  if (categoryInput) categoryInput.checked = true;
  const aiInput = form.querySelector(`input[name="aiAssisted"][value="${existing?.aiAssisted ? '是' : '否'}"]`);
  if (aiInput) aiInput.checked = true;
  onMaterialAiToggle();
  if (existing) { form.elements.title.value = existing.title || ''; form.elements.ipName.value = existing.ipName || ''; form.elements.description.value = existing.description || ''; EMIE.projectState.materialRefImages = Array.isArray(existing.referenceImages) ? existing.referenceImages.slice() : parseMaterialJson(existing.referenceImagesJson, []); EMIE.projectState.materialAttachments = Array.isArray(existing.files) ? existing.files.slice() : parseMaterialJson(existing.materialFilesJson, []); renderFileList(EMIE.projectState.materialRefImages, '参考图片'); renderFileList(EMIE.projectState.materialAttachments, '附件'); onMaterialIpChange(form.elements.ipName); const sub = parseMaterialJson(existing.ipSubOptionsJson || existing.ipSubOptions, []); document.getElementById('materialIpSubOptions').value = JSON.stringify(sub); document.querySelectorAll('#materialIpSubChips .chip').forEach(chip => { if (sub.includes(chip.dataset.value)) chip.classList.add('selected'); }); }
}
function closeMaterialUpload() { document.getElementById('materialUploadModal')?.remove(); }
function handleMaterialRefImages(input) { handleFileUpload(input, EMIE.projectState.materialRefImages, 6, '参考图片', true); }
function handleFreeOrIdeaReferenceImages(input) {
  if (document.getElementById('materialUploadForm')?.dataset.postType === 'free'
      && document.querySelector('input[name="freeCategory"]:checked')?.value !== 'product_concept') {
    input.value = '';
    return;
  }
  handleMaterialRefImages(input);
}
function handleMaterialAttachments(input) { handleFileUpload(input, EMIE.projectState.materialAttachments, 5, '附件', false); }
function onMaterialIpChange(select) { const ip = (EMIE.state.ipOptions || []).find(item => item.name === select.value); const options = parseMaterialJson(ip?.subOptionsJson, []); const group = document.getElementById('materialIpSubGroup'); const chips = document.getElementById('materialIpSubChips'); const input = document.getElementById('materialIpSubOptions'); const hint = document.getElementById('materialIpSubHint'); if (!group || !chips || !input || !hint) return; input.value = '[]'; chips.innerHTML = ''; if (!options.length) { group.style.display = 'none'; return; } group.style.display = ''; group.dataset.selectionMode = ip.subOptionSelectionMode === 'single' ? 'single' : 'multiple'; hint.textContent = group.dataset.selectionMode === 'single' ? '请选择一个二级 IP' : '可多选'; chips.innerHTML = options.map(v => `<span class="chip" data-value="${escHtml(v)}" data-emie-action="click:market-ip-sub-toggle">${escHtml(v)}</span>`).join(''); }
function toggleMaterialIpSubOption(el) { const group = document.getElementById('materialIpSubGroup'); if (group?.dataset.selectionMode === 'single') document.querySelectorAll('#materialIpSubChips .chip').forEach(x => x.classList.remove('selected')); el.classList.toggle('selected'); document.getElementById('materialIpSubOptions').value = JSON.stringify([...document.querySelectorAll('#materialIpSubChips .chip.selected')].map(x => x.dataset.value)); }
async function submitMaterialUpload() { const form = document.getElementById('materialUploadForm'); if (!form.reportValidity()) return; if (EMIE.projectState.uploadingCount > 0) return EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); if (!form.dataset.materialId && !EMIE.projectState.materialRefImages.length) return EMIE.actions.showSystemAlert('请至少上传一张参考图片'); const btn = document.querySelector('#materialUploadModal .btn-primary'); btn.disabled = true; try { const fd = new FormData(form); const body = { title: fd.get('title'), category: fd.get('category'), ipName: fd.get('ipName'), ipSubOptions: fd.get('ipSubOptions'), aiAssisted: fd.get('aiAssisted'), description: fd.get('description'), filesJson: JSON.stringify(EMIE.projectState.materialAttachments), referenceImagesJson: JSON.stringify(EMIE.projectState.materialRefImages) }; if (form.dataset.materialId) await apiPut(`/materials/${form.dataset.materialId}`, body); else await apiPost('/materials', body); closeMaterialUpload(); closeMaterialDetail(); await EMIE.actions.render(); } catch (e) { const error = document.getElementById('materialUploadError'); error.hidden = false; error.textContent = e.message; btn.disabled = false; } }

function renderFreeUploadModal() {
  EMIE.projectState.materialRefImages = [];
  EMIE.projectState.materialAttachments = [];
  packagingFlatImage.length = 0;
  packagingFoldedImage.length = 0;
  const overlay = document.createElement('div'); overlay.className = 'modal-overlay'; overlay.id = 'materialUploadModal';
  overlay.innerHTML = `<div class="modal modal-lg"><div class="modal-header"><div class="modal-title">发布自由单</div><button class="modal-close" data-emie-action="click:market-close-upload">✕</button></div><form id="materialUploadForm" class="modal-body" data-post-type="free"><div class="form-group"><label class="form-label">标题 <span class="required-mark">*</span></label><input class="form-input" name="title" required maxlength="120"></div><div class="form-group"><label class="form-label">自由单类型 <span class="required-mark">*</span></label><div class="material-category-options"><label><input type="radio" name="freeCategory" value="product_concept" checked data-emie-action="change:market-free-category"><span>产品概念</span></label><label><input type="radio" name="freeCategory" value="packaging_concept" data-emie-action="change:market-free-category"><span>产品包装概念</span></label><label><input type="radio" name="freeCategory" value="brainstorming" data-emie-action="change:market-free-category"><span>集思广益</span></label></div></div><div class="form-group"><label class="form-label">灵感来源 <span class="required-mark">*</span></label><textarea class="form-textarea" name="inspirationSource" required rows="4"></textarea></div><div class="form-group" data-free-fields="product_concept"><label class="form-label">配置和产品特性 <span class="required-mark">*</span></label><textarea class="form-textarea" name="configurationFeatures" required rows="4"></textarea></div><div class="form-group" data-free-fields="product_concept"><label class="form-label">预估成本（如有）</label><input class="form-input" name="estimatedCost" placeholder="选填"></div><div class="form-group" data-free-fields="brainstorming" hidden><label class="form-label">类型素材 <span class="required-mark">*</span></label><textarea class="form-textarea" name="materialType" rows="3"></textarea></div><div class="form-group"><label class="form-label">相关链接（如有）</label><textarea class="form-textarea" name="relatedLinks" rows="2" placeholder="多个链接请用空格或换行分隔"></textarea></div><div class="form-group material-upload-group" data-free-fields="product_concept"><label class="form-label">设计平面图 / 渲染图 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-ref-input"><div>上传至少一张设计平面图或渲染图</div><input class="form-input" type="file" id="materialRefImageInput" multiple accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-action="change:market-ref-images"></div><div class="file-list" id="materialRefImageList"></div></div><div class="form-group material-upload-group" data-free-fields="packaging_concept" hidden><label class="form-label">包装展开图 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-packaging-flat"><div>单独上传包装展开图</div><input class="form-input" type="file" id="packagingFlatInput" accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-action="change:market-packaging-flat-file"></div><div class="file-list" id="packagingFlatList"></div><label class="form-label">折叠效果图 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-packaging-folded"><div>单独上传折叠效果图</div><input class="form-input" type="file" id="packagingFoldedInput" accept="${escHtml(EMIE.fileAccept.reference)}" style="display:none" data-emie-action="change:market-packaging-folded-file"></div><div class="file-list" id="packagingFoldedList"></div></div><div class="form-group material-upload-group" data-free-fields="brainstorming" hidden><label class="form-label">素材附件 <span class="required-mark">*</span></label><div class="upload-area" data-emie-action="click:market-attachment-input"><div>上传至少一个素材文件</div><input class="form-input" type="file" id="materialAttachmentInput" multiple accept="${escHtml(EMIE.fileAccept.attachment)}" style="display:none" data-emie-action="change:market-attachments"></div><div class="file-list" id="materialAttachmentList"></div></div><div id="materialUploadError" class="form-error" hidden></div></form><div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:market-close-upload">取消</button><button class="btn btn-primary" data-emie-action="click:market-submit-upload">发布自由单</button></div></div>`;
  document.body.appendChild(overlay);
  EMIE.installUploadHints?.(overlay);
  onFreeCategoryChange();
}

function onFreeCategoryChange() {
  const form = document.getElementById('materialUploadForm');
  if (!form?.dataset.postType) return;
  const category = form.querySelector('input[name="freeCategory"]:checked')?.value;
  form.querySelectorAll('[data-free-fields]').forEach(group => {
    const visible = group.dataset.freeFields.split(' ').includes(category);
    group.hidden = !visible;
    group.querySelectorAll('[required]').forEach(input => input.required = visible);
  });
  const packageType = category === 'packaging_concept';
  form.elements.configurationFeatures.required = category === 'product_concept';
  form.elements.materialType.required = category === 'brainstorming';
}

function handlePackagingImage(input, target, listId, label) {
  if (!input.files?.length) return;
  handleFileUpload(input, target, 1, label, true);
}

async function submitFreeMaterial() {
  const form = document.getElementById('materialUploadForm');
  if (!form.reportValidity()) return;
  if (EMIE.projectState.uploadingCount > 0) return EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成');
  const fd = new FormData(form);
  const category = fd.get('freeCategory');
  const relatedLinks = String(fd.get('relatedLinks') || '').trim();
  if (relatedLinks && relatedLinks.split(/\s+/).some(link => {
    try { const url = new URL(link); return !['http:', 'https:'].includes(url.protocol) || !url.hostname; }
    catch (_) { return true; }
  })) {
    const error = document.getElementById('materialUploadError');
    error.hidden = false;
    error.textContent = '相关链接请填写完整的 http:// 或 https:// 地址；没有链接时可以留空。';
    return;
  }
  const error = document.getElementById('materialUploadError');
  error.hidden = true;
  error.textContent = '';
  if (category === 'product_concept' && !EMIE.projectState.materialRefImages.length) return EMIE.actions.showSystemAlert('请上传设计平面图或渲染图');
  if (category === 'packaging_concept' && (!packagingFlatImage.length || !packagingFoldedImage.length)) return EMIE.actions.showSystemAlert('请分别上传包装展开图和折叠效果图');
  if (category === 'brainstorming' && !EMIE.projectState.materialAttachments.length) return EMIE.actions.showSystemAlert('请上传集思广益类素材');
  const button = document.querySelector('#materialUploadModal .btn-primary'); button.disabled = true;
  try {
    const freeContent = Object.fromEntries(['inspirationSource', 'configurationFeatures', 'estimatedCost', 'materialType', 'relatedLinks'].map(key => [key, String(fd.get(key) || '').trim()]));
    await apiPost('/materials', { postType: 'free', freeCategory: category, title: fd.get('title'), freeContentJson: JSON.stringify(freeContent), referenceImagesJson: JSON.stringify(category === 'product_concept' ? EMIE.projectState.materialRefImages : []), packagingFlatImageJson: JSON.stringify(packagingFlatImage), packagingFoldedImageJson: JSON.stringify(packagingFoldedImage), filesJson: JSON.stringify(category === 'brainstorming' ? EMIE.projectState.materialAttachments : []) });
    closeMaterialUpload(); await EMIE.actions.render();
  } catch (e) { const error = document.getElementById('materialUploadError'); error.hidden = false; error.textContent = e.message; button.disabled = false; }
}

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

function setMarketPostTypeFilter(type) {
  marketPostType = ['all', 'idea', 'free'].includes(type) ? type : 'all';
  document.querySelectorAll('[data-market-post-type]').forEach(button => button.classList.toggle('is-active', button.dataset.marketPostType === marketPostType));
  document.querySelectorAll('.market-filter-tab-row:not(.market-post-type-row):not(#marketFreeCategoryFilters)').forEach(row => { row.hidden = marketPostType === 'free'; });
  const freeRow = document.getElementById('marketFreeCategoryFilters');
  if (freeRow) freeRow.hidden = marketPostType !== 'free';
  updateMaterialStats();
  filterMaterials();
}

function updateMaterialStats() {
  const allItems = EMIE.materialState.items || [];
  const items = marketPostType === 'all' ? allItems : allItems.filter(item => (item.postType === 'free' ? 'free' : 'idea') === marketPostType);
  const total = document.getElementById('marketTotal');
  const other = document.getElementById('marketAvailable');
  const labels = document.querySelectorAll('.market-stats button span');
  if (total) total.textContent = items.length;
  if (other) other.textContent = marketPostType !== 'idea'
    ? items.filter(item => item.postType === 'free').reduce((sum, item) => sum + Number(item.likeCount || 0), 0)
    : items.filter(item => materialStatus(item) === 'available').length;
  if (labels[0]) labels[0].textContent = marketPostType === 'all' ? '全部素材' : marketPostType === 'free' ? '全部自由单' : '全部创意单';
  if (labels[1]) labels[1].textContent = marketPostType === 'idea' ? '待采纳创意' : '自由单累计点赞';
  const availableButton = document.querySelector('[data-emie-action="click:market-status-available"]');
  if (availableButton) availableButton.disabled = marketPostType === 'free';
}

function setFreeCategoryFilter(category) {
  const input = document.getElementById('materialFreeCategoryFilter');
  if (input) input.value = category;
  document.querySelectorAll('[data-market-free-category]').forEach(button => button.classList.toggle('is-active', button.dataset.marketFreeCategory === category));
  filterMaterials();
}

function resetMaterialFilters() {
  const search = document.getElementById('materialSearch');
  const status = document.getElementById('materialAdoptionFilter');
  const category = document.getElementById('materialCategoryFilter');
  const ip = document.getElementById('materialIpFilter');
  const freeCategory = document.getElementById('materialFreeCategoryFilter');
  if (search) search.value = '';
  if (status) status.value = 'all';
  if (category) category.value = 'all';
  if (ip) ip.value = '';
  if (freeCategory) freeCategory.value = 'all';
  document.querySelectorAll('[data-market-adoption-filter],[data-market-category-filter]').forEach(button => button.classList.toggle('is-active', button.dataset.marketAdoptionFilter === 'all' || button.dataset.marketCategoryFilter === 'all'));
  document.querySelectorAll('[data-market-free-category]').forEach(button => button.classList.toggle('is-active', button.dataset.marketFreeCategory === 'all'));
  setMarketPostTypeFilter('all');
}

async function renderMaterialMarket(main) {
  const role = EMIE.state.currentRole;
  clearInterval(marketBannerTimer);
  main.innerHTML = `<section class="market-hero"><div class="market-hero-copy"><span class="market-eyebrow">EMIE CREATIVE MARKET</span><h1>让好创意，被看见</h1><p>发现团队里的灵感宝藏，把优秀设计快速变成产品。</p><div class="market-hero-actions">${role === 'designer' ? '<button class="btn market-primary-btn" data-emie-action="click:market-upload-modal">＋ 发布我的创意</button>' : '<span class="market-hint">浏览灵感，寻找下一个爆款</span>'}</div></div><div class="market-hero-art"><span>✦</span><span>◇</span><span>✧</span></div></section><div class="market-filter-tabs"><input type="hidden" id="materialAdoptionFilter" value="all"><input type="hidden" id="materialCategoryFilter" value="all"><div class="market-filter-tab-row"><span>采纳方式</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-adoption-filter" data-market-adoption-filter="all">全部</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="available">待采纳</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="design">设计采纳</button><button data-emie-action="click:market-adoption-filter" data-market-adoption-filter="direct">直接采纳</button></div></div><div class="market-filter-tab-row"><span>作品分类</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-category-filter" data-market-category-filter="all">全部分类</button><button data-emie-action="click:market-category-filter" data-market-category-filter="id">ID</button><button data-emie-action="click:market-category-filter" data-market-category-filter="visual">视觉</button><button data-emie-action="click:market-category-filter" data-market-category-filter="graphic">平面</button></div></div></div><div class="market-toolbar-row"><div class="market-filter-card"><div class="market-stats"><button type="button" data-emie-action="click:market-status-all"><strong id="marketTotal">—</strong><span>全部素材</span></button><button type="button" data-emie-action="click:market-status-available"><strong id="marketAvailable">—</strong><span>待采纳创意</span></button></div><div class="market-filter-fields"><label><span>IP</span><select id="materialIpFilter" class="form-select" data-emie-action="change:market-filter"><option value="">全部 IP</option></select></label><button class="btn btn-outline btn-sm market-filter-reset" data-emie-action="click:market-reset-filters">重置</button></div></div><div class="material-toolbar">${role === 'designer' ? '<button class="btn market-primary-btn market-upload-toolbar-btn" data-emie-action="click:market-upload-modal">＋ 发布我的创意</button>' : ''}<div class="market-search-wrap"><span>⌕</span><input id="materialSearch" class="form-input" placeholder="搜索标题、分类、IP、作者或设计构思" data-emie-action="input:market-filter"><button class="btn market-search-btn" data-emie-action="click:market-filter">搜索</button></div></div></div><div id="materialGrid" class="material-grid"><div class="market-empty"><div class="market-empty-icon">✦</div><h3>正在寻找灵感…</h3><p>素材广场马上为你呈现最新创意</p></div></div>`;
  main.querySelector('.market-hero-actions')?.remove();
  main.querySelector('.market-filter-tabs')?.insertAdjacentHTML('afterbegin', '<div class="market-filter-tab-row market-post-type-row"><span>素材类型</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-type-filter" data-market-post-type="all">全部</button><button data-emie-action="click:market-type-filter" data-market-post-type="idea">创意单</button><button data-emie-action="click:market-type-filter" data-market-post-type="free">自由单</button></div></div>');
  main.querySelector('.market-filter-tabs')?.insertAdjacentHTML('beforeend', '<input type="hidden" id="materialFreeCategoryFilter" value="all"><div class="market-filter-tab-row" id="marketFreeCategoryFilters" hidden><span>自由单类型</span><div class="market-tab-list"><button class="is-active" data-emie-action="click:market-free-filter" data-market-free-category="all">全部</button><button data-emie-action="click:market-free-filter" data-market-free-category="product_concept">产品概念</button><button data-emie-action="click:market-free-filter" data-market-free-category="packaging_concept">包装概念</button><button data-emie-action="click:market-free-filter" data-market-free-category="brainstorming">集思广益</button></div></div>');
  main.querySelector('.material-toolbar')?.insertAdjacentHTML('afterbegin', '<button class="btn market-primary-btn market-upload-toolbar-btn market-free-upload-toolbar-btn" data-emie-action="click:market-free-upload-modal">＋ 发布自由单</button>');
  const ideaButton = main.querySelector('[data-emie-action="click:market-upload-modal"]');
  if (ideaButton) ideaButton.textContent = '＋ 发布创意单';
  setMarketPostTypeFilter(marketPostType);
  document.querySelector('.market-filter-card')?.insertAdjacentHTML('afterbegin', '<div class="market-filter-intro"><span>⌘</span><div><strong>筛选素材</strong><small>按 IP 快速定位</small></div></div>');
  document.querySelector('.market-search-btn')?.remove();
  const [items, publicConfig] = await Promise.all([loadMaterials(), apiGet('/admin/public-config')]);
  let banners = [];
  try { banners = JSON.parse(publicConfig['market.bannerImages'] || '[]'); } catch (_) { banners = []; }
  if (Array.isArray(banners) && banners.length) initMarketBanners(banners);
  EMIE.materialState.items = items;
  updateMaterialStats();
  const ipFilter = document.getElementById('materialIpFilter');
  if (ipFilter) ipFilter.insertAdjacentHTML('beforeend', [...new Set(items.map(m => m.ipName).filter(Boolean))].sort().map(ip => `<option value="${escHtml(ip)}">${escHtml(ip)}</option>`).join(''));
  filterMaterials();
}

function initMarketBanners(banners) {
  const hero = document.querySelector('.market-hero');
  if (!hero) return;
  hero.classList.add('market-hero-slides');
  hero.innerHTML = `${banners.map((url, index) => `<img class="market-banner-backdrop ${index === 0 ? 'is-active' : ''}" src="${escHtml(authenticatedFileUrl(url))}" alt="" aria-hidden="true"><img class="market-banner-slide ${index === 0 ? 'is-active' : ''}" src="${escHtml(authenticatedFileUrl(url))}" alt="素材广场指引 ${index + 1}" ${index ? 'aria-hidden="true"' : ''}>`).join('')}${banners.length > 1 ? `<div class="market-banner-dots">${banners.map((_, index) => `<button type="button" class="${index === 0 ? 'is-active' : ''}" aria-label="第 ${index + 1} 张 Banner" data-index="${index}"></button>`).join('')}</div>` : ''}`;
  if (banners.length < 2) return;
  let current = 0;
  const show = (index) => {
    current = index % banners.length;
    hero.querySelectorAll('.market-banner-slide').forEach((slide, i) => { slide.classList.toggle('is-active', i === current); slide.setAttribute('aria-hidden', i === current ? 'false' : 'true'); });
    hero.querySelectorAll('.market-banner-backdrop').forEach((backdrop, i) => backdrop.classList.toggle('is-active', i === current));
    hero.querySelectorAll('.market-banner-dots button').forEach((dot, i) => dot.classList.toggle('is-active', i === current));
  };
  const start = () => { clearInterval(marketBannerTimer); marketBannerTimer = setInterval(() => show((current + 1) % banners.length), 5000); };
  hero.querySelectorAll('.market-banner-dots button').forEach(dot => dot.addEventListener('click', () => { show(Number(dot.dataset.index)); start(); }));
  hero.addEventListener('mouseenter', () => clearInterval(marketBannerTimer));
  hero.addEventListener('mouseleave', start);
  hero.addEventListener('focusin', () => clearInterval(marketBannerTimer));
  hero.addEventListener('focusout', start);
  start();
}

function filterMaterials() {
  const q = (document.getElementById('materialSearch')?.value || '').trim().toLowerCase();
  const adoption = document.getElementById('materialAdoptionFilter')?.value || 'all';
  const category = document.getElementById('materialCategoryFilter')?.value || 'all';
  const ip = document.getElementById('materialIpFilter')?.value || '';
  const freeCategory = document.getElementById('materialFreeCategoryFilter')?.value || 'all';
  const items = (EMIE.materialState.items || []).filter(m => {
    const isFree = m.postType === 'free';
    if (marketPostType !== 'all' && (isFree ? 'free' : 'idea') !== marketPostType) return false;
    const matchesKeyword = `${m.title} ${m.description} ${materialCategory(m)} ${FREE_CATEGORY_LABELS[m.freeCategory] || ''} ${materialIp(m)} ${m.authorName}`.toLowerCase().includes(q);
    const matchesAdoption = isFree || adoption === 'all' || (adoption === 'available' ? materialStatus(m) === 'available' : adoptionUsed(m, adoption));
    const matchesCategory = isFree ? (marketPostType !== 'free' || freeCategory === 'all' || m.freeCategory === freeCategory) : (category === 'all' || m.category === category) && (!ip || m.ipName === ip);
    return matchesKeyword && matchesAdoption && matchesCategory;
  });
  renderMaterialGrid(items);
}

EMIE.registerActions({ renderMaterialMarket, openMaterialDetail, closeMaterialDetail, adoptMaterial, toggleMaterialLike, renderMaterialUploadModal, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, setMaterialCategoryFilter, resetMaterialFilters, onMaterialIpChange, onMaterialAiToggle, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments });
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
  registerEventAction('market-submit-upload', () => document.getElementById('materialUploadForm')?.dataset.postType === 'free' ? submitFreeMaterial() : submitMaterialUpload());
  registerEventAction('market-free-upload-modal', () => renderFreeUploadModal());
  registerEventAction('market-free-category', () => onFreeCategoryChange());
  registerEventAction('market-type-filter', (_event, el) => setMarketPostTypeFilter(el.dataset.marketPostType));
  registerEventAction('market-free-filter', (_event, el) => setFreeCategoryFilter(el.dataset.marketFreeCategory));
  registerEventAction('market-ip-change', (_event, el) => onMaterialIpChange(el));
  registerEventAction('market-ai-toggle', () => onMaterialAiToggle());
  registerEventAction('market-ref-input', () => document.getElementById('materialRefImageInput')?.click());
  registerEventAction('market-packaging-flat', () => document.getElementById('packagingFlatInput')?.click());
  registerEventAction('market-packaging-folded', () => document.getElementById('packagingFoldedInput')?.click());
  registerEventAction('market-packaging-flat-file', (_event, el) => handlePackagingImage(el, packagingFlatImage, 'packagingFlatList', '包装展开图'));
  registerEventAction('market-packaging-folded-file', (_event, el) => handlePackagingImage(el, packagingFoldedImage, 'packagingFoldedList', '折叠效果图'));
  registerEventAction('market-attachment-input', () => document.getElementById('materialAttachmentInput')?.click());
  registerEventAction('market-ref-images', (_event, el) => handleFreeOrIdeaReferenceImages(el));
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
EMIE.registerModule('materialMarket', { renderMaterialMarket, openMaterialDetail, closeMaterialDetail, adoptMaterial, toggleMaterialLike, renderMaterialUploadModal, openMaterialEdit, deleteMaterial, unpublishMaterial, closeMaterialUpload, submitMaterialUpload, filterMaterials, setMaterialStatusFilter, setMaterialCategoryFilter, resetMaterialFilters, onMaterialIpChange, onMaterialAiToggle, toggleMaterialIpSubOption, handleMaterialRefImages, handleMaterialAttachments, packagingFlatImage, packagingFoldedImage });
