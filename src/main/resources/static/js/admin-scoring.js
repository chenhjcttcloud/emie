const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);
const showAdminToast = (...args) => EMIE.actions.showAdminToast(...args);

function compareRuleCodes(left, right) {
  return String(left?.ruleCode || '').localeCompare(String(right?.ruleCode || ''), undefined, {
    numeric: true,
    sensitivity: 'base'
  });
}

// ==================== 评分权重管理 ====================
async function renderAdminScoring(container) {
  container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</div>`;
  try {
    const data = await apiGet('/admin/scoring-weights');
    const types = data.types || [];
    container.innerHTML = `
      <div style="max-width:760px;margin:0 auto;">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
          <div>
            <h2 style="font-size:18px;margin:0 0 4px;">⭐ 评分权重管理</h2>
            <p style="font-size:13px;color:var(--gray-400);margin:0;">按项目类型分别设置各角色评分权重（百分比）</p>
          </div>
          <button class="btn btn-outline btn-sm" data-emie-action="click:scoring-reset-weights" style="color:var(--gray-500);">↺ 重置默认</button>
        </div>
        ${types.map((t, ti) => `
          <div style="background:#fff;border:1px solid var(--gray-200);border-radius:10px;padding:20px;margin-bottom:16px;">
            <h3 style="font-size:14px;font-weight:500;margin:0 0 16px;">${t.label}</h3>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">
              ${t.weights.map(w => `
                <div style="background:var(--bg-secondary,#F9FAFB);border-radius:8px;padding:12px;">
                  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                    <span style="font-size:13px;font-weight:500;">${w.label}</span>
                    <span style="font-size:20px;font-weight:600;" id="sd_${t.type}_${w.role}">${Math.round(w.weight)}</span>
                    <span style="font-size:12px;color:var(--gray-400);">%</span>
                  </div>
                  <div style="display:flex;align-items:center;gap:8px;">
                    <input type="range" min="0" max="100" step="1" value="${Math.round(w.weight)}" style="flex:1;" data-emie-action="input:scoring-pct" data-score-type="${t.type}" data-score-role="${w.role}">
                    <input type="number" class="form-input" value="${Math.round(w.weight)}" min="0" max="100" step="1" style="width:55px;text-align:center;padding:4px 6px;" data-emie-action="change:scoring-pct" data-score-type="${t.type}" data-score-role="${w.role}">
                  </div>
                </div>
              `).join('')}
            </div>
            <div style="margin-top:12px;display:flex;justify-content:space-between;align-items:center;font-size:12px;">
              <span style="color:var(--gray-400);">合计：<strong style="font-size:16px;" id="sum_${t.type}">${t.weights.reduce((s,w) => s + Math.round(w.weight), 0)}</strong>%</span>
            </div>
          </div>
        `).join('')}
        <div style="text-align:right;">
          <button class="btn btn-primary" data-emie-action="click:scoring-save-weights">💾 保存权重</button>
        </div>
        <div style="margin-top:16px;padding:12px;background:var(--warning-light,#FFFBEB);border-radius:8px;font-size:12px;color:#92400E;">
          💡 权重为百分比（0~100%），建议各项目类型的权重合计为 100%。综合分 = Σ(角色评分 × 权重%) ÷ Σ(权重%)。修改仅影响新建评分，历史评分不受影响。
        </div>
      </div>`;
    EMIE.adminState.scoringWeights = {};
    types.forEach(t => {
      EMIE.adminState.scoringWeights[t.type] = {};
      t.weights.forEach(w => { EMIE.adminState.scoringWeights[t.type][w.role] = Math.round(w.weight); });
    });
  } catch (e) {
    container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--danger);">加载失败: ${escHtml(e.message || '未知错误')}</div>`;
  }
}

function roleColor(role) {
  const colors = { planner: '#534AB7', sales: '#378ADD', designer: '#639922', admin: '#D85A30' };
  return colors[role] || '#888780';
}

const updateScoringPct = function(type, role, val) {
  const num = Math.round(parseFloat(val || 0));
  const clamped = Math.max(0, Math.min(100, num));
  if (!EMIE.adminState.scoringWeights) EMIE.adminState.scoringWeights = {};
  if (!EMIE.adminState.scoringWeights[type]) EMIE.adminState.scoringWeights[type] = {};
  EMIE.adminState.scoringWeights[type][role] = clamped;
  const display = document.getElementById('sd_' + type + '_' + role);
  if (display) display.textContent = clamped;
  const weights = EMIE.adminState.scoringWeights[type] || {};
  const sum = Object.values(weights).reduce((a, b) => a + b, 0);
  const sumEl = document.getElementById('sum_' + type);
  if (sumEl) {
    sumEl.textContent = sum;
    sumEl.style.color = sum === 100 ? 'var(--success,#059669)' : sum > 100 ? 'var(--danger)' : 'var(--warning,#D97706)';
  }
};

const saveScoringWeights = async function() {
  if (!EMIE.adminState.scoringWeights) return;
  try {
    await apiPut('/admin/scoring-weights', EMIE.adminState.scoringWeights);
    window.EMIE.actions.showSystemAlert('评分权重已保存');
  } catch (e) {
    window.EMIE.actions.showSystemAlert('保存失败: ' + e.message);
  }
};

