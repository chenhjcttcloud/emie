const EMIE = window.EMIE;
const getCurrentUserName = (...args) => EMIE.actions.getCurrentUserName(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const isModalOpen = (...args) => EMIE.actions.isModalOpen(...args);
const submitGuard = (...args) => EMIE.actions.submitGuard(...args);
const formModified = (...args) => EMIE.actions.formModified(...args);
const renderDatePicker = (...args) => EMIE.actions.renderDatePicker(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const render = (...args) => EMIE.actions.render(...args);
const renderFileList = (...args) => EMIE.actions.renderFileList(...args);
const handleCreateRefImages = (...args) => EMIE.actions.handleCreateRefImages(...args);
const handleCreateAttachments = (...args) => EMIE.actions.handleCreateAttachments(...args);

// ==================== 产品类目 / 目标市场选择 ====================
function onCategoryChange(sel) {
  const wrapper = document.getElementById('categoryNoteWrapper');
  if (sel.value === '其他') {
    wrapper.style.display = 'block';
  } else {
    wrapper.style.display = 'none';
    const ta = wrapper.querySelector('textarea');
    if (ta) ta.value = '';
  }
  sel.closest('.form-group')?.querySelector('.field-error')?.remove();
  sel.style.borderColor = '';
  formModified();
}

function toggleMarket(el) {
  el.classList.toggle('selected');
  const selected = [];
  document.querySelectorAll('#marketChips .chip.selected').forEach(c => selected.push(c.dataset.value));
  document.getElementById('targetMarketInput').value = JSON.stringify(selected);
  formModified();
}

function toggleCompliance(el) {
  el.classList.toggle('selected');
  const selected = [];
  document.querySelectorAll('#complianceChips .chip.selected').forEach(c => selected.push(c.dataset.value));
  document.getElementById('complianceItemsInput').value = JSON.stringify(selected);
  formModified();
}

function togglePriceRange(el) {
  document.querySelectorAll('#priceRangeChips .chip').forEach(c => c.classList.remove('selected'));
  el.classList.add('selected');
  document.getElementById('priceRangeInput').value = el.dataset.value;
  formModified();
}

// 切换子任务负责人类型（设计师/供应链）
const switchAssigneeType = function(prefix, role, el) {
  // 更新 radio 选中样式
  if (el) {
    document.querySelectorAll(`#addSubTaskForm .checkbox-item, #editSubTaskForm .checkbox-item`).forEach(c => c.classList.remove('checked'));
    el.classList.add('checked');
  }
  // 更新 hidden input
  const hidden = document.getElementById(prefix + 'SubTaskAssigneeRole');
  if (hidden) hidden.value = role;
  // 切换负责人下拉选项
  const sel = document.getElementById(prefix + 'SubTaskDesignerId');
  if (!sel) return;
  if (role === 'designer') {
    sel.innerHTML = '<option value="">请选择设计师</option>' +
      (EMIE.state.users.designer || []).map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('');
  } else if (role === 'planner') {
    sel.innerHTML = (EMIE.state.users.planner && EMIE.state.users.planner.length
      ? '<option value="">请选择企划</option>' +
        EMIE.state.users.planner.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
      : '<option value="">暂无企划人员</option>');
  } else {
    sel.innerHTML = (EMIE.state.users.supplychain && EMIE.state.users.supplychain.length
      ? '<option value="">请选择供应链</option>' +
        EMIE.state.users.supplychain.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
      : '<option value="">暂无供应链人员</option>');
  }
};

// 编辑子任务时切换负责人类型
const switchEditAssigneeType = function(role, el) {
  document.querySelectorAll('#editTaskForm .checkbox-item').forEach(c => c.classList.remove('checked'));
  el.classList.add('checked');
  document.getElementById('editSubTaskAssigneeRole').value = role;
  const sel = document.getElementById('editSubTaskDesignerId');
  if (!sel) return;
  if (role === 'designer') {
    sel.innerHTML = '<option value="">请选择设计师</option>' +
      (EMIE.state.users.designer || []).map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('');
  } else if (role === 'planner') {
    sel.innerHTML = (EMIE.state.users.planner && EMIE.state.users.planner.length
      ? '<option value="">请选择企划</option>' +
        EMIE.state.users.planner.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
      : '<option value="">暂无企划人员</option>');
  } else {
    sel.innerHTML = (EMIE.state.users.supplychain && EMIE.state.users.supplychain.length
      ? '<option value="">请选择供应链</option>' +
        EMIE.state.users.supplychain.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
      : '<option value="">暂无供应链人员</option>');
  }
};


function openCreateProject(type) {
  if (isModalOpen()) return;
  if (document.getElementById('createProjectModal')) return;
  EMIE.projectState.formModified = false;
  EMIE.projectState.createProjectType = type;

  const draftJson = sessionStorage.getItem('design_pm_create_draft');
  let draft = null;
  if (draftJson) {
    try { draft = JSON.parse(draftJson); } catch(e) {}
    if (draft && draft._type !== type) draft = null;
  }

  EMIE.projectState.createRefImages = draft?._refImages || [];
  EMIE.projectState.createAttachments = draft?._attachments || [];

  const defaultPlanner = draft?.plannerId || (EMIE.state.currentRole === 'planner' ? EMIE.state.currentUserId : '');
  const defaultSales = draft?.salesId || (EMIE.state.currentRole === 'sales' ? EMIE.state.currentUserId : '');

  const plannerOpts = `<option value="">请选择产品企划</option>` + EMIE.state.users.planner.map(u =>
      `<option value="${u.userId}" ${defaultPlanner === u.userId ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`
  ).join('');
  const salesOpts = `<option value="">请选择需求方</option>` + EMIE.state.users.sales.map(u =>
    `<option value="${u.userId}" ${defaultSales === u.userId ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`
  ).join('');
  const title = type === 'channel_custom' ? '新建渠道定制项目' : '新建常规品设计项目';

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'createProjectModal';
  modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('createProjectModal')">✕</button>
    <div class="modal modal-lg">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📝 ${title}</div></div></div>
      <div class="modal-body">
        <form id="createProjectForm">
          ${type === 'channel_custom' ? `
          <div class="form-group"><label class="form-label"><span class="required">*</span> 需求方（销售）</label>
            <select class="form-select" name="salesId" ${EMIE.state.currentRole === 'sales' ? 'disabled' : ''} data-emie-onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${salesOpts}</select>
            ${EMIE.state.currentRole === 'sales' ? `<input type="hidden" name="salesId" value="${EMIE.state.currentUserId}">` : ''}
          </div>` : ''}
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品企划</label>
            <select class="form-select" name="plannerId" ${EMIE.state.currentRole === 'planner' ? 'disabled' : ''} data-emie-onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${plannerOpts}</select>
            ${EMIE.state.currentRole === 'planner' ? `<input type="hidden" name="plannerId" value="${EMIE.state.currentUserId}">` : ''}
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品类目</label>
            <select class="form-select" name="productCategory" id="productCategorySelect" data-emie-onchange="onCategoryChange(this)">
              <option value="">请选择产品类目</option>
              ${EMIE.state.categories.map(c => `<option value="${c.name}">${c.name}</option>`).join('')}
            </select>
            <div id="categoryNoteWrapper" style="display:none;margin-top:8px;">
              <textarea class="form-textarea" name="productCategoryNote" placeholder="请说明其他类目的具体内容..." data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()"></textarea>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品名称</label>
            <input class="form-input" name="productName" value="${escHtml(draft?.productName || '')}" placeholder="请输入产品名称" maxlength="200" data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">
          </div>
          <div class="form-group"><label class="form-label">IP<span style="color:var(--gray-400);font-weight:400;margin-left:4px;">（可选）</span></label>
            <select class="form-select" name="ipName" data-emie-onchange="formModified()">
              <option value="">无IP</option>
              ${EMIE.state.ipOptions.map(ip => `<option value="${escHtml(ip.name)}" ${draft?.ipName === ip.name ? 'selected' : ''}>${escHtml(ip.name)}</option>`).join('')}
            </select>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 参考零售价</label>
            <div class="chip-group" id="priceRangeChips">
              ${EMIE.state.priceRanges.map(p => `<span class="chip" data-value="${p.name}" data-emie-onclick="togglePriceRange(this)">${p.name}</span>`).join('')}
            </div>
            <input type="hidden" name="priceRange" id="priceRangeInput" value="">
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 目标市场<span style="color:var(--gray-400);font-weight:400;margin-left:4px;">（可多选）</span></label>
            <div class="chip-group" id="marketChips">
              <span class="chip" data-value="国内" data-emie-onclick="toggleMarket(this)">国内</span>
              <span class="chip" data-value="海外" data-emie-onclick="toggleMarket(this)">海外</span>
            </div>
            <input type="hidden" name="targetMarket" id="targetMarketInput" value="">
            <div class="form-hint">可多选</div>
          </div>
          <div class="form-group"><label class="form-label">合规处罚<span style="color:var(--gray-400);font-weight:400;margin-left:4px;">（可多选，非必选）</span></label>
            <div class="chip-group" id="complianceChips">
              ${EMIE.state.complianceItems.map(c => `<span class="chip" data-value="${c.name}" data-emie-onclick="toggleCompliance(this)">${c.name}</span>`).join('')}
            </div>
            <input type="hidden" name="complianceItems" id="complianceItemsInput" value="">
            <div class="form-hint">提醒产品企划关注相关供应商是否有相关资质</div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 要求完成时间</label>${renderDatePicker('deadline', {value: draft?.deadline || ''})}</div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品要求</label><textarea class="form-textarea" name="productRequirements" placeholder="产品的基本要求和目标..." data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${escHtml(draft?.productRequirements || '')}</textarea></div>
          <div class="form-group"><label class="form-label">细节描述（可选）</label><textarea class="form-textarea" name="description" placeholder="补充细节说明..." data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${escHtml(draft?.description || '')}</textarea></div>
        </form>

        <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片</div>
          <div class="upload-area" data-emie-onclick="document.getElementById('createRefImageInput').click()">
            <div>📁 点击上传参考图片</div>
            <input type="file" id="createRefImageInput" multiple accept="image/*" style="display:none" data-emie-onchange="handleCreateRefImages(this)">
          </div>
          <div class="file-list" id="createRefImageList"></div>
        </div>

        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">📎 附件</div>
          <div class="upload-area" data-emie-onclick="document.getElementById('createAttachmentInput').click()">
            <div>📁 点击上传附件</div>
            <input type="file" id="createAttachmentInput" multiple style="display:none" data-emie-onchange="handleCreateAttachments(this)">
          </div>
          <div class="file-list" id="createAttachmentList"></div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" data-emie-onclick="closeM('createProjectModal')">取消</button>
        <button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitCreateProject('${type}'))">创建项目</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  if (EMIE.projectState.createRefImages.length) renderFileList(EMIE.projectState.createRefImages, '参考图片');
  if (EMIE.projectState.createAttachments.length) renderFileList(EMIE.projectState.createAttachments, '附件');

  // 恢复草稿中的产品类目和目标市场
  if (draft && type === 'channel_custom') {
    if (draft.productCategory) {
      const sel = document.getElementById('productCategorySelect');
      if (sel) {
        sel.value = draft.productCategory;
        onCategoryChange(sel);
      }
    }
    if (draft.targetMarket) {
      try {
        const markets = JSON.parse(draft.targetMarket);
        markets.forEach(m => {
          const chip = document.querySelector(`#marketChips .chip[data-value="${m}"]`);
          if (chip) chip.classList.add('selected');
        });
        document.getElementById('targetMarketInput').value = draft.targetMarket;
      } catch(e) {}
    }
    if (draft.complianceItems) {
      try {
        const items = JSON.parse(draft.complianceItems);
        items.forEach(m => {
          const chip = document.querySelector(`#complianceChips .chip[data-value="${m}"]`);
          if (chip) chip.classList.add('selected');
        });
        document.getElementById('complianceItemsInput').value = draft.complianceItems;
      } catch(e) {}
    }
    if (draft.priceRange) {
      const chip = document.querySelector(`#priceRangeChips .chip[data-value="${draft.priceRange}"]`);
      if (chip) {
        chip.classList.add('selected');
        document.getElementById('priceRangeInput').value = draft.priceRange;
      }
    }
  }
}

async function submitCreateProject(type) {
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  // 清除之前的错误提示
  document.querySelectorAll('.field-error').forEach(el => el.remove());

  const fd = new FormData(document.getElementById('createProjectForm'));
  const data = Object.fromEntries(fd.entries());
  let hasError = false;

  // 验证必填字段
  function addFieldError(fieldName, msg) {
    hasError = true;
    // 找到对应表单组，追加红色提示
    const formGroup = document.querySelector(`[name="${fieldName}"]`)?.closest('.form-group');
    if (!formGroup) { alert(msg); return; }
    const old = formGroup.querySelector('.field-error');
    if (old) old.remove();
    const err = document.createElement('div');
    err.className = 'field-error';
    err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
    err.textContent = '❌ ' + msg;
    formGroup.appendChild(err);
    // 高亮输入框
    const input = formGroup.querySelector('.form-input, .form-select, .form-textarea');
    if (input) input.style.borderColor = 'var(--danger)';
  }

  function clearFieldHighlight() {
    document.querySelectorAll('.form-input, .form-select, .form-textarea').forEach(el => {
      el.style.borderColor = '';
    });
  }
  clearFieldHighlight();

  // 需求方（渠道定制单）
  if (type === 'channel_custom' && !data.salesId) addFieldError('salesId', '请选择需求方（销售）');

  // 产品企划（必选）
  if (!data.plannerId) addFieldError('plannerId', '请选择产品企划');

  // 产品名称（两类项目均必填）
  if (!data.productName || !data.productName.trim()) addFieldError('productName', '请填写产品名称');

  // 产品类目
  const category = data.productCategory || '';
  if (!category) {
    addFieldError('productCategory', '请选择产品类目');
  } else if (category === '其他') {
    const note = data.productCategoryNote || '';
    if (!note.trim()) {
      addFieldError('productCategoryNote', '请补充其他类目的具体说明');
    }
  }

  // 参考零售价
  if (!data.priceRange) {
    addFieldError('priceRange', '请选择参考零售价');
  }

  // 目标市场
  const marketVal = data.targetMarket || '';
  if (!marketVal || marketVal === '[]') {
    addFieldError('targetMarket', '请选择目标市场');
  }

  // 要求完成时间
  if (!data.deadline) addFieldError('deadline', '请填写要求完成时间');

  // 验证日期格式和不能早于今天
  if (data.deadline) {
    const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
    if (!dateRegex.test(data.deadline) || isNaN(new Date(data.deadline).getTime())) {
      addFieldError('deadline', '日期格式不正确，请使用 yyyy-mm-dd（如：2026-07-15）');
    } else {
      const parts = data.deadline.split('-');
      const m = parseInt(parts[1]), day = parseInt(parts[2]);
      if (m < 1 || m > 12 || day < 1 || day > 31) {
        addFieldError('deadline', '日期超出有效范围（月份 1-12，日期 1-31）');
      } else {
        // 禁止选择早于今天的日期
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const selected = new Date(data.deadline);
        if (selected < today) {
          addFieldError('deadline', '要求完成时间不能早于今天');
        }
      }
    }
  }

  // 产品要求
  if (!data.productRequirements) addFieldError('productRequirements', '请填写产品要求');

  if (hasError) return;

  data.type = type;
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;

  // 组装参考图JSON（改用url引用，不再传base64）
  const refImgs = EMIE.projectState.createRefImages.map(img => ({ name: img.name, url: img.url, size: img.size, storedName: img.storedName }));
  data.referenceImagesJson = JSON.stringify(refImgs);

  // 组装附件JSON
  const atts = EMIE.projectState.createAttachments.map(a => ({ name: a.name, url: a.url, size: a.size, storedName: a.storedName }));
  data.attachmentsJson = JSON.stringify(atts);

  // 处理未指定的字段
  if (!data.plannerId) data.plannerId = '';
  if (!data.salesId) {
    data.salesId = EMIE.state.currentRole === 'sales' ? EMIE.state.currentUserId : '';
  }

  try {
    await apiPost('/projects', data);
    // 创建成功，清除草稿和缓存
    sessionStorage.removeItem('design_pm_create_draft');
    EMIE.state.cache.orders = []; // 清除项目列表缓存
    closeM('createProjectModal', true); // force=true 跳过保存提示
    // 创建渠道定制项目后跳转到渠道列表视图
    if (type === 'channel_custom') {
      EMIE.state.currentView = 'channel';
    }
    render();
  } catch (e) {
    alert('创建失败: ' + e.message);
  }
}

// 日期字段内联错误提示
function showDateError(inputName, msg) {
  // 找到日期选择器容器，在下方插入红色提示
  const datePicker = document.querySelector(`.date-picker input[name="${inputName}"]`)?.closest('.date-picker');
  if (!datePicker) { alert(msg); return; }
  // 移除旧错误
  const old = datePicker.parentElement.querySelector('.field-error');
  if (old) old.remove();
  const err = document.createElement('div');
  err.className = 'field-error';
  err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
  err.textContent = '❌ ' + msg;
  datePicker.parentElement.appendChild(err);
}

// 页面关闭/刷新时清除草稿
window.addEventListener('beforeunload', function() {
  sessionStorage.removeItem('design_pm_create_draft');
});


EMIE.registerActions({
  onCategoryChange,
  toggleMarket,
  toggleCompliance,
  togglePriceRange,
  openCreateProject,
  submitCreateProject,
  showDateError,
  switchAssigneeType,
  switchEditAssigneeType,
});

EMIE.registerModule('projectForm', {
  onCategoryChange,
  toggleMarket,
  toggleCompliance,
  togglePriceRange,
  switchAssigneeType,
  switchEditAssigneeType,
  openCreateProject,
  submitCreateProject,
});
