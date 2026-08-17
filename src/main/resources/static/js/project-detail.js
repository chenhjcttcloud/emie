const EMIE = window.EMIE;
const getCurrentUserName = (...args) => EMIE.actions.getCurrentUserName(...args);
const getCurrentUserId = (...args) => EMIE.actions.getCurrentUserId(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const openEditProject = (...args) => EMIE.actions.openEditProject(...args);
const tryOpenModal = (...args) => EMIE.actions.tryOpenModal(...args);
const doneOpenModal = (...args) => EMIE.actions.doneOpenModal(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const getTaskStatusInfo = (...args) => EMIE.actions.getTaskStatusInfo(...args);
const formatDate = (...args) => EMIE.actions.formatDate(...args);
const fmtDT = (...args) => EMIE.actions.fmtDT(...args);
const fmtSize = (...args) => EMIE.actions.fmtSize(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const displayText = (...args) => EMIE.actions.displayText(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const renderScoringMini = (...args) => EMIE.actions.renderScoringMini(...args);
const refreshAfterMutation = (...args) => EMIE.actions.refreshAfterMutation(...args);
const render = (...args) => EMIE.actions.render(...args);
const openFilePreview = (...args) => EMIE.actions.openFilePreview(...args);
const showDownloadOptions = (...args) => EMIE.actions.showDownloadOptions(...args);
const shareProject = (...args) => EMIE.actions.shareProject(...args);
const addSubTask = (...args) => EMIE.actions.addSubTask(...args);
const editTask = (...args) => EMIE.actions.editTask(...args);
const deleteTask = (...args) => EMIE.actions.deleteTask(...args);
const withdrawMarketTask = (...args) => EMIE.actions.withdrawMarketTask(...args);
const taskAccept = (...args) => EMIE.actions.taskAccept(...args);
const taskDeliver = (...args) => EMIE.actions.taskDeliver(...args);
const taskRedeliver = (...args) => EMIE.actions.taskRedeliver(...args);
const taskConfirmRevision = (...args) => EMIE.actions.taskConfirmRevision(...args);
const taskApprove = (...args) => EMIE.actions.taskApprove(...args);
const taskReject = (...args) => EMIE.actions.taskReject(...args);
const submitTaskReview = (...args) => EMIE.actions.submitTaskReview(...args);
const clearSWRCache = (...args) => EMIE.actions.clearSWRCache(...args);
const openScoring = (...args) => EMIE.actions.openScoring(...args);

function formatProjectIp(ipName, ipSubOptions) {
  if (!ipName) return '无IP';
  try {
    const subOptions = JSON.parse(ipSubOptions || '[]');
    return Array.isArray(subOptions) && subOptions.length ? `${ipName} / ${subOptions.join('、')}` : ipName;
  } catch (e) {
    return ipName;
  }
}

function pointDifficultyLabel(code) {
  return { STANDARD: '标准', COMPLEX: '复杂', MAJOR: '重大' }[String(code || 'STANDARD').toUpperCase()] || String(code || '标准');
}

function taskPointSummary(task) {
  if (!task?.pointRuleCode) return '';
  const base = Number(task.basePointSnapshot || 0);
  const multiplier = Number(task.difficultyMultiplierSnapshot || 1);
  const estimated = Math.round(base * multiplier * 100) / 100;
  const labels = { TASK_APPROVED: '通用任务（验收完成）', A1: '包装整套设计', A2: '包装单项设计', A3: '包装修改/刀模/箱规', A4: '包装多语言版', A5: '详情页全套设计', A6: '详情页局部/改版', A7: '主图/单张卖点图', A8: '海报/立牌/单页', A9: '展会物料整套', A10: 'UI界面/灯珠图案/待机页', A11: 'AI生图/场景图/推广图', B1: '原创产品设计', B2: '外采产品IP化设计', B3: '新增SKU/配色衍生', B4: '展会样品/客户定制产品', B5: '3D建模渲染出图', B6: '3D公仔建模/输出', E1: '执行类任务', E2: '执行类任务', E3: '执行类任务', E4: '执行类任务', S1: '特殊专项任务' };
  const code = String(task.pointRuleCode).toUpperCase();
  return `${code}（${labels[code] || '积分任务'}） · ${pointDifficultyLabel(task.difficultyCode)} · ${estimated} 分`;
}

function taskCollaborationSummary(task) {
  try {
    const rows = JSON.parse(task?.collaboratorAllocationsJson || '[]');
    if (!Array.isArray(rows) || !rows.length) return '主负责人 100%';
    const used = rows.reduce((sum, item) => sum + Number(item.ratio || 0), 0);
    return [`主负责人 ${100 - used}%`, ...rows.map(item => `${item.name || item.userId} ${Number(item.ratio || 0)}%`)].join(' · ');
  } catch (_) { return '比例快照异常'; }
}

function renderProjectRelatedRoles(detail) {
  const groups = { designer: [], supplychain: [], promotion: [] };
  (detail.tasks || []).forEach(task => {
    const role = String(task.assigneeRole || 'designer').toLowerCase();
    if (!groups[role]) return;
    const name = task.designerName || task.designerId;
    if (name && !groups[role].includes(name)) groups[role].push(name);
  });
  const entries = [];
  groups.designer.forEach((name, index) => entries.push({ label: `设计师${index + 1}`, name, tone: 'blue' }));
  if (groups.supplychain.length) entries.push({ label: '供应链', name: groups.supplychain.join('、'), tone: 'teal' });
  if (groups.promotion.length) entries.push({ label: '产品推广', name: groups.promotion.join('、'), tone: 'purple' });
  if (!entries.length) return '';
  const tones = { blue: ['#EFF6FF', '#1D4ED8'], teal: ['#F0FDFA', '#0D9488'], purple: ['#F5F3FF', '#7C3AED'] };
  return `<div class="detail-section" style="margin-top:14px;"><div class="detail-section-title">👥 项目关联角色</div><div style="display:flex;flex-wrap:wrap;gap:10px;">${entries.map(entry => { const tone = tones[entry.tone]; return `<div style="display:flex;align-items:center;gap:8px;padding:8px 12px;border-radius:10px;background:${tone[0]};color:${tone[1]};min-width:150px;"><span style="font-size:12px;font-weight:700;">${entry.label}</span><span style="font-size:13px;font-weight:600;">${escHtml(entry.name)}</span></div>`; }).join('')}</div></div>`;
}

// ==================== 项目详情 ====================
async function openProjectDetail(pid) {
  if (!tryOpenModal('projectDetailModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'projectDetailModal';
    modal.innerHTML = `
    <button class="modal-close-float" data-emie-onclick="closeM('projectDetailModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-header-left"><div class="modal-title">${detail.type === 'channel_custom' ? '📦 渠道定制项目' : '🏭 常规品设计项目'} ${escHtml(detail.projectCode || ('#' + detail.id))}</div></div>
        </div>
        <div class="modal-body">${renderProjectDetailContent(detail)}</div>
        <div class="modal-footer" id="detailActions">${renderProjectActions(detail)}</div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('projectDetailModal');
  } catch (e) {
    doneOpenModal('projectDetailModal');
    window.EMIE.actions.showSystemAlert('加载失败: ' + e.message);
  }
}

function renderProjectDetailContent(detail) {
  EMIE.projectState.currentProjectDetail = detail;
  const isChannel = detail.type === 'channel_custom';
  const canManageSubTasks = EMIE.state.currentRole === 'planner'
    && detail.plannerId === getCurrentUserId()
    && EMIE.actions.hasPermission('subtask.create');
  const canEditInformation = (isChannel
      && EMIE.state.currentRole === 'sales' && detail.salesId === getCurrentUserId()
      && EMIE.actions.hasPermission('project.channel.edit'))
    || (!isChannel
      && EMIE.state.currentRole === 'planner' && detail.plannerId === getCurrentUserId()
      && EMIE.actions.hasPermission('project.regular.edit'));
  // 子任务只有完成验收后才计为完成，已交付仍属于待验收。
  const totalTasks = detail.tasks.length;
  const doneStatuses = ['planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const doneTasks = detail.tasks.filter(t => {
    return doneStatuses.includes(t.status);
  }).length;

  return `
    ${detail.complianceItems ? (() => { try {
      const items = JSON.parse(detail.complianceItems);
      return `<div style="background:#FEF3C7;border:1px solid #FDE68A;border-radius:8px;padding:12px 16px;margin-bottom:16px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:600;color:#92400E;white-space:nowrap;">⚠️ 合规处罚提醒</span>
        <span style="font-size:12px;color:#92400E;white-space:nowrap;">该产品涉及以下合规事项，请关注供应商资质：</span>
        ${items.map(i => `<span style="display:inline-block;padding:3px 10px;border-radius:10px;font-size:12px;font-weight:500;background:#FDE68A;color:#92400E;">${escHtml(i)}</span>`).join('')}
      </div>`;
    } catch(e) { return ''; } })() : ''}
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:20px;">
      <span class="badge ${escHtml(detail.statusCls)}" style="font-size:13px;padding:5px 14px;">${escHtml(detail.statusLabel)}</span>
      <span style="font-size:12px;color:var(--gray-400);">创建：${fmtDT(detail.createdAt)}</span>
      <span style="font-size:12px;color:var(--gray-400);">更新：${fmtDT(detail.updatedAt)}</span>
    </div>

    <div class="detail-section">
      <div class="detail-section-title">📋 项目信息
        ${canEditInformation ? `<button class="btn btn-outline btn-sm" style="margin-left:auto;" data-emie-onclick="openEditProject(${detail.id})">✏️ 编辑项目信息</button>` : ''}
      </div>
      <div class="detail-grid">
        <div class="detail-item"><div class="detail-label">项目类型</div><div class="detail-value">${isChannel ? '渠道定制单' : '公司常规品'}</div></div>
        <div class="detail-item"><div class="detail-label">产品名称</div><div class="detail-value">${escHtml(displayText(detail.productName, '未设置'))}</div></div>
        <div class="detail-item"><div class="detail-label">IP</div><div class="detail-value">${escHtml(formatProjectIp(detail.ipName, detail.ipSubOptions))}</div></div>
        ${isChannel ? `<div class="detail-item"><div class="detail-label">需求方（销售）</div><div class="detail-value">${escHtml(detail.salesName || '-')}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">产品企划</div><div class="detail-value">${escHtml(detail.plannerName || '-')}</div></div>
        ${detail.productCategory ? `<div class="detail-item"><div class="detail-label">产品类目</div><div class="detail-value">${escHtml(detail.productCategory)}${detail.productCategory === '其他' && detail.productCategoryNote ? `（${escHtml(detail.productCategoryNote)}）` : ''}</div></div>` : ''}
        ${detail.priceRange ? `<div class="detail-item"><div class="detail-label">参考零售价</div><div class="detail-value">${escHtml(detail.priceRange)}</div></div>` : ''}
        ${detail.targetMarket ? `<div class="detail-item"><div class="detail-label">目标市场</div><div class="detail-value">${(() => { try { return JSON.parse(detail.targetMarket).map(escHtml).join('、'); } catch(e) { return escHtml(detail.targetMarket); } })()}</div></div>` : ''}
        ${detail.complianceItems ? `<div class="detail-item" style="grid-column:1/-1;background:linear-gradient(135deg,#FFFBEB,#FFF7ED);border:1px solid #FDE68A;border-radius:10px;padding:12px 14px;"><div style="display:flex;align-items:center;gap:7px;color:#92400E;font-weight:700;font-size:13px;">⚠️ 合规事项 <span style="font-size:11px;font-weight:400;color:#A16207;">请确认相关供应商资质</span></div><div class="detail-value" style="display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;">${(() => { try { return JSON.parse(detail.complianceItems).map(i => `<span style="display:inline-flex;align-items:center;padding:5px 10px;border-radius:8px;font-size:12px;font-weight:600;background:#FEF3C7;color:#92400E;border:1px solid #FCD34D;">${escHtml(i)}</span>`).join(''); } catch(e) { return escHtml(detail.complianceItems); } })()}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">要求完成时间</div><div class="detail-value">${formatDate(detail.deadline)}</div></div>
      </div>
      <div style="margin-top:8px;"><div class="detail-label">产品要求</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.productRequirements || '-')}</div></div>
      ${detail.description ? `<div style="margin-top:4px;"><div class="detail-label">细节描述</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(detail.description)}</div></div>` : ''}
      ${renderProjectReferenceImages(detail)}
      ${renderProjectAttachments(detail)}
    </div>

    ${renderProductArchiveSection(detail)}

    ${renderProjectRelatedRoles(detail)}

    <div class="detail-section">
      <div class="detail-section-title">
        📌 子任务列表
        <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${doneTasks}/${detail.tasks.length} 完成</span>
        ${canManageSubTasks && (detail.status === 'planner_accepted' || detail.status === 'in_progress' || detail.status === 'completed' || detail.status === 'completed_pending_score') ? `<button class="btn btn-primary btn-sm" style="margin-left:auto;" data-emie-onclick="addSubTask(${detail.id})">➕ 添加子任务</button>` : ''}
      </div>
      ${detail.tasks.length === 0 ? `<div class="empty" style="padding:30px;"><div class="empty-icon">📭</div><p>暂无子任务，等待产品企划添加</p></div>` : ''}
      ${detail.tasks.map((t, i) => renderSubTaskCard(detail, t, i)).join('')}
    </div>

    ${renderProjectScoringSummary(detail)}

    ${renderSubTaskProgress(detail)}

    ${renderProjectPipeline(detail)}

    <div class="detail-section">
      <div class="detail-section-title">📜 操作日志</div>
      <div class="timeline">${detail.logs.map(l => `
        <div class="timeline-item"><div class="timeline-dot done"></div><div class="timeline-content"><div class="timeline-title">${escHtml(cleanLogAction(l.action))}</div><div class="timeline-time">${renderLogLabel(l)} · ${fmtDT(l.time)}</div></div></div>
      `).join('')}</div>
    </div>`;
}

function renderProductArchiveSection(detail) {
  let raw = {}; try { raw = JSON.parse(detail.productArchiveJson || '{}') || {}; } catch (e) {}
  const types = [['production_config','生产配置表（盖章）'],['manual','说明书'],['quality_report','质检报告'],['ccc_report','3C报告'],['business_license','制造商营业执照'],['packaging_sign','包装签字表'],['specification_box','规格箱'],['packaging_pdf','包装PDF']];
  const hasFiles = types.some(([key]) => Array.isArray(raw[key]?.files) && raw[key].files.length);
  return `<div class="detail-section"><div class="detail-section-title">📦 产品档案资料 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">（可选）</span></div>
    ${!hasFiles && !raw.remark ? '<div class="empty" style="padding:18px;">暂无档案资料</div>' : `<div class="detail-grid">${types.map(([key,label]) => { const files = Array.isArray(raw[key]?.files) ? raw[key].files : []; return files.length ? `<div class="detail-item"><div class="detail-label">${label}</div><div class="detail-value">${files.map(f => `<a href="${escHtml(normalizeFileUrl(f.url))}" target="_blank" rel="noopener">📎 ${escHtml(f.name)}</a>`).join('<br>')}</div></div>` : ''; }).join('')}</div>
    <div style="margin-top:10px;display:flex;gap:20px;font-size:13px;"><span>是否齐全：${raw.complete ? '是' : '未标记'}</span>${raw.remark ? `<span>备注：${escHtml(raw.remark)}</span>` : ''}</div>`}</div>`;
}

// 清理操作日志文本：删除末尾的（xxx）、（planner已评）等
function cleanLogAction(action) {
  if (!action) return '';
  return action.replace(/\s*（[^）]*）\s*$/, '').trim();
}

function renderLogLabel(l) {
  const roleName = l.role === 'sales' ? '销售' : l.role === 'planner' ? '产品企划' : l.role === 'designer' ? '设计师' : l.role === 'supplychain' ? '供应链' : l.role === 'admin' ? '管理员' : l.role;
  const isScore = l.action && l.action.includes('评分');
  // 评分日志特殊格式
  if (isScore) {
    // 提取角色名: "子任务评分：xxx（planner已评）"
    const match = l.action.match(/（(.+?)已评/);
    const scoreRole = match ? match[1] : l.role;
    const scoreRoleName = scoreRole === 'sales' ? '销售' : scoreRole === 'planner' ? '产品企划' : scoreRole === 'designer' ? '设计师' : scoreRole === 'supplychain' ? '供应链' : scoreRole;
    return `（${escHtml(scoreRoleName)}：${escHtml(l.user)} 已评）`;
  }
  return `（${escHtml(roleName)}：${escHtml(l.user)}）`;
}

function renderSubTaskCard(detail, task, idx) {
  const tsi = getTaskStatusInfo(task.status);
  const isPlanner = EMIE.state.currentRole === 'planner';
  const myTask = ['designer', 'supplychain', 'planner', 'promotion'].includes(EMIE.state.currentRole) && task.designerId === getCurrentUserId();
  const needScore = task.scoringRecords && task.scoringRecords.some(sr => sr.score == null && sr.role === EMIE.state.currentRole);
  // 产品企划对设计师/供应链交付的首轮验收会在“通过并评分”弹窗中一次完成，
  // 不再同时展示一个独立的评分按钮，避免产生重复入口。
  const plannerApproveAndScore = isPlanner && task.status === 'submitted_for_review';
  const plannerSubmitReview = isPlanner && String(getCurrentUserId()) === String(detail.plannerId || '') && task.status === 'delivered' && ['designer', 'supplychain'].includes(task.assigneeRole);
  const doneStatuses = ['delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const isDone = doneStatuses.includes(task.status);
  const workflowStageLabel = {
    design: '设计',
    design_review: '设计送审',
    three_d_review: '3D送审',
    sample_review: '打样送审',
    promotion: '产品宣发',
    bulk: '大货',
  }[task.workflowStage] || '未设置阶段';
  const rejectionRecords = Array.isArray(task.rejectionRecords) ? task.rejectionRecords : [];
  const latestRejection = rejectionRecords.length ? rejectionRecords[rejectionRecords.length - 1] : null;
  const deliveryVersions = Array.isArray(task.deliveryVersions) ? task.deliveryVersions : [];
  const visibleDeadline = task.status === 'rejected' && latestRejection?.requiredCompletionDate
    ? latestRejection.requiredCompletionDate : task.plannedDate;

  return `<div class="subtask-card${isDone ? ' completed' : ''}" role="button" tabindex="0"
    data-emie-onclick="openProjectSubTaskDetail(event,${task.id})"
    data-emie-onkeydown="if(event.key==='Enter'||event.key===' '){openProjectSubTaskDetail(event,${task.id})}"
    style="cursor:pointer;">
    <div class="subtask-header">
      <div class="subtask-name"><span class="subtask-number">${idx + 1}</span> ${escHtml(task.name)}</div>
      <span class="badge ${tsi.cls}">${tsi.label}</span>
    </div>
    <div class="subtask-meta">
      <div class="subtask-meta-item">📍 所属阶段：<strong>${escHtml(workflowStageLabel)}</strong></div>
      <div class="subtask-meta-item">👤 负责人：<strong>${escHtml(task.designerName || '待分配')}</strong>${task.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${task.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : task.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : task.assigneeRole === 'promotion' ? 'background:#F5F3FF;color:#7C3AED;' : 'background:#FEF2F2;color:#DC2626;'}">${task.assigneeRole === 'supplychain' ? '供应链' : task.assigneeRole === 'planner' ? '企划' : task.assigneeRole === 'promotion' ? '产品推广' : task.assigneeRole === 'sales' ? '销售' : '设计师'}</span>` : ''}</div>
      <div class="subtask-meta-item">📅 ${task.status === 'rejected' && latestRejection?.requiredCompletionDate ? '驳回后要求完成' : '计划完成'}：<strong>${formatDate(visibleDeadline)}</strong></div>
      ${task.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(task.actualDate)}</strong></div>` : ''}
      ${task.pointRuleCode ? `<div class="subtask-meta-item">🏅 积分：<strong>${escHtml(taskPointSummary(task))}</strong></div>` : ''}
    </div>
    ${task.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:6px;white-space:pre-wrap;">📝 ${escHtml(task.details)}</div>` : ''}
    ${task.referenceImagesJson ? renderSubTaskImages(task.referenceImagesJson) : ''}
    ${task.attachmentsJson ? renderTaskAttachments(task.attachmentsJson) : ''}

    ${['delivered', 'submitted_for_review', 'planner_approved', 'sales_approved', 'admin_approved', 'approved', 'completed', 'rejected'].includes(task.status) ? `
    <div class="subtask-deliver">
      ${task.deliverables ? `<div class="detail-item"><div class="detail-label">交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
    </div>` : ''}

    ${deliveryVersions.length ? `<div style="margin-top:12px;border-top:1px solid var(--gray-100);padding-top:10px;"><div style="font-size:13px;font-weight:700;margin-bottom:6px;">交付版本 <span style="color:var(--gray-400);font-weight:400;">(${deliveryVersions.length})</span></div>${deliveryVersions.map(version => { const rejection = rejectionRecords.find(record => Number(record.attemptNo) === Number(version.versionNo)); return `<details style="border:1px solid var(--gray-200);border-radius:8px;margin-bottom:5px;overflow:hidden;background:var(--gray-50);"><summary style="display:flex;align-items:center;gap:8px;padding:7px 10px;cursor:pointer;list-style:none;font-size:12px;"><strong>V${version.versionNo}</strong><span class="badge ${version.submissionType === 'redelivery' ? 'badge-rejected' : version.submissionType === 'correction' ? 'badge-pending' : 'badge-completed'}">${version.submissionType === 'redelivery' ? '驳回后重交' : version.submissionType === 'correction' ? '主动修正' : '首次交付'}</span><span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--gray-600);">${escHtml(version.changeSummary || '')}</span><span style="color:var(--gray-400);white-space:nowrap;">${version.submittedAt ? fmtDT(version.submittedAt) : ''}</span><span style="color:var(--gray-400);">⌄</span></summary><div style="padding:10px 12px;border-top:1px solid var(--gray-200);background:#fff;"><div style="font-size:12px;color:var(--gray-700);white-space:pre-wrap;">${escHtml(version.deliverables || '未填写文字交付内容')}</div>${rejection ? `<div style="margin-top:8px;padding:8px 10px;border-radius:6px;background:#FFF8F8;color:#A32D2D;font-size:12px;"><strong>驳回意见：</strong>${escHtml(rejection.reason || '未填写驳回意见')}</div>` : ''}${version.referenceImagesJson ? renderSubTaskImages(version.referenceImagesJson) : ''}${version.attachmentsJson ? renderTaskAttachments(version.attachmentsJson) : ''}</div></details>`; }).join('')}</div>` : ''}

    ${task.reviewComments ? `<div class="review-box ${task.status === 'rejected' ? 'rejected' : 'approved'}"><strong>${task.status === 'rejected' ? '驳回意见' : '验收意见'}：</strong>${escHtml(task.reviewComments)}</div>` : ''}

    ${task.scoringRecords && ['planner_approved', 'sales_approved', 'admin_approved', 'approved', 'completed'].includes(task.status) ? renderScoringMini(task, isDone) : ''}

    <div class="subtask-actions">
      ${/* 企划验收（首轮）：常规品直接通过；渠道定制单进入企划确认状态 */''}
      ${plannerSubmitReview ? `<button class="btn btn-primary btn-sm" data-emie-onclick="submitTaskReview(${detail.id},${task.id})">🔎 送审</button>` : ''}
      ${isPlanner && task.status === 'submitted_for_review' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'${detail.type}')">✅ ${plannerApproveAndScore ? '通过并评分' : '验收通过'}</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${isPlanner && task.status === 'delivered' ? `<button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>` : ''}
      ${/* 渠道定制单：销售第二轮确认 */''}
      ${EMIE.state.currentRole === 'sales' && detail.type === 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'channel_custom')">✅ 销售确认通过</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${EMIE.state.currentRole === 'admin' && detail.type !== 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" data-emie-onclick="taskApprove(${detail.id},${task.id},'regular')">✅ 管理确认通过</button>
        <button class="btn btn-danger btn-sm" data-emie-onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${myTask && task.status === 'pending' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskAccept(${detail.id},${task.id})">✅ 接单</button>` : ''}
      ${myTask && task.status === 'accepted' ? `<button class="btn btn-primary btn-sm" data-emie-onclick="taskDeliver(${detail.id},${task.id})">📤 交付成果</button>` : ''}
      ${myTask && task.status === 'rejected' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="taskConfirmRevision(${detail.id},${task.id})">🛠️ 确认修改</button>` : ''}
      ${myTask && ['delivered', 'planner_approved', 'sales_approved', 'admin_approved'].includes(task.status) ? `<button class="btn btn-outline btn-sm" data-emie-onclick="taskCorrectDelivery(${detail.id},${task.id})">📝 修正交付</button>` : ''}
      ${isPlanner && detail.status !== 'paused' && (task.status === 'pending' || task.status === 'accepted') ? `
        ${task.allocationStatus === 'market_open' ? `<button class="btn btn-warning btn-sm" data-emie-onclick="withdrawMarketTask(${detail.id},${task.id})">撤回市场</button>` : ''}
        <button class="btn btn-outline btn-sm" data-emie-onclick="editTask(${detail.id},${task.id})">✏️ 编辑</button>
        <button class="btn btn-outline btn-sm" data-emie-onclick="deleteTask(${detail.id},${task.id})" style="color:var(--danger);border-color:var(--danger);">🗑️ 删除</button>
      ` : ''}
      ${needScore && !(isPlanner && ['delivered', 'submitted_for_review'].includes(task.status)) ? `<button class="btn btn-warning btn-sm" data-emie-onclick="openScoring(${detail.id},${task.id})">⭐ 评分</button>` : ''}
    </div>
  </div>`;
}

function openProjectSubTaskDetail(event, taskId) {
  if (event?.target?.closest('button,a,input,textarea,select,details,summary,[data-emie-no-card-open]')) return;
  if (event?.type === 'keydown') event.preventDefault();
  if (document.getElementById('projectSubTaskDetailModal')) return;
  const task = EMIE.projectState.currentProjectDetail?.tasks?.find(item => Number(item.id) === Number(taskId));
  if (!task) return;
  const tsi = getTaskStatusInfo(task.status);
  const records = Array.isArray(task.rejectionRecords) ? task.rejectionRecords : [];
  const deliveryVersions = Array.isArray(task.deliveryVersions) ? task.deliveryVersions : [];
  const workflowStageLabel = {
    design: '设计', design_review: '设计送审', three_d_review: '3D送审',
    sample_review: '打样送审', promotion: '产品宣发', bulk: '大货',
  }[task.workflowStage] || '未设置阶段';
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'projectSubTaskDetailModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:760px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-onclick="closeM('projectSubTaskDetailModal')">✕</button>
        <div class="modal-header-left">
          <div class="modal-title">子任务详情 · ${escHtml(task.name || '-')}</div>
          <div style="font-size:12px;color:var(--gray-400);margin-top:3px;">所属阶段：${escHtml(workflowStageLabel)} · #${task.id}</div>
        </div>
        <span class="badge ${tsi.cls}">${tsi.label}</span>
      </div>
      <div class="modal-body">
        <div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;">
          <div class="detail-item"><div class="detail-label">负责人</div><div class="detail-value">${escHtml(task.designerName || '待分配')}</div></div>
          <div class="detail-item"><div class="detail-label">计划完成</div><div class="detail-value">${formatDate(task.plannedDate)}</div></div>
          <div class="detail-item"><div class="detail-label">实际完成</div><div class="detail-value">${task.actualDate ? formatDate(task.actualDate) : '-'}</div></div>
          <div class="detail-item"><div class="detail-label">修改要求次数</div><div class="detail-value">${records.length} 次</div></div>
          ${task.pointRuleCode ? `<div class="detail-item"><div class="detail-label">积分规则</div><div class="detail-value">${escHtml(task.pointRuleCode)}</div></div><div class="detail-item"><div class="detail-label">难度与积分快照</div><div class="detail-value">${escHtml(pointDifficultyLabel(task.difficultyCode))} · ${Number(task.basePointSnapshot || 0)} × ${Number(task.difficultyMultiplierSnapshot || 1)}</div></div><div class="detail-item"><div class="detail-label">合作分配快照</div><div class="detail-value">${escHtml(taskCollaborationSummary(task))}</div></div><div class="detail-item"><div class="detail-label">积分归属月份</div><div class="detail-value">${escHtml(task.milestoneMonth || '按实际入账月份')}</div></div><div class="detail-item"><div class="detail-label">指派/立项说明</div><div class="detail-value">${escHtml(task.assignmentReason || '-')}</div></div>` : ''}
        </div>
        <div class="detail-item" style="margin-top:12px;">
          <div class="detail-label">任务要求</div>
          <div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.details || '未填写')}</div>
        </div>
        ${task.referenceImagesJson ? renderSubTaskImages(task.referenceImagesJson) : ''}
        ${task.attachmentsJson ? renderTaskAttachments(task.attachmentsJson) : ''}
        ${task.deliverables ? `<div class="detail-item" style="margin-top:12px;"><div class="detail-label">当前交付成果</div><div class="detail-value" style="white-space:pre-wrap;">${escHtml(task.deliverables)}</div></div>` : ''}
        <div style="margin-top:18px;">
          <div style="font-size:14px;font-weight:700;margin-bottom:8px;">交付版本 <span style="color:var(--gray-400);font-weight:400;">(${deliveryVersions.length})</span></div>
          ${deliveryVersions.length ? deliveryVersions.map(version => `
            <details style="border:1px solid var(--gray-200);border-radius:9px;margin-bottom:8px;overflow:hidden;">
              <summary style="display:flex;align-items:center;gap:8px;padding:10px 12px;background:var(--gray-50);cursor:pointer;list-style:none;">
                <strong>V${version.versionNo}</strong>
                <span class="badge ${version.submissionType === 'correction' ? 'badge-pending' : version.submissionType === 'redelivery' ? 'badge-rejected' : 'badge-completed'}">${version.submissionType === 'correction' ? '主动修正' : version.submissionType === 'redelivery' ? '驳回后重交' : '首次交付'}</span>
                <span style="flex:1;color:var(--gray-600);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escHtml(version.changeSummary || '')}</span>
                <span style="font-size:11px;color:var(--gray-400);">${fmtDT(version.submittedAt)}</span>
              </summary>
              <div style="padding:12px;">
                <div class="detail-label">提交人：${escHtml(version.submittedByName || '-')} · 自评：${version.selfScore ?? '-'} 分</div>
                <div class="detail-value" style="white-space:pre-wrap;margin-top:5px;">${escHtml(version.deliverables || '未填写文字交付内容')}</div>
                ${version.referenceImagesJson ? renderSubTaskImages(version.referenceImagesJson) : ''}
                ${version.attachmentsJson ? renderTaskAttachments(version.attachmentsJson) : ''}
              </div>
            </details>`).join('') : '<div style="font-size:12px;color:var(--gray-400);">暂无交付版本记录</div>'}
        </div>
        <div style="margin-top:18px;">
          <div style="font-size:14px;font-weight:700;margin-bottom:8px;">修改要求记录 <span style="color:var(--gray-400);font-weight:400;">(${records.length})</span></div>
          ${records.length ? records.map(record => `
            <button type="button" data-emie-onclick="openTaskRejectionRecord(${task.id},${record.attemptNo})"
              style="width:100%;display:flex;align-items:center;gap:8px;padding:10px 12px;margin-bottom:8px;border:1px solid #F3C1C1;border-radius:9px;background:#FFF8F8;cursor:pointer;text-align:left;">
              <strong style="color:#A32D2D;white-space:nowrap;">第 ${record.attemptNo} 次修改要求</strong>
              <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--gray-600);">${escHtml(record.reason || '未填写修改意见')}</span>
              <span style="font-size:11px;color:var(--gray-400);white-space:nowrap;">${fmtDT(record.reviewedAt)}</span>
              <span style="color:var(--primary);white-space:nowrap;">查看详情 ›</span>
            </button>`).join('') : '<div class="empty" style="padding:24px;"><p>暂无修改要求记录</p></div>'}
        </div>
      </div>
      <div class="modal-footer"><button class="btn btn-primary" data-emie-onclick="closeM('projectSubTaskDetailModal')">关闭</button></div>
    </div>`;
  document.body.appendChild(modal);
}

function openTaskRejectionRecord(taskId, attemptNo) {
  if (document.getElementById('taskRejectionRecordModal')) return;
  const task = EMIE.projectState.currentProjectDetail?.tasks?.find(item => Number(item.id) === Number(taskId));
  const record = task?.rejectionRecords?.find(item => Number(item.attemptNo) === Number(attemptNo));
  if (!task || !record) return;
  const roleNames = { planner: '产品企划', sales: '销售', admin: '管理员', designer: '设计师', supplychain: '供应链', promotion: '产品推广' };
  const submittedImages = record.referenceImagesJson ? renderSubTaskImages(record.referenceImagesJson) : '';
  const submittedAttachments = record.attachmentsJson ? renderTaskAttachments(record.attachmentsJson) : '';
  const rejectionImages = record.rejectionReferenceImagesJson ? renderSubTaskImages(record.rejectionReferenceImagesJson) : '';
  const rejectionAttachments = record.rejectionAttachmentsJson ? renderTaskAttachments(record.rejectionAttachmentsJson) : '';
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'taskRejectionRecordModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:700px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-onclick="closeM('taskRejectionRecordModal')">✕</button>
        <div class="modal-header-left">
          <div class="modal-title">↩ 第 ${record.attemptNo} 次驳回详情</div>
          <div style="font-size:12px;color:var(--gray-400);margin-top:3px;">${escHtml(task.name)} · ${fmtDT(record.reviewedAt)}</div>
        </div>
      </div>
      <div class="modal-body">
        <div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-bottom:14px;">
          <div class="detail-item"><div class="detail-label">本次提交人</div><div class="detail-value">${escHtml(record.submittedByName || task.designerName || '-')}</div></div>
          <div class="detail-item"><div class="detail-label">实际提交时间</div><div class="detail-value">${record.actualDate ? formatDate(record.actualDate) : '-'}</div></div>
        </div>
        <div style="padding:14px 16px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:9px;">
          <div class="detail-label">本次提交内容</div>
          <div class="detail-value" style="white-space:pre-wrap;margin-top:5px;">${escHtml(record.deliverables || '未填写文字交付内容')}</div>
          ${submittedImages}
          ${submittedAttachments}
          ${record.legacy ? '<div style="font-size:11px;color:var(--gray-400);margin-top:8px;">历史记录创建时未保存独立快照，附件展示为当前可恢复内容。</div>' : ''}
        </div>
        <div style="margin-top:14px;padding:14px 16px;background:#FFF8F8;border:1px solid #F7C1C1;border-radius:9px;">
          <div style="display:flex;gap:8px;align-items:center;margin-bottom:7px;">
            <span style="font-size:12px;font-weight:600;color:#A32D2D;">驳回意见</span>
            <span style="font-size:11px;color:var(--gray-500);">${escHtml(roleNames[record.reviewerRole] || record.reviewerRole || '')} · ${escHtml(record.reviewerName || '-')}</span>
          </div>
          <div class="detail-label" style="color:#A32D2D;margin-bottom:4px;">要求完成时间：${record.requiredCompletionDate ? formatDate(record.requiredCompletionDate) : '-'}</div>
          <div style="font-size:13px;line-height:1.7;color:var(--gray-700);white-space:pre-wrap;">${escHtml(record.reason || '未填写驳回意见')}</div>
          ${rejectionImages ? `<div style="margin-top:14px;"><div class="detail-label">驳回参考图</div>${rejectionImages}</div>` : ''}
          ${rejectionAttachments ? `<div style="margin-top:14px;"><div class="detail-label">驳回附件</div>${rejectionAttachments}</div>` : ''}
        </div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('taskRejectionRecordModal')">关闭</button></div>
    </div>`;
  document.body.appendChild(modal);
}

function renderProjectActions(detail) {
  let actions = '';
  // 与 ProjectAccessPolicy.canManage 保持一致：角色本身不代表拥有当前项目的操作权。
  // 产品企划只能管理自己负责的常规品项目，销售只能管理自己负责的渠道定制项目；管理员可管理全部项目。
  const currentUserId = getCurrentUserId();
  const canManageProject = EMIE.state.currentRole === 'admin'
    || (EMIE.state.currentRole === 'planner'
      && ((detail.type !== 'channel_custom'
        && detail.plannerId != null
        && String(detail.plannerId) === String(currentUserId))
        || (detail.type === 'channel_custom'
          && detail.status === 'pending_planner'
          && !detail.plannerId)))
    || (EMIE.state.currentRole === 'sales'
      && detail.type === 'channel_custom'
      && detail.salesId != null
      && String(detail.salesId) === String(currentUserId));
  const canCreateProjectChat = ['admin', 'planner'].includes(EMIE.state.currentRole)
    || canManageProject;

  if (EMIE.state.currentRole === 'planner' && detail.status === 'pending_planner' && detail.type === 'channel_custom') {
    actions += `<button class="btn btn-primary" data-emie-onclick="plannerAcceptProject(${detail.id})">✅ 接单</button>`;
  }

  if (canManageProject) {
    const activeStatuses = ['pending_planner', 'planner_accepted', 'in_progress'];

    // 终止按钮（含暂停状态，暂停也可终止）
    if (activeStatuses.includes(detail.status) || detail.status === 'paused') {
      actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="terminateProject(${detail.id})">终止项目</button>`;
    }
    // 终止确认中 - 对方可以确认
    if (detail.status === 'pending_terminate') {
      actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="terminateProject(${detail.id})">确认终止</button>`;
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="cancelTerminate(${detail.id})" style="color:var(--gray-600);border-color:var(--gray-300);">↩️ 取消终止</button>`;
    }

    // 暂停 / 继续
    if (activeStatuses.includes(detail.status)) {
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="pauseProject(${detail.id})" style="color:var(--primary);border-color:var(--primary);">暂停</button>`;
    }
    if (detail.status === 'paused') {
      actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="resumeProject(${detail.id})" style="color:var(--success);border-color:var(--success);">继续</button>`;
    }
  }

  if (detail.status === 'terminated') {
    actions += `<span style="color:var(--danger);font-size:13px;font-weight:600;">⛔ 该项目已终止，无法进行任何操作</span>`;
  }
  // 群 ID 才是群聊是否已创建的权威依据。成员同步失败会把状态记为 failed，
  // 但群本身仍然存在，此时必须继续保留“进入项目群”入口。
  const hasProjectChat = !!String(detail.feishuChatId || '').trim();
  if (hasProjectChat) {
    actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="openProjectFeishuChat('${escHtml(escJsString(detail.feishuChatId))}')">💬 进入项目群</button>`;
  } else if (canCreateProjectChat && !['terminated', 'pending_terminate'].includes(detail.status)
      && (!detail.feishuChatStatus || ['not_created', 'failed', 'dissolved'].includes(detail.feishuChatStatus))) {
    actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="createProjectFeishuChat(${detail.id})">💬 创建项目群</button>`;
  }
  if (hasProjectChat && ['completed', 'terminated', 'pending_terminate'].includes(detail.status)) {
    actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="dissolveProjectFeishuChat(${detail.id})">解散项目群</button>`;
  }
  // 管理员可永久删除项目
  if (EMIE.state.currentRole === 'admin') {
    actions += `<button class="btn btn-danger btn-sm" data-emie-onclick="deleteProject(${detail.id})" title="永久删除项目和所有关联数据">🗑️ 删除</button>`;
  }
  actions += `<button class="btn btn-outline btn-sm" data-emie-onclick="shareProject(${detail.id})">🔗 分享</button>`;
  actions += `<button class="btn btn-outline" data-emie-onclick="closeM('projectDetailModal')">关闭</button>`;
  return actions;
}

async function createProjectFeishuChat(id) {
  try { await apiPost(`/projects/${id}/feishu-chat/create`, {}); window.EMIE.actions.showSystemAlert('项目群创建成功'); await refreshAfterMutation(id); }
  catch (e) { window.EMIE.actions.showSystemAlert('创建项目群失败：' + e.message); }
}
async function dissolveProjectFeishuChat(id) {
  if (!confirm('确认解散项目群？解散后无法恢复。')) return;
  try { await apiPost(`/projects/${id}/feishu-chat/dissolve`, {}); window.EMIE.actions.showSystemAlert('项目群已解散'); await refreshAfterMutation(id); }
  catch (e) { window.EMIE.actions.showSystemAlert('解散项目群失败：' + e.message); }
}
function openProjectFeishuChat(chatId) {
  const url = `https://applink.feishu.cn/client/chat/open?openChatId=${encodeURIComponent(chatId)}`;
  // 新窗口打开飞书，保留当前项目管理系统页面和填写状态。
  const opened = window.open(url, '_blank');
  if (!opened) window.EMIE.actions.showSystemAlert('浏览器拦截了新窗口，请允许本站弹窗后重试。');
}

// 飞书兼容的确认弹窗
function showConfirmDialog(message, onConfirm, confirmText, cancelText) {
  if (document.getElementById('confirmDialogOverlay')) return;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'confirmDialogOverlay';
  overlay.style.zIndex = '300';
  overlay.innerHTML = `
    <div class="modal" style="max-width:380px;">
      <div class="modal-header">
        <button class="modal-close" data-emie-onclick="this.closest('.modal-overlay').remove()">✕</button>
        <div class="modal-header-left"><div class="modal-title">⚠️ 确认操作</div></div>
      </div>
      <div class="modal-body" style="text-align:center;padding:28px 20px;">
        <p style="font-size:14px;color:var(--gray-700);margin:0;line-height:1.6;">${message}</p>
      </div>
      <div class="modal-footer" style="justify-content:center;gap:12px;padding:12px 20px;">
        <button class="btn btn-outline" data-emie-onclick="this.closest('.modal-overlay').remove()" style="padding:8px 20px;">${cancelText || '取消'}</button>
        <button class="btn btn-danger" id="confirmDialogOk" style="padding:8px 20px;">${confirmText || '确定'}</button>
      </div>
    </div>`;
  overlay.querySelector('#confirmDialogOk').onclick = function() { overlay.remove(); onConfirm(); };
  document.body.appendChild(overlay);
}

async function terminateProject(pid) {
  showConfirmDialog('确定要终止该项目吗？终止后项目将无法恢复。', async () => {
    try {
      await apiPost(`/projects/${pid}/terminate`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { window.EMIE.actions.showSystemAlert('操作失败: ' + e.message); }
  });
}

async function cancelTerminate(pid) {
  showConfirmDialog('确定要取消终止吗？', async () => {
    try {
      await apiPost(`/projects/${pid}/cancel-terminate`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { window.EMIE.actions.showSystemAlert('操作失败: ' + e.message); }
  });
}

async function deleteProject(pid) {
  showConfirmDialog('⚠️ 确定要永久删除项目 #' + pid + ' 吗？<br>此操作不可恢复！<br>子任务、日志、评分记录将一并删除。', async () => {
    try {
      await apiDelete(`/projects/${pid}`);
      closeM('projectDetailModal');
      EMIE.state.cache.orders = [];
      clearSWRCache();
      render();
    } catch(e) { window.EMIE.actions.showSystemAlert('删除失败: ' + e.message); }
  });
}

async function pauseProject(pid) {
  showConfirmDialog('确定要暂停该项目吗？暂停期间无法进行任何操作。', async () => {
    try {
      await apiPost(`/projects/${pid}/pause`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { window.EMIE.actions.showSystemAlert('操作失败: ' + e.message); }
  });
}

async function resumeProject(pid) {
  try {
    await apiPost(`/projects/${pid}/resume`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole });
    await refreshAfterMutation(pid);
  } catch(e) { window.EMIE.actions.showSystemAlert('操作失败: ' + e.message); }
}

// 渲染项目参考图片
// 兼容历史数据中的旧域名、/uploads 路径和缺少当前下载路径的记录。
function normalizeFileUrl(fileOrUrl) {
  const raw = typeof fileOrUrl === 'string' ? fileOrUrl : (fileOrUrl?.url || '');
  const storedName = typeof fileOrUrl === 'object' && fileOrUrl?.storedName
    ? fileOrUrl.storedName
    : raw.split('?')[0].split('/').pop();
  if (!storedName) return raw;
  // 历史数据可能把原始文件名直接写进 url；交给后端按 originalName 找回真实存储名。
  if (typeof fileOrUrl === 'object' && !fileOrUrl?.storedName && fileOrUrl?.name && storedName === fileOrUrl.name) {
    return '/api/files/download-by-original?name=' + encodeURIComponent(fileOrUrl.name);
  }
  return '/api/files/download/' + encodeURIComponent(storedName);
}

function storedNameFromFile(fileOrUrl) {
  if (typeof fileOrUrl === 'object' && fileOrUrl?.storedName) return fileOrUrl.storedName;
  const normalized = normalizeFileUrl(fileOrUrl).split('?')[0];
  const rawName = normalized.split('/').pop() || '';
  try { return decodeURIComponent(rawName); } catch (e) { return rawName; }
}

function originalFileUrl(fileOrUrl, kind) {
  const name = typeof fileOrUrl === 'object' ? (fileOrUrl?.name || '') : '';
  if (name && typeof fileOrUrl === 'object' && !fileOrUrl?.storedName) {
    return '/api/files/' + kind + '-by-original?name=' + encodeURIComponent(name);
  }
  return '/api/files/' + kind + '/' + encodeURIComponent(storedNameFromFile(fileOrUrl));
}

function protectedFileUrl(url) {
  const token = localStorage.getItem('design_pm_token');
  if (!token) return url;
  return url + (url.includes('?') ? '&' : '?') + 'access_token=' + encodeURIComponent(token);
}

function isPreviewableFile(fileName) {
  return /\.(pdf|ppt|pptx)$/i.test(fileName || '');
}

function isRasterImageFile(fileName) {
  return /\.(png|jpe?g|gif|webp|bmp)$/i.test(fileName || '');
}

function renderAttachmentActions(fileOrUrl, compact = false) {
  const fileName = typeof fileOrUrl === 'object'
    ? (fileOrUrl?.name || storedNameFromFile(fileOrUrl))
    : storedNameFromFile(fileOrUrl);
  const fileSize = typeof fileOrUrl === 'object' ? (fileOrUrl?.size || 0) : 0;
  const fileUrl = normalizeFileUrl(fileOrUrl);
  const previewButton = isPreviewableFile(fileName)
    ? `<button class="attachment-action-btn preview" data-emie-onclick="event.stopPropagation();openFilePreview('${escHtml(escJsString(fileUrl))}','${escHtml(escJsString(fileName))}',${fileSize})" title="在线预览">${compact ? '👁' : '👁 预览'}</button>`
    : '';
  return `<span class="attachment-actions">${previewButton}<button class="attachment-action-btn download" data-emie-onclick="event.stopPropagation();showDownloadOptions('${escHtml(escJsString(fileUrl))}','${escHtml(escJsString(fileName))}',${fileSize})" title="下载选项">${compact ? '⬇' : '⬇ 下载'}</button></span>`;
}

function renderProjectReferenceImages(detail) {
  if (!detail.referenceImagesJson) return '';
  let imgs;
  try { imgs = JSON.parse(detail.referenceImagesJson); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const authUrl = u => protectedFileUrl(normalizeFileUrl(u));
  const thumbUrl = u => protectedFileUrl(originalFileUrl(u, 'thumbnail'));
  return `<div style="margin-top:8px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => isRasterImageFile(img.name || storedNameFromFile(img)) ? `<div style="position:relative;display:inline-block;">
          <img src="${escHtml(thumbUrl(img))}" data-full-src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" title="${escHtml(img.name || '')}" class="img-clickable" loading="eager" decoding="async" style="cursor:pointer;">
          <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escHtml(escJsString(normalizeFileUrl(img)))}','${escHtml(escJsString(img.name || 'image.png'))}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>` : `<div class="attachment-item" style="width:100%;display:flex;align-items:center;gap:8px;"><span>📐</span><span class="attachment-name" style="flex:1;">${escHtml(img.name || storedNameFromFile(img))}</span>${renderAttachmentActions(img)}</div>`).join('')}
    </div></div>`;
}

/* 子任务参考图 */
function renderSubTaskImages(jsonStr) {
  if (!jsonStr) return '';
  let imgs;
  try { imgs = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const authUrl = u => protectedFileUrl(normalizeFileUrl(u));
  const thumbUrl = u => protectedFileUrl(originalFileUrl(u, 'thumbnail'));
  return `<div style="margin-top:8px;padding-left:4px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => isRasterImageFile(img.name || storedNameFromFile(img)) ? `<div style="position:relative;display:inline-block;">
          <img src="${escHtml(thumbUrl(img))}" data-full-src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" class="img-clickable" loading="eager" decoding="async" style="cursor:pointer;">
          <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escHtml(escJsString(normalizeFileUrl(img)))}','${escHtml(escJsString(img.name || 'image.png'))}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>` : `<div class="attachment-item" style="width:100%;display:flex;align-items:center;gap:8px;"><span>📐</span><span class="attachment-name" style="flex:1;">${escHtml(img.name || storedNameFromFile(img))}</span>${renderAttachmentActions(img)}</div>`).join('')}
    </div></div>`;
}

// 渲染项目附件
function renderProjectAttachments(detail) {
  if (!detail.attachmentsJson) return '';
  let atts;
  try { atts = JSON.parse(detail.attachmentsJson); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';
  return `<div style="margin-top:8px;"><div class="detail-label">📎 附件</div>
    ${atts.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
      <span>📎</span><span class="attachment-name" style="flex:1;">${escHtml(a.name)}</span>
      ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
      ${renderAttachmentActions(a)}
    </div>`).join('')}
    </div>`;
}

// 渲染子任务交付附件
function renderTaskAttachments(jsonStr) {
  if (!jsonStr) return '';
  let atts;
  try { atts = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';

  // 分离图片和附件文件
  const images = atts.filter(a => a.name && a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));
  const files = atts.filter(a => !a.name || !a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));

  let html = '';
  const authUrl = u => protectedFileUrl(normalizeFileUrl(u));
  const thumbUrl = u => protectedFileUrl(originalFileUrl(u, 'thumbnail'));
  // 图片预览
  if (images.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">🖼️ 交付图片</div>
      <div class="image-preview" style="margin-top:4px;">
        ${images.map(img => `<div style="position:relative;display:inline-block;">
            <img src="${escHtml(thumbUrl(img))}" data-full-src="${escHtml(authUrl(img))}" alt="${escHtml(img.name || '')}" class="img-clickable img-preview-large" draggable="true" loading="eager" decoding="async" style="cursor:grab;">
            <button data-emie-onclick="event.stopPropagation();showDownloadOptions('${escHtml(escJsString(normalizeFileUrl(img)))}','${escHtml(escJsString(img.name || 'image.png'))}',${img.size || 0})" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
        </div>`).join('')}
      </div></div>`;
  }
  // 附件下载
  if (files.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">📎 交付附件</div>
      ${files.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
        <span>📎</span><span class="attachment-name" style="flex:1;">${escHtml(a.name)}</span>
        ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
        ${renderAttachmentActions(a)}
      </div>`).join('')}
      </div>`;
  }
  return html;
}

function renderProjectScoringSummary(detail) {
  const approvedTasks = detail.tasks.filter(t => ['approved', 'completed', 'sales_approved', 'admin_approved'].includes(t.status) && t.scoringRecords && t.scoringRecords.length > 0);
  if (approvedTasks.length === 0) return '';
  const allScoredTasks = approvedTasks.filter(t => t.scoringRecords.every(sr => sr.score != null));

  let html = `<div class="detail-section"><div class="detail-section-title">⭐ 项目评分汇总 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${allScoredTasks.length}/${approvedTasks.length} 已完成</span></div>`;
  approvedTasks.forEach((task, i) => {
    const records = task.scoringRecords;
    let ta = 0, tw = 0;
    records.forEach(r => {
      if (r.score != null) {
        ta += r.score * r.weight;
        tw += r.weight;
      }
    });
    const final = tw > 0 ? Math.round(ta / tw) : null;
    html += `<div style="display:flex;align-items:center;gap:12px;padding:8px 12px;background:var(--gray-50);border-radius:6px;margin-bottom:6px;font-size:13px;">
      <span style="font-weight:600;">#${i + 1} ${escHtml(task.name)}</span>
      <span style="flex:1;"></span>
      ${final ? `<span style="font-size:16px;font-weight:700;color:var(--primary);">${final}分</span>` : `<span style="color:var(--gray-400);">评分中…</span>`}
    </div>`;
  });
  html += `</div>`;
  return html;
}

// ===== 子任务进度 =====
function renderSubTaskProgress(detail) {
  const workflow = detail.subTaskWorkflow || {};
  const stages = workflow.stages || [
    { key: 'design', label: '设计' },
    { key: 'design_review', label: '设计送审' },
    { key: 'three_d_review', label: '3D送审' },
    { key: 'sample_review', label: '打样送审' },
    { key: 'promotion', label: '产品宣发' },
    { key: 'bulk', label: '大货' },
  ];
  const tasks = Array.isArray(detail.tasks) ? detail.tasks : [];
  const completedStatuses = ['completed'];

  const stageHtml = stages.map((stage, index) => {
    const stageTasks = tasks.filter(task => (task.workflowStage || 'design') === stage.key);
    const completedCount = stageTasks.filter(task => completedStatuses.includes(task.status)).length;
    const rejectedCount = stageTasks.filter(task => task.status === 'rejected').length;
    const state = stageTasks.length === 0 ? 'pending'
      : rejectedCount > 0 ? 'error'
        : completedCount === stageTasks.length ? 'done' : 'current';
    const dotStyle = state === 'done'
      ? 'background:#3B6D11;'
      : state === 'current'
        ? 'background:#EF9F27;box-shadow:0 0 0 4px #FAEEDA;'
        : state === 'error'
          ? 'background:#E24B4A;box-shadow:0 0 0 4px #FCEBEB;'
          : 'background:var(--gray-50);border:2px solid var(--gray-300);';
    const labelColor = state === 'done' ? '#3B6D11'
      : state === 'error' ? '#A32D2D'
        : state === 'current' ? '#854F0B' : 'var(--gray-400)';
    let summary = state === 'pending' ? '待进行'
      : state === 'error' ? `${rejectedCount} 个被驳回`
        : state === 'done' ? `${completedCount}/${stageTasks.length} 已完成`
          : `${completedCount}/${stageTasks.length} 已完成`;
    const connector = index < stages.length - 1
      ? `<div style="position:absolute;top:14px;left:56%;right:-16px;height:3px;background:${state === 'done' ? '#3B6D11' : 'var(--gray-200)'};z-index:-1;"></div>`
      : '';
    const dotInner = state === 'done' ? '<span style="color:#fff;font-size:11px;">✓</span>'
      : state === 'error' ? '<span style="color:#fff;font-size:12px;">!</span>'
        : state === 'current' ? '<span style="color:#fff;font-size:12px;">●</span>'
          : `<span style="color:var(--gray-500);font-size:11px;font-weight:600;">${index + 1}</span>`;
    return `<div style="flex:1;text-align:center;position:relative;min-width:92px;">
      <div style="width:28px;height:28px;border-radius:50%;margin:0 auto 6px;display:flex;align-items:center;justify-content:center;${dotStyle}">${dotInner}</div>
      <div style="font-size:11px;color:${labelColor};font-weight:${state === 'current' || state === 'error' ? '600' : '500'};">${escHtml(stage.label)}</div>
      <div style="font-size:10px;color:${labelColor};margin-top:2px;">${summary}</div>
      ${connector}
    </div>`;
  }).join('');

  return `<div class="detail-section">
    <div class="detail-section-title">📌 子任务进度
      <span style="font-size:12px;color:var(--gray-400);font-weight:400;">按各阶段子任务的实际完成情况自动更新</span>
    </div>
    <div style="padding:20px 8px 8px;overflow-x:auto;">
      <div style="display:flex;gap:0;min-width:520px;">${stageHtml}</div>
    </div>
  </div>`;
}

async function completeWorkflowExecution(projectId) {
  if (!confirm('确认完成当前阶段并进入下一阶段？')) return;
  try {
    await apiPost(`/projects/${projectId}/workflow/complete-execution`, {});
    await refreshAfterMutation(projectId);
  } catch (e) { window.EMIE.actions.showSystemAlert('操作失败：' + e.message); }
}

async function submitWorkflowReview(projectId) {
  if (!confirm('确认提交本轮审核？提交后将等待审核人处理。')) return;
  try {
    await apiPost(`/projects/${projectId}/workflow/submit-review`, {});
    await refreshAfterMutation(projectId);
  } catch (e) { window.EMIE.actions.showSystemAlert('提交失败：' + e.message); }
}

function openWorkflowReviewModal(projectId, decision, stageLabel) {
  if (document.getElementById('workflowReviewModal')) return;
  const isReject = decision === 'rejected';
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'workflowReviewModal';
  modal.innerHTML = `<div class="modal" style="max-width:480px;">
    <div class="modal-header"><button class="modal-close" data-emie-onclick="closeM('workflowReviewModal')">✕</button>
      <div class="modal-header-left"><div class="modal-title">${isReject ? '↩️ 驳回' : '✅ 通过'}：${escHtml(stageLabel)}</div></div>
    </div>
    <div class="modal-body">
      <label class="form-label">${isReject ? '<span class="required">*</span>驳回原因' : '审核说明（选填）'}</label>
      <textarea class="form-textarea" id="workflowReviewComment" rows="4" maxlength="1000" placeholder="${isReject ? '请说明需要修改的内容，便于下一轮准确处理' : '可填写本轮审核说明'}"></textarea>
    </div>
    <div class="modal-footer">
      <button class="btn btn-outline" data-emie-onclick="closeM('workflowReviewModal')">取消</button>
      <button class="btn ${isReject ? 'btn-danger' : 'btn-primary'}" data-emie-onclick="submitWorkflowDecision(${projectId},'${decision}')">确认${isReject ? '驳回' : '通过'}</button>
    </div>
  </div>`;
  document.body.appendChild(modal);
}

async function submitWorkflowDecision(projectId, decision) {
  const comment = document.getElementById('workflowReviewComment')?.value?.trim() || '';
  if (decision === 'rejected' && !comment) { window.EMIE.actions.showSystemAlert('请填写驳回原因'); return; }
  try {
    await apiPost(`/projects/${projectId}/workflow/review`, { decision, comment });
    closeM('workflowReviewModal');
    await refreshAfterMutation(projectId);
  } catch (e) { window.EMIE.actions.showSystemAlert('审核失败：' + e.message); }
}

// ===== 项目总进度管道 =====
function renderProjectPipeline(detail) {
  const isChannel = detail.type === 'channel_custom';
  const tasks = detail.tasks || [];

  // 项目总进度只展示项目生命周期；评分属于子任务验收细节，不作为项目节点。
  const stages = [
    { key: 'create', label: '创建项目', detail: isChannel ? '销售 ' + (detail.salesName || '') : '' },
    { key: 'accept', label: '企划接单', detail: detail.plannerName || '' },
    { key: 'execute', label: '子任务进行中', detail: '' },
    { key: 'complete', label: '项目完结', detail: '' },
  ];

  // 计算各阶段状态: done / current / pending / error
  const status = detail.status;
  const taskStatuses = tasks.map(t => t.status);
  const bulkTasks = tasks.filter(t => t.workflowStage === 'bulk');
  const bulkStageCompleted = bulkTasks.length > 0 && bulkTasks.every(t => t.status === 'completed');
  const allTasksCompleted = tasks.length > 0 && tasks.every(t => t.status === 'completed');

  function stageState(key) {
    switch (key) {
      case 'create':
        return 'done';
      case 'accept':
        return !['pending_planner'].includes(status) ? 'done' : 'current';
      case 'execute': {
        if (taskStatuses.length === 0) return status === 'completed' ? 'done' : 'current';
        if (status === 'completed' || (bulkStageCompleted && allTasksCompleted)) return 'done';
        return !['pending_planner'].includes(status) ? 'current' : 'pending';
      }
      case 'complete': {
        if (status === 'completed') return 'done';
        return bulkStageCompleted && allTasksCompleted ? 'current' : 'pending';
      }
    }
    return 'pending';
  }

  // 构建下一步提示
  function getNextHint() {
    if (['terminated', 'pending_terminate'].includes(status)) {
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⛔', title: '项目已终止', text: '该项目已被终止，无法继续操作。' };
    }
    if (status === 'paused') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '⏸️', title: '项目已暂停', text: '点击"继续"按钮可恢复项目。' };
    }
    if (status === 'completed') {
      return { color: '#3B6D11', bg: '#EAF3DE', border: '#C0DD97', icon: '🎉', title: '项目已完成', text: '所有子任务已验收评分完毕。' };
    }

    if (status === 'pending_planner') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待企划接单', text: '产品企划 ' + (detail.plannerName || '待指定') + ' 需要先接单才能开始工作。' };
    }

    // 子任务层面分析
    const pendingTasks = tasks.filter(t => t.status === 'pending');
    const unassignedTasks = tasks.filter(t => t.status === 'pending' && (!t.designerId || t.designerId === ''));
    const acceptedTasks = tasks.filter(t => t.status === 'accepted');
    const deliveredTasks = tasks.filter(t => t.status === 'delivered');
    const plannerApprovedTasks = tasks.filter(t => t.status === 'planner_approved');
    const rejectedTasks = tasks.filter(t => t.status === 'rejected');

    if (rejectedTasks.length > 0) {
      const names = rejectedTasks.map(t => t.name).join('、');
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⚠️', title: '有子任务被驳回', text: '子任务「' + names + '」需要设计师重新交付。' };
    }
    // 优先级：已企划评分 → 已交付 → 执行中 → 待分配
    if (plannerApprovedTasks.length > 0) {
      const names = plannerApprovedTasks.map(t => t.name).join('、');
      const confirmer = isChannel ? '销售' : '管理';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待' + confirmer + '确认', text: '子任务「' + names + '」企划已评分通过，等待' + confirmer + '确认。' };
    }
    if (deliveredTasks.length > 0) {
      const names = deliveredTasks.map(t => t.name).join('、');
      const role = isChannel ? '企划' : '企划';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待验收评分', text: '子任务「' + names + '」已交付，等待' + role + '验收评分。' };
    }
    if (acceptedTasks.length > 0) {
      const names = acceptedTasks.map(t => t.name).join('、');
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待交付', text: '子任务「' + names + '」正在执行中，等待子任务负责人交付成果。' };
    }
    if (unassignedTasks.length > 0) {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待分配子任务', text: '还有 ' + unassignedTasks.length + ' 个子任务未指派，请先指派负责人。' };
    }
    return null;
  }

  // 生成管道圆点 HTML
  const stageHtml = stages.map((s, i) => {
    const st = stageState(s.key);
    const isLast = i === stages.length - 1;
    let dotStyle, labelColor, detailColor;
    if (st === 'done') {
      dotStyle = 'background:#3B6D11;';
      labelColor = 'color:#3B6D11;';
      detailColor = 'color:var(--color-text-tertiary);';
    } else if (st === 'current') {
      dotStyle = 'background:#EF9F27;box-shadow:0 0 0 4px #FAEEDA;';
      labelColor = 'color:#854F0B;font-weight:600;';
      detailColor = 'color:#854F0B;';
    } else if (st === 'error') {
      dotStyle = 'background:#E24B4A;';
      labelColor = 'color:#A32D2D;';
      detailColor = 'color:#A32D2D;';
    } else {
      dotStyle = 'background:var(--gray-50);border:2px solid var(--gray-300);';
      labelColor = 'color:var(--gray-400);';
      detailColor = 'color:var(--gray-400);';
    }
    const connector = !isLast ? `<div style="position:absolute;top:14px;left:56%;right:-16px;height:3px;background:${st === 'done' ? '#3B6D11' : 'var(--gray-200)'};z-index:-1;"></div>` : '';
    const dotInner = st === 'done' ? '<span style="color:#fff;font-size:11px;">✓</span>'
      : st === 'current' ? '<span style="color:#fff;font-size:12px;">●</span>'
        : st === 'error' ? '<span style="color:#fff;font-size:12px;">!</span>'
          : `<span style="color:var(--gray-500);font-size:11px;font-weight:600;">${i + 1}</span>`;
    return `<div style="flex:1;text-align:center;position:relative;">
      <div style="width:28px;height:28px;border-radius:50%;margin:0 auto 6px;display:flex;align-items:center;justify-content:center;${dotStyle}">${dotInner}</div>
      <div style="font-size:11px;${labelColor}">${s.label}</div>
      <div style="font-size:10px;${detailColor};margin-top:2px;">${st === 'done' ? '已完成' : st === 'current' ? '进行中' : '待进行'}${s.detail ? ' · ' + escHtml(s.detail) : ''}</div>
      ${connector}
    </div>`;
  }).join('');

  const hint = getNextHint();
  const hintHtml = hint ? `
    <div style="margin-top:16px;background:${hint.bg};border-radius:8px;padding:12px 16px;border:0.5px solid ${hint.border};">
      <div style="display:flex;align-items:center;gap:8px;">
        <span style="font-size:16px;">${hint.icon}</span>
        <div>
          <div style="font-size:12px;font-weight:500;color:${hint.color};">${hint.title}</div>
          <div style="font-size:12px;color:${hint.color};opacity:0.85;">${escHtml(hint.text)}</div>
        </div>
      </div>
    </div>` : '';

  return `<div class="detail-section">
    <div class="detail-section-title">🔵 项目总进度 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${isChannel ? '渠道定制单' : '公司常规品'}</span></div>
    <div style="padding:20px 8px 8px;">
      <div style="display:flex;gap:0;">${stageHtml}</div>
      ${hintHtml}
    </div>
    <div style="margin-top:8px;font-size:11px;color:var(--color-text-tertiary);display:flex;gap:16px;padding:0 4px;">
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#3B6D11;vertical-align:middle;margin-right:4px;"></span>已完成</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#EF9F27;vertical-align:middle;margin-right:4px;"></span>进行中</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:var(--gray-50);border:1px solid var(--gray-300);vertical-align:middle;margin-right:4px;"></span>待进行</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#E24B4A;vertical-align:middle;margin-right:4px;"></span>异常</span>
    </div>
  </div>`;
}
async function plannerAcceptProject(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, userId: getCurrentUserId() });
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('操作失败: ' + e.message);
  }
}

// 从列表直接接单（不需要打开弹窗）
async function plannerAcceptFromList(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: EMIE.state.currentRole, userId: getCurrentUserId() });
    EMIE.state.cache.orders = [];
    await refreshAfterMutation(pid);
  } catch (e) {
    window.EMIE.actions.showSystemAlert('操作失败: ' + e.message);
  }
}