const resetScoringWeights = async function() {
  if (!await EMIE.actions.showSystemConfirm('确定重置所有权重为默认值？')) return;
  window.location.reload();
};

async function renderAdminPoints(container) {
  if (!container) return;
  container.innerHTML = '<div style="padding:40px;text-align:center;color:var(--gray-400);">加载积分规则…</div>';
  try {
    const targetMonth = new Date().toISOString().slice(0, 7);
    const [rules, difficulties, appeals, poProgress, systemConfigs] = await Promise.all([
      apiGet('/points/rules'),
      apiGet('/points/difficulties'),
      apiGet('/point-governance/appeals'),
      apiGet('/point-governance/po/progress'), apiGet('/admin/configs'),
    ]);
    const ruleList = (Array.isArray(rules) ? rules : []).slice().sort(compareRuleCodes);
    const difficultyList = Array.isArray(difficulties) ? difficulties : [];
    // 复核区只展示待处理异议，已通过/已驳回记录保留在数据库审计链中，不再占用待办列表。
    const appealList = Array.isArray(appeals) ? appeals.filter(item => ['SUBMITTED', 'PLANNER_PROCESSED'].includes(item.status)) : [];
    const poProgressList = Array.isArray(poProgress) ? poProgress : [];
    const governanceConfig = Object.fromEntries((systemConfigs?.business || []).filter(x => String(x.configKey || '').startsWith('points.withdrawal.')).map(x => [x.configKey, x.configValue || '']));
    container.innerHTML = `<div class="admin-points-config" style="max-width:1100px;margin:0 auto;">
      <div class="admin-points-page-head" style="margin-bottom:18px;"><div><h2 style="font-size:18px;margin:0 0 4px;">🏅 积分与绩效配置</h2><p style="color:var(--gray-500);font-size:13px;margin:0;">规则修改只影响后续入账，历史积分台账不会重算。</p></div></div>
      <div class="card admin-point-rules-card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><div><h3>积分规则</h3><p class="admin-section-hint">共 ${ruleList.length} 条，列表显示 5 条，可滚动查看</p></div><button class="btn btn-primary btn-sm" data-emie-action="click:scoring-create-rule">＋ 新增规则</button></div>
        <div class="admin-point-rule-filters"><div class="admin-filter-card"><span class="admin-filter-title">筛选规则</span><input class="form-input" id="adminPointRuleSearch" placeholder="搜索规则编号或说明"><select class="form-select" id="adminPointRuleCategory"><option value="">全部类别</option>${[...new Set(ruleList.map(rule => String(rule.category || '').trim()).filter(Boolean))].sort().map(category => `<option value="${escHtml(category)}">${escHtml(category)}</option>`).join('')}</select><select class="form-select" id="adminPointRuleStatus"><option value="">全部状态</option><option value="enabled">已启用</option><option value="disabled">已停用</option></select><button class="btn btn-primary btn-sm" data-emie-action="click:scoring-filter-rules">查询</button><button class="btn btn-outline btn-sm" data-emie-action="click:scoring-reset-rules">重置</button></div></div>
        ${ruleList.length ? `<div class="admin-point-rules-scroll">${ruleList.map((rule, index) => `<div class="admin-point-rule-item" data-rule-code="${escHtml(String(rule.ruleCode || '').toLowerCase())}" data-rule-description="${escHtml(String(rule.description || '').toLowerCase())}" data-rule-category="${escHtml(String(rule.category || ''))}" data-rule-enabled="${rule.enabled === false ? 'disabled' : 'enabled'}" style="${index ? 'border-top:1px solid var(--gray-200);' : ''}">
        <div style="display:grid;grid-template-columns:1.1fr 1fr .8fr .9fr .9fr;gap:10px;align-items:end;">
          <label class="form-label">规则编号<input class="form-input" value="${escHtml(rule.ruleCode || '')}" disabled></label>
          <label class="form-label">类别<input class="form-input" id="pr_category_${index}" value="${escHtml(rule.category || '')}" placeholder="如 A / B / E / S"></label>
          <label class="form-label">基础分<input class="form-input" type="number" min="0" id="pr_points_${index}" value="${Number(rule.points || 0)}"></label>
          <label class="form-label">质量阈值<input class="form-input" type="number" min="0" id="pr_threshold_${index}" value="${Number(rule.qualityBonusThreshold || 0)}"></label>
          <label class="form-label">加分比例<input class="form-input" type="number" min="0" step="0.05" id="pr_ratio_${index}" value="${Number(rule.qualityBonusRatio || 0)}"></label>
        </div>
        <div style="display:flex;gap:12px;align-items:center;margin-top:12px;flex-wrap:wrap;">
          <input class="form-input" id="pr_desc_${index}" value="${escHtml(rule.description || '')}" placeholder="规则说明" style="flex:1;min-width:240px;">
          <label class="checkbox-item ${rule.enabled === false ? '' : 'checked'}"><input type="checkbox" id="pr_enabled_${index}" ${rule.enabled === false ? '' : 'checked'} data-emie-action="change:scoring-rule-toggle"> 启用</label>
          <button class="btn btn-primary btn-sm" data-emie-action="click:scoring-save-rule" data-rule-code="${escHtml(escJsString(rule.ruleCode))}" data-rule-index="${index}">保存规则</button>
          <button class="btn btn-danger btn-sm" data-emie-action="click:scoring-delete-rule" data-rule-code="${escHtml(escJsString(rule.ruleCode))}">移除</button>
        </div>
      </div>`).join('')}</div><div class="admin-point-rule-empty empty-state" hidden>没有符合条件的积分规则</div>` : '<div class="empty-state">暂无积分规则</div>'}</div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>难度档位</h3></div><div class="table-wrap"><table><thead><tr><th>档位</th><th>系数</th><th>说明</th><th>启用</th><th>操作</th></tr></thead><tbody>${difficultyList.map((item,index)=>`<tr><td><strong>${escHtml(item.difficultyCode || '-')}</strong></td><td><input class="form-input" type="number" min="0.1" max="10" step="0.1" id="pd_multiplier_${index}" value="${Number(item.multiplier || 1)}"></td><td><input class="form-input" id="pd_desc_${index}" value="${escHtml(item.description || '')}"></td><td><input type="checkbox" id="pd_enabled_${index}" ${item.enabled === false ? '' : 'checked'}></td><td><button class="btn btn-primary btn-sm" data-emie-action="click:scoring-save-difficulty" data-difficulty-code="${escHtml(escJsString(item.difficultyCode))}" data-difficulty-index="${index}">保存</button></td></tr>`).join('')}</tbody></table></div></div>
    </div>`;
    const appealCard = [...container.querySelectorAll('.card')].find(card => card.textContent.includes('积分异议复核'));
    const appealRows = appealCard ? appealCard.querySelectorAll('tbody tr') : [];
    appealList.forEach((item, index) => { const descriptionCell = appealRows[index]?.children?.[3]; if (descriptionCell && item.attachmentsJson) descriptionCell.insertAdjacentHTML('beforeend', renderAppealImages(item.attachmentsJson)); });
  } catch (e) { container.innerHTML = `<div class="empty">积分规则加载失败：${escHtml(e.message || '')}</div>`; }
}

