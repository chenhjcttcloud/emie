const EMIE = window.EMIE;
const REFERENCE_FILE_ACCEPT = EMIE.fileAccept?.reference || 'image/*';
const ATTACHMENT_FILE_ACCEPT = EMIE.fileAccept?.attachment
  || '.ai,.step,.stp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.zip,.rar,.7z,image/*';
const hasPermission = (...args) => EMIE.actions.hasPermission(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const getProjectStatusInfo = (...args) => EMIE.actions.getProjectStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const renderDesignerTasks = (...args) => EMIE.actions.renderDesignerTasks(...args);
const renderProjectRow = (...args) => EMIE.actions.renderProjectRow(...args);
const openProjectDetail = (...args) => EMIE.actions.openProjectDetail(...args);
const openCreateProject = (...args) => EMIE.actions.openCreateProject(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

function renderDesignRequirementMaterials(referenceImagesJson, attachmentsJson, delivery = false) {
  const imageRenderer = delivery
    ? EMIE.actions.renderSubTaskImages
    : EMIE.actions.renderProjectReferenceImages;
  const attachmentRenderer = delivery
    ? EMIE.actions.renderTaskAttachments
    : EMIE.actions.renderProjectAttachments;
  const images = typeof imageRenderer === 'function'
    ? (delivery
      ? imageRenderer(referenceImagesJson)
      : imageRenderer({ referenceImagesJson }))
    : '';
  const attachments = typeof attachmentRenderer === 'function'
    ? (delivery
      ? attachmentRenderer(attachmentsJson)
      : attachmentRenderer({ attachmentsJson }))
    : '';
  return images + attachments;
}

function openListItemDetail(type, id) {
  if (type === 'design_requirement') {
    openDesignRequirementDetail(id);
    return;
  }
  openProjectDetail(id);
}

async function openDesignRequirementDetail(id) {
  if (document.getElementById('designRequirementDetailModal')) return;
  try {
    const detail = await apiGet(`/design-requirements/${id}`);
    const myRole = String(EMIE.state.currentRole || '').toLowerCase();
    const myScore = (detail.scoringRecords || []).find(s =>
      s.reviewerId === EMIE.state.currentUserId || (!s.reviewerId && s.role === myRole));
    const canDeliver = myRole === 'designer' && detail.designerId === EMIE.state.currentUserId;
    const requirementMaterials = renderDesignRequirementMaterials(
      detail.referenceImagesJson, detail.attachmentsJson);
    const deliveryMaterials = renderDesignRequirementMaterials(
      detail.deliveryReferenceImagesJson, detail.deliveryAttachmentsJson, true);
    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'designRequirementDetailModal';
    modal.innerHTML = `
      <button class="modal-close-float" data-emie-onclick="closeM('designRequirementDetailModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left">
          <div class="modal-title">🎨 设计/送审需求详情</div>
          <div style="font-size:12px;color:var(--gray-400);margin-top:3px;">${escHtml(detail.projectCode || ('#' + detail.id))}</div>
        </div></div>
        <div class="modal-body">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:16px;">
            <span class="badge ${escHtml(detail.statusCls || 'badge-progress')}">${escHtml(detail.statusLabel || detail.status || '-')}</span>
            <span style="font-size:12px;color:var(--gray-400);">创建：${detail.createdAt ? new Date(detail.createdAt).toLocaleString('zh-CN', { hour12: false }) : '-'}</span>
          </div>
          <div class="detail-section">
            <div class="detail-section-title">📋 基本信息</div>
            <div class="detail-grid">
              <div class="detail-item"><div class="detail-label">产品名称</div><div class="detail-value">${escHtml(detail.productName || '-')}</div></div>
              <div class="detail-item"><div class="detail-label">要求完成时间</div><div class="detail-value">${formatDate(detail.deadline)}</div></div>
              <div class="detail-item"><div class="detail-label">需求负责人</div><div class="detail-value">${escHtml(detail.responsibleName || detail.ownerName || '-')}</div></div>
              <div class="detail-item"><div class="detail-label">交付设计师</div><div class="detail-value">${escHtml(detail.designerName || '待指定')}</div></div>
              <div class="detail-item"><div class="detail-label">产品企划</div><div class="detail-value">${escHtml(detail.plannerName || '待指定')}</div></div>
              <div class="detail-item"><div class="detail-label">客户名称</div><div class="detail-value">${escHtml(detail.customerName || '-')}</div></div>
            </div>
            <div style="margin-top:12px;"><div class="detail-label">产品要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.productRequirements || '-')}</div></div>
            ${detail.description ? `<div style="margin-top:12px;"><div class="detail-label">细节描述</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.description)}</div></div>` : ''}
          </div>
          ${requirementMaterials ? `
          <div class="detail-section">
            <div class="detail-section-title">🗂️ 需求资料</div>
            ${requirementMaterials}
          </div>` : ''}
          <div class="detail-section">
            <div class="detail-section-title">📦 交付与评分</div>
            ${detail.deliveryContent
              ? `<div class="detail-label">设计师交付成果</div><div class="detail-value" style="white-space:pre-wrap;margin-bottom:12px;">${escHtml(detail.deliveryContent)}</div>`
              : '<div style="color:var(--gray-400);font-size:13px;margin-bottom:12px;">设计师尚未提交交付成果</div>'}
            ${deliveryMaterials ? `<div style="margin-bottom:14px;">${deliveryMaterials}</div>` : ''}
            <div style="display:flex;gap:8px;flex-wrap:wrap;">
              ${(detail.scoringRecords || []).map(s => `<span class="badge ${s.status === 'completed' ? 'badge-completed' : 'badge-pending'}">${escHtml(s.stage === 'self' ? '设计师自评' : roleLabel(s.role) + '评分')}：${s.status === 'completed' ? `${s.score}分` : s.status === 'pending' ? '待评分' : '等待中'}</span>`).join('')}
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-outline" data-emie-onclick="closeM('designRequirementDetailModal')">关闭</button>
          ${canDeliver && !detail.deliveryContent ? `<button class="btn btn-primary" data-emie-onclick="openDesignRequirementDelivery(${id})">📦 提交交付成果</button>` : ''}
          ${myScore?.status === 'pending' && myScore.stage === 'self' ? `<button class="btn btn-warning" data-emie-onclick="openDesignRequirementScore(${id},true)">⭐ 完成自评</button>` : ''}
          ${myScore?.status === 'pending' && myScore.stage === 'review' ? `<button class="btn btn-primary" data-emie-onclick="openDesignRequirementScore(${id},false)">⭐ 立即评分</button>` : ''}
        </div>
      </div>`;
    document.body.appendChild(modal);
  } catch (error) {
    alert('加载详情失败：' + error.message);
  }
}

