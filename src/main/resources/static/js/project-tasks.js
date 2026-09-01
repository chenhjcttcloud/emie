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
const clearSWRCache = (...args) => EMIE.actions.clearSWRCache(...args);
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
  // 素材广场采纳积分由采纳流程自动发放，不属于子任务积分；S1 内部建设规则已下线。
  const hiddenFromSubTasks = new Set(['S1', 'M1', 'M2', 'M3', 'MATERIAL_MARKET_LAUNCH', 'MATERIAL_MARKET_DESIGN_ADOPTION', 'MATERIAL_MARKET_DIRECT_ADOPTION']);
  return (Array.isArray(rules) ? rules : []).filter(rule => rule.enabled !== false && !hiddenFromSubTasks.has(String(rule.ruleCode || '').toUpperCase()));
}

function pointRuleDisplayName(code, description) {
  const labels = {
    TASK_APPROVED: '通用任务（验收完成）',
    A1: '包装整套设计', A2: '包装单项设计', A3: '包装修改 / 刀模 / 箱规', A4: '包装多语言版',
    A5: '详情页全套设计', A6: '详情页局部 / 改版', A7: '主图 / 单张卖点图', A8: '海报 / 立牌 / 单页',
    A9: '展会物料整套', A10: 'UI界面 / 灯珠图案 / 待机页', A11: 'AI生图 / 场景图 / 推广图',
    B1: '原创产品设计', B2: '外采产品IP化设计', B3: '新增SKU / 配色衍生', B4: '展会样品 / 客户定制产品',
    B5: '3D建模渲染出图', B6: '3D公仔建模 / 输出',
    E1: '送审文件 / 送审调整', E2: '打样文件输出', E3: '报价文件', E4: '工厂跟单调色 / 大货文件',
    S1: '内部建设（素材库 / 模板库 / 提示词库 / 竞品图库）',
  };
  return labels[String(code || '').toUpperCase()] || description || code;
}

function renderPointRuleOptions(rules, selectedCode) {
  const active = enabledPointRules(rules);
  const selected = String(selectedCode || '');
  const missingSelected = selected && !active.some(rule => String(rule.ruleCode || '') === selected)
    ? `<option value="${escHtml(selected)}" selected>${escHtml(selected)}（已停用，仅保留当前任务）</option>` : '';
  const groups = new Map();
  active.forEach(rule => { const key = String(rule.category || '其他').toUpperCase(); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(rule); });
  const order = { A: 1, B: 2, E: 3, S: 4, 其他: 9 };
  return `<option value="" ${selected ? '' : 'selected'}>不设置积分规则</option>` + missingSelected + [...groups.entries()].sort((a, b) => (order[a[0]] || 8) - (order[b[0]] || 8)).map(([category, items]) => `<optgroup label="${escHtml(category)}">${items.sort((a, b) => { const na = Number(String(a.ruleCode || '').match(/\d+/)?.[0] || 0); const nb = Number(String(b.ruleCode || '').match(/\d+/)?.[0] || 0); return na - nb; }).map(rule => { const code = String(rule.ruleCode || ''); const label = pointRuleDisplayName(code, rule.description); return `<option value="${escHtml(code)}" ${code === selected ? 'selected' : ''}>${escHtml(label)}（${Number(rule.points || 0)} 分）</option>`; }).join('')}</optgroup>`).join('');
}

function pointRuleCategoryHint(category) {
  return ({ A: '常规设计执行类任务', B: '复杂/重点设计任务', E: '简单辅助类任务', S: '特殊或专项任务' }[String(category || '').toUpperCase()] || '按管理员配置的积分规则执行');
}