function filterAdminPointRules() {
  const search = String(document.getElementById('adminPointRuleSearch')?.value || '').trim().toLowerCase();
  const category = String(document.getElementById('adminPointRuleCategory')?.value || '');
  const status = String(document.getElementById('adminPointRuleStatus')?.value || '');
  let visible = 0;
  document.querySelectorAll('.admin-point-rule-item').forEach(item => {
    const matches = (!search || item.dataset.ruleCode.includes(search) || item.dataset.ruleDescription.includes(search)) &&
      (!category || item.dataset.ruleCategory === category) && (!status || item.dataset.ruleEnabled === status);
    item.hidden = !matches;
    if (matches) visible++;
  });
  const empty = document.querySelector('.admin-point-rule-empty');
  if (empty) empty.hidden = visible > 0;
}

function resetAdminPointRuleFilters() {
  ['adminPointRuleSearch', 'adminPointRuleCategory', 'adminPointRuleStatus'].forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
  filterAdminPointRules();
}

async function saveWithdrawalGovernanceConfig() {
  const configs = { 'points.withdrawal.free_minutes': String(Math.max(0, Number(document.getElementById('withdraw_free_minutes').value || 60))), 'points.withdrawal.suspend_count': String(Math.max(1, Number(document.getElementById('withdraw_suspend_count').value || 3))), 'points.withdrawal.penalty_rate': String(Math.min(100, Math.max(0, Number(document.getElementById('withdraw_penalty_rate').value || 10)))), 'points.withdrawal.suspend_days': String(Math.max(1, Number(document.getElementById('withdraw_suspend_days').value || 7))) };
  try { await apiPut('/admin/configs', {configs}); window.EMIE.actions.showSystemAlert('退单处罚规则已保存'); } catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

function renderDesignerTargetRows(rows) {
  return rows.length ? rows.map((item, index) => `<tr><td><strong>${escHtml(item.userName || '-')}</strong></td><td>${escHtml(item.userId || '-')}</td><td><input class="form-input" type="number" min="0" id="dt_points_${index}" value="${Number(item.targetPoints || 0)}"></td><td>${item.configured ? '<span class="badge badge-completed">已配置</span>' : '<span class="badge badge-pending">待配置</span>'}</td><td><button class="btn btn-primary btn-sm" data-emie-action="click:scoring-save-target" data-target-index="${index}">保存</button></td></tr>`).join('') : '<tr><td colspan="5"><div class="empty-state">暂无在职设计师</div></td></tr>';
}

function renderProposalDesignFiles(value) {
  let files = [];
  try { files = JSON.parse(value || '[]'); } catch (_) { return ''; }
  if (!Array.isArray(files) || !files.length) return '';
  return `<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px;">${files.map(file => {
    const storedName = String(file.storedName || '').replace(/[^a-zA-Z0-9._-]/g, '');
    const url = storedName ? `/api/files/download/${encodeURIComponent(storedName)}?download=true` : '';
    const authenticatedUrl = url ? (EMIE.actions.authenticatedFileUrl ? EMIE.actions.authenticatedFileUrl(url) : url) : '';
    return storedName ? `<a class="btn btn-outline btn-sm" href="${escHtml(authenticatedUrl)}" download="${escHtml(file.name || storedName)}" rel="noopener">下载设计图 · ${escHtml(file.name || '附件')}</a>` : '';
  }).join('')}</div>`;
}

function renderAppealImages(value) {
  let files = []; try { files = JSON.parse(value || '[]'); } catch (_) { return ''; }
  if (!Array.isArray(files) || !files.length) return '';
  return `<div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:6px;">${files.slice(0, 3).map(file => { const storedName = String(file.storedName || '').replace(/[^a-zA-Z0-9._-]/g, ''); const url = storedName ? `/api/files/download/${encodeURIComponent(storedName)}` : ''; const authenticatedUrl = url && EMIE.actions.authenticatedFileUrl ? EMIE.actions.authenticatedFileUrl(url) : url; return authenticatedUrl ? `<a href="${escHtml(authenticatedUrl)}" target="_blank" rel="noopener"><img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==" data-auth-src="${escHtml(authenticatedUrl)}" style="width:56px;height:56px;object-fit:cover;border:1px solid var(--gray-200);border-radius:6px;" alt="相关图片"></a>` : ''; }).join('')}</div>`;
}

async function loadDesignerTargetMonth(month) {
  if (!/^\d{4}-\d{2}$/.test(month || '')) return;
  try {
    const rows = await apiGet('/performance/designer-targets?month=' + encodeURIComponent(month));
    EMIE.adminState.designerTargets = Array.isArray(rows) ? rows : [];
    const body = document.getElementById('designerTargetBody');
    if (body) body.innerHTML = renderDesignerTargetRows(EMIE.adminState.designerTargets);
  } catch (e) { window.EMIE.actions.showSystemAlert('目标加载失败：' + e.message); }
}

async function saveDesignerTarget(index) {
  const item = (EMIE.adminState.designerTargets || [])[index];
  const monthKey = document.getElementById('designerTargetMonth')?.value;
  if (!item || !monthKey) return;
  try {
    await apiPut('/performance/designer-targets/' + encodeURIComponent(item.userId), { monthKey, targetPoints: Number(document.getElementById('dt_points_' + index)?.value || 0) });
    await loadDesignerTargetMonth(monthKey);
  } catch (e) { window.EMIE.actions.showSystemAlert('目标保存失败：' + e.message); }
}

async function createPointRule() {
  const ruleCode = (await EMIE.actions.showSystemInput('规则编号（字母、数字、下划线或短横线）') || '').trim().toUpperCase();
  if (!ruleCode) return;
  const category = (await EMIE.actions.showSystemInput('规则分类', 'GENERAL') || 'GENERAL').trim();
  const points = Number(await EMIE.actions.showSystemInput('基础积分', '10'));
  const description = (await EMIE.actions.showSystemInput('规则说明', '') || '').trim();
  try {
    await apiPost('/points/rules', { ruleCode, category, points, description });
    const pointsContainer = EMIE.state.currentView === 'points'
      ? document.getElementById('mainContent')
      : document.getElementById('adminContent');
    await renderAdminPoints(pointsContainer);
  } catch (e) { window.EMIE.actions.showSystemAlert('新增失败：' + e.message); }
}

async function deletePointRule(code) {
  if (!await EMIE.actions.showSystemConfirm(`确定移除积分规则 ${code} 吗？历史任务快照和积分台账不会变化。`)) return;
  try { await apiDelete('/points/rules/' + encodeURIComponent(code)); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { window.EMIE.actions.showSystemAlert('移除失败：' + e.message); }
}

async function savePointRule(code, index) {
  try {
    const value = id => document.getElementById(id)?.value ?? '';
    const checked = id => Boolean(document.getElementById(id)?.checked);
    await apiPut('/points/rules/' + encodeURIComponent(code), {
      points: Number(value('pr_points_' + index)), category: value('pr_category_' + index).trim(),
      qualityBonusThreshold: Number(value('pr_threshold_' + index)), qualityBonusRatio: Number(value('pr_ratio_' + index)),
      qualityTopThreshold: Number(value('pr_top_threshold_' + index) || 0), qualityTopRatio: Number(value('pr_top_ratio_' + index) || 0),
      // The current rule editor does not expose a cap input. Keep the backend's
      // required minimum at 1 instead of accidentally submitting 0.
      maxTotalMultiplier: Number(value('pr_cap_' + index) || 1), description: value('pr_desc_' + index).trim(), enabled: checked('pr_enabled_' + index)
    });
    window.EMIE.actions.showSystemAlert('积分规则已保存');
  } catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

async function savePointDifficulty(code, index) {
  try {
    await apiPut('/points/difficulties/' + encodeURIComponent(code), { multiplier: Number(document.getElementById('pd_multiplier_' + index).value), description: document.getElementById('pd_desc_' + index).value.trim(), enabled: document.getElementById('pd_enabled_' + index).checked });
    window.EMIE.actions.showSystemAlert('难度档位已保存');
  } catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

async function deleteStandardPointConfig(id) {
  if (!id || !await EMIE.actions.showSystemConfirm('确定删除这条设计师个人绩效配置吗？')) return;
  const scrollTop = document.getElementById('adminContent')?.scrollTop || 0;
  try { await apiDelete('/performance/standard/' + id); await renderAdminPoints(document.getElementById('adminContent')); requestAnimationFrame(() => { const el = document.getElementById('adminContent'); if (el) el.scrollTop = scrollTop; }); }
  catch (e) { window.EMIE.actions.showSystemAlert('删除失败：' + e.message); }
}

async function saveStandardPointConfig(index) {
  const scrollTop = document.getElementById('adminContent')?.scrollTop || 0;
  const current = index >= 0 ? (EMIE.adminState.pointStandards || [])[index] : null;
  const configCode = current ? document.getElementById('sp_code_' + index)?.value.trim() : document.getElementById('sp_new_user')?.value;
  if (!configCode) return;
  const points = current ? Number(document.getElementById('sp_points_' + index)?.value) : Number(document.getElementById('sp_new_points')?.value || 0);
  const description = current ? document.getElementById('sp_desc_' + index)?.value.trim() : document.getElementById('sp_new_desc')?.value.trim();
  const enabled = current ? document.getElementById('sp_enabled_' + index)?.checked : true;
  const performanceBase = current ? Number(document.getElementById('sp_base_' + index)?.value) : Number(document.getElementById('sp_new_base')?.value || 0);
  const departmentType = current?.departmentType || 'SUPPORT';
  try {
    await apiPut('/performance/standard', { id: current?.id || null, configCode, points, performanceBase, departmentType, description, enabled });
    showAdminToast('标准积分配置已保存', 'success');
    const pointsContainer = EMIE.state.currentView === 'points'
      ? document.getElementById('mainContent')
      : document.getElementById('adminContent');
    await renderAdminPoints(pointsContainer);
    requestAnimationFrame(() => { const el = document.getElementById('adminContent'); if (el) el.scrollTop = scrollTop; });
  } catch (e) { showAdminToast('保存失败：' + e.message, 'error'); }
}

async function saveMonthlyPerformanceConfig(index) {
  const current = index >= 0 ? (EMIE.adminState.pointMonths || [])[index] : null;
  const now = new Date();
  const defaultMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const monthKey = current ? document.getElementById('mp_month_' + index)?.value : defaultMonth;
  const targetPoints = current ? Number(document.getElementById('mp_target_' + index)?.value) : Number(await EMIE.actions.showSystemInput(`${monthKey} 的目标积分`, '100') || 0);
  const multiplier = current ? Number(document.getElementById('mp_multiplier_' + index)?.value) : Number(await EMIE.actions.showSystemInput(`${monthKey} 的绩效系数`, '1') || 1);
  const salesRaw = current ? document.getElementById('mp_sales_' + index)?.value : await EMIE.actions.showSystemInput(`${monthKey} 公司销售额（万元，留空使用手动系数）`, '');
  const salesAmount = salesRaw === '' || salesRaw === null ? null : Number(salesRaw);
  const supplyShortage = current ? document.getElementById('mp_shortage_' + index)?.checked : await EMIE.actions.showSystemConfirm('该月是否存在企划供单不足？');
  if (!monthKey) return;
  try {
    await apiPut('/performance/monthly/' + encodeURIComponent(monthKey), { targetPoints, multiplier, salesAmount, supplyShortage });
    window.EMIE.actions.showSystemAlert('月度绩效配置已保存');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

async function reviewPointAppeal(id, approve) {
  let adjustmentPoints = null;
  let comment;
  if (approve) {
    const values = await window.EMIE.actions.showSystemForm([
      { label: '更正后的最终分数（0-100，最多一位小数）', type: 'number', min: 0, max: 100, step: 0.1 },
      { label: '备注（选填）', multiline: true, required: false, placeholder: '可填写为什么需要这样更正分数' },
    ], '异议处理');
    if (!values) return;
    adjustmentPoints = Number(values[0]);
    comment = values[1];
    if (!Number.isFinite(adjustmentPoints) || adjustmentPoints < 0 || adjustmentPoints > 100 || Math.round(adjustmentPoints * 10) !== adjustmentPoints * 10) { window.EMIE.actions.showSystemAlert('更正后的分数必须在0到100之间，最多保留一位小数'); return; }
  } else {
    comment = await window.EMIE.actions.showSystemInput('请输入驳回备注', '', '填写驳回备注');
    if (comment === null || !comment.trim()) return;
  }
  const scrollTop = document.getElementById('adminContent')?.scrollTop || window.scrollY || 0;
  try { await apiPost(`/point-governance/appeals/${id}/admin-review`, { decision: approve ? 'APPROVE' : 'REJECT', comment, adjustmentPoints }); if (EMIE.state.currentView === 'points') { await EMIE.actions.renderPointsView(document.getElementById('mainContent')); } else { await renderAdminPoints(document.getElementById('adminContent')); } requestAnimationFrame(() => { const el = document.getElementById('adminContent'); if (el) el.scrollTop = scrollTop; window.scrollTo({ top: scrollTop, behavior: 'instant' }); }); }
  catch (e) { window.EMIE.actions.showSystemAlert('处理失败：' + e.message); }
}

// ==================== 手动调账（管理员主动补分/扣分） ====================
async function openManualAdjustmentModal() {
  if (document.getElementById('manualAdjustmentModal')) return;
  let users;
  try { users = await apiGet('/admin/users'); } catch (e) { window.EMIE.actions.showSystemAlert('读取成员列表失败：' + e.message); return; }
  const activeUsers = (Array.isArray(users) ? users : []).filter(u => (u.status || 'active') === 'active');
  // 手动调账仅面向有积分资格的角色（设计师），与后端校验一致；角色可能为英文标准值或中文别名（'设计师'）。
  const eligibleUsers = activeUsers.filter(u => u.role === 'designer' || u.role === '设计师');
  if (!eligibleUsers.length) { window.EMIE.actions.showSystemAlert('暂无可调账的启用成员（仅设计师）'); return; }
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'manualAdjustmentModal';
  modal.innerHTML = `<div class="modal" style="max-width:540px;">
    <div class="modal-header"><div class="modal-header-left"><div class="modal-title">手动调账</div><div class="modal-subtitle">管理员主动补分或扣分，必填备注并记入调账台账</div></div><button class="modal-close" data-emie-action="click:scoring-close-adjustment">✕</button></div>
    <div class="modal-body">
      <div class="form-group"><label class="form-label">成员 <span style="color:var(--gray-400);">（仅设计师）</span></label><select class="form-input" id="manualAdjUser"><option value="">请选择成员</option>${eligibleUsers.map(u => `<option value="${escHtml(u.userId)}">${escHtml(u.name || u.userId)}（${escHtml(u.userId)}）</option>`).join('')}</select></div>
      <div class="form-group"><label class="form-label">积分 <span style="color:var(--danger);">*</span></label><input class="form-input" type="number" step="1" id="manualAdjPoints" placeholder="例如：50 或 -30"><div style="font-size:12px;color:var(--gray-400);margin-top:4px;">非零整数，绝对值不超过 100000；增加填正数，扣减填负数。</div></div>
      <div class="form-group"><label class="form-label">备注 <span style="color:var(--danger);">*</span></label><textarea class="form-input" id="manualAdjReason" rows="3" maxlength="500" placeholder="必填，说明调账原因（500 字内）"></textarea></div>
      <div id="manualAdjError" style="display:none;color:var(--danger);font-size:13px;"></div>
    </div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:scoring-close-adjustment">取消</button><button class="btn btn-primary" data-emie-action="click:scoring-submit-adjustment">确认调账</button></div>
  </div>`;
  document.body.appendChild(modal);
}

async function submitManualAdjustment(button) {
  const error = document.getElementById('manualAdjError');
  if (error) { error.style.display = 'none'; }
  const userId = document.getElementById('manualAdjUser')?.value;
  const pointsRaw = document.getElementById('manualAdjPoints')?.value;
  const reason = document.getElementById('manualAdjReason')?.value.trim();
  if (!userId) { if (error) { error.textContent = '请选择成员。'; error.style.display = 'block'; } return; }
  const points = Number(pointsRaw);
  if (String(pointsRaw || '').trim() === '' || !Number.isInteger(points) || points === 0 || Math.abs(points) > 100000) {
    if (error) { error.textContent = '积分必须为非零整数，且绝对值不超过 100000。'; error.style.display = 'block'; }
    return;
  }
  if (!reason) { if (error) { error.textContent = '请填写调账备注。'; error.style.display = 'block'; } return; }
  if (reason.length > 500) { if (error) { error.textContent = '备注不能超过 500 字。'; error.style.display = 'block'; } return; }
  if (button) { button.disabled = true; button.textContent = '调账中…'; }
  try {
    await apiPost('/point-governance/manual-adjustment', { userId, points, reason });
    closeM('manualAdjustmentModal');
    showAdminToast('调账成功，已记入调账台账', 'success');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) {
    if (error) { error.textContent = '调账失败：' + (e.message || '请稍后重试'); error.style.display = 'block'; }
    if (button) { button.disabled = false; button.textContent = '确认调账'; }
  }
}

async function createPoPointProject() {
  if (document.getElementById('poPointProjectModal')) return;
  let designers = Array.isArray(EMIE.adminState.designerTargets) ? EMIE.adminState.designerTargets : [];
  if (!designers.length) {
    try {
      const rows = await apiGet('/performance/designer-targets?month=' + encodeURIComponent(new Date().toISOString().slice(0, 7)));
      designers = Array.isArray(rows) ? rows : [];
      EMIE.adminState.designerTargets = designers;
    } catch (error) {
      designers = [];
    }
  }
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'poPointProjectModal';
  modal.innerHTML = `<div class="modal" style="max-width:540px;">
    <div class="modal-header"><div class="modal-header-left"><div class="modal-title">新增 PO 产品</div><div class="modal-subtitle">为产品指定负责设计师及月度固定积分</div></div><button class="modal-close" data-emie-action="click:scoring-close-po">✕</button></div>
    <div class="modal-body">
      <div class="form-group"><label class="form-label">产品名称</label><input class="form-input" id="poProjectName" maxlength="100" placeholder="请输入产品名称"></div>
      <div class="form-group"><label class="form-label">PO 设计师</label><select class="form-input" id="poProjectOwner"><option value="">请选择设计师</option>${designers.map(item => `<option value="${escHtml(item.userId)}" data-name="${escHtml(item.userName || item.userId)}">${escHtml(item.userName || item.userId)}</option>`).join('')}</select>${designers.length ? '' : '<div style="font-size:12px;color:var(--danger);margin-top:6px;">暂无在职设计师，请先在用户管理中添加设计师。</div>'}</div>
      <div class="form-group"><label class="form-label">每月固定积分</label><input class="form-input" type="number" min="0" step="1" value="30" id="poProjectMonthlyPoints"></div>
      <div id="poProjectError" style="display:none;color:var(--danger);font-size:13px;"></div>
    </div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-action="click:scoring-close-po">取消</button><button class="btn btn-primary" data-emie-action="click:scoring-submit-po" ${designers.length ? '' : 'disabled'}>确认新增</button></div>
  </div>`;
  document.body.appendChild(modal);
}

async function submitPoPointProjectForm(button) {
  const owner = document.getElementById('poProjectOwner');
  const payload = {
    name: document.getElementById('poProjectName')?.value.trim(),
    ownerUserId: owner?.value,
    ownerName: owner?.selectedOptions?.[0]?.dataset.name || '',
    monthlyPoints: Number(document.getElementById('poProjectMonthlyPoints')?.value || 0),
  };
  const error = document.getElementById('poProjectError');
  if (!payload.name || !payload.ownerUserId || !Number.isFinite(payload.monthlyPoints) || payload.monthlyPoints < 0) {
    if (error) { error.textContent = '请填写产品名称、选择 PO 设计师并确认月度积分。'; error.style.display = 'block'; }
    return;
  }
  if (button) { button.disabled = true; button.textContent = '新增中…'; }
  try {
    await apiPost('/point-governance/po/projects', payload);
    closeM('poPointProjectModal');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) {
    if (error) { error.textContent = '新增失败：' + (e.message || '请稍后重试'); error.style.display = 'block'; }
    if (button) { button.disabled = false; button.textContent = '确认新增'; }
  }
}

async function reviewPoProgress(id, approve) {
  const comment = await EMIE.actions.showSystemInput(approve ? '请输入确认说明（可简填）' : '请输入驳回原因', approve ? '确认完成本月履职' : '');
  if (comment === null || (!approve && !comment.trim())) return;
  try { await apiPost(`/point-governance/po/progress/${id}/review`, { approve, comment }); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { window.EMIE.actions.showSystemAlert('处理失败：' + e.message); }
}

async function archivePointMonth() {
  const month = await window.EMIE.actions.showSystemInput('请输入需要归档的月份（YYYY-MM）', '', '月度积分归档'); if (!month) return;
  if (!await window.EMIE.actions.showSystemConfirm(`归档 ${month} 后将不可修改，确认继续？`, '确认归档')) return;
  try { await apiPost(`/point-governance/archives/${encodeURIComponent(month)}/archive`, {}); window.EMIE.actions.showSystemAlert('月度积分已归档'); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { window.EMIE.actions.showSystemAlert('归档失败：' + e.message); }
}

async function configurePointSkills() {
  const userId = await EMIE.actions.showSystemInput('请输入成员用户 ID'); if (!userId) return;
  let current = [];
  try {
    const data = await apiGet('/points/skills/' + encodeURIComponent(userId.trim()));
    current = JSON.parse(data.skillsJson || '[]');
  } catch (e) { window.EMIE.actions.showSystemAlert('读取失败：' + e.message); return; }
  const raw = await EMIE.actions.showSystemInput('请输入能力标签（逗号分隔，最多20项）', current.join('、'));
  if (raw === null) return;
  const skills = raw.split(/[,，、]/).map(value => value.trim()).filter(Boolean);
  try { await apiPut('/points/skills/' + encodeURIComponent(userId.trim()), { skills }); window.EMIE.actions.showSystemAlert('能力标签已保存'); }
  catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

async function configureMarketEligibility() {
  const input = window.EMIE.actions.showSystemInput;
  let users; try { users = await apiGet('/admin/users'); } catch (e) { return window.EMIE.actions.showSystemAlert('读取成员列表失败：' + e.message); }
  const options = (Array.isArray(users) ? users : []).filter(u => String(u.role || '').toLowerCase() === 'designer').map(u => ({ value: u.userId, label: `${u.name || u.userName || '未命名'}（${u.userId}）` }));
  const userId = await window.EMIE.actions.showSystemSelect('选择需要管理接单资格的成员', options, '配置成员接单资格'); if (!userId) return;
  let current; try { current = await apiGet('/points/market-eligibility/' + encodeURIComponent(userId.trim())); } catch (e) { return window.EMIE.actions.showSystemAlert('读取失败：' + e.message); }
  const until = await input('暂停到期时间（YYYY-MM-DDTHH:mm，留空立即恢复）', current.suspendedUntil ? String(current.suspendedUntil).slice(0,16) : '', '设置暂停时间'); if (until === null) return;
  const reason = until ? await input('请输入暂停原因', current.reason || '无故退单/长期占单/多次延期', '填写暂停原因') : '恢复开放接单资格'; if (reason === null) return;
  const violationRaw = until ? await input('累计违规次数', String(Number(current.violationCount || 0) + 1), '更新违规次数') : String(Number(current.violationCount || 0)); if (violationRaw === null) return;
  const violationCount = Number(violationRaw || 0);
  try { await apiPut('/points/market-eligibility/' + encodeURIComponent(userId.trim()), { suspendedUntil: until || null, reason, violationCount }); window.EMIE.actions.showSystemAlert(until ? '已暂停该成员开放接单资格' : '已恢复该成员开放接单资格'); }
  catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}

async function preparePointArchive() {
  const month = await EMIE.actions.showSystemInput('请输入需要自动生成归档草稿的月份（YYYY-MM）'); if (!month) return;
  try { await apiPost(`/point-governance/archives/${encodeURIComponent(month)}/prepare`, {}); window.EMIE.actions.showSystemAlert('已根据积分、个人标准和供单状态生成草稿'); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { window.EMIE.actions.showSystemAlert('生成失败：' + e.message); }
}
async function savePointArchiveDraft() {
  const month = await EMIE.actions.showSystemInput('归档月份（YYYY-MM）'); if (!/^\d{4}-\d{2}$/.test(month || '')) { if (month) window.EMIE.actions.showSystemAlert('月份格式应为 YYYY-MM'); return; }
  const userId = await EMIE.actions.showSystemInput('成员用户 ID'); if (!userId) return;
  const earnedPoints = Number(await EMIE.actions.showSystemInput('该月实际获得积分', '0')); if (!Number.isFinite(earnedPoints)) return window.EMIE.actions.showSystemAlert('实际积分格式错误');
  const targetPoints = Number(await EMIE.actions.showSystemInput('该月目标积分', '100')); if (!Number.isFinite(targetPoints) || targetPoints < 0) return window.EMIE.actions.showSystemAlert('目标积分格式错误');
  const suppliedPoints = Number(await EMIE.actions.showSystemInput('该月供单积分', '0')); if (!Number.isFinite(suppliedPoints)) return window.EMIE.actions.showSystemAlert('供单积分格式错误');
  const quarterlyAveragePoints = Number(await EMIE.actions.showSystemInput('季度平均积分（归档快照）', String(earnedPoints))); if (!Number.isFinite(quarterlyAveragePoints)) return window.EMIE.actions.showSystemAlert('季度平均积分格式错误');
  const protectionEnabled = await EMIE.actions.showSystemConfirm('是否启用供单不足保护？');
  try {
    await apiPut(`/point-governance/archives/${encodeURIComponent(month)}/${encodeURIComponent(userId.trim())}`, { earnedPoints, targetPoints, suppliedPoints, protectionEnabled, quarterlyAveragePoints });
    window.EMIE.actions.showSystemAlert('归档草稿已保存');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { window.EMIE.actions.showSystemAlert('保存失败：' + e.message); }
}


EMIE.registerActions({
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
  renderAdminPoints,
  filterAdminPointRules,
  resetAdminPointRuleFilters,
  savePointRule,
  createPointRule, deletePointRule, loadDesignerTargetMonth, saveDesignerTarget,
  savePointDifficulty,
  saveStandardPointConfig,
  deleteStandardPointConfig,
  saveMonthlyPerformanceConfig,
  reviewPointAppeal,
  openManualAdjustmentModal,
  submitManualAdjustment,
  createPoPointProject,
  submitPoPointProjectForm,
  reviewPoProgress,
  configurePointSkills,
  configureMarketEligibility,
  saveWithdrawalGovernanceConfig,
  preparePointArchive,
  savePointArchiveDraft,
  archivePointMonth,
});

const registerEventAction = EMIE.actions.registerEventAction;
if (registerEventAction) {
  registerEventAction('scoring-reset-weights', () => resetScoringWeights());
  registerEventAction('scoring-save-weights', () => saveScoringWeights());
  registerEventAction('scoring-filter-rules', () => filterAdminPointRules());
  registerEventAction('scoring-reset-rules', () => resetAdminPointRuleFilters());
  registerEventAction('scoring-pct', (_event, el) => updateScoringPct(el.dataset.scoreType, el.dataset.scoreRole, el.value));
  registerEventAction('scoring-rule-toggle', (_event, el) => el.closest('.checkbox-item')?.classList.toggle('checked', el.checked));
  registerEventAction('scoring-save-rule', (_event, el) => savePointRule(el.dataset.ruleCode, Number(el.dataset.ruleIndex)));
  registerEventAction('scoring-delete-rule', (_event, el) => deletePointRule(el.dataset.ruleCode));
  registerEventAction('scoring-save-difficulty', (_event, el) => savePointDifficulty(el.dataset.difficultyCode, Number(el.dataset.difficultyIndex)));
  registerEventAction('scoring-save-target', (_event, el) => saveDesignerTarget(Number(el.dataset.targetIndex)));
  registerEventAction('scoring-create-rule', () => createPointRule());
  registerEventAction('scoring-close-adjustment', () => closeM('manualAdjustmentModal'));
  registerEventAction('scoring-submit-adjustment', (_event, el) => submitManualAdjustment(el));
  registerEventAction('scoring-close-po', () => closeM('poPointProjectModal'));
  registerEventAction('scoring-submit-po', (_event, el) => submitPoPointProjectForm(el));
}

EMIE.registerModule('adminScoring', {
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
  renderAdminPoints,
  filterAdminPointRules,
  resetAdminPointRuleFilters,
  savePointRule,
  createPointRule, deletePointRule, loadDesignerTargetMonth, saveDesignerTarget,
  savePointDifficulty,
  saveStandardPointConfig,
  deleteStandardPointConfig,
  saveMonthlyPerformanceConfig,
  reviewPointAppeal,
  openManualAdjustmentModal,
  submitManualAdjustment,
  createPoPointProject,
  submitPoPointProjectForm,
  reviewPoProgress,
  configurePointSkills,
  configureMarketEligibility,
  saveWithdrawalGovernanceConfig,
  preparePointArchive,
  savePointArchiveDraft,
  archivePointMonth,
});