function openDesignRequirementDelivery(id) {
  closeM('designRequirementDetailModal');
  EMIE.projectState.deliverImages = [];
  EMIE.projectState.deliverAttachments = [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'designRequirementDeliveryModal';
  modal.innerHTML = `<div class="modal"><div class="modal-header"><div class="modal-title">📦 提交设计成果</div></div>
    <div class="modal-body">
      <div class="form-group"><label class="form-label">交付内容</label><textarea class="form-textarea" id="designRequirementDeliveryContent" rows="7" placeholder="请填写本次交付内容或送审说明"></textarea></div>
      <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
        <div class="form-label" style="margin-bottom:8px;">🖼️ 交付参考图</div>
        <div class="upload-area" data-emie-onclick="document.getElementById('designRequirementDeliverImageInput').click()">
          <div>📁 拖拽图片到此处，或点击选择图片</div>
          <input type="file" id="designRequirementDeliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverImages(this)">
        </div>
        <div class="file-list" id="deliverImageList"></div>
      </div>
      <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
        <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
        <div class="upload-area" data-emie-onclick="document.getElementById('designRequirementDeliverAttachmentInput').click()">
          <div>📁 拖拽文件到此处，或点击选择文件</div>
          <input type="file" id="designRequirementDeliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverAttachments(this)">
        </div>
        <div class="file-list" id="deliverAttachmentList"></div>
      </div>
    </div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('designRequirementDeliveryModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitDesignRequirementDelivery(${id})">提交交付</button></div></div>`;
  document.body.appendChild(modal);
}

async function submitDesignRequirementDelivery(id) {
  if (EMIE.projectState.uploadingCount > 0) return alert('文件正在上传中，请等待上传完成');
  const deliveryContent = document.getElementById('designRequirementDeliveryContent')?.value?.trim();
  if (!deliveryContent) return alert('请填写交付成果');
  try {
    await apiPost(`/design-requirements/${id}/deliver`, {
      deliveryContent,
      deliveryReferenceImagesJson: JSON.stringify((EMIE.projectState.deliverImages || []).map(i => ({
        name: i.name, url: i.url, size: i.size, storedName: i.storedName
      }))),
      deliveryAttachmentsJson: JSON.stringify(EMIE.projectState.deliverAttachments || []),
    });
    closeM('designRequirementDeliveryModal');
    await openDesignRequirementDetail(id);
  } catch (e) { alert('提交失败：' + e.message); }
}

function openDesignRequirementScore(id, selfScore) {
  closeM('designRequirementDetailModal');
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'designRequirementScoreModal';
  modal.innerHTML = `<div class="modal"><div class="modal-header"><div class="modal-title">⭐ ${selfScore ? '设计师自评' : '需求评分'}</div></div>
    <div class="modal-body"><p style="color:var(--gray-500);margin-bottom:16px;">请对本次设计交付进行综合评分（1-100分）</p><input type="number" class="form-input" id="designRequirementScoreValue" min="1" max="100" style="font-size:24px;text-align:center;"></div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('designRequirementScoreModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitDesignRequirementScore(${id},${selfScore})">提交评分</button></div></div>`;
  document.body.appendChild(modal);
}

async function submitDesignRequirementScore(id, selfScore) {
  const score = Number(document.getElementById('designRequirementScoreValue')?.value);
  if (!Number.isInteger(score) || score < 1 || score > 100) return alert('请输入1-100的整数评分');
  try {
    await apiPost(`/design-requirements/${id}/${selfScore ? 'self-score' : 'score'}`, { score });
    closeM('designRequirementScoreModal');
    EMIE.actions.clearSWRCache?.();
    if (EMIE.state.currentView === 'scoring') await EMIE.actions.renderScoringView(document.getElementById('mainContent'), EMIE.state.currentRole, EMIE.state.currentUserId);
    else await openDesignRequirementDetail(id);
  } catch (e) { alert('评分失败：' + e.message); }
}

async function renderOrderList(main, type, role, uid, titleOverride = '', endpoint = '/projects/page') {
  let title = '全部项目';
  if (type === 'channel_custom') title = '📦 渠道定制单';
  else if (type === 'regular') title = '🏭 公司常规品';
  else title = '📋 全部项目';
  if (titleOverride) title = titleOverride;

  const participating = role === 'designer' || role === 'supplychain';
  const state = EMIE.projectListState = { type, role, uid, endpoint, page: 0, total: 0, totalPages: 0, filters: {}, loading: false };
  main.innerHTML = `<div class="project-query-loading"><span class="project-query-spinner"></span>正在查询项目…</div>`;
  const result = await loadProjectListPage(0);

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h2 style="font-size:20px;">${title} <span id="projectListCount" style="font-size:13px;color:var(--gray-400);font-weight:400;">（${result.total} 个）</span></h2>
      <div style="display:flex;gap:8px;">
        ${type === 'channel_custom' && hasPermission('project.channel.create') ? `<button class="btn btn-primary" data-emie-onclick="openCreateProject('channel_custom')">➕ 新建渠道定制项目</button>` : ''}
        ${type === 'regular' && hasPermission('project.regular.create') ? `<button class="btn btn-primary" data-emie-onclick="openCreateProject('regular')">➕ 新建常规品设计项目</button>` : ''}
        ${type === 'design_requirement' && hasPermission('design_requirement.create') ? `<button class="btn btn-primary" data-emie-onclick="openCreateProject('design_requirement')">➕ 新建设计/送审需求</button>` : ''}
      </div>
    </div>
    <div class="filter-bar" style="margin-bottom:16px;">
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectStatusFilter">
        <option value="all">全部状态</option>
        <option value="in_progress">进行中</option>
        <option value="paused">已暂停</option>
        <option value="completed">已完成</option>
        <option value="completed_pending_score">待评分</option>
        <option value="pending_planner">待企划接单</option>
        <option value="pending_terminate">终止确认中</option>
        <option value="terminated">已终止</option>
      </select>
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectCategoryFilter">
        <option value="all">全部类目</option>
        ${(EMIE.state.categories || []).map(c => `<option value="${escHtml(c.name)}">${escHtml(c.name)}</option>`).join('')}
      </select>
      <select class="form-select" data-emie-onchange="filterProjectList()" style="min-width:120px;" id="projectMarketFilter">
        <option value="all">全部市场</option>
        <option value="国内">国内</option>
        <option value="海外">海外</option>
      </select>
      <select class="form-select" id="projectOwnerRoleFilter" data-emie-onchange="changeProjectOwnerRole(this.value)" style="min-width:140px;">
        <option value="all">全部负责人</option>
        <option value="sales">需求方（销售）</option>
        <option value="planner">产品企划</option>
      </select>
      <select class="form-select" id="projectOwnerFilter" style="min-width:150px;" disabled>
        <option value="">请先选择负责人类型</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索编号/描述..." data-emie-oninput="filterProjectList()" style="min-width:180px;" id="searchInput">
      <input type="date" class="form-input" id="filterDateStart" data-emie-onchange="filterProjectList()" style="min-width:130px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:12px;">~</span>
      <input type="date" class="form-input" id="filterDateEnd" data-emie-onchange="filterProjectList()" style="min-width:130px;" title="结束日期">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterProjectList()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetProjectFilters()">↺ 重置</button>
    </div>
    <div class="card project-list-card" id="projectListContainer">${renderProjectTable(result.items, { compact: true, showType: !type })}</div>
  `;
}

async function loadProjectListPage(page) {
  const state = EMIE.projectListState;
  if (!state) return { items: [], total: 0, totalPages: 0, page: 0 };
  const params = new URLSearchParams({ page: String(page), size: '15' });
  if (state.type) params.set('type', state.type);
  if (state.participating) params.set('participating', 'true');
  Object.entries(state.filters || {}).forEach(([key, value]) => {
    if (value) params.set(key, value);
  });
  const result = await apiGet(`${state.endpoint}?${params}`);
  state.total = result.total || 0;
  state.totalPages = result.totalPages || 0;
  return result;
}

function renderProjectTable(orders, options = {}) {
  if (!orders.length) return `<div class="empty" style="padding:40px;"><div class="empty-icon">📭</div><p>暂无项目</p></div>`;
  const { compact = false, showType = true } = options;
  const headers = compact
    ? `<th class="col-id">编号</th>${showType ? '<th>类型</th>' : ''}<th class="col-name">产品名称</th><th>需求方 / 企划</th><th>类目 / 市场</th><th>进度</th><th>截止</th><th>状态</th><th class="col-action">操作</th>`
    : '<th>编号</th><th>类型</th><th>产品名称</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>价格</th><th>子任务</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th>';
  return `<div class="table-wrap project-table-wrap"><table class="${compact ? 'project-list-table' : ''}">
    <thead><tr>${headers}</tr></thead>
    <tbody>${orders.map(o => renderProjectRow(o, compact, showType)).join('')}</tbody>
  </table></div>${compact ? renderProjectPagination(orders.length) : ''}`;
}

function renderProjectPagination(currentCount) {
  const state = EMIE.projectListState || {};
  const total = state.total;
  const pages = state.totalPages;
  const page = state.page || 0;
  const from = total ? page * 15 + 1 : 0;
  const to = total ? page * 15 + currentCount : 0;
  return `<div class="project-pagination"><span>显示 ${from}-${to} / ${total} · 共 ${pages} 页</span><div><button class="btn btn-outline btn-sm" ${page <= 0 ? 'disabled' : ''} data-emie-onclick="changeProjectListPage(${page - 1})">上一页</button><span class="project-page-indicator">${pages ? `${page + 1} / ${pages}` : '0 / 0'}</span><button class="btn btn-outline btn-sm" ${page >= pages - 1 ? 'disabled' : ''} data-emie-onclick="changeProjectListPage(${page + 1})">下一页</button><span class="project-page-jump">跳至 <input id="projectPageJumpInput" type="number" min="1" max="${Math.max(pages, 1)}" value="${pages ? page + 1 : 1}" ${pages ? '' : 'disabled'}> 页</span><button class="btn btn-outline btn-sm" ${pages ? '' : 'disabled'} data-emie-onclick="jumpProjectListPage()">跳转</button></div></div>`;
}

function renderProjectListLoading() {
  return `<div class="project-query-loading"><span class="project-query-spinner"></span>正在查询项目…</div>`;
}

async function changeProjectListPage(page) {
  const state = EMIE.projectListState;
  if (!state || page < 0 || state.loading) return;
  const container = document.getElementById('projectListContainer');
  state.loading = true;
  if (container) container.innerHTML = renderProjectListLoading();
  try {
    const result = await loadProjectListPage(page);
    state.page = result.page ?? page;
    if (container) container.innerHTML = renderProjectTable(result.items || [], { compact: true, showType: !state.type });
    const count = document.getElementById('projectListCount');
    if (count) count.textContent = `（${result.total || 0} 个）`;
  } catch (e) {
    if (container) container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>查询失败：${escHtml(e.message)}</p></div>`;
  } finally {
    state.loading = false;
  }
}

