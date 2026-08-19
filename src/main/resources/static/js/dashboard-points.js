const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const uploadFile = (...args) => EMIE.actions.uploadFile(...args);
let pointsRulesCache = [];
let pointsLedgerPage = 0;
const pointsLedgerSize = 20;
let selfProposalDesignFiles = [];

function currentMonthKey() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

function formatPoints(value) {
  const number = Number(value || 0);
  return Number.isInteger(number) ? String(number) : number.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

function pointRuleCategoryLabel(category) {
  return { A: '平面与视觉（A类）', B: '产品与原创（B类）', E: '执行与跟单（E类）', S: '内部建设（S类）', GENERAL: '通用规则（通用类）', '通用': '通用规则（通用类）' }[category] || category || '通用规则（通用类）';
}

function pointRuleTaskLabel(code) {
  const value = String(code || '').replace(/:(BASE|QUALITY)$/i, '').toUpperCase();
  const labels = { TASK_APPROVED: '通用任务（验收完成）', A1: '包装整套设计', A2: '包装单项设计', A3: '包装修改/刀模/箱规', A4: '包装多语言版', A5: '详情页全套设计', A6: '详情页局部/改版', A7: '主图/单张卖点图', A8: '海报/立牌/单页', A9: '展会物料整套', A10: 'UI界面/灯珠图案/待机页', A11: 'AI生图/场景图/推广图', B1: '原创产品设计', B2: '外采产品IP化设计', B3: '新增SKU/配色衍生', B4: '展会样品/客户定制产品', B5: '3D建模渲染出图', B6: '3D公仔建模/输出', E1: '常规执行任务', E2: '资料整理/跟进任务', E3: '打样/修改执行任务', E4: '其他执行任务', S1: '特殊专项任务' };
  return value ? `${value}（${labels[value] || '积分任务'}）` : '积分任务';
}

function comparePointRuleCodes(left, right) {
  return String(left?.ruleCode || '').localeCompare(String(right?.ruleCode || ''), undefined, {
    numeric: true,
    sensitivity: 'base'
  });
}

function renderPointRuleRows(rules) {
  if (!rules.length) return '<tr><td colspan="6"><div class="points-empty-compact">该分类暂无生效规则</div></td></tr>';
  return rules.map(rule => `<tr><td><span class="points-category-badge">${escHtml(pointRuleCategoryLabel(rule.category))}</span></td><td><strong>${escHtml(rule.ruleCode || '-')}</strong><div style="font-size:12px;color:var(--gray-400);margin-top:3px;">${escHtml(rule.description || '')}</div></td><td>${formatPoints(rule.points)}</td><td>创建任务时选择档位并锁定</td><td>${Number(rule.qualityBonusRatio || 0) > 0 ? `评分 ≥ ${formatPoints(rule.qualityBonusThreshold)}，加 ${formatPoints(Number(rule.qualityBonusRatio) * 100)}%` : '无'}</td></tr>`).join('');
}

function filterPointsRules(category) {
  const selected = category || document.querySelector('.points-rule-filter select')?.value || '';
  const keyword = String(document.getElementById('pointsRuleSearch')?.value || '').trim().toLowerCase();
  const rows = pointsRulesCache.filter(rule => {
    if (!keyword && String(rule.category || 'GENERAL') !== selected) return false;
    if (!keyword) return true;
    return [rule.ruleCode, rule.description, rule.category]
      .some(value => String(value || '').toLowerCase().includes(keyword));
  });
  const body = document.getElementById('pointsRulesBody');
  const count = document.getElementById('pointsRulesCount');
  if (body) body.innerHTML = renderPointRuleRows(rows);
  const scrollArea = body?.closest('.points-rules-scroll');
  if (scrollArea) scrollArea.scrollTop = 0;
  if (count) count.textContent = `当前分类 ${rows.length} 条规则`;
}

function renderLeaderboardRows(rows, currentUserId) {
  if (!rows.length) return '<div class="points-empty-compact">当前月份暂无可展示的团队积分排名</div>';
  return rows.map((row, index) => {
    const mine = String(row.userId || '') === String(currentUserId || '');
    const rankClass = index < 3 ? ` top-${index + 1}` : '';
    return `<div class="points-rank-row${mine ? ' is-me' : ''}"><span class="points-rank-number${rankClass}">${index + 1}</span><span class="points-rank-avatar">${escHtml(String(row.name || row.userId || '?').slice(0, 1))}</span><span class="points-rank-person"><strong>${escHtml(row.name || row.userId || '-')}</strong>${mine ? '<small class="points-rank-self">本人</small>' : '<small>本月已入账</small>'}</span><strong class="points-rank-score">${formatPoints(row.points)}<small>分</small></strong></div>`;
  }).join('');
}

async function refreshPointsMonth(month) {
  const isAdminView = EMIE.state.currentRole === 'admin';
  const total = month === 'total';
  const requestedMonth = /^\d{4}-\d{2}$/.test(month || '') ? month : currentMonthKey();
  const leaderboardBody = document.getElementById('pointsLeaderboardBody');
  const previewBox = document.getElementById('pointsPerformancePreview');
  if (leaderboardBody) leaderboardBody.innerHTML = '<div class="loading">正在加载…</div>';
  if (previewBox) previewBox.innerHTML = '<div class="loading">正在计算…</div>';
  try {
    const query = total ? '' : '?month=' + encodeURIComponent(requestedMonth);
    const [leaderboard, preview] = await Promise.all([
      apiGet('/performance/leaderboard' + query),
      // 总计只影响顶部累计统计，下面的月度绩效卡保持当前月份口径，避免页面出现重复的累计摘要。
      isAdminView ? Promise.resolve({}) : apiGet('/performance/preview?month=' + encodeURIComponent(requestedMonth)),
    ]);
    const rows = Array.isArray(leaderboard) ? leaderboard : [];
    if (leaderboardBody) leaderboardBody.innerHTML = renderLeaderboardRows(rows, EMIE.state.currentUserId);
    if (previewBox) {
      const target = Number(preview.targetPoints || 0);
      const actual = Number(preview.points || 0);
      const rate = Number.isFinite(Number(preview.attainmentRate))
        ? Math.round(Number(preview.attainmentRate) * 1000) / 10
        : (target > 0 ? Math.round(actual / target * 1000) / 10 : 0);
      const progress = Math.max(0, Math.min(100, rate));
      previewBox.innerHTML = `<div class="points-performance-head"><div><span>本月目标进度</span><strong>${target > 0 ? rate + '%' : '未配置'}</strong></div><span>${formatPoints(actual)} / ${formatPoints(target)} 分</span></div><div class="points-progress"><span style="width:${progress}%"></span></div>`;
    }
  } catch (error) {
    if (leaderboardBody) leaderboardBody.innerHTML = `<div class="points-empty-compact">加载失败：${escHtml(error.message || '请稍后重试')}</div>`;
    if (previewBox) previewBox.innerHTML = `<div class="empty-state">计算失败：${escHtml(error.message || '请稍后重试')}</div>`;
  }
}

function renderAdminAppealPanel(appealList) {
  return '';
}

function renderAdminGovernance(configs) {
  const values = Object.fromEntries((configs?.business || []).filter(item => String(item.configKey || '').startsWith('points.withdrawal.')).map(item => [item.configKey, item.configValue || '']));
  return `<div class="card governance-card points-admin-governance-card"><div class="governance-card-heading"><div><h3>⚙️ 接单治理</h3><p>规则触发后自动生成积分调账记录，并同步更新成员接单资格。</p></div></div><div class="governance-section governance-eligibility"><div class="governance-section-heading"><div><h4>成员接单资格</h4><p>管理员可手动暂停或恢复成员的接单资格。</p></div><button class="btn btn-outline btn-sm" data-emie-onclick="configureMarketEligibility()">暂停/恢复接单资格</button></div></div><div class="governance-section governance-penalty"><div class="governance-section-heading"><div><h4>退单处罚规则</h4><p>设计师退单超过免罚时长后，系统按累计次数计算扣分并自动写入积分记录。</p></div><button class="btn btn-primary btn-sm" data-emie-onclick="saveWithdrawalGovernanceConfig()">保存规则</button></div><div class="governance-settings-grid"><label class="governance-setting"><span>免罚时长<small>分钟</small></span><input class="form-input" type="number" min="0" id="withdraw_free_minutes" value="${Number(values['points.withdrawal.free_minutes'] || 60)}"></label><label class="governance-setting"><span>暂停阈值<small>次</small></span><input class="form-input" type="number" min="1" id="withdraw_suspend_count" value="${Number(values['points.withdrawal.suspend_count'] || 3)}"></label><label class="governance-setting"><span>每次扣分比例<small>%</small></span><input class="form-input" type="number" min="0" max="100" id="withdraw_penalty_rate" value="${Number(values['points.withdrawal.penalty_rate'] || 10)}"></label><label class="governance-setting"><span>暂停天数<small>天</small></span><input class="form-input" type="number" min="1" id="withdraw_suspend_days" value="${Number(values['points.withdrawal.suspend_days'] || 7)}"></label></div></div></div></div>`;
}

function renderAdminPrograms(poProgress, proposals) {
  const progress = Array.isArray(poProgress) ? poProgress : [];
  const proposalList = Array.isArray(proposals) ? proposals : [];
  return `<div class="points-admin-program-grid"><div class="card points-admin-program-card"><div class="card-header"><div><h3>📅 PO 月度积分</h3><span class="points-ledger-hint">审核 PO 月度履职后自动入账</span></div><button class="btn btn-outline btn-sm" data-emie-onclick="createPoPointProject()">＋ 新增 PO 产品</button></div>${progress.length ? `<div class="table-wrap"><table><thead><tr><th>月份</th><th>项目ID</th><th>进展</th><th>状态</th><th>操作</th></tr></thead><tbody>${progress.map(item => `<tr><td>${escHtml(item.monthKey || '-')}</td><td>#${Number(item.poProjectId || 0)}</td><td>${escHtml(item.summary || '-')}</td><td>${escHtml(item.status || '-')}</td><td>${item.status === 'SUBMITTED' ? `<button class="btn btn-success btn-sm" data-emie-onclick="reviewPoProgress(${Number(item.id)},true)">确认入账</button> <button class="btn btn-danger btn-sm" data-emie-onclick="reviewPoProgress(${Number(item.id)},false)">驳回</button>` : '-'}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty-state">暂无 PO 月度进展</div>'}</div><div class="card points-admin-program-card"><div class="card-header"><div><h3>💡 自主开发提案</h3><span class="points-ledger-hint">审核通过后进入项目流程</span></div></div>${proposalList.length ? proposalList.map(item => `<div class="admin-proposal-row"><div><strong>${escHtml(item.title || '-')}</strong><span>${escHtml(item.applicantName || '-')} · ${escHtml(item.status || '-')}</span><p>${escHtml(item.description || '')}</p></div>${item.status === 'SUBMITTED' ? `<div class="admin-proposal-actions"><button class="btn btn-success btn-sm" data-emie-onclick="reviewSelfProposal(${Number(item.id)},true)">立项</button><button class="btn btn-danger btn-sm" data-emie-onclick="reviewSelfProposal(${Number(item.id)},false)">驳回</button></div>` : ''}</div>`).join('') : '<div class="empty-state">暂无提案</div>'}</div></div>`;
}

function renderAdjustmentLedger(adjustments) {
  adjustments = adjustments.filter(item => item.sourceType !== 'APPEAL');
  if (!adjustments.length) return '';
  return `<div class="card adjustment-ledger-card" style="margin-bottom:18px;"><div class="card-header"><div><h3>调账与 PO 积分</h3><span style="font-size:12px;color:var(--gray-500);">补充台账记录，不修改原始积分明细</span></div><span class="points-ledger-count">${adjustments.length} 条记录</span></div><div class="adjustment-ledger-list">${adjustments.map(item => { const points = Number(item.points || 0); const source = item.sourceType === 'PO_PROGRESS' ? 'PO月度履职' : item.sourceType === 'APPEAL' ? '积分异议' : item.sourceType === 'MANUAL' ? '管理员调账' : String(item.sourceType || '其他'); return `<div class="adjustment-ledger-row"><div class="adjustment-ledger-source"><span class="adjustment-source-icon">${item.sourceType === 'APPEAL' ? '⚖' : item.sourceType === 'PO_PROGRESS' ? '📅' : '✦'}</span><div><strong>${escHtml(source)}</strong><small>${escHtml(String(item.createdAt || '-').replace('T', ' ').slice(0, 19))}</small></div></div><div class="adjustment-ledger-reason">${escHtml(item.reason || '—')}</div><strong class="adjustment-ledger-points ${points >= 0 ? 'is-positive' : 'is-negative'}">${points >= 0 ? '+' : ''}${formatPoints(points)} 分</strong></div>`; }).join('')}</div></div>`;
}

async function renderPointsView(main) {
  main.innerHTML = '<div class="loading">正在加载积分…</div>';
  try {
    const [mine, rules, appeals, poData, archives, proposals, adminConfigs, adminProgress, adminProposals] = await Promise.all([
      apiGet(`/points/me?page=${pointsLedgerPage}&size=${pointsLedgerSize}`), apiGet('/points/rules'), apiGet('/point-governance/appeals'),
      apiGet('/point-governance/po/me'), apiGet('/point-governance/archives'), apiGet('/point-program/proposals'),
      EMIE.state.currentRole === 'admin' ? apiGet('/admin/configs') : Promise.resolve({}),
      EMIE.state.currentRole === 'admin' ? apiGet('/point-governance/po/progress') : Promise.resolve([]),
      EMIE.state.currentRole === 'admin' ? apiGet('/point-program/proposals') : Promise.resolve([]),
    ]);
    const plannerMode = EMIE.state.currentRole === 'planner';
    const isAdminView = EMIE.state.currentRole === 'admin';
    const isDesignerView = EMIE.state.currentRole === 'designer';
    const plannerTasks = plannerMode ? await apiGet('/projects/my-subtasks') : [];
    const issuedTasks = (Array.isArray(plannerTasks) ? plannerTasks : [])
      .filter(task => task.publisherId === EMIE.state.currentUserId
        && Number(task.issuedPoints || 0) > 0);
    const issuedPoints = issuedTasks.reduce((sum, task) => sum + Number(task.issuedPoints || 0), 0);
    const ledger = Array.isArray(mine.ledger) ? mine.ledger : [];
    const ledgerPages = Number(mine.ledgerPages || 0);
    const adjustments = Array.isArray(mine.adjustmentLedger) ? mine.adjustmentLedger : [];
    const enabledRules = (Array.isArray(rules) ? rules : [])
      .filter(rule => rule.enabled !== false)
      .sort(comparePointRuleCodes);
    pointsRulesCache = enabledRules;
    const ruleCategories = [...new Set(enabledRules.map(rule => String(rule.category || 'GENERAL')))];
    const categoryPriority = { A: 1, B: 2, E: 3, S: 4, GENERAL: 5 };
    ruleCategories.sort((a, b) => (categoryPriority[a] || 99) - (categoryPriority[b] || 99) || a.localeCompare(b));
    const initialRuleCategory = ruleCategories[0] || '';
    const initialCategoryRules = enabledRules.filter(rule => String(rule.category || 'GENERAL') === initialRuleCategory);
    const appealList = Array.isArray(appeals) ? appeals : [];
    const poProjects = Array.isArray(poData?.projects) ? poData.projects : [];
    const poProgress = Array.isArray(poData?.progress) ? poData.progress : [];
    const archiveList = Array.isArray(archives) ? archives : [];
    const proposalList = Array.isArray(proposals) ? proposals : [];
    const canPlannerProcessAppeals = false;
    const ledgerSection = isAdminView ? '' : `<div class="card points-ledger-card" style="margin-bottom:18px;"><div class="card-header"><div><h3>我的积分明细</h3><span class="points-ledger-hint">积分记录按入账时间排列</span></div><span style="font-size:12px;color:var(--gray-400);">如需调整积分，请直接与管理员沟通</span></div>${ledger.length ? `<div class="table-wrap"><table class="points-ledger-table"><thead><tr><th>任务</th><th>任务类别 / 积分阶段</th><th>归属月</th><th>积分</th><th>入账时间</th></tr></thead><tbody>${ledger.map(item => { const code=String(item.ruleCode||'-'); const stage=code.endsWith(':BASE')?'基础积分':code.endsWith(':QUALITY')?'质量加分':'积分'; return `<tr><td>#${escHtml(String(item.subTaskId || '-'))}</td><td><strong>${escHtml(pointRuleTaskLabel(code))}</strong><div style="font-size:12px;color:var(--gray-400);">${escHtml(code.replace(/:(BASE|QUALITY)$/,''))} · ${stage}</div></td><td>${escHtml(item.accountingMonth || '-')}</td><td style="color:var(--success);font-weight:700;">+${formatPoints(item.points)}</td><td>${escHtml(String(item.createdAt || '-').replace('T', ' ').slice(0, 19))}</td></tr>`; }).join('')}</tbody></table></div>${ledgerPages > 1 ? `<div class="pagination"><button class="btn btn-outline btn-sm" ${pointsLedgerPage <= 0 ? 'disabled' : ''} data-emie-onclick="changePointsLedgerPage(${pointsLedgerPage - 1})">上一页</button><span>第 ${pointsLedgerPage + 1} / ${ledgerPages} 页</span><button class="btn btn-outline btn-sm" ${pointsLedgerPage >= ledgerPages - 1 ? 'disabled' : ''} data-emie-onclick="changePointsLedgerPage(${pointsLedgerPage + 1})">下一页</button></div>` : ''}` : '<div class="empty-state">暂无积分记录</div>'}</div>`;
    const month = currentMonthKey();
    main.innerHTML = `
      <div class="points-page">
      <section class="points-hero"><div class="points-hero-copy"><span class="points-eyebrow">${plannerMode ? 'PLANNER POINTS' : isAdminView ? 'ADMIN POINTS' : 'MY PERFORMANCE'}</span><h2>${plannerMode ? '发放任务与积分' : isAdminView ? '设计师积分总览' : '积分与绩效中心'}</h2><p>${plannerMode ? '查看你发放的子任务及其实际积分入账情况' : isAdminView ? '查看所有设计师积分情况，快速完成积分调整' : '每一次交付都有记录，每一份成长都清晰可见'}</p>${isAdminView ? '<button class="btn btn-light points-admin-adjust-btn" data-emie-onclick="openManualAdjustmentModal()">＋ 调整积分</button>' : `<div class="points-hero-stats"><div><strong>${plannerMode ? issuedTasks.length : formatPoints(mine.balance)}</strong><span>${plannerMode ? '已发放子任务' : '累计积分'}</span></div><i></i><div><strong>${plannerMode ? formatPoints(issuedPoints) : (mine.ledgerTotal + adjustments.length)}</strong><span>${plannerMode ? '已入账积分' : '已入账记录'}</span></div></div>`}</div></section>
      ${isAdminView ? renderAdminAppealPanel(appealList) : ''}
      ${isAdminView ? renderAdminGovernance(adminConfigs) : ''}
      ${isAdminView ? renderAdminPrograms(adminProgress, adminProposals) : ''}
      <div class="points-dashboard-grid">${isDesignerView ? '<section class="points-panel"><div class="points-section-head"><div><span class="points-section-icon purple">↗</span><div><h3>月度绩效</h3><p>目标、积分与绩效结果一目了然</p></div></div><label class="points-month-picker"><span>查询月份</span><input id="pointsMonthFilter" class="points-month-input points-month-inline" type="month" value="' + month + '" data-emie-onchange="refreshPointsMonth(this.value)"></label></div><div id="pointsPerformancePreview" class="points-panel-body"></div></section>' : ''}<section class="points-panel ${isAdminView ? 'points-admin-leaderboard-panel' : ''}"><div class="points-section-head"><div><span class="points-section-icon amber">★</span><div><h3>${isAdminView ? '设计师积分排名' : '积分排行榜'}</h3><p>${isAdminView ? '按当前月份查看所有设计师的积分情况' : '所选月份的团队积分排名'}</p></div></div>${isAdminView ? '<label class="points-month-picker"><span>查询月份</span><input class="points-month-input points-month-inline" type="month" value="' + month + '" data-emie-onchange="refreshPointsMonth(this.value)"></label>' : ''}</div><div id="pointsLeaderboardBody" class="points-rank-list"></div></section></div>
      ${ledgerSection}
      ${renderAdjustmentLedger(adjustments)}
      ${false && appealList.length ? '<div></div>' : ''}
      ${poProjects.length ? `<div class="card" style="margin-bottom:18px;"><div class="card-header"><h3>PO 月度履职</h3></div><div class="table-wrap"><table><thead><tr><th>PO 项目</th><th>月度积分</th><th>本月状态</th><th>操作</th></tr></thead><tbody>${poProjects.map(project => { const progress = poProgress.find(item => Number(item.poProjectId) === Number(project.id) && item.monthKey === month); return `<tr><td>${escHtml(project.name || '-')}</td><td>${formatPoints(project.monthlyPoints)}</td><td>${progress ? escHtml(poProgressStatusLabel(progress.status)) : '未提交'}</td><td>${progress ? '-' : `<button class="btn btn-primary btn-sm" data-emie-onclick="submitPoProgress(${Number(project.id)},'${month}')">提交本月进展</button>`}</td></tr>`; }).join('')}</tbody></table></div></div>` : ''}
      ${archiveList.length ? `<div class="card" style="margin-bottom:18px;"><div class="card-header"><h3>月度归档</h3></div><div class="table-wrap"><table><thead><tr><th>月份</th><th>获得积分</th><th>目标积分</th><th>供单积分</th><th>供单保护</th><th>状态</th></tr></thead><tbody>${archiveList.map(item => `<tr><td>${escHtml(item.monthKey || '-')}</td><td>${formatPoints(item.earnedPoints)}</td><td>${formatPoints(item.targetPoints)}</td><td>${formatPoints(item.suppliedPoints)}</td><td>${item.insufficientSupplyProtection ? '已启用' : '未启用'}</td><td>${item.status === 'ARCHIVED' ? '已归档' : '待确认'}</td></tr>`).join('')}</tbody></table></div></div>` : ''}
      <div class="card points-rules-card"><div class="card-header"><div><h3>当前生效规则</h3><span id="pointsRulesCount" class="points-rules-count">当前分类 ${initialCategoryRules.length} 条规则</span></div>${enabledRules.length ? `<label class="points-rule-filter"><span>规则分类</span><select data-emie-onchange="filterPointsRules(this.value)">${ruleCategories.map(category => `<option value="${escHtml(category)}">${escHtml(pointRuleCategoryLabel(category))}</option>`).join('')}</select><input id="pointsRuleSearch" class="form-input" placeholder="全类别搜索规则编号/说明" data-emie-oninput="filterPointsRules()" style="width:180px;"></label>` : ''}</div>${enabledRules.length ? `<div class="table-wrap points-rules-scroll"><table><thead><tr><th>类别</th><th>规则</th><th>基础分</th><th>难度</th><th>质量加分</th></tr></thead><tbody id="pointsRulesBody">${renderPointRuleRows(initialCategoryRules)}</tbody></table></div>` : '<div class="empty-state">暂无生效积分规则</div>'}</div></div>`;
    if (isDesignerView || isAdminView) await refreshPointsMonth(month);
  } catch (error) {
    main.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>积分加载失败：${escHtml(error.message || '请稍后重试')}</p></div>`;
  }
}

function pointAppealTypeLabel(type) {
  return { CATEGORY: '任务类别', BASE_POINTS: '基础分', DIFFICULTY: '难度系数', QUALITY_BONUS: '质量加分', ELIGIBILITY: '计分资格', OTHER: '其他' }[type] || type || '-';
}

function pointAppealStatusLabel(status) {
  return { SUBMITTED: '待管理员处理', PLANNER_PROCESSED: '待管理员处理', APPROVED: '异议通过', REJECTED: '异议驳回' }[status] || status || '-';
}

function poProgressStatusLabel(status) {
  return { SUBMITTED: '待管理确认', CONFIRMED: '已确认入账', REJECTED: '已驳回' }[status] || status || '-';
}

async function rerenderPointsView() {
  const main = document.getElementById('mainContent') || document.querySelector('main.main-content') || document.querySelector('.main-content');
  if (main) await renderPointsView(main);
}

async function submitPointAppeal(ledgerId) {
  const values = await window.EMIE.actions.showSystemForm([
    { label: '正确分数（0-100，最多一位小数）', type: 'number', min: 0, max: 100, step: 0.1 },
    { label: '问题描述', multiline: true, placeholder: '请描述分数不正确的原因' },
    { label: '参考图片（非必填，最多6张）', type: 'file', accept: EMIE.fileAccept?.reference || 'image/*', multiple: true, maxCount: 6, fileKind: 'reference', required: false },
    { label: '附件（非必填，最多5个）', type: 'file', accept: EMIE.fileAccept?.attachment || '*/*', multiple: true, maxCount: 5, fileKind: 'attachment', required: false },
  ], '提交积分异议');
  if (!values) return;
  const correctedScore = Number(values[0]);
  if (!Number.isFinite(correctedScore) || correctedScore < 0 || correctedScore > 100 || Math.round(correctedScore * 10) !== correctedScore * 10) { window.EMIE.actions.showSystemAlert('正确分数必须在0到100之间，最多保留一位小数'); return; }
  if ((values[2] || []).length > 6) { window.EMIE.actions.showSystemAlert('参考图片最多上传6张'); return; }
  if ((values[3] || []).length > 5) { window.EMIE.actions.showSystemAlert('附件最多上传5个'); return; }
  const uploads = [];
  for (const [files, kind] of [[values[2] || [], '图片'], [values[3] || [], '附件']]) for (const file of files) { if (kind === '图片' && !file.type.startsWith('image/')) { window.EMIE.actions.showSystemAlert('参考图片只能上传图片'); return; } if (file.size > 200 * 1024 * 1024) { window.EMIE.actions.showSystemAlert(`单个${kind}不能超过200MB`); return; } try { uploads.push(await uploadFile(file)); } catch (e) { window.EMIE.actions.showSystemAlert(`${kind}上传失败：` + e.message); return; } }
  try {
    await apiPost('/point-governance/appeals', { pointLedgerId: ledgerId, type: 'OTHER', reason: values[1].trim(), correctedScore, attachmentsJson: JSON.stringify(uploads) });
    window.EMIE.actions.showSystemAlert('积分异议已提交');
    await rerenderPointsView();
  } catch (error) { window.EMIE.actions.showSystemAlert('提交失败：' + (error.message || '请稍后重试')); }
}

async function changePointsLedgerPage(page) {
  pointsLedgerPage = Math.max(0, Number(page) || 0);
  const main = document.getElementById('mainContent') || document.querySelector('main.main-content') || document.querySelector('.main-content');
  if (main) await renderPointsView(main);
}

async function processPointAppeal(id, approve) {
  const comment = await window.EMIE.actions.showSystemInput(approve ? '请输入企划初审通过说明' : '请输入企划初审驳回说明', '', '处理积分异议');
  if (!comment) return;
  try {
    await apiPost(`/point-governance/appeals/${id}/planner-process`, { decision: approve ? 'APPROVE' : 'REJECT', comment: comment.trim() });
    window.EMIE.actions.showSystemAlert('异议初审已处理，等待管理复核');
    await rerenderPointsView();
  } catch (error) { window.EMIE.actions.showSystemAlert('处理失败：' + (error.message || '请稍后重试')); }
}

async function submitPoProgress(projectId, month) {
  const summary = await window.EMIE.actions.showSystemInput(`请填写 ${month} 的 PO 履职进展`, '', '提交 PO 月度进展');
  if (!summary) return;
  try {
    await apiPost(`/point-governance/po/projects/${projectId}/progress`, { month, summary: summary.trim() });
    window.EMIE.actions.showSystemAlert('PO 月度进展已提交');
    await rerenderPointsView();
  } catch (error) { window.EMIE.actions.showSystemAlert('提交失败：' + (error.message || '请稍后重试')); }
}

async function submitSelfTaskProposal() {
  if (document.getElementById('selfTaskProposalModal')) return;
  const today = new Date().toISOString().slice(0, 10);
  selfProposalDesignFiles = [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'selfTaskProposalModal';
  modal.innerHTML = `<div class="modal" style="max-width:680px;">
    <div class="modal-header"><div class="modal-header-left"><div class="modal-title">提交自主开发提案</div><div class="modal-subtitle">填写完整信息后提交管理层审核</div></div><button class="modal-close" data-emie-onclick="closeM('selfTaskProposalModal')">✕</button></div>
    <div class="modal-body">
      <div class="form-group"><label class="form-label">提案名称</label><input class="form-input" id="selfProposalTitle" maxlength="100" placeholder="例如：节日限定包装自主开发"></div>
      <div class="form-group"><label class="form-label">产品构想、价值和交付范围</label><textarea class="form-input" id="selfProposalDescription" rows="4" maxlength="1000" placeholder="说明为什么要做、预期价值以及计划交付内容"></textarea></div>
      <div class="form-group"><label class="form-label">附件设计图 <span style="color:var(--danger);">*</span></label><div class="upload-area" data-emie-onclick="document.getElementById('selfProposalDesignInput').click()"><div>📁 点击选择设计图，支持一次上传多张</div><input type="file" id="selfProposalDesignInput" multiple accept="${EMIE.fileAccept?.reference || 'image/*'}" style="display:none" data-emie-onchange="handleSelfProposalDesignFiles(this)"></div><div class="file-list" id="selfProposalDesignFileList"></div></div>
      <div class="form-group"><label class="form-label">计划完成日期</label><input class="form-input" type="date" min="${today}" id="selfProposalDate"></div>
      <div id="selfProposalError" style="display:none;color:var(--danger);font-size:13px;"></div>
    </div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('selfTaskProposalModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitSelfTaskProposalForm(this)">提交提案</button></div>
  </div>`;
  document.body.appendChild(modal);
}

async function submitSelfTaskProposalForm(button) {
  const payload = {
    title: document.getElementById('selfProposalTitle')?.value.trim(),
    description: document.getElementById('selfProposalDescription')?.value.trim(),
    referenceImagesJson: JSON.stringify(selfProposalDesignFiles),
    plannedDate: document.getElementById('selfProposalDate')?.value,
  };
  const error = document.getElementById('selfProposalError');
  if (!payload.title || !payload.description || !payload.plannedDate || !selfProposalDesignFiles.length) {
    if (error) { error.textContent = '请完整填写所有提案信息。'; error.style.display = 'block'; }
    return;
  }
  if (button) { button.disabled = true; button.textContent = '提交中…'; }
  try {
    await apiPost('/point-program/proposals', payload);
    closeM('selfTaskProposalModal');
    await rerenderPointsView();
  } catch (e) {
    if (error) { error.textContent = '提交失败：' + (e.message || '请稍后重试'); error.style.display = 'block'; }
    if (button) { button.disabled = false; button.textContent = '提交提案'; }
  }
}

function renderSelfProposalDesignFiles() {
  const list = document.getElementById('selfProposalDesignFileList');
  if (!list) return;
  list.innerHTML = selfProposalDesignFiles.map((file, index) => `<div class="file-item"><span class="file-item-name">🖼️ ${escHtml(file.name || '设计图')}</span><button type="button" class="remove-file" data-emie-onclick="removeSelfProposalDesignFile(${index})">✕</button></div>`).join('');
}

async function handleSelfProposalDesignFiles(input) {
  const error = document.getElementById('selfProposalError');
  const files = Array.from(input?.files || []);
  if (selfProposalDesignFiles.length + files.length > 6) {
    if (error) { error.textContent = '设计图最多上传 6 张。'; error.style.display = 'block'; }
    if (input) input.value = '';
    return;
  }
  for (const file of files) {
    try {
      const result = await uploadFile(file);
      selfProposalDesignFiles.push({ name: result.name, url: result.url, size: result.size, storedName: result.storedName });
      renderSelfProposalDesignFiles();
    } catch (e) {
      if (error) { error.textContent = `${file.name} 上传失败：${e.message || '请稍后重试'}`; error.style.display = 'block'; }
    }
  }
  if (input) input.value = '';
}

function removeSelfProposalDesignFile(index) {
  selfProposalDesignFiles.splice(index, 1);
  renderSelfProposalDesignFiles();
}

EMIE.registerActions({ refreshPointsMonth, filterPointsRules, submitPointAppeal, processPointAppeal, submitPoProgress, submitSelfTaskProposal, submitSelfTaskProposalForm, handleSelfProposalDesignFiles, removeSelfProposalDesignFile });
EMIE.registerModule('dashboardPoints', { renderPointsView, refreshPointsMonth, filterPointsRules, submitPointAppeal, processPointAppeal, submitPoProgress, submitSelfTaskProposal, submitSelfTaskProposalForm, handleSelfProposalDesignFiles, removeSelfProposalDesignFile });