function renderDifficultyOptions(difficulties, selectedCode) {
  const configured = (Array.isArray(difficulties) ? difficulties : []).filter(item => item.enabled !== false);
  const labels = { STANDARD: '标准任务', COMPLEX: '复杂任务', MAJOR: '重大任务' };
  const items = configured.length ? configured.map(item => ({ code: item.difficultyCode, label: labels[String(item.difficultyCode || '').toUpperCase()] || '其他任务', multiplier: Number(item.multiplier || 1) })) : POINT_DIFFICULTIES.map(item => ({ ...item, label: `${item.label}任务`, multiplier: 1 }));
  return items.map(item => `<option value="${escHtml(item.code)}" ${item.code === String(selectedCode || 'STANDARD').toUpperCase() ? 'selected' : ''}>${escHtml(item.label)} ×${item.multiplier} 积分</option>`).join('');
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
    window.EMIE.actions.showSystemAlert('当前账号没有新建子任务的权限');
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
    window.EMIE.actions.showSystemAlert('积分规则加载失败：' + (error.message || '请稍后重试'));
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
    <button class="modal-close-float" data-emie-action="click:task-add-close">✕</button>
    <div class="modal modal-lg">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">➕ 添加子任务</div></div></div>
      <div class="modal-body">
        <form id="addSubTaskForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 子任务名称</label><input type="text" class="form-input" name="name" required placeholder="如：首页Banner设计、详情页布局..." data-emie-action="input:task-clear-field-error"></div>
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
            <div class="form-group"><label class="form-label"><span class="required">*</span> 积分规则</label>
              <select class="form-select" name="pointRuleCode" required>${renderPointRuleOptions(pointRules, '')}</select>
              <div style="font-size:12px;color:var(--gray-500);margin-top:5px;">创建后将锁定规则快照并参与积分计算。</div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 难度档位</label>
              <select class="form-select" name="difficultyCode" required><option value="" selected>请选择难度档位</option>${renderDifficultyOptions(pointDifficulties, '').replace(/ selected/g, '')}</select>
              <div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务开始执行后不可修改。</div>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
            <div style="display:flex;gap:16px;">
              <label class="checkbox-item checked" style="cursor:pointer;" data-emie-action="click:task-add-assignee" data-assignee-role="designer">
                <input type="radio" name="assigneeRole" value="designer" checked style="display:none;"> 👨‍🎨 设计师
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-action="click:task-add-assignee" data-assignee-role="supplychain">
                <input type="radio" name="assigneeRole" value="supplychain" style="display:none;"> 🛒 供应链
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-action="click:task-add-assignee" data-assignee-role="planner">
                <input type="radio" name="assigneeRole" value="planner" style="display:none;"> 📋 企划
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-action="click:task-add-assignee" data-assignee-role="sales">
                <input type="radio" name="assigneeRole" value="sales" style="display:none;"> 💼 销售
              </label>
              <label class="checkbox-item" style="cursor:pointer;" data-emie-action="click:task-add-assignee" data-assignee-role="promotion">
                <input type="radio" name="assigneeRole" value="promotion" style="display:none;"> 📣 产品推广
              </label>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 指派子任务负责人</label>
            <div id="addSubTaskAssignmentMode" style="display:flex;gap:12px;margin-bottom:10px;">
              <label class="checkbox-item checked"><input type="radio" name="assignmentMode" value="direct" checked data-emie-action="change:task-assignment-mode" data-market-mode="false"> 指定设计师</label>
              <label class="checkbox-item"><input type="radio" name="assignmentMode" value="market" data-emie-action="change:task-assignment-mode" data-market-mode="true"> 发布到接单市场</label>
            </div>
            <select class="form-select" name="designerId" id="addSubTaskDesignerId" data-emie-action="change:task-clear-field-error">${designerOpts}</select>
            <div id="addSubTaskMarketHint" style="display:none;margin-top:8px;font-size:12px;color:var(--gray-500);">发布后所有设计师均可查看，先抢先得。</div>
            <input type="hidden" name="assigneeRole" id="addSubTaskAssigneeRole" value="designer">
          </div>
          <div class="form-group"><label class="form-label">细节要求说明</label><textarea class="form-textarea" name="details" placeholder="子任务的具体要求说明..." data-emie-action="input:task-clear-field-error"></textarea></div>
        </form>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片（可选）</div>
          <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="subTaskRefImageInput">
            <div>📁 拖拽图片到此处，或点击选择图片</div>
            <input type="file" id="subTaskRefImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-subtask-ref-images">
          </div>
          <div class="file-list" id="createRefImageList"></div>
        </div>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
          <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="subTaskAttachmentInput">
            <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="subTaskAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-subtask-attachments">
          </div>
          <div class="file-list" id="createAttachmentList"></div>
        </div>
        <div style="margin-top:8px;padding:10px;background:var(--warning-light);border-radius:8px;font-size:12px;color:#92400E;">
          💡 提示：可多次添加子任务。所有子任务完成后，项目才算完成。
        </div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-add-close">取消</button><button class="btn btn-outline" data-emie-action="click:task-save-draft" data-project-id="${pid}">保存草稿</button><button class="btn btn-primary" data-emie-action="click:task-submit-add" data-project-id="${pid}">确认添加</button></div>
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
  if (!pid) { window.EMIE.actions.showSystemAlert('项目ID无效'); return; }
  if (EMIE.projectState.uploadingCount > 0) { window.EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); return; }
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
  let hasErr = false;

  if (!data.name) { showError('name', '请填写子任务名称'); hasErr = true; }
  if (!data.workflowStage) { showError('workflowStage', '请选择子任务所属阶段'); hasErr = true; }
  if (!data.pointRuleCode) { showError('pointRuleCode', '请选择积分规则'); hasErr = true; }
  if (!data.difficultyCode) { showError('difficultyCode', '请选择难度档位'); hasErr = true; }
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
    window.EMIE.actions.showSystemAlert('添加失败: ' + (e.message || '未知错误'));
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
    <button class="modal-close-float" data-emie-action="click:task-edit-close">✕</button>
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
            <input type="hidden" name="requiredSkillTagsText" value="">
            <div class="form-row">
              <div class="form-group"><label class="form-label">积分规则（可选）</label>
                <select class="form-select" name="pointRuleCode" ${task.status !== 'pending' ? 'disabled' : ''}>${renderPointRuleOptions(rules, task.pointRuleCode)}</select>
                ${task.status !== 'pending' ? '<div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务已开始，积分规则快照不可修改。</div>' : '<div style="font-size:12px;color:var(--gray-500);margin-top:5px;">留空则不计积分；选择规则后，任务开始后不可修改。</div>'}
              </div>
              <div class="form-group"><label class="form-label">难度档位</label>
                <select class="form-select" name="difficultyCode" ${task.status !== 'pending' ? 'disabled' : ''}>${renderDifficultyOptions(difficulties, task.difficultyCode)}</select>
                ${task.status !== 'pending' ? '<div style="font-size:12px;color:var(--gray-500);margin-top:5px;">任务已开始，难度档位不可修改。</div>' : ''}
              </div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
              <div style="display:flex;gap:16px;">
                <label class="checkbox-item ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''}" style="cursor:pointer;" data-emie-action="click:task-edit-assignee" data-assignee-role="designer">
                  <input type="radio" name="assigneeRole" value="designer" ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''} style="display:none;"> 👨‍🎨 设计师
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'supplychain' ? 'checked' : ''}" style="cursor:pointer;" data-emie-action="click:task-edit-assignee" data-assignee-role="supplychain">
                  <input type="radio" name="assigneeRole" value="supplychain" ${task.assigneeRole === 'supplychain' ? 'checked' : ''} style="display:none;"> 🛒 供应链
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'planner' ? 'checked' : ''}" style="cursor:pointer;" data-emie-action="click:task-edit-assignee" data-assignee-role="planner">
                  <input type="radio" name="assigneeRole" value="planner" ${task.assigneeRole === 'planner' ? 'checked' : ''} style="display:none;"> 📋 企划
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'sales' ? 'checked' : ''}" style="cursor:pointer;" data-emie-action="click:task-edit-assignee" data-assignee-role="sales">
                  <input type="radio" name="assigneeRole" value="sales" ${task.assigneeRole === 'sales' ? 'checked' : ''} style="display:none;"> 💼 销售
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'promotion' ? 'checked' : ''}" style="cursor:pointer;" data-emie-action="click:task-edit-assignee" data-assignee-role="promotion">
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
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="editRefImageInput">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="editRefImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-edit-ref-images">
            </div>
            <div class="file-list" id="createRefImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="editAttachmentInput">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="editAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-edit-attachments">
            </div>
            <div class="file-list" id="createAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-edit-close">取消</button><button class="btn btn-primary" data-emie-action="click:task-submit-edit" data-project-id="${pid}" data-task-id="${tid}">保存修改</button></div>
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
  if (EMIE.projectState.uploadingCount > 0) { window.EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); return; }
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
    } catch (_) { window.EMIE.actions.showSystemAlert('合作积分分配格式应为 用户ID:比例'); return; }
    delete data.collaboratorAllocationsText;
  }

  // 验证计划时间不能早于今天
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  if (data.plannedDate && /^\d{4}-\d{2}-\d{2}$/.test(data.plannedDate)) {
    const selected = new Date(data.plannedDate);
    if (selected < today) {
      window.EMIE.actions.showSystemAlert('计划完成时间不能早于今天');
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
    window.EMIE.actions.showSystemAlert('编辑失败: ' + e.message);
  }
}