function jumpProjectListPage() {
  const state = EMIE.projectListState;
  const input = document.getElementById('projectPageJumpInput');
  const pages = state?.totalPages;
  const requested = Number.parseInt(input?.value, 10);
  if (!Number.isInteger(requested) || requested < 1 || requested > pages) {
    alert(`请输入 1 至 ${pages || 1} 的页码`);
    return;
  }
  changeProjectListPage(requested - 1);
}

function filterProjectList() {
  clearTimeout(filterProjectList._timer);
  filterProjectList._timer = setTimeout(applyFilterProjectList, 100);
}

async function applyFilterProjectList() {
  const state = EMIE.projectListState;
  if (!state) return;
  const status = document.getElementById('projectStatusFilter')?.value || 'all';
  const category = document.getElementById('projectCategoryFilter')?.value || 'all';
  const market = document.getElementById('projectMarketFilter')?.value || 'all';
  const ownerRole = document.getElementById('projectOwnerRoleFilter')?.value || 'all';
  const ownerId = document.getElementById('projectOwnerFilter')?.value || '';
  state.filters = {
    ...(status !== 'all' ? { status } : {}),
    ...(category !== 'all' ? { category } : {}),
    ...(market !== 'all' ? { market } : {}),
    ...(ownerRole !== 'all' && ownerId ? { ownerRole, ownerId } : {}),
    ...(document.getElementById('searchInput')?.value?.trim() ? { keyword: document.getElementById('searchInput').value.trim() } : {}),
    ...(document.getElementById('filterDateStart')?.value ? { deadlineStart: document.getElementById('filterDateStart').value } : {}),
    ...(document.getElementById('filterDateEnd')?.value ? { deadlineEnd: document.getElementById('filterDateEnd').value } : {}),
  };
  state.page = 0;
  await changeProjectListPage(0);
}

