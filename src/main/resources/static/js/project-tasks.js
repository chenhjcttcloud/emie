const EMIE = window.EMIE;
const REFERENCE_FILE_ACCEPT = EMIE.fileAccept?.reference || 'image/*';
const ATTACHMENT_FILE_ACCEPT = EMIE.fileAccept?.attachment
  || '.ai,.step,.stp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.zip,.rar,.7z,image/*';
const roleLabel = (...args) => EMIE.actions.roleLabel(...args);
const getCurrentUserName = (...args) => EMIE.actions.getCurrentUserName(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const tryOpenModal = (...args) => EMIE.actions.tryOpenModal(...args);
const doneOpenModal = (...args) => EMIE.actions.doneOpenModal(...args);
const showActionError = (...args) => EMIE.actions.showActionError(...args);
const submitGuard = (...args) => EMIE.actions.submitGuard(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const renderDatePicker = (...args) => EMIE.actions.renderDatePicker(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const refreshAfterMutation = (...args) => EMIE.actions.refreshAfterMutation(...args);
const renderProjectDetailContent = (...args) => EMIE.actions.renderProjectDetailContent(...args);
const renderProjectActions = (...args) => EMIE.actions.renderProjectActions(...args);
const switchAssigneeType = (...args) => EMIE.actions.switchAssigneeType(...args);
const switchEditAssigneeType = (...args) => EMIE.actions.switchEditAssigneeType(...args);
const handleFileUpload = (...args) => EMIE.actions.handleFileUpload(...args);
const renderFileList = (...args) => EMIE.actions.renderFileList(...args);
const handleDeliverImages = (...args) => EMIE.actions.handleDeliverImages(...args);
const handleDeliverAttachments = (...args) => EMIE.actions.handleDeliverAttachments(...args);

const POINT_DIFFICULTIES = [
  { code: 'STANDARD', label: '标准', description: '常规工作量与复杂度' },
  { code: 'COMPLEX', label: '复杂', description: '跨模块或高复杂度任务' },
  { code: 'MAJOR', label: '重大', description: '重大项目或高影响任务' },
];

function enabledPointRules(rules) {
  return (Array.isArray(rules) ? rules : []).filter(rule => rule.enabled !== false);
}

function renderPointRuleOptions(rules, selectedCode) {
  const active = enabledPointRules(rules);
  const selected = String(selectedCode || '');
  const missingSelected = selected && !active.some(rule => String(rule.ruleCode || '') === selected)
    ? `<option value="${escHtml(selected)}" selected>${escHtml(selected)}（已停用，仅保留当前任务）</option>` : '';
  const groups = new Map();
  active.forEach(rule => { const key = String(rule.category || '其他').toUpperCase(); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(rule); });
  const order = { A: 1, B: 2, E: 3, S: 4, 其他: 9 };
  return `<option value="" ${selected ? '' : 'selected'} disabled>请选择积分规则</option>` + missingSelected + [...groups.entries()].sort((a, b) => (order[a[0]] || 8) - (order[b[0]] || 8)).map(([category, items]) => `<optgroup label="${escHtml(category)}">${items.sort((a, b) => { const na = Number(String(a.ruleCode || '').match(/\d+/)?.[0] || 0); const nb = Number(String(b.ruleCode || '').match(/\d+/)?.[0] || 0); return na - nb; }).map(rule => `<option value="${escHtml(rule.ruleCode || '')}" ${String(rule.ruleCode || '') === selected ? 'selected' : ''}>${escHtml(rule.ruleCode || '')}（${Number(rule.points || 0)} 分）${rule.description ? ' · ' + escHtml(rule.description) : ''}</option>`).join('')}</optgroup>`).join('');
}

function pointRuleCategoryHint(category) {
  return ({ A: '常规设计执行类任务', B: '复杂/重点设计任务', E: '简单辅助类任务', S: '特殊或专项任务' }[String(category || '').toUpperCase()] || '按管理员配置的积分规则执行');
}

function renderDifficultyOptions(difficulties, selectedCode) {
  const configured = (Array.isArray(difficulties) ? difficulties : []).filter(item => item.enabled !== false);
  const items = configured.length ? configured.map(item => ({ code: item.difficultyCode, label: item.difficultyCode, description: `${item.description || ''} ×${Number(item.multiplier || 1)}` })) : POINT_DIFFICULTIES;
  return items.map(item => `<option value="${item.code}" ${item.code === String(selectedCode || 'STANDARD').toUpperCase() ? 'selected' : ''}>${escHtml(item.label)} · ${escHtml(item.description)}</option>`).join('');
}

// ==================== 添加 / 编辑子任务 ====================

function handleSubTaskRefImages(input) {
  handleFileUpload(input, EMIE.projectState.subTaskRefImages, 6, '参考图片', true);
}
function handleSubTaskAttachments(input) {
  handleFileUpload(input, EMIE.projectState.subTaskAttachments, 9, '附件', false);
}

async function addSubTask(pid) {
  if (document.getElementById('addSubTaskModal')) return;
  if (!EMIE.actions.hasPermission('subtask.create')) {
    alert('当前账号没有新建子任务的权限');
    return;
  }
  // 清理可能残留的其他弹窗，避免遮挡
  document.querySelectorAll('.modal-overlay').forEach(el => {
    if (el.id !== 'projectDetailModal' && el.id !== 'addSubTaskModal') el.remove();
  });
  EMIE.projectState.subTaskRefImages = [];
  EMIE.projectState.subTaskAttachments = [];
  let pointRules = [];
  let pointDifficulties = [];
  try {
    [pointRules, pointDifficulties] = await Promise.all([apiGet('/points/rules'), apiGet('/points/difficulties')]);
    pointRules = enabledPointRules(pointRules);
  } catch (error) {
    alert('积分规则加载失败：' + (error.message || '请稍后重试'));
    return;
  }
  if (!pointRules.length) {
    alert('当前没有可用的积分规则，请联系管理员启用后再创建子任务');
    return;
  }

  const designerOpts = `<option value="">请选择设计师</option>` +
    EMIE.state.users.designer.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('');
  const supplychainOpts = EMIE.state.users.supplychain && EMIE.state.users.supplychain.length
    ? `<option value="">请选择供应链</option>` +
    EMIE.state.users.supplychain.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
    : `<option value="">暂无供应链人员</option>`;
  const plannerOpts = EMIE.state.users.planner && EMIE.state.users.planner.length
    ? `<option value="">请选择企划</option>` +
    EMIE.state.users.planner.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
    : `<option value="">暂无企划人员</option>`;
  const salesOpts = EMIE.state.users.sales && EMIE.state.users.sales.length
    ? `<option value="">请选择销售</option>` +
      EMIE.state.users.sales.map(u => `<option value="${u.userId}">${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
    : `<option value="">暂无销售人员</option>`;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'addSubTaskModal';
  modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('addSubTaskModal')">✕</button>
    <div class="modal modal-lg">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">➕ 添加子任务</div></div></div>
      <div class="modal-body">
        <form id="addSubTaskForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 子任务名称</label><input type="text" class="form-input" name="name" required placeholder="如：首页Banner设计、详情页布局..." data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''"></div>
          <div class="form-row">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 所属阶段</label>
              <select class="form-select" name="workflowStage" required>
                <option value="">请选择子任务所属阶段</option>
                <option value="design">设计</option>
                <option value="design_review">设计送审</option>
                <option value="three_d_review">3D送审</option>
                <option value="sample_review">打样送审</option>
                <option value="promotion">产品宣发</option>
                <option value="bulk">大货</option>
              </select>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划要求完成时间</label>${renderDatePicker('plannedDate', {required:true})}</div>
          </div>
          <div class="form-row">
          </div>
          <div class="form-row">
            <div class="form-group"><label class="form-label">积分规则（未启用时可留空）</label>
              <select class="form-select" name="pointRuleCode"><option value="">暂不设置积分规则</option>${renderPointRuleOptions(pointRules, '').replace('<option value="" selected disabled>请选择积分规则</option>', '')}</select>
              <div style="font-size:12px;color:var(--gray-500);margin-top:5px;">A：常规设计；B：复杂/重点设计；E：简单辅助；S：特殊专项。创建时锁定基础分，后续调整规则不会影响该任务。</div>
            </div>
            <div class="form-group"><label class="form-label">难度档位（未启用时可留空）</label>
              <select class="form-select" name="difficultyCode"><option value="" selected>暂不设置难度</option>${renderDifficultyOptions(pointDifficulties, '').replace(/ selected/g, '')}</select>
              <div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务开始执行后不可修改。</div>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
            <div style="display:flex;gap:16px;">
              <label class="checkbox-item checked" style="cursor:pointer;" data-emie-onclick="switchAssigneeType('add', 'designer', this)">
                <input type="radio" name="assigneeRole" value="designer" checked data-emie-onchange="switchAssigneeType('add', 'designer')" style="display:none;"> 👨‍🎨 设计师
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-onclick="switchAssigneeType('add', 'supplychain', this)">
                <input type="radio" name="assigneeRole" value="supplychain" data-emie-onchange="switchAssigneeType('add', 'supplychain')" style="display:none;"> 🛒 供应链
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-onclick="switchAssigneeType('add', 'planner', this)">
                <input type="radio" name="assigneeRole" value="planner" data-emie-onchange="switchAssigneeType('add', 'planner')" style="display:none;"> 📋 企划
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-onclick="switchAssigneeType('add', 'sales', this)">
                <input type="radio" name="assigneeRole" value="sales" data-emie-onchange="switchAssigneeType('add', 'sales')" style="display:none;"> 💼 销售
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-onclick="switchAssigneeType('add', 'promotion', this)">
                <input type="radio" name="assigneeRole" value="promotion" data-emie-onchange="switchAssigneeType('add', 'promotion')" style="display:none;"> 📣 产品推广
              </label>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 指派子任务负责人</label>
            <div id="addSubTaskAssignmentMode" style="display:flex;gap:12px;margin-bottom:10px;">
              <label class="checkbox-item checked"><input type="radio" name="assignmentMode" value="direct" checked data-emie-onchange="toggleSubTaskMarketMode(false)"> 指定设计师</label>
              <label class="checkbox-item"><input type="radio" name="assignmentMode" value="market" data-emie-onchange="toggleSubTaskMarketMode(true)"> 发布到接单市场</label>
            </div>
            <select class="form-select" name="designerId" id="addSubTaskDesignerId" data-emie-onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''">${designerOpts}</select>
            <div id="addSubTaskMarketHint" style="display:none;margin-top:8px;font-size:12px;color:var(--gray-500);">发布后所有设计师均可查看，先抢先得。</div>
            <input type="hidden" name="assigneeRole" id="addSubTaskAssigneeRole" value="designer">
          </div>
          <div class="form-group"><label class="form-label">指派/立项说明</label><input class="form-input" name="assignmentReason" maxlength="500" placeholder="说明指派原因；自主提案请填写立项依据"></div>
          <div class="form-group"><label class="form-label">细节要求说明</label><textarea class="form-textarea" name="details" placeholder="子任务的具体要求说明..." data-emie-oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''"></textarea></div>
        </form>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片（可选）</div>
          <div class="upload-area" data-emie-onclick="document.getElementById('subTaskRefImageInput').click()">
            <div>📁 拖拽图片到此处，或点击选择图片</div>
            <input type="file" id="subTaskRefImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleSubTaskRefImages(this)">
          </div>
          <div class="file-list" id="createRefImageList"></div>
        </div>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
          <div class="upload-area" data-emie-onclick="document.getElementById('subTaskAttachmentInput').click()">
            <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="subTaskAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleSubTaskAttachments(this)">
          </div>
          <div class="file-list" id="createAttachmentList"></div>
        </div>
        <div style="margin-top:8px;padding:10px;background:var(--warning-light);border-radius:8px;font-size:12px;color:#92400E;">
          💡 提示：可多次添加子任务。所有子任务完成后，项目才算完成。
        </div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('addSubTaskModal')">取消</button><button class="btn btn-outline" data-emie-onclick="saveSubTaskDraft('${pid}')">保存草稿</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitAddSubTask('${pid}'))">确认添加</button></div>
    </div>`;
  document.body.appendChild(modal);
  enhanceDateInputs(modal);
  try {
    const draft = JSON.parse(sessionStorage.getItem(`design_pm_subtask_draft_${pid}`) || 'null');
    if (draft) {
      Object.entries(draft).forEach(([name, value]) => {
        const fields = [...document.querySelectorAll(`#addSubTaskForm [name="${name}"]`)];
        if (fields[0]?.type === 'radio') {
          fields.forEach(field => { field.checked = field.value === value; });
        } else if (fields[0] && typeof value === 'string') {
          fields[0].value = value;
        }
      });
      const market = document.querySelector('#addSubTaskForm [name="assignmentMode"]:checked')?.value === 'market';
      toggleSubTaskMarketMode(market);
    }
  } catch (e) { console.warn('恢复子任务草稿失败', e); }
}

function toggleSubTaskMarketMode(market) {
  const form = document.getElementById('addSubTaskForm');
  const select = document.getElementById('addSubTaskDesignerId');
  const hint = document.getElementById('addSubTaskMarketHint');
  if (form) form.dataset.assignmentMode = market ? 'market' : 'direct';
  if (select) {
    select.disabled = market;
    select.style.display = market ? 'none' : '';
    if (market) select.value = '';
  }
  if (hint) hint.style.display = market ? '' : 'none';
  document.querySelectorAll('#addSubTaskAssignmentMode .checkbox-item').forEach(label => {
    label.classList.toggle('checked', label.querySelector('input')?.checked === true);
  });
}

function saveSubTaskDraft(pid) {
  const form = document.getElementById('addSubTaskForm');
  if (!form || !pid) return;
  const draft = Object.fromEntries(new FormData(form).entries());
  sessionStorage.setItem(`design_pm_subtask_draft_${pid}`, JSON.stringify(draft));
  closeM('addSubTaskModal', true);
  showActionError('子任务草稿已保存');
}

async function submitAddSubTask(pid) {
  if (!pid) { alert('项目ID无效'); return; }
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  // 清除之前错误
  document.querySelectorAll('#addSubTaskForm .field-error').forEach(el => el.remove());
  document.querySelectorAll('#addSubTaskForm .form-input, #addSubTaskForm .form-select, #addSubTaskForm .form-textarea').forEach(el => el.style.borderColor = '');

  function showError(name, msg) {
    const input = document.querySelector(`#addSubTaskForm [name="${name}"]`);
    if (!input) return;
    const group = input.closest('.form-group');
    if (!group) return;
    const err = document.createElement('div');
    err.className = 'field-error';
    err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
    err.textContent = '❌ ' + msg;
    group.appendChild(err);
    input.style.borderColor = 'var(--danger)';
  }

  const fd = new FormData(document.getElementById('addSubTaskForm'));
  const data = Object.fromEntries(fd.entries());
  // 直接从当前选中的 radio 读取，避免事件代理或同名字段导致模式丢失。
  const form = document.getElementById('addSubTaskForm');
  data.publishToMarket = form?.dataset.assignmentMode === 'market'
    || document.querySelector('#addSubTaskForm [name="assignmentMode"]:checked')?.value === 'market';
  delete data.assignmentMode;
  data.requiredSkillTags = JSON.stringify(String(data.requiredSkillTagsText || '').split(/[,，]/).map(value => value.trim()).filter(Boolean));
  delete data.requiredSkillTagsText;
  try {
    data.collaboratorAllocations = JSON.stringify(String(data.collaboratorAllocationsText || '').split(/[,，]/).map(item => item.trim()).filter(Boolean).map(item => {
      const [userId, ratio] = item.split(/[:：]/); if (!userId || !ratio) throw new Error();
      return { userId: userId.trim(), ratio: Number(ratio) };
    }));
  } catch (_) { showError('collaboratorAllocationsText', '格式应为 用户ID:比例，多个用逗号分隔'); return; }
  delete data.collaboratorAllocationsText;
  data.selfInitiated = data.selfInitiated === 'true';
  let hasErr = false;

  if (!data.name) { showError('name', '请填写子任务名称'); hasErr = true; }
  if (!data.workflowStage) { showError('workflowStage', '请选择子任务所属阶段'); hasErr = true; }
  if (!data.plannedDate) { showError('plannedDate', '请选择计划完成时间'); hasErr = true; }
  else if (!/^\d{4}-\d{2}-\d{2}$/.test(data.plannedDate) || isNaN(new Date(data.plannedDate).getTime())) {
    showError('plannedDate', '日期格式不正确（yyyy-mm-dd）');
    hasErr = true;
  } else {
    const parts = data.plannedDate.split('-');
    const m = parseInt(parts[1]), day = parseInt(parts[2]);
    if (m < 1 || m > 12 || day < 1 || day > 31) {
      showError('plannedDate', '日期超出有效范围');
      hasErr = true;
    } else {
      // 禁止选择早于今天的日期
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const selected = new Date(data.plannedDate);
      if (selected < today) {
        showError('plannedDate', '计划完成时间不能早于今天');
        hasErr = true;
      }
    }
  }

  // 子任务负责人：未指定可以提交，但"请选择子任务负责人"不允许
  const designerSel = document.querySelector('#addSubTaskForm [name="designerId"]');
  if (!data.publishToMarket && designerSel && designerSel.selectedIndex === 0) {
    showError('designerId', '请选择子任务负责人');
    hasErr = true;
  }
  if (hasErr) return;

  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  // 始终提交图片和附件列表
  data.referenceImagesJson = JSON.stringify(EMIE.projectState.subTaskRefImages.map(img => ({name: img.name, url: img.url, size: img.size, storedName: img.storedName})));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.subTaskAttachments.map(a => ({name: a.name, url: a.url, size: a.size, storedName: a.storedName})));

  try {
    const detail = await apiPost(`/projects/${pid}/tasks`, data);
    sessionStorage.removeItem(`design_pm_subtask_draft_${pid}`);
    closeM('addSubTaskModal');
    const detailBody = document.querySelector('#projectDetailModal .modal-body');
    const detailActions = document.getElementById('detailActions');
    if (detailBody) detailBody.innerHTML = renderProjectDetailContent(detail);
    if (detailActions) detailActions.innerHTML = renderProjectActions(detail);
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('添加失败: ' + (e.message || '未知错误'));
  }
}

function editTask(pid, tid) {
  if (!tryOpenModal('editTaskModal')) return;
  Promise.all([apiGet(`/projects/${pid}`), apiGet('/points/rules'), apiGet('/points/difficulties')]).then(([detail, rules, difficulties]) => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) { doneOpenModal('editTaskModal'); return; }
    let requiredSkillTagsText = '';
    try { requiredSkillTagsText = (JSON.parse(task.requiredSkillTagsJson || '[]') || []).join('、'); } catch (e) {}
    let collaboratorAllocationsText = '';
    try { collaboratorAllocationsText = (JSON.parse(task.collaboratorAllocationsJson || '[]') || []).map(item => `${item.userId}:${item.ratio}`).join('、'); } catch (e) {}
    // 加载现有图片和附件
    EMIE.projectState.editTaskRefImages = [];
    EMIE.projectState.editTaskAttachments = [];
    if (task.referenceImagesJson) {
      try { EMIE.projectState.editTaskRefImages = JSON.parse(task.referenceImagesJson); } catch(e) {}
    }
    if (task.attachmentsJson) {
      try { EMIE.projectState.editTaskAttachments = JSON.parse(task.attachmentsJson); } catch(e) {}
    }

    const designerOpts = task.assigneeRole === 'promotion'
      ? (EMIE.state.users.promotion && EMIE.state.users.promotion.length
          ? '<option value="">请选择产品推广</option>' +
            EMIE.state.users.promotion.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
          : '<option value="">暂无产品推广人员</option>')
      : task.assigneeRole === 'planner'
      ? (EMIE.state.users.planner && EMIE.state.users.planner.length
          ? '<option value="">请选择企划</option>' +
            EMIE.state.users.planner.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
          : '<option value="">暂无企划人员</option>')
      : task.assigneeRole === 'sales'
        ? (EMIE.state.users.sales && EMIE.state.users.sales.length
            ? '<option value="">请选择销售</option>' +
              EMIE.state.users.sales.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
            : '<option value="">暂无销售人员</option>')
      : task.assigneeRole === 'supplychain'
        ? (EMIE.state.users.supplychain && EMIE.state.users.supplychain.length
            ? '<option value="">请选择供应链</option>' +
              EMIE.state.users.supplychain.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('')
            : '<option value="">暂无供应链人员</option>')
        : '<option value="">请选择设计师</option>' +
          (EMIE.state.users.designer || []).map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${escHtml(displayText(u.name))} (${escHtml(displayText(u.title, '未设置职级'))})</option>`).join('');

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'editTaskModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('editTaskModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑子任务</div></div></div>
        <div class="modal-body">
          <form id="editTaskForm">
            <div class="form-group"><label class="form-label">子任务名称</label><input type="text" class="form-input" name="name" value="${escHtml(task.name)}"></div>
            <div class="form-row">
              <div class="form-group"><label class="form-label"><span class="required">*</span> 所属阶段</label>
                <select class="form-select" name="workflowStage" required>
                  <option value="design" ${!task.workflowStage || task.workflowStage === 'design' ? 'selected' : ''}>设计</option>
                  <option value="design_review" ${task.workflowStage === 'design_review' ? 'selected' : ''}>设计送审</option>
                  <option value="three_d_review" ${task.workflowStage === 'three_d_review' ? 'selected' : ''}>3D送审</option>
                  <option value="sample_review" ${task.workflowStage === 'sample_review' ? 'selected' : ''}>打样送审</option>
                  <option value="promotion" ${task.workflowStage === 'promotion' ? 'selected' : ''}>产品宣发</option>
                  <option value="bulk" ${task.workflowStage === 'bulk' ? 'selected' : ''}>大货</option>
                </select>
              </div>
              <div class="form-group"><label class="form-label">计划完成时间</label>${renderDatePicker('plannedDate', {value: task.plannedDate || ''})}</div>
            </div>
            <div class="form-row">
              <div class="form-group"><label class="form-label">合作积分分配</label><input class="form-input" name="collaboratorAllocationsText" value="${escHtml(collaboratorAllocationsText)}" placeholder="designer02:30" ${task.status !== 'pending' ? 'disabled' : ''}></div>
            </div>
            <div class="form-group"><label class="form-label">指派/立项说明</label><input class="form-input" name="assignmentReason" maxlength="500" value="${escHtml(task.assignmentReason || '')}"></div>
            <div class="form-group"><label class="form-label">接单能力要求（可选）</label><input class="form-input" name="requiredSkillTagsText" value="${escHtml(requiredSkillTagsText)}" placeholder="如：包装、3D、AI（使用逗号分隔）" ${task.status !== 'pending' ? 'disabled' : ''}></div>
            <div class="form-row">
              <div class="form-group"><label class="form-label">积分规则</label>
                <select class="form-select" name="pointRuleCode" ${task.status !== 'pending' ? 'disabled' : ''}>${renderPointRuleOptions(rules, task.pointRuleCode)}</select>
                ${task.status !== 'pending' ? '<div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务已开始，积分规则快照不可修改。</div>' : ''}
              </div>
              <div class="form-group"><label class="form-label">难度档位</label>
                <select class="form-select" name="difficultyCode" ${task.status !== 'pending' ? 'disabled' : ''}>${renderDifficultyOptions(difficulties, task.difficultyCode)}</select>
                ${task.status !== 'pending' ? '<div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务已开始，难度档位不可修改。</div>' : ''}
              </div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
              <div style="display:flex;gap:16px;">
                <label class="checkbox-item ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''}" style="cursor:pointer;" data-emie-onclick="switchEditAssigneeType('designer', this)">
                  <input type="radio" name="assigneeRole" value="designer" ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''} style="display:none;"> 👨‍🎨 设计师
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'supplychain' ? 'checked' : ''}" style="cursor:pointer;" data-emie-onclick="switchEditAssigneeType('supplychain', this)">
                  <input type="radio" name="assigneeRole" value="supplychain" ${task.assigneeRole === 'supplychain' ? 'checked' : ''} style="display:none;"> 🛒 供应链
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'planner' ? 'checked' : ''}" style="cursor:pointer;" data-emie-onclick="switchEditAssigneeType('planner', this)">
                  <input type="radio" name="assigneeRole" value="planner" ${task.assigneeRole === 'planner' ? 'checked' : ''} style="display:none;"> 📋 企划
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'sales' ? 'checked' : ''}" style="cursor:pointer;" data-emie-onclick="switchEditAssigneeType('sales', this)">
                  <input type="radio" name="assigneeRole" value="sales" ${task.assigneeRole === 'sales' ? 'checked' : ''} style="display:none;"> 💼 销售
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'promotion' ? 'checked' : ''}" style="cursor:pointer;" data-emie-onclick="switchEditAssigneeType('promotion', this)">
                  <input type="radio" name="assigneeRole" value="promotion" ${task.assigneeRole === 'promotion' ? 'checked' : ''} style="display:none;"> 📣 产品推广
                </label>
              </div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 指派子任务负责人</label>
              <select class="form-select" name="designerId" id="editSubTaskDesignerId">${designerOpts}</select>
              <input type="hidden" name="assigneeRole" id="editSubTaskAssigneeRole" value="${task.assigneeRole || 'designer'}">
            </div>
            <div class="form-group"><label class="form-label">细节要求说明</label><textarea class="form-textarea" name="details">${escHtml(task.details)}</textarea></div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片（可选）</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('editRefImageInput').click()">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="editRefImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleEditRefImages(this)">
            </div>
            <div class="file-list" id="createRefImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('editAttachmentInput').click()">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="editAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleEditAttachments(this)">
            </div>
            <div class="file-list" id="createAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('editTaskModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitEditTask('${pid}','${tid}'))">保存修改</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('editTaskModal');
    // 渲染现有文件
    if (EMIE.projectState.editTaskRefImages.length) renderFileList(EMIE.projectState.editTaskRefImages, '编辑参考图片');
    if (EMIE.projectState.editTaskAttachments.length) renderFileList(EMIE.projectState.editTaskAttachments, '编辑附件');
  }).catch(() => doneOpenModal('editTaskModal'));
}

function handleEditRefImages(input) { handleFileUpload(input, EMIE.projectState.editTaskRefImages, 6, '编辑参考图片', true); }
function handleEditAttachments(input) { handleFileUpload(input, EMIE.projectState.editTaskAttachments, 9, '编辑附件', false); }

async function submitEditTask(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('editTaskForm'));
  const data = Object.fromEntries(fd.entries());
  if (Object.prototype.hasOwnProperty.call(data, 'requiredSkillTagsText')) {
    data.requiredSkillTags = JSON.stringify(String(data.requiredSkillTagsText || '').split(/[,，、]/).map(value => value.trim()).filter(Boolean));
    delete data.requiredSkillTagsText;
  }
  if (Object.prototype.hasOwnProperty.call(data, 'collaboratorAllocationsText')) {
    try {
      data.collaboratorAllocations = JSON.stringify(String(data.collaboratorAllocationsText || '').split(/[,，、]/).map(item => item.trim()).filter(Boolean).map(item => {
        const [userId, ratio] = item.split(/[:：]/); if (!userId || !ratio) throw new Error();
        return { userId: userId.trim(), ratio: Number(ratio) };
      }));
    } catch (_) { alert('合作积分分配格式应为 用户ID:比例'); return; }
    delete data.collaboratorAllocationsText;
  }

  // 验证计划时间不能早于今天
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  if (data.plannedDate && /^\d{4}-\d{2}-\d{2}$/.test(data.plannedDate)) {
    const selected = new Date(data.plannedDate);
    if (selected < today) {
      alert('计划完成时间不能早于今天');
      return;
    }
  }

  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  // 始终提交当前图片和附件列表（包含已有的和新上传的）
  data.referenceImagesJson = JSON.stringify(EMIE.projectState.editTaskRefImages.map(img => ({name: img.name, url: img.url, size: img.size, storedName: img.storedName})));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.editTaskAttachments.map(a => ({name: a.name, url: a.url, size: a.size, storedName: a.storedName})));

  try {
    await apiPut(`/projects/${pid}/tasks/${tid}`, data);
    closeM('editTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('编辑失败: ' + e.message);
  }
}

async function deleteTask(pid, tid) {
  if (!confirm('确定要删除这个子任务吗？此操作不可恢复。')) return;
  try {
    await apiDelete(`/projects/${pid}/tasks/${tid}`);
    closeM('editTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('删除失败: ' + e.message);
  }
}

async function withdrawMarketTask(pid, tid) {
  if (!confirm('确认将该任务撤出接单市场？撤回后设计师将无法抢单。')) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/withdraw-market`, {});
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('撤回失败: ' + e.message);
  }
}

// ==================== 任务工作流 ====================
async function taskAccept(pid, tid, marketTask = null) {
  if (!tryOpenModal('taskAcceptModal')) return;
  try {
    // 接单市场只返回当前可接任务；设计师可能没有项目详情权限，不能为了打开确认框再请求项目详情。
    // 优先使用市场列表中的任务快照，旧入口未传快照时再兼容读取项目详情。
    let task = marketTask;
    if (!task) {
      const detail = await apiGet(`/projects/${pid}`);
      task = detail.tasks.find(t => t.id === tid);
    }
    if (!task) return;

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskAcceptModal';
    modal.innerHTML = `
      <button class="modal-close-float" data-emie-onclick="closeM('taskAcceptModal')">✕</button>
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✅ 接单：${task.name}</div></div></div>
        <div class="modal-body">
          ${marketTask ? '' : `<p style="margin-bottom:12px;color:var(--gray-500);">负责人：<strong>${task.designerName || '未指定'}</strong></p>`}
          <form id="taskAcceptForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划完成时间</label>${renderDatePicker('plannedDate', {required:true, value: task.plannedDate || ''})}</div>
          </form>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskAcceptModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitTaskAccept(${pid},${tid}))">确认接单</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskAcceptModal');
  } catch (e) {
    doneOpenModal('taskAcceptModal');
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskAccept(pid, tid) {
  const fd = new FormData(document.getElementById('taskAcceptForm'));
  const plannedDate = fd.get('plannedDate');
  if (!plannedDate) { alert('请选择计划完成时间'); return; }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(plannedDate) || isNaN(new Date(plannedDate).getTime())) {
    alert('日期格式不正确（yyyy-mm-dd）');
    return;
  }
  const today = new Date(); today.setHours(0, 0, 0, 0);
  if (new Date(plannedDate) < today) {
    alert('计划完成时间不能早于今天');
    return;
  }

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/accept`, {
      plannedDate,
      currentUser: getCurrentUserName(),
      currentRole: EMIE.state.currentRole,
      designerUserId: getCurrentUserId(),
    });
    closeM('taskAcceptModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    await refreshAfterMutation(pid);
    alert('操作失败: ' + e.message);
  }
}

async function taskDeliver(pid, tid) {
  if (!tryOpenModal('taskDeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    EMIE.projectState.deliverImages = [];
    EMIE.projectState.deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskDeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('taskDeliverModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 交付：${task.name}</div></div></div>
        <div class="modal-body">
          <form id="taskDeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required placeholder="描述交付的设计成果..." style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" data-emie-oninput="validateScoreInput(this)">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 交付参考图</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('deliverImageInput').click()">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="deliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverImages(this)">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('deliverAttachmentInput').click()">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="deliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverAttachments(this)">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskDeliverModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitTaskDeliver(${pid},${tid}))">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

// ===== 自评分数输入校验：1-100，整数 =====
const validateScoreInput = function(input) {
  let val = input.value.trim();
  if (val === '') { input.setCustomValidity(''); return; }
  const num = parseInt(val);
  if (isNaN(num) || num < 1 || num > 100) {
    input.setCustomValidity('请输入 1 ~ 100 之间的整数分数');
  } else {
    // 检查是否为整数（不允许小数）
    if (val.includes('.') || val.includes(',')) {
      input.setCustomValidity('不允许小数点，请输入整数');
    } else {
      input.setCustomValidity('');
    }
  }
  input.reportValidity();
};

async function submitTaskDeliver(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskDeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { alert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = EMIE.state.currentUserId;
  data.referenceImagesJson = JSON.stringify(EMIE.projectState.deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/deliver`, data);
    closeM('taskDeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

async function submitTaskReview(pid, tid) {
  if (!confirm('确认将该子任务送审吗？')) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/submit-review`, {
      currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, currentUserId: getCurrentUserId()
    });
    await refreshAfterMutation(pid);
  } catch (e) { alert('送审失败: ' + e.message); }
}

async function taskRedeliver(pid, tid) {
  if (!tryOpenModal('taskRedeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    EMIE.projectState.deliverImages = [];
    EMIE.projectState.deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskRedeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('taskRedeliverModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 重新交付：${task.name}</div></div></div>
        <div class="modal-body">
          ${task.reviewComments ? `<div class="review-box rejected" style="margin-bottom:16px;"><strong>驳回意见：</strong>${task.reviewComments}</div>` : ''}
          <form id="taskRedeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" data-emie-oninput="validateScoreInput(this)">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 交付参考图</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('deliverImageInput').click()">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="deliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverImages(this)">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('deliverAttachmentInput').click()">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="deliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverAttachments(this)">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskRedeliverModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitTaskRedeliver(${pid},${tid}))">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskRedeliverModal');
  } catch (e) {
    doneOpenModal('taskRedeliverModal');
    alert('加载失败: ' + e.message);
  }
}

async function taskConfirmRevision(pid, tid) {
  if (!confirm('确认开始修改该子任务吗？')) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/confirm-revision`, {
      currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, currentUserId: getCurrentUserId()
    });
    await refreshAfterMutation(pid);
  } catch (e) { alert('确认修改失败: ' + e.message); }
}

async function submitTaskRedeliver(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskRedeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { alert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = EMIE.state.currentUserId;
  data.referenceImagesJson = JSON.stringify(EMIE.projectState.deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/redeliver`, data);
    closeM('taskRedeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

async function taskCorrectDelivery(pid, tid) {
  if (!tryOpenModal('taskCorrectDeliveryModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    try { EMIE.projectState.deliverImages = JSON.parse(task.referenceImagesJson || '[]'); } catch (_) { EMIE.projectState.deliverImages = []; }
    try { EMIE.projectState.deliverAttachments = JSON.parse(task.attachmentsJson || '[]'); } catch (_) { EMIE.projectState.deliverAttachments = []; }
    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskCorrectDeliveryModal';
    modal.innerHTML = `
      <button class="modal-close-float" data-emie-onclick="closeM('taskCorrectDeliveryModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left">
          <div class="modal-title">📝 修正交付：${escHtml(task.name)}</div>
          <div style="font-size:12px;color:var(--gray-500);margin-top:4px;">将生成新的交付版本；已产生的审核结论会失效并重新进入审核。</div>
        </div></div>
        <div class="modal-body">
          <form id="taskCorrectDeliveryForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 本次修正说明</label><textarea class="form-textarea" name="changeSummary" required maxlength="500" placeholder="例如：补充渲染源文件，移除误传的旧版包装图"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required style="min-height:100px;">${escHtml(task.deliverables || '')}</textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label><input type="number" class="form-input" name="selfScore" required min="1" max="100" step="1" value="${task.selfScore || ''}" data-emie-oninput="validateScoreInput(this)" style="max-width:200px;"></div>
          </form>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label">🖼️ 当前交付参考图（可删除错误文件或补充文件）</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('correctDeliverImageInput').click()"><div>📁 点击补充图片或模型文件</div><input type="file" id="correctDeliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverImages(this)"></div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label">📎 当前交付附件（可删除错误文件或补充文件）</div>
            <div class="upload-area" data-emie-onclick="document.getElementById('correctDeliverAttachmentInput').click()"><div>📁 点击补充附件</div><input type="file" id="correctDeliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleDeliverAttachments(this)"></div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskCorrectDeliveryModal')">取消</button><button class="btn btn-warning" data-emie-onclick="submitGuard(this,()=>submitTaskCorrectDelivery(${pid},${tid}))">生成新版本并重新送审</button></div>
      </div>`;
    document.body.appendChild(modal);
    renderFileList(EMIE.projectState.deliverImages, '交付参考图');
    renderFileList(EMIE.projectState.deliverAttachments, '交付附件');
    doneOpenModal('taskCorrectDeliveryModal');
  } catch (e) {
    doneOpenModal('taskCorrectDeliveryModal');
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskCorrectDelivery(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const form = document.getElementById('taskCorrectDeliveryForm');
  if (!form?.reportValidity()) return;
  const data = Object.fromEntries(new FormData(form).entries());
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { alert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.actualDate = new Date().toISOString().split('T')[0];
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = getCurrentUserId();
  data.referenceImagesJson = JSON.stringify(EMIE.projectState.deliverImages);
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/correct-delivery`, data);
    closeM('taskCorrectDeliveryModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('修正交付失败: ' + e.message);
  }
}

// ==================== 验收 ====================
function taskApprove(pid, tid, projectType) {
  if (!tryOpenModal('taskApproveModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;

    const isChannel = projectType === 'channel_custom';
    const isSalesConfirm = EMIE.state.currentRole === 'sales';
    const isAdminConfirm = EMIE.state.currentRole === 'admin' && !isChannel;
    const needsScore = EMIE.state.currentRole === 'planner' || isSalesConfirm || isAdminConfirm;
    const title = isSalesConfirm
      ? '✅ 销售确认评分通过'
      : (isAdminConfirm ? '✅ 管理确认评分通过' : (isChannel ? '👍 企划确认评分通过' : '✅ 验收评分通过'));

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskApproveModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">${title}：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:12px;">${isSalesConfirm ? '销售确认该子任务通过并评分？' : (isAdminConfirm ? '管理确认该子任务通过并评分？' : (isChannel ? '企划确认该子任务通过并评分？之后需销售再次确认评分。' : '企划确认该子任务验收通过并评分？之后需管理再次确认。'))}</p>
          ${needsScore ? `
          <div class="form-group">
              <label class="form-label"><span class="required">*</span> 综合评分</label>
              <input type="number" class="form-input" id="approveScore" min="1" max="100" step="1" placeholder="1-100" required style="max-width:200px;text-align:center;">
              <div style="font-size:11px;color:var(--gray-400);margin-top:4px;">总分100分，填写1-100的整数</div>
            </div>` : ''}
          <div class="form-group"><label class="form-label">验收意见（可选）</label><textarea class="form-textarea" id="approveComments" placeholder="输入验收意见..."></textarea></div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskApproveModal')">取消</button><button class="btn btn-success" data-emie-onclick="submitGuard(this,()=>submitTaskApprove(${pid},${tid},'${projectType}'))">${needsScore ? '确认通过并评分' : '确认通过'}</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitTaskApprove(pid, tid) {
  const scoreVal = parseInt(document.getElementById('approveScore')?.value);
  const hasScoreFields = document.getElementById('approveScore') !== null;
  if (hasScoreFields) {
    if (isNaN(scoreVal) || scoreVal < 1 || scoreVal > 100) { alert('请输入有效的评分（1-100）'); return; }
  }
  const comments = document.getElementById('approveComments')?.value || '';

  const data = {
    comments: comments,
    score: hasScoreFields ? scoreVal : null,
    currentUser: getCurrentUserName(),
    currentRole: EMIE.state.currentRole,
  };

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/approve`, data);
    closeM('taskApproveModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + (e.message || '未知错误'));
  }
}


function taskReject(pid, tid) {
  if (document.getElementById('taskRejectModal')) return;
  EMIE.projectState.rejectionImages = [];
  EMIE.projectState.rejectionAttachments = [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'taskRejectModal';
  modal.innerHTML = `
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">↩️ 驳回修改</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 修改意见</label><textarea class="form-textarea" id="rejectComments" required placeholder="请详细说明修改意见..." style="min-height:100px;"></textarea></div>
        <div class="form-group"><label class="form-label"><span class="required">*</span> 要求完成时间</label><div class="date-picker" style="max-width:280px;"><input type="date" class="form-input" id="rejectDeadline" required min="${new Date().toISOString().slice(0, 10)}" aria-label="选择要求完成时间" autocomplete="off" style="width:100%;min-height:38px;cursor:pointer;"></div><div class="form-hint">设计师重新交付需在此日期前完成</div></div>
        <div class="form-group"><label class="form-label">参考图片（选填）</label><div class="upload-area" data-emie-onclick="document.getElementById('rejectImageInput').click()"><div>📁 拖拽图片到此处，或点击选择图片</div><input type="file" id="rejectImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleRejectImages(this)"></div><div class="file-list" id="rejectImageList"></div></div>
        <div class="form-group"><label class="form-label">附件（选填）</label><div class="upload-area" data-emie-onclick="document.getElementById('rejectAttachmentInput').click()"><div>📁 拖拽文件到此处，或点击选择文件</div><input type="file" id="rejectAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-onchange="handleRejectAttachments(this)"></div><div class="file-list" id="rejectAttachmentList"></div></div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskRejectModal')">取消</button><button class="btn btn-danger" data-emie-onclick="submitGuard(this,()=>submitTaskReject(${pid},${tid}))">确认驳回</button></div>
    </div>`;
  document.body.appendChild(modal);
  enhanceDateInputs(modal);
}

function handleRejectImages(input) {
  handleFileUpload(input, EMIE.projectState.rejectionImages, 6, '驳回参考图', true);
  setTimeout(() => renderFileList(EMIE.projectState.rejectionImages, '驳回参考图'), 0);
}

function handleRejectAttachments(input) {
  handleFileUpload(input, EMIE.projectState.rejectionAttachments, 5, '驳回附件', false);
  setTimeout(() => renderFileList(EMIE.projectState.rejectionAttachments, '驳回附件'), 0);
}

function updateRejectDeadlineLabel(input) {
  const label = document.querySelector('#rejectDeadlineButton span');
  if (label) label.textContent = input.value || '请选择日期';
}

async function submitTaskReject(pid, tid) {
  const comments = document.getElementById('rejectComments')?.value || '';
  const requiredCompletionDate = document.getElementById('rejectDeadline')?.value || '';
  if (!comments) { alert('请填写修改意见'); return; }
  if (!requiredCompletionDate) { alert('请选择要求完成时间'); return; }
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/reject`, {
      comments,
      requiredCompletionDate,
      rejectionReferenceImagesJson: JSON.stringify(EMIE.projectState.rejectionImages),
      rejectionAttachmentsJson: JSON.stringify(EMIE.projectState.rejectionAttachments),
      currentUser: getCurrentUserName(),
      currentRole: EMIE.state.currentRole,
    });
    closeM('taskRejectModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// ==================== 评分 ====================
function openScoring(pid, tid) {
  if (!tryOpenModal('scoringModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task || !task.scoringRecords) return;
    const myRecord = task.scoringRecords.find(sr => sr.role === EMIE.state.currentRole);
    if (!myRecord) { alert('您无需对此任务评分'); return; }

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'scoringModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><button class="modal-close" data-emie-onclick="closeM('scoringModal')">✕</button><div class="modal-header-left"><div class="modal-title">⭐ 评分：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:8px;color:var(--gray-500);">评分人：<strong>${roleLabel(EMIE.state.currentRole)}</strong>（${getCurrentUserName()}）</p>
          <p style="margin-bottom:16px;color:var(--gray-500);">请对 <strong>${task.name}</strong> 进行评分（1-100分）</p>
          <div>
            <div class="form-group"><label class="form-label">⭐ 综合评分</label><input type="number" class="form-input" id="scoreValue" min="1" max="100" step="1" placeholder="1-100" value="${myRecord.score ?? ''}" style="font-size:24px;text-align:center;max-width:200px;margin:0 auto;"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('scoringModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitGuard(this,()=>submitScoring(${pid},${tid}))">提交评分</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitScoring(pid, tid) {
  const score = parseInt(document.getElementById('scoreValue').value);
  if (isNaN(score) || score < 1 || score > 100) { alert('请输入有效的评分（1-100分）'); return; }

  const data = {
    role: EMIE.state.currentRole,
    score,
    currentUser: getCurrentUserName(),
    currentRole: EMIE.state.currentRole,
  };

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/score`, data);
    closeM('scoringModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('评分提交失败: ' + e.message);
  }
}


EMIE.registerActions({
  handleSubTaskRefImages,
  handleSubTaskAttachments,
  addSubTask,
  saveSubTaskDraft,
  submitAddSubTask,
  editTask,
  handleEditRefImages,
  handleEditAttachments,
  submitEditTask,
  deleteTask,
  withdrawMarketTask,
  taskAccept,
  submitTaskAccept,
  taskDeliver,
  submitTaskDeliver,
  submitTaskReview,
  taskRedeliver,
  taskConfirmRevision,
  taskCorrectDelivery,
  submitTaskCorrectDelivery,
  submitTaskRedeliver,
  taskApprove,
  submitTaskApprove,
  taskReject,
  handleRejectImages,
  handleRejectAttachments,
  submitTaskReject,
  openScoring,
  submitScoring,
  toggleSubTaskMarketMode,
  validateScoreInput,
});

EMIE.registerModule('projectTasks', {
  addSubTask,
  saveSubTaskDraft,
  submitAddSubTask,
  editTask,
  submitEditTask,
  deleteTask,
  withdrawMarketTask,
  handleSubTaskRefImages,
  handleSubTaskAttachments,
  handleEditRefImages,
  handleEditAttachments,
  taskAccept,
  submitTaskAccept,
  taskDeliver,
  submitTaskDeliver,
  submitTaskReview,
  taskRedeliver,
  taskConfirmRevision,
  taskCorrectDelivery,
  submitTaskCorrectDelivery,
  submitTaskRedeliver,
  taskApprove,
  submitTaskApprove,
  taskReject,
  handleRejectImages,
  handleRejectAttachments,
  submitTaskReject,
  openScoring,
  submitScoring,
  toggleSubTaskMarketMode,
  validateScoreInput,
});