async function deleteTask(pid, tid) {
  if (!await EMIE.actions.showSystemConfirm('确定要删除这个子任务吗？此操作不可恢复。')) return;
  try {
    await apiDelete(`/projects/${pid}/tasks/${tid}`);
    closeM('editTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('删除失败: ' + e.message);
  }
}

async function withdrawMarketTask(pid, tid) {
  if (!await EMIE.actions.showSystemConfirm('确认将该任务撤出接单市场？撤回后设计师将无法抢单。')) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/withdraw-market`, {});
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('撤回失败: ' + e.message);
  }
}

async function withdrawAcceptedTask(pid, tid) {
  if (!await EMIE.actions.showSystemConfirm('确认退单？接单后1小时内退单免扣分，超过1小时将按累计退单次数比例扣分。')) return;
  try { await apiPost(`/projects/${pid}/tasks/${tid}/withdraw`, {}); await refreshAfterMutation(pid); }
  catch (e) { window.EMIE.actions.showSystemAlert('退单失败：' + e.message); }
}

async function cancelAcceptedTask(pid, tid) {
  if (!await EMIE.actions.showSystemConfirm('确认取消该任务的接单吗？当前负责人将被释放，任务内容会保留并回到待处理。')) return;
  try { await apiPost(`/projects/${pid}/tasks/${tid}/cancel-accept`, {}); await refreshAfterMutation(pid); }
  catch (e) { window.EMIE.actions.showSystemAlert('取消接单失败：' + e.message); }
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
      <button class="modal-close-float" data-emie-action="click:task-accept-close">✕</button>
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✅ 接单：${escHtml(task.name)}</div></div></div>
        <div class="modal-body">
          ${marketTask ? '' : `<p style="margin-bottom:12px;color:var(--gray-500);">负责人：<strong>${escHtml(task.designerName || '未指定')}</strong></p>`}
          <form id="taskAcceptForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划完成时间</label>${renderDatePicker('plannedDate', {required:true, value: task.plannedDate || ''})}</div>
          </form>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-accept-close">取消</button><button class="btn btn-primary" data-emie-action="click:task-submit-accept" data-project-id="${pid}" data-task-id="${tid}">确认接单</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskAcceptModal');
  } catch (e) {
    doneOpenModal('taskAcceptModal');
    window.EMIE.actions.showSystemAlert('加载失败: ' + e.message);
  }
}

async function submitTaskAccept(pid, tid) {
  const fd = new FormData(document.getElementById('taskAcceptForm'));
  const plannedDate = fd.get('plannedDate');
  if (!plannedDate) { window.EMIE.actions.showSystemAlert('请选择计划完成时间'); return; }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(plannedDate) || isNaN(new Date(plannedDate).getTime())) {
    window.EMIE.actions.showSystemAlert('日期格式不正确（yyyy-mm-dd）');
    return;
  }
  const today = new Date(); today.setHours(0, 0, 0, 0);
  if (new Date(plannedDate) < today) {
    window.EMIE.actions.showSystemAlert('计划完成时间不能早于今天');
    return;
  }

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/accept`, {
      plannedDate,
      currentUser: getCurrentUserName(),
      currentRole: EMIE.state.currentRole,
      designerUserId: getCurrentUserId(),
    });
    // 立即同步工作台缓存，避免列表仍显示旧的“待接单”状态。
    const cached = EMIE.dashboardState?.designerTaskCache;
    if (Array.isArray(cached)) {
      const current = cached.find(item => Number(item.id) === Number(tid));
      if (current) {
        current.status = 'accepted';
        current.designerId = getCurrentUserId();
        current.designerName = getCurrentUserName();
        current.plannedDate = plannedDate;
      }
      if (typeof EMIE.actions.applyFilterDesignerTasks === 'function') EMIE.actions.applyFilterDesignerTasks();
    }
    closeM('taskAcceptModal');
    closeM('publishedSubTaskDetailModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    await refreshAfterMutation(pid);
    window.EMIE.actions.showSystemAlert('操作失败: ' + e.message);
  }
}