function resetProjectFilters() {
  const statusEl = document.getElementById('projectStatusFilter');
  const searchEl = document.getElementById('searchInput');
  const dateStartEl = document.getElementById('filterDateStart');
  const dateEndEl = document.getElementById('filterDateEnd');
  if (statusEl) statusEl.value = 'all';
  const categoryEl = document.getElementById('projectCategoryFilter');
  const marketEl = document.getElementById('projectMarketFilter');
  const ownerRoleEl = document.getElementById('projectOwnerRoleFilter');
  const ownerEl = document.getElementById('projectOwnerFilter');
  if (searchEl) searchEl.value = '';
  if (categoryEl) categoryEl.value = 'all';
  if (marketEl) marketEl.value = 'all';
  if (ownerRoleEl) ownerRoleEl.value = 'all';
  if (ownerEl) { ownerEl.innerHTML = '<option value="">请先选择负责人类型</option>'; ownerEl.disabled = true; }
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  if (EMIE.projectListState) EMIE.projectListState.filters = {};
  changeProjectListPage(0);
}

function changeProjectOwnerRole(role) {
  const select = document.getElementById('projectOwnerFilter');
  if (!select) return;
  if (role === 'all') {
    select.innerHTML = '<option value="">请先选择负责人类型</option>';
    select.disabled = true;
    return;
  }
  const users = EMIE.state.users?.[role] || [];
  select.innerHTML = '<option value="">请选择具体负责人</option>' +
    users.map(u => `<option value="${escHtml(u.userId)}">${escHtml(u.name)}${u.title ? `（${escHtml(u.title)}）` : ''}</option>`).join('');
  select.disabled = users.length === 0;
}