EMIE.registerActions({
  openProjectDetail,
  renderProjectDetailContent,
  cleanLogAction,
  renderLogLabel,
  renderSubTaskCard,
  openProjectSubTaskDetail,
  openTaskRejectionRecord,
  renderProjectActions,
  showConfirmDialog,
  terminateProject,
  cancelTerminate,
  deleteProject,
  pauseProject,
  resumeProject,
  normalizeFileUrl,
  storedNameFromFile,
  isPreviewableFile,
  renderAttachmentActions,
  renderProjectReferenceImages,
  renderSubTaskImages,
  renderProjectAttachments,
  renderProductArchiveSection,
  renderTaskAttachments,
  renderProjectScoringSummary,
  renderSubTaskProgress,
  completeWorkflowExecution,
  submitWorkflowReview,
  openWorkflowReviewModal,
  submitWorkflowDecision,
  renderProjectPipeline,
  plannerAcceptProject,
  plannerAcceptFromList,
  createProjectFeishuChat,
  dissolveProjectFeishuChat,
  openProjectFeishuChat,
});

EMIE.registerModule('projectDetail', {
  openProjectDetail,
  renderProjectDetailContent,
  renderProjectActions,
  terminateProject,
  cancelTerminate,
  deleteProject,
  pauseProject,
  resumeProject,
  normalizeFileUrl,
  renderAttachmentActions,
  plannerAcceptProject,
  plannerAcceptFromList,
  createProjectFeishuChat,
  dissolveProjectFeishuChat,
  openProjectFeishuChat,
});