async function taskDeliver(pid, tid) {
  if (!tryOpenModal('taskDeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    EMIE.projectState.deliverImages = [];
    EMIE.projectState.deliverLibraryImages = [];
    EMIE.projectState.deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskDeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-action="click:task-deliver-close">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 交付：${escHtml(task.name)}</div></div></div>
        <div class="modal-body">
          <form id="taskDeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required placeholder="描述交付的设计成果..." style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" data-emie-action="input:task-score-input">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 本地上传的交付参考图</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="deliverImageInput">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="deliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-images">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div class="delivery-library-section"><div class="task-delivery-image-heading"><div><strong>🔗 关联图档库</strong><small>从团队图档库选择已有图片</small></div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:task-open-library-picker">选择图档</button></div><div id="deliveryLibraryImageList" class="delivery-library-selected-list"><span>暂未关联图档库图片</span></div></div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="deliverAttachmentInput">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
            <input type="file" id="deliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-attachments">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-deliver-close">取消</button><button class="btn btn-primary" data-emie-action="click:task-submit-deliver" data-project-id="${pid}" data-task-id="${tid}">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('加载失败: ' + e.message);
  }
}

async function openDeliveryLibraryPicker() {
  if (document.getElementById('deliveryLibraryPicker')) return;
  try {
    const items = await apiGet('/image-library');
    const images = items.flatMap(item => (item.images || []).filter(file => !String(file.name || '').toLowerCase().endsWith('.ai')).map(file => ({ ...file, libraryItemId: item.id, libraryName: item.name, ipName: item.ipName })));
    EMIE.deliveryLibraryPickerImages = images;
    const selectedNames = new Set((EMIE.projectState.deliverLibraryImages || []).map(file => file.storedName));
    const token = localStorage.getItem('design_pm_token');
    const authUrl = url => token ? `${url}${url.includes('?') ? '&' : '?'}authToken=${encodeURIComponent(token)}` : url;
    const overlay = document.createElement('div'); overlay.id = 'deliveryLibraryPicker'; overlay.className = 'modal-overlay modal-detail-drawer';
    overlay.innerHTML = `<div class="modal"><div class="modal-header"><div><div class="modal-title">关联图档库图片</div><div class="form-hint">选择后会作为本次交付参考图，交付图片总数最多 6 张</div></div><button class="modal-close" data-emie-action="click:task-close-library-picker">✕</button></div><div class="modal-body"><div class="delivery-library-picker-grid">${images.length ? images.map((file, index) => { const thumb = `/api/files/thumbnail/${file.storedName}`; const selected = selectedNames.has(file.storedName); return `<button type="button" class="delivery-library-picker-card ${selected ? 'selected' : ''}" data-emie-action="click:task-toggle-library-image" data-file-index="${index}"><img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(authUrl(thumb))}" alt="${escHtml(file.name || file.libraryName)}"><strong>${escHtml(file.libraryName || file.name)}</strong><small>${escHtml(file.ipName || '')}</small><span>✓</span></button>`; }).join('') : '<div class="empty-state">图档库中暂无可关联的图片</div>'}</div></div><div class="modal-footer"><span id="deliveryLibrarySelectedCount" class="form-hint">已选择 ${selectedNames.size} 张</span><button class="btn btn-outline" data-emie-action="click:task-close-library-picker">取消</button><button class="btn btn-primary" data-emie-action="click:task-confirm-library-picker">确认关联</button></div></div>`;
    overlay.addEventListener('click', event => { if (event.target === overlay) closeDeliveryLibraryPicker(); }); document.body.appendChild(overlay);
  } catch (error) { window.EMIE.actions.showSystemAlert('图档库加载失败：' + error.message); }
}
function closeDeliveryLibraryPicker() { document.getElementById('deliveryLibraryPicker')?.remove(); EMIE.deliveryLibraryPickerImages = []; }
function toggleDeliveryLibraryImage(button) { button.classList.toggle('selected'); const count = document.querySelectorAll('#deliveryLibraryPicker .delivery-library-picker-card.selected').length; document.getElementById('deliveryLibrarySelectedCount').textContent = `已选择 ${count} 张`; }
function confirmDeliveryLibraryPicker() {
  const files = [...document.querySelectorAll('#deliveryLibraryPicker .delivery-library-picker-card.selected')].map(button => EMIE.deliveryLibraryPickerImages[Number(button.dataset.fileIndex)]).filter(Boolean);
  const selected = files.map(file => ({ name: file.name, url: file.url || `/api/files/download/${file.storedName}`, size: file.size, storedName: file.storedName, libraryItemId: file.libraryItemId, libraryName: file.libraryName }));
  if ((EMIE.projectState.deliverImages || []).length + selected.length > 6) return window.EMIE.actions.showSystemAlert('本地图片和关联图档合计最多 6 张，请减少选择');
  EMIE.projectState.deliverLibraryImages = selected; renderDeliveryLibrarySelection(); closeDeliveryLibraryPicker();
}