// ==================== 我的子任务（企划派发任务界面） ====================
async function renderMyTasks(main, role, uid, bucket = 'all') {
  if (role === 'designer' || role === 'supplychain' || role === 'planner' || role === 'promotion') {
    // 所有可作为子任务负责人的执行角色，展示分配给自己的子任务卡片。
    await renderDesignerTasks(main, uid, bucket);
    return;
  }

  if (role === 'admin') {
    await renderDesignerTasks(main, uid, bucket, role);
    return;
  }

  // 其他角色: 展示项目列表，方便查看和添加子任务
  EMIE.taskProjectListState = { page: 0, total: 0, totalPages: 0, filters: {} };
  const initialPage = await loadTaskProjectPage(0);
  const orders = initialPage.items || [];

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">📌 子任务管理 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${orders.length} 个项目)</span></h2>
    </div>
    <div class="filter-bar">
      <select class="form-select" data-emie-onchange="filterTaskProjects()" style="min-width:120px;" id="taskProjectFilter">
        <option value="all">全部项目</option>
        <option value="paused">已暂停</option>
        <option value="in_progress">进行中</option>
        <option value="planner_accepted">待添加子任务</option>
        <option value="completed_pending_score">待评分</option>
        <option value="completed">已完成</option>
        <option value="pending_terminate">终止确认中</option>
        <option value="terminated">已终止</option>
      </select>
      <select class="form-select" data-emie-onchange="filterTaskProjects()" style="min-width:120px;" id="taskProjectTypeFilter">
        <option value="all">全部类型</option>
        <option value="channel_custom">渠道定制单</option>
        <option value="regular">公司常规品</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索项目编号/描述..." data-emie-oninput="filterTaskProjects()" style="min-width:180px;" id="taskProjectSearch">
      <input type="date" class="form-input" id="taskProjectDateStart" data-emie-onchange="filterTaskProjects()" style="min-width:130px;" title="要求日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="taskProjectDateEnd" data-emie-onchange="filterTaskProjects()" style="min-width:130px;" title="要求日期止">
      <button class="btn btn-primary btn-sm" data-emie-onclick="filterTaskProjects()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" data-emie-onclick="resetTaskProjectFilters()">↺ 重置</button>
    </div>
    <div id="taskProjectContainer">${renderTaskProjectTable(orders)}</div>
  `;
  EMIE.dashboardState.taskProjectsCache = orders;
}

async function loadTaskProjectPage(page) {
  const state = EMIE.taskProjectListState;
  const params = new URLSearchParams({ page: String(page), size: '15' });
  Object.entries(state?.filters || {}).forEach(([key, value]) => { if (value) params.set(key, value); });
  const result = await apiGet(`/projects/page?${params}`);
  if (state) { state.page = result.page ?? page; state.total = result.total || 0; state.totalPages = result.totalPages || 0; }
  return result;
}

function renderTaskProjectTable(orders) {
  if (!orders.length) return `<div class="empty"><div class="empty-icon">📭</div><p>${EMIE.state.currentRole === 'admin' ? '暂无项目' : '暂无进行中的项目'}</p></div>`;
  const state = EMIE.taskProjectListState || {};
  const pagination = state.totalPages > 1 ? `<div class="project-pagination"><span>共 ${state.total} 个项目 · ${state.page + 1} / ${state.totalPages} 页</span><div><button class="btn btn-outline btn-sm" ${state.page <= 0 ? 'disabled' : ''} data-emie-onclick="changeTaskProjectPage(${state.page - 1})">上一页</button><button class="btn btn-outline btn-sm" ${state.page >= state.totalPages - 1 ? 'disabled' : ''} data-emie-onclick="changeTaskProjectPage(${state.page + 1})">下一页</button></div></div>` : '';
  return `<div class="card"><div class="table-wrap"><table>
    <thead><tr>
      <th>项目编号</th>
      <th>类型</th>
      <th>产品要求</th>
      <th>产品企划</th>
      <th>子任务</th>
      <th>要求时间</th>
      <th>状态</th>
      <th>操作</th>
    </tr></thead>
    <tbody>${orders.map(o => {
      const st = getProjectStatusInfo(o.status);
      return `<tr>
        <td><strong>${escHtml(o.projectCode || ('#' + o.id))}</strong></td>
        <td>${o.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escHtml(o.productRequirements || '')}">${escHtml(o.productRequirements || '-')}</td>
        <td>${o.plannerName ? escHtml(o.plannerName) : '<span style="color:var(--gray-400);">未指定</span>'}</td>
        <td>${o.approvedTaskCount}/${o.taskCount}</td>
        <td>${formatDate(o.deadline)}</td>
        <td><span class="badge ${st.cls}">${st.label}</span></td>
        <td>
          <button class="btn btn-primary btn-sm" data-emie-onclick="openProjectDetail(${o.id})">📋 管理子任务</button>
          <button class="btn btn-outline btn-sm" data-emie-onclick="openProjectDetail(${o.id})">查看</button>
        </td>
      </tr>`;
    }).join('')}</tbody>
  </table></div>${pagination}</div>`;
}

async function changeTaskProjectPage(page) {
  const state = EMIE.taskProjectListState;
  if (!state || page < 0 || page >= state.totalPages) return;
  const container = document.getElementById('taskProjectContainer');
  if (container) container.innerHTML = renderProjectListLoading();
  const result = await loadTaskProjectPage(page);
  if (container) container.innerHTML = renderTaskProjectTable(result.items || []);
  EMIE.dashboardState.taskProjectsCache = result.items || [];
}

async function renderDepartmentTasks(main, role, uid, bucket = 'all') {
  await renderDesignerTasks(main, uid, bucket, role, '/projects/department-subtasks', true);
}

function filterTaskProjects() {
  clearTimeout(filterTaskProjects._timer);
  filterTaskProjects._timer = setTimeout(applyFilterTaskProjects, 100);
}

async function applyFilterTaskProjects() {
  const filter = document.getElementById('taskProjectFilter')?.value || 'all';
  const q = document.getElementById('taskProjectSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('taskProjectDateStart')?.value;
  const dateEnd = document.getElementById('taskProjectDateEnd')?.value;
  const state = EMIE.taskProjectListState || { filters: {} };
  state.filters = {};
  if (filter !== 'all') state.filters.status = filter;
  const type = document.getElementById('taskProjectTypeFilter')?.value || 'all';
  if (type !== 'all') state.filters.type = type;
  if (q) state.filters.keyword = q;
  if (dateStart) state.filters.deadlineStart = dateStart;
  if (dateEnd) state.filters.deadlineEnd = dateEnd;
  state.page = 0;
  const c = document.getElementById('taskProjectContainer');
  if (c) c.innerHTML = renderProjectListLoading();
  const result = await loadTaskProjectPage(0);
  if (c) c.innerHTML = renderTaskProjectTable(result.items || []);
  EMIE.dashboardState.taskProjectsCache = result.items || [];
}

function resetTaskProjectFilters() {
  const filterEl = document.getElementById('taskProjectFilter');
  const typeEl = document.getElementById('taskProjectTypeFilter');
  const searchEl = document.getElementById('taskProjectSearch');
  const dateStartEl = document.getElementById('taskProjectDateStart');
  const dateEndEl = document.getElementById('taskProjectDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (typeEl) typeEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterTaskProjects();
}

// ==================== 待评分页面 ====================

function normalizeFilterText(value) {
  if (Array.isArray(value)) return value.join(' ')
    .toLocaleLowerCase();
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) return parsed.join(' ').toLocaleLowerCase();
    } catch (e) { /* 普通文本 */ }
  }
  return String(value ?? '').toLocaleLowerCase();
}

function matchesSearchText(query, ...values) {
  const normalizedQuery = normalizeFilterText(query).trim();
  if (!normalizedQuery) return true;
  return values.some(value => normalizeFilterText(value).includes(normalizedQuery));
}

function projectMatchesKeyword(project, query) {
  return matchesSearchText(query,
    project.projectCode, project.id, project.type, project.status, project.productName,
    project.productRequirements, project.salesName, project.plannerName,
    project.productCategory, project.targetMarket, project.complianceItems,
    project.priceRange, project.ipName
  );
}

function isDateInRange(value, start, end) {
  if (!start && !end) return true;
  const date = String(value || '').slice(0, 10);
  if (!date) return false;
  return (!start || date >= start) && (!end || date <= end);
}

EMIE.registerActions({
  renderOrderList,
  openListItemDetail,
  openDesignRequirementDetail,
  openDesignRequirementDelivery,
  submitDesignRequirementDelivery,
  openDesignRequirementScore,
  submitDesignRequirementScore,
  renderProjectTable,
  changeProjectListPage,
  jumpProjectListPage,
  filterProjectList,
  applyFilterProjectList,
  changeProjectOwnerRole,
  resetProjectFilters,
  renderMyTasks,
  renderDepartmentTasks,
  renderTaskProjectTable,
  filterTaskProjects,
  applyFilterTaskProjects,
  resetTaskProjectFilters,
  normalizeFilterText,
  matchesSearchText,
  projectMatchesKeyword,
  isDateInRange,
});

EMIE.registerModule('dashboardLists', {
  renderOrderList,
  openListItemDetail,
  openDesignRequirementDetail,
  openDesignRequirementDelivery,
  openDesignRequirementScore,
  changeProjectListPage,
  jumpProjectListPage,
  filterProjectList,
  resetProjectFilters,
  renderMyTasks,
  renderDepartmentTasks,
  filterTaskProjects,
  resetTaskProjectFilters,
});