function removeDeliveryLibraryImage(index) { EMIE.projectState.deliverLibraryImages.splice(index, 1); renderDeliveryLibrarySelection(); }
function renderDeliveryLibrarySelection() {
  const container = document.getElementById('deliveryLibraryImageList'); if (!container) return;
  const token = localStorage.getItem('design_pm_token'), authUrl = url => token ? `${url}${url.includes('?') ? '&' : '?'}authToken=${encodeURIComponent(token)}` : url;
  const files = EMIE.projectState.deliverLibraryImages || [];
  container.innerHTML = files.length ? files.map((file, index) => `<article><img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(authUrl(`/api/files/thumbnail/${file.storedName}`))}" alt="${escHtml(file.name || '')}"><div><strong>${escHtml(file.libraryName || file.name || '图档库图片')}</strong><small>${escHtml(file.name || '')}</small></div><button type="button" data-emie-action="click:task-remove-library-image" data-index="${index}" aria-label="取消关联">✕</button></article>`).join('') : '<span>暂未关联图档库图片</span>';
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
  if (EMIE.projectState.uploadingCount > 0) { window.EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); return; }
  if (EMIE.projectState.deliverImages.length + EMIE.projectState.deliverLibraryImages.length > 6) { window.EMIE.actions.showSystemAlert('本地图片和关联图档合计最多 6 张'); return; }
  const fd = new FormData(document.getElementById('taskDeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { window.EMIE.actions.showSystemAlert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { window.EMIE.actions.showSystemAlert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = EMIE.state.currentUserId;
  data.referenceImagesJson = JSON.stringify([...EMIE.projectState.deliverImages, ...EMIE.projectState.deliverLibraryImages].map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName, libraryItemId: i.libraryItemId || null })));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/deliver`, data);
    closeM('taskDeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('交付失败: ' + e.message);
  }
}

async function submitTaskReview(pid, tid) {
  if (EMIE.actions.showSystemConfirm && !(await EMIE.actions.showSystemConfirm('确认将该子任务送审吗？', '提交送审'))) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/submit-review`, {
      currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, currentUserId: getCurrentUserId()
    });
    // 送审后统一清理缓存并刷新项目详情，避免项目详情页继续显示送审前的状态。
    clearSWRCache();
    await refreshAfterMutation(pid);
    const detail = await apiGet(`/projects/${pid}`);
    const updatedTask = (detail.tasks || []).find(task => Number(task.id) === Number(tid));
    if (updatedTask) {
      const cache = EMIE.dashboardState.designerTaskCache || [];
      EMIE.dashboardState.designerTaskCache = cache.map(task => Number(task.id) === Number(tid) ? { ...task, ...updatedTask } : task);
    }
    closeM('publishedSubTaskDetailModal');
    setTimeout(() => EMIE.actions.openPublishedSubTaskDetail?.(tid), 0);
    // 同步主页的子任务分组：只重载任务区域，不刷新整个工作台。
    if (EMIE.state.currentRole === 'planner') {
      await EMIE.actions.loadDashboardPlannerTasks?.(getCurrentUserId());
    }
  } catch (e) { window.EMIE.actions.showSystemAlert('送审失败: ' + e.message); }
}

async function taskRedeliver(pid, tid) {
  if (!tryOpenModal('taskRedeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    EMIE.projectState.deliverImages = [];
    EMIE.projectState.deliverLibraryImages = [];
    EMIE.projectState.deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskRedeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-action="click:task-redeliver-close">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 重新交付：${escHtml(task.name)}</div></div></div>
        <div class="modal-body">
          ${task.reviewComments ? `<div class="review-box rejected" style="margin-bottom:16px;"><strong>驳回意见：</strong>${escHtml(task.reviewComments)}</div>` : ''}
          <form id="taskRedeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" data-emie-action="input:task-score-input">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 本地上传的交付参考图</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="deliverImageInput">
              <div>📁 拖拽图片到此处，或点击选择图片</div>
              <input type="file" id="deliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-images">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div class="delivery-library-section"><div class="task-delivery-image-heading"><div><strong>🔗 关联图档库</strong><small>从团队图档库选择已有图片</small></div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:task-open-library-picker">选择图档</button></div><div id="deliveryLibraryImageList" class="delivery-library-selected-list"><span>暂未关联图档库图片</span></div></div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="deliverAttachmentInput">
              <div>📁 拖拽文件到此处，或点击选择文件</div>
              <input type="file" id="deliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-attachments">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-redeliver-close">取消</button><button class="btn btn-primary" data-emie-action="click:task-submit-redeliver" data-project-id="${pid}" data-task-id="${tid}">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskRedeliverModal');
  } catch (e) {
    doneOpenModal('taskRedeliverModal');
    window.EMIE.actions.showSystemAlert('加载失败: ' + e.message);
  }
}

async function taskConfirmRevision(pid, tid) {
  if (!await EMIE.actions.showSystemConfirm('确认开始修改该子任务吗？')) return;
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/confirm-revision`, {
      currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, currentUserId: getCurrentUserId()
    });
    await refreshAfterMutation(pid);
  } catch (e) { window.EMIE.actions.showSystemAlert('确认修改失败: ' + e.message); }
}

async function submitTaskRedeliver(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { window.EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); return; }
  if (EMIE.projectState.deliverImages.length + EMIE.projectState.deliverLibraryImages.length > 6) { window.EMIE.actions.showSystemAlert('本地图片和关联图档合计最多 6 张'); return; }
  const fd = new FormData(document.getElementById('taskRedeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { window.EMIE.actions.showSystemAlert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { window.EMIE.actions.showSystemAlert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = EMIE.state.currentUserId;
  data.referenceImagesJson = JSON.stringify([...EMIE.projectState.deliverImages, ...EMIE.projectState.deliverLibraryImages].map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName, libraryItemId: i.libraryItemId || null })));
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/redeliver`, data);
    closeM('taskRedeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('交付失败: ' + e.message);
  }
}

async function taskCorrectDelivery(pid, tid) {
  if (!tryOpenModal('taskCorrectDeliveryModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    try { const allImages = JSON.parse(task.referenceImagesJson || '[]'); EMIE.projectState.deliverLibraryImages = allImages.filter(file => file.libraryItemId); EMIE.projectState.deliverImages = allImages.filter(file => !file.libraryItemId); } catch (_) { EMIE.projectState.deliverImages = []; EMIE.projectState.deliverLibraryImages = []; }
    try { EMIE.projectState.deliverAttachments = JSON.parse(task.attachmentsJson || '[]'); } catch (_) { EMIE.projectState.deliverAttachments = []; }
    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskCorrectDeliveryModal';
    modal.innerHTML = `
      <button class="modal-close-float" data-emie-action="click:task-correct-close">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left">
          <div class="modal-title">📝 修正交付：${escHtml(task.name)}</div>
          <div style="font-size:12px;color:var(--gray-500);margin-top:4px;">将生成新的交付版本；已产生的审核结论会失效并重新进入审核。</div>
        </div></div>
        <div class="modal-body">
          <form id="taskCorrectDeliveryForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 本次修正说明</label><textarea class="form-textarea" name="changeSummary" required maxlength="500" placeholder="例如：补充渲染源文件，移除误传的旧版包装图"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required style="min-height:100px;">${escHtml(task.deliverables || '')}</textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label><input type="number" class="form-input" name="selfScore" required min="1" max="100" step="1" value="${task.selfScore || ''}" data-emie-action="input:task-score-input" style="max-width:200px;"></div>
          </form>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label">🖼️ 当前本地交付参考图（可删除错误文件或补充文件）</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="correctDeliverImageInput"><div>📁 点击补充图片或模型文件</div><input type="file" id="correctDeliverImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-images"></div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div class="delivery-library-section"><div class="task-delivery-image-heading"><div><strong>🔗 关联图档库</strong><small>独立管理本次交付关联的图库图片</small></div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:task-open-library-picker">选择图档</button></div><div id="deliveryLibraryImageList" class="delivery-library-selected-list"></div></div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label">📎 当前交付附件（可删除错误文件或补充文件）</div>
            <div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="correctDeliverAttachmentInput"><div>📁 点击补充附件</div><input type="file" id="correctDeliverAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-deliver-attachments"></div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-correct-close">取消</button><button class="btn btn-warning" data-emie-action="click:task-submit-correct" data-project-id="${pid}" data-task-id="${tid}">生成新版本并重新送审</button></div>
      </div>`;
    document.body.appendChild(modal);
    renderFileList(EMIE.projectState.deliverImages, '交付参考图');
    renderDeliveryLibrarySelection();
    renderFileList(EMIE.projectState.deliverAttachments, '交付附件');
    doneOpenModal('taskCorrectDeliveryModal');
  } catch (e) {
    doneOpenModal('taskCorrectDeliveryModal');
    window.EMIE.actions.showSystemAlert('加载失败: ' + e.message);
  }
}

async function submitTaskCorrectDelivery(pid, tid) {
  if (EMIE.projectState.uploadingCount > 0) { window.EMIE.actions.showSystemAlert('文件正在上传中，请等待上传完成'); return; }
  if (EMIE.projectState.deliverImages.length + EMIE.projectState.deliverLibraryImages.length > 6) { window.EMIE.actions.showSystemAlert('本地图片和关联图档合计最多 6 张'); return; }
  const form = document.getElementById('taskCorrectDeliveryForm');
  if (!form?.reportValidity()) return;
  const data = Object.fromEntries(new FormData(form).entries());
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { window.EMIE.actions.showSystemAlert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.actualDate = new Date().toISOString().split('T')[0];
  data.currentUser = getCurrentUserName();
  data.currentRole = EMIE.state.currentRole;
  data.currentUserId = getCurrentUserId();
  data.referenceImagesJson = JSON.stringify([...EMIE.projectState.deliverImages, ...EMIE.projectState.deliverLibraryImages]);
  data.attachmentsJson = JSON.stringify(EMIE.projectState.deliverAttachments);
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/correct-delivery`, data);
    closeM('taskCorrectDeliveryModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('修正交付失败: ' + e.message);
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
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">${title}：${escHtml(task.name)}</div></div></div>
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
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-approve-close">取消</button><button class="btn btn-success" data-emie-action="click:task-submit-approve" data-project-id="${pid}" data-task-id="${tid}" data-project-type="${escHtml(projectType)}">${needsScore ? '确认通过并评分' : '确认通过'}</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitTaskApprove(pid, tid) {
  const scoreVal = parseInt(document.getElementById('approveScore')?.value);
  const hasScoreFields = document.getElementById('approveScore') !== null;
  if (hasScoreFields) {
    if (isNaN(scoreVal) || scoreVal < 1 || scoreVal > 100) { window.EMIE.actions.showSystemAlert('请输入有效的评分（1-100）'); return; }
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
    window.EMIE.actions.showSystemAlert('操作失败: ' + (e.message || '未知错误'));
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
        <div class="form-group"><label class="form-label">参考图片（选填）</label><div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="rejectImageInput"><div>📁 拖拽图片到此处，或点击选择图片</div><input type="file" id="rejectImageInput" multiple accept="${REFERENCE_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-reject-images"></div><div class="file-list" id="rejectImageList"></div></div>
        <div class="form-group"><label class="form-label">附件（选填）</label><div class="upload-area" data-emie-action="click:task-open-file-input" data-input-id="rejectAttachmentInput"><div>📁 拖拽文件到此处，或点击选择文件</div><input type="file" id="rejectAttachmentInput" multiple accept="${ATTACHMENT_FILE_ACCEPT}" style="display:none" data-emie-action="change:task-reject-attachments"></div><div class="file-list" id="rejectAttachmentList"></div></div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-reject-close">取消</button><button class="btn btn-danger" data-emie-action="click:task-submit-reject" data-project-id="${pid}" data-task-id="${tid}">确认驳回</button></div>
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
  if (!comments) { window.EMIE.actions.showSystemAlert('请填写修改意见'); return; }
  if (!requiredCompletionDate) { window.EMIE.actions.showSystemAlert('请选择要求完成时间'); return; }
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
    window.EMIE.actions.showSystemAlert('操作失败: ' + e.message);
  }
}

// ==================== 评分 ====================
function openScoring(pid, tid) {
  if (!tryOpenModal('scoringModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task || !task.scoringRecords) return;
    const myRecord = task.scoringRecords.find(sr => sr.role === EMIE.state.currentRole);
    if (!myRecord) { window.EMIE.actions.showSystemAlert('您无需对此任务评分'); return; }

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'scoringModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><button class="modal-close" data-emie-action="click:task-score-close">✕</button><div class="modal-header-left"><div class="modal-title">⭐ 评分：${escHtml(task.name)}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:8px;color:var(--gray-500);">评分人：<strong>${escHtml(roleLabel(EMIE.state.currentRole))}</strong>（${escHtml(getCurrentUserName())}）</p>
          <p style="margin-bottom:16px;color:var(--gray-500);">请对 <strong>${escHtml(task.name)}</strong> 进行评分（1-100分）</p>
          <div>
            <div class="form-group"><label class="form-label">⭐ 综合评分</label><input type="number" class="form-input" id="scoreValue" min="1" max="100" step="1" placeholder="1-100" value="${myRecord.score ?? ''}" style="font-size:24px;text-align:center;max-width:200px;margin:0 auto;"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:task-score-close">取消</button><button class="btn btn-primary" data-emie-action="click:task-submit-score" data-project-id="${pid}" data-task-id="${tid}">提交评分</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitScoring(pid, tid) {
  const score = parseInt(document.getElementById('scoreValue').value);
  if (isNaN(score) || score < 1 || score > 100) { window.EMIE.actions.showSystemAlert('请输入有效的评分（1-100分）'); return; }

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
    window.EMIE.actions.showSystemAlert('评分提交失败: ' + e.message);
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
  withdrawAcceptedTask,
  cancelAcceptedTask,
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
  withdrawAcceptedTask,
  cancelAcceptedTask,
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

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('task-add-close', () => closeM('addSubTaskModal'));
  registerEventAction('task-clear-field-error', (_event, element) => {
    element.closest('.form-group')?.querySelector('.field-error')?.remove();
    element.style.borderColor = '';
  });
  registerEventAction('task-add-assignee', (_event, element) =>
    switchAssigneeType('add', element.dataset.assigneeRole, element));
  registerEventAction('task-assignment-mode', (_event, element) =>
    toggleSubTaskMarketMode(element.dataset.marketMode === 'true'));
  registerEventAction('task-open-file-input', (_event, element) =>
    document.getElementById(element.dataset.inputId)?.click());
  registerEventAction('task-subtask-ref-images', (_event, element) => handleSubTaskRefImages(element));
  registerEventAction('task-subtask-attachments', (_event, element) => handleSubTaskAttachments(element));
  registerEventAction('task-save-draft', (_event, element) => saveSubTaskDraft(element.dataset.projectId));
  registerEventAction('task-submit-add', (_event, element) =>
    submitGuard(element, () => submitAddSubTask(element.dataset.projectId)));
  registerEventAction('task-edit-close', () => closeM('editTaskModal'));
  registerEventAction('task-edit-assignee', (_event, element) =>
    switchEditAssigneeType(element.dataset.assigneeRole, element));
  registerEventAction('task-edit-ref-images', (_event, element) => handleEditRefImages(element));
  registerEventAction('task-edit-attachments', (_event, element) => handleEditAttachments(element));
  registerEventAction('task-submit-edit', (_event, element) =>
    submitGuard(element, () => submitEditTask(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-accept-close', () => closeM('taskAcceptModal'));
  registerEventAction('task-submit-accept', (_event, element) =>
    submitGuard(element, () => submitTaskAccept(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-deliver-close', () => closeM('taskDeliverModal'));
  registerEventAction('task-open-library-picker', () => openDeliveryLibraryPicker());
  registerEventAction('task-close-library-picker', () => closeDeliveryLibraryPicker());
  registerEventAction('task-toggle-library-image', (_event, element) => toggleDeliveryLibraryImage(element));
  registerEventAction('task-confirm-library-picker', () => confirmDeliveryLibraryPicker());
  registerEventAction('task-remove-library-image', (_event, element) => removeDeliveryLibraryImage(Number(element.dataset.index)));
  registerEventAction('task-score-input', (_event, element) => validateScoreInput(element));
  registerEventAction('task-deliver-images', (_event, element) => handleDeliverImages(element));
  registerEventAction('task-deliver-attachments', (_event, element) => handleDeliverAttachments(element));
  registerEventAction('task-submit-deliver', (_event, element) =>
    submitGuard(element, () => submitTaskDeliver(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-redeliver-close', () => closeM('taskRedeliverModal'));
  registerEventAction('task-submit-redeliver', (_event, element) =>
    submitGuard(element, () => submitTaskRedeliver(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-correct-close', () => closeM('taskCorrectDeliveryModal'));
  registerEventAction('task-submit-correct', (_event, element) =>
    submitGuard(element, () => submitTaskCorrectDelivery(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-approve-close', () => closeM('taskApproveModal'));
  registerEventAction('task-submit-approve', (_event, element) =>
    submitGuard(element, () => submitTaskApprove(element.dataset.projectId, element.dataset.taskId, element.dataset.projectType)));
  registerEventAction('task-reject-close', () => closeM('taskRejectModal'));
  registerEventAction('task-reject-images', (_event, element) => handleRejectImages(element));
  registerEventAction('task-reject-attachments', (_event, element) => handleRejectAttachments(element));
  registerEventAction('task-submit-reject', (_event, element) =>
    submitGuard(element, () => submitTaskReject(element.dataset.projectId, element.dataset.taskId)));
  registerEventAction('task-score-close', () => closeM('scoringModal'));
  registerEventAction('task-submit-score', (_event, element) =>
    submitGuard(element, () => submitScoring(element.dataset.projectId, element.dataset.taskId)));
}
