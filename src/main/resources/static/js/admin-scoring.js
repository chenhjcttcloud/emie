const EMIE = window.EMIE;
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const apiPut = (...args) => EMIE.actions.apiPut(...args);
const apiPost = (...args) => EMIE.actions.apiPost(...args);
const apiDelete = (...args) => EMIE.actions.apiDelete(...args);
const escJsString = (...args) => EMIE.actions.escJsString(...args);
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const closeM = (...args) => EMIE.actions.closeM(...args);

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
          <button class="btn btn-outline btn-sm" data-emie-onclick="resetScoringWeights()" style="color:var(--gray-500);">↺ 重置默认</button>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;margin-top:10px;">
          <label class="form-label">卓越阈值<input class="form-input" type="number" min="0" id="pr_top_threshold_${index}" value="${Number(rule.qualityTopThreshold ?? 97)}"></label>
          <label class="form-label">卓越加分比例<input class="form-input" type="number" min="0" step="0.05" id="pr_top_ratio_${index}" value="${Number(rule.qualityTopRatio ?? 0.6)}"></label>
          <label class="form-label">总积分封顶倍数<input class="form-input" type="number" min="1" step="0.1" id="pr_cap_${index}" value="${Number(rule.maxTotalMultiplier ?? 3)}"></label>
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
                    <input type="range" min="0" max="100" step="1" value="${Math.round(w.weight)}" style="flex:1;" data-emie-oninput="updateScoringPct('${t.type}','${w.role}',this.value)">
                    <input type="number" class="form-input" value="${Math.round(w.weight)}" min="0" max="100" step="1" style="width:55px;text-align:center;padding:4px 6px;" data-emie-onchange="updateScoringPct('${t.type}','${w.role}',this.value)">
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
          <button class="btn btn-primary" data-emie-onclick="saveScoringWeights()">💾 保存权重</button>
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
    alert('评分权重已保存');
  } catch (e) {
    alert('保存失败: ' + e.message);
  }
};

const resetScoringWeights = async function() {
  if (!confirm('确定重置所有权重为默认值？')) return;
  window.location.reload();
};

async function renderAdminPoints(container) {
  container.innerHTML = '<div style="padding:40px;text-align:center;color:var(--gray-400);">加载积分规则…</div>';
  try {
    const targetMonth = new Date().toISOString().slice(0, 7);
    const [rules, difficulties, standards, months, designerTargets, appeals, poProgress, archives, proposals, forecasts] = await Promise.all([
      apiGet('/points/rules'),
      apiGet('/points/difficulties'),
      apiGet('/performance/standard'),
      apiGet('/performance/monthly'),
      apiGet('/performance/designer-targets?month=' + encodeURIComponent(targetMonth)),
      apiGet('/point-governance/appeals'),
      apiGet('/point-governance/po/progress'),
      apiGet('/point-governance/archives'),
      apiGet('/point-program/proposals'), apiGet('/point-program/forecasts'),
    ]);
    const ruleList = Array.isArray(rules) ? rules : [];
    const difficultyList = Array.isArray(difficulties) ? difficulties : [];
    const standardList = Array.isArray(standards) ? standards : [];
    const monthList = Array.isArray(months) ? months : [];
    const designerTargetList = Array.isArray(designerTargets) ? designerTargets : [];
    const configuredStandardIds = new Set(standardList.map(item => String(item.configCode || '')));
    const availableDesigners = designerTargetList.filter(user => !configuredStandardIds.has(String(user.userId || '')));
    const appealList = Array.isArray(appeals) ? appeals : [];
    const poProgressList = Array.isArray(poProgress) ? poProgress : [];
    const archiveList = Array.isArray(archives) ? archives : [];
    const proposalList = Array.isArray(proposals) ? proposals : [];
    const forecastList = Array.isArray(forecasts) ? forecasts : [];
    container.innerHTML = `<div style="max-width:1100px;margin:0 auto;">
      <div style="margin-bottom:18px;"><h2 style="font-size:18px;margin:0 0 4px;">🏅 积分与绩效配置</h2><p style="color:var(--gray-500);font-size:13px;margin:0;">规则修改只影响后续入账，历史积分台账不会重算。</p></div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>积分规则</h3><button class="btn btn-primary btn-sm" data-emie-onclick="createPointRule()">＋ 新增规则</button></div>${ruleList.length ? ruleList.map((rule, index) => `<div style="padding:16px 0;${index ? 'border-top:1px solid var(--gray-200);' : ''}">
        <div style="display:grid;grid-template-columns:1.1fr 1fr .8fr .9fr .9fr;gap:10px;align-items:end;">
          <label class="form-label">规则编号<input class="form-input" value="${escHtml(rule.ruleCode || '')}" disabled></label>
          <label class="form-label">类别<input class="form-input" id="pr_category_${index}" value="${escHtml(rule.category || '')}" placeholder="如 A / B / E / S"></label>
          <label class="form-label">基础分<input class="form-input" type="number" min="0" id="pr_points_${index}" value="${Number(rule.points || 0)}"></label>
          <label class="form-label">质量阈值<input class="form-input" type="number" min="0" id="pr_threshold_${index}" value="${Number(rule.qualityBonusThreshold || 0)}"></label>
          <label class="form-label">加分比例<input class="form-input" type="number" min="0" step="0.05" id="pr_ratio_${index}" value="${Number(rule.qualityBonusRatio || 0)}"></label>
        </div>
        <div style="display:flex;gap:12px;align-items:center;margin-top:12px;flex-wrap:wrap;">
          <input class="form-input" id="pr_desc_${index}" value="${escHtml(rule.description || '')}" placeholder="规则说明" style="flex:1;min-width:240px;">
          <label class="checkbox-item ${rule.enabled === false ? '' : 'checked'}"><input type="checkbox" id="pr_enabled_${index}" ${rule.enabled === false ? '' : 'checked'}> 启用</label>
          <label class="checkbox-item ${rule.countInPerformance === false ? '' : 'checked'}"><input type="checkbox" id="pr_performance_${index}" ${rule.countInPerformance === false ? '' : 'checked'}> 计入绩效</label>
          <button class="btn btn-primary btn-sm" data-emie-onclick="savePointRule('${escJsString(rule.ruleCode)}',${index})">保存规则</button>
          <button class="btn btn-danger btn-sm" data-emie-onclick="deletePointRule('${escJsString(rule.ruleCode)}')">移除</button>
        </div>
      </div>`).join('') : '<div class="empty-state">暂无积分规则</div>'}</div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><div><h3>设计师本月目标</h3><p style="font-size:12px;color:var(--gray-500);margin:4px 0 0;">管理员按月份为每位设计师单独设置目标积分。</p></div><input class="form-input" type="month" id="designerTargetMonth" value="${targetMonth}" data-emie-onchange="loadDesignerTargetMonth(this.value)" style="width:160px;"></div>
        <div class="table-wrap"><table><thead><tr><th>设计师</th><th>用户 ID</th><th>目标积分</th><th>状态</th><th>操作</th></tr></thead><tbody id="designerTargetBody">${renderDesignerTargetRows(designerTargetList)}</tbody></table></div>
      </div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>难度档位</h3></div><div class="table-wrap"><table><thead><tr><th>档位</th><th>系数</th><th>说明</th><th>启用</th><th>操作</th></tr></thead><tbody>${difficultyList.map((item,index)=>`<tr><td><strong>${escHtml(item.difficultyCode || '-')}</strong></td><td><input class="form-input" type="number" min="0.1" max="10" step="0.1" id="pd_multiplier_${index}" value="${Number(item.multiplier || 1)}"></td><td><input class="form-input" id="pd_desc_${index}" value="${escHtml(item.description || '')}"></td><td><input type="checkbox" id="pd_enabled_${index}" ${item.enabled === false ? '' : 'checked'}></td><td><button class="btn btn-primary btn-sm" data-emie-onclick="savePointDifficulty('${escJsString(item.difficultyCode)}',${index})">保存</button></td></tr>`).join('')}</tbody></table></div></div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>设计师个人绩效配置</h3></div>
        <p style="font-size:12px;color:var(--gray-500);">仅支持设计师，用于配置个人标准积分与绩效基数。</p>
        <div style="display:grid;grid-template-columns:1.4fr .8fr .8fr 1.2fr auto;gap:8px;align-items:end;margin:14px 0 18px;">
          <label class="form-label">选择设计师<select class="form-select" id="sp_new_user"><option value="">请选择设计师</option>${availableDesigners.map(user => `<option value="${escHtml(user.userId)}">${escHtml(user.userName || user.userId)}</option>`).join('')}</select></label>
          <label class="form-label">标准积分<input class="form-input" type="number" min="0" id="sp_new_points" value="100"></label>
          <label class="form-label">绩效基数<input class="form-input" type="number" min="0" step="0.01" id="sp_new_base" value="0"></label>
          <label class="form-label">说明<input class="form-input" id="sp_new_desc" value="个人绩效配置"></label>
          <button class="btn btn-primary btn-sm" data-emie-onclick="saveStandardPointConfig(-1)" ${availableDesigners.length ? '' : 'disabled'}>添加</button>
        </div>
        <div class="table-wrap"><table><thead><tr><th>用户 ID</th><th>标准积分</th><th>绩效基数</th><th>说明</th><th>状态</th><th>操作</th></tr></thead><tbody>${standardList.length ? standardList.map((item, index) => `<tr><td><input class="form-input" id="sp_code_${index}" value="${escHtml(item.configCode || '')}" ${item.id ? 'disabled' : ''}></td><td><input class="form-input" type="number" min="0" id="sp_points_${index}" value="${Number(item.points || 0)}"></td><td><input class="form-input" type="number" min="0" step="0.01" id="sp_base_${index}" value="${Number(item.performanceBase || 0)}"></td><td><input class="form-input" id="sp_desc_${index}" value="${escHtml(item.description || '')}"></td><td><label class="checkbox-item ${item.enabled === false ? '' : 'checked'}"><input type="checkbox" id="sp_enabled_${index}" ${item.enabled === false ? '' : 'checked'}> 启用</label></td><td><button class="btn btn-primary btn-sm" data-emie-onclick="saveStandardPointConfig(${index})">保存</button></td></tr>`).join('') : '<tr><td colspan="6"><div class="empty-state">暂无个人标准积分配置，可点击上方添加</div></td></tr>'}</tbody></table></div>
      </div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><div><h3>月度绩效配置</h3><div id="feishuSalesSyncResult" class="points-sync-result">销售额自动同步已开启，每小时更新</div></div><div style="display:flex;gap:8px;"><button class="btn btn-outline btn-sm" data-emie-onclick="syncFeishuSalesPerformance(this)">↻ 同步销售额</button><button class="btn btn-outline btn-sm" data-emie-onclick="saveMonthlyPerformanceConfig(-1)">＋ 配置当前月</button></div></div>
        <p style="font-size:12px;color:var(--gray-500);">月度目标与系数用于排行榜及个人绩效预览，最终结果以月度归档为准。</p>
        <div class="table-wrap"><table><thead><tr><th>月份</th><th>目标积分</th><th>公司销售额（万元）</th><th>手动系数</th><th>供单不足</th><th>操作</th></tr></thead><tbody>${monthList.length ? monthList.map((item, index) => `<tr><td><input class="form-input" type="month" id="mp_month_${index}" value="${escHtml(item.monthKey || '')}" ${item.id ? 'disabled' : ''}></td><td><input class="form-input" type="number" min="0" id="mp_target_${index}" value="${Number(item.targetPoints || 0)}"></td><td><input class="form-input" type="number" min="0" step="0.01" id="mp_sales_${index}" value="${item.salesAmount ?? ''}" placeholder="填写后自动匹配档位"></td><td><input class="form-input" type="number" min="0" step="0.01" id="mp_multiplier_${index}" value="${Number(item.multiplier || 1)}"></td><td><input type="checkbox" id="mp_shortage_${index}" ${item.supplyShortage ? 'checked' : ''}></td><td><button class="btn btn-primary btn-sm" data-emie-onclick="saveMonthlyPerformanceConfig(${index})">保存</button></td></tr>`).join('') : '<tr><td colspan="6"><div class="empty-state">暂无月度配置，可点击右上角配置当前月</div></td></tr>'}</tbody></table></div>
      </div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>积分异议复核</h3></div>${appealList.length ? `<div class="table-wrap"><table><thead><tr><th>申请人</th><th>记录</th><th>类型与原因</th><th>状态</th><th>操作</th></tr></thead><tbody>${appealList.map(item => `<tr><td>${escHtml(item.applicantName || item.applicantUserId || '-')}</td><td>#${Number(item.pointLedgerId || 0)}</td><td>${escHtml(item.type || '-')}<div style="font-size:12px;color:var(--gray-500);">${escHtml(item.reason || '')}</div></td><td>${escHtml(item.status || '-')}</td><td>${item.status === 'PLANNER_PROCESSED' ? `<button class="btn btn-success btn-sm" data-emie-onclick="reviewPointAppeal(${Number(item.id)},true)">通过</button> <button class="btn btn-danger btn-sm" data-emie-onclick="reviewPointAppeal(${Number(item.id)},false)">驳回</button>` : '-'}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty-state">暂无积分异议</div>'}</div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>PO 月度积分</h3><button class="btn btn-outline btn-sm" data-emie-onclick="createPoPointProject()">＋ 新增 PO 产品</button></div>${poProgressList.length ? `<div class="table-wrap"><table><thead><tr><th>月份</th><th>项目ID</th><th>进展</th><th>状态</th><th>操作</th></tr></thead><tbody>${poProgressList.map(item => `<tr><td>${escHtml(item.monthKey || '-')}</td><td>#${Number(item.poProjectId || 0)}</td><td>${escHtml(item.summary || '-')}</td><td>${escHtml(item.status || '-')}</td><td>${item.status === 'SUBMITTED' ? `<button class="btn btn-success btn-sm" data-emie-onclick="reviewPoProgress(${Number(item.id)},true)">确认入账</button> <button class="btn btn-danger btn-sm" data-emie-onclick="reviewPoProgress(${Number(item.id)},false)">驳回</button>` : '-'}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty-state">暂无 PO 月度进展</div>'}</div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>成员接单治理</h3><div style="display:flex;gap:8px;"><button class="btn btn-outline btn-sm" data-emie-onclick="configurePointSkills()">配置能力标签</button><button class="btn btn-outline btn-sm" data-emie-onclick="configureMarketEligibility()">暂停/恢复接单资格</button></div></div><p style="font-size:12px;color:var(--gray-500);">能力标签控制可接任务范围；无故退单、长期占单或多次延期可暂停开放接单资格。</p></div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>自主开发提案</h3></div>${proposalList.length?proposalList.map(item=>`<div style="padding:10px 0;border-bottom:1px solid var(--gray-100);"><strong>${escHtml(item.title)}</strong> · ${escHtml(item.applicantName)} · ${escHtml(item.status)} ${item.status==='SUBMITTED'?`<button class="btn btn-success btn-sm" data-emie-onclick="reviewSelfProposal(${item.id},true)">立项</button> <button class="btn btn-danger btn-sm" data-emie-onclick="reviewSelfProposal(${item.id},false)">驳回</button>`:''}<div style="font-size:12px;color:var(--gray-500);">${escHtml(item.description||'')}</div>${renderProposalDesignFiles(item.referenceImagesJson)}</div>`).join(''):'<div class="empty-state">暂无提案</div>'}</div>
      <div class="card" style="padding:16px;margin-bottom:18px;"><div class="card-header"><h3>下月任务预发布</h3><button class="btn btn-primary btn-sm" data-emie-onclick="createTaskForecast()">新增预告</button></div>${forecastList.length?forecastList.map(item=>`<div style="padding:8px 0;">${escHtml(item.monthKey)} · <strong>${escHtml(item.title)}</strong> · ${escHtml(item.status)} ${item.status==='DRAFT'?`<button class="btn btn-success btn-sm" data-emie-onclick="publishTaskForecast(${item.id})">发布</button>`:''}</div>`).join(''):'<div class="empty-state">暂无任务预告</div>'}</div>
      <div class="card" style="padding:16px;"><div class="card-header"><h3>月度积分归档</h3><div style="display:flex;gap:8px;"><button class="btn btn-outline btn-sm" data-emie-onclick="preparePointArchive()">自动生成草稿</button><button class="btn btn-outline btn-sm" data-emie-onclick="savePointArchiveDraft()">手工调整草稿</button><button class="btn btn-primary btn-sm" data-emie-onclick="archivePointMonth()">归档指定月份</button></div></div>${archiveList.length ? `<div class="table-wrap"><table><thead><tr><th>月份</th><th>用户</th><th>获得 / 目标 / 供单</th><th>保护</th><th>季度均值</th><th>状态</th></tr></thead><tbody>${archiveList.map(item => `<tr><td>${escHtml(item.monthKey || '-')}</td><td>${escHtml(item.userId || '-')}</td><td>${Number(item.earnedPoints || 0)} / ${Number(item.targetPoints || 0)} / ${Number(item.suppliedPoints || 0)}</td><td>${item.insufficientSupplyProtection ? '已启用' : '否'}</td><td>${Number(item.quarterlyAveragePoints || 0)}</td><td>${item.status === 'ARCHIVED' ? '已归档' : '草稿'}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty-state">暂无月度归档草稿，可自动生成后统一归档。</div>'}</div>
    </div>`;
    EMIE.adminState.pointStandards = standardList;
    EMIE.adminState.pointMonths = monthList;
    EMIE.adminState.designerTargets = designerTargetList;
  } catch (e) { container.innerHTML = `<div class="empty">积分规则加载失败：${escHtml(e.message || '')}</div>`; }
}

function renderDesignerTargetRows(rows) {
  return rows.length ? rows.map((item, index) => `<tr><td><strong>${escHtml(item.userName || '-')}</strong></td><td>${escHtml(item.userId || '-')}</td><td><input class="form-input" type="number" min="0" id="dt_points_${index}" value="${Number(item.targetPoints || 0)}"></td><td>${item.configured ? '<span class="badge badge-completed">已配置</span>' : '<span class="badge badge-pending">待配置</span>'}</td><td><button class="btn btn-primary btn-sm" data-emie-onclick="saveDesignerTarget(${index})">保存</button></td></tr>`).join('') : '<tr><td colspan="5"><div class="empty-state">暂无在职设计师</div></td></tr>';
}

function renderProposalDesignFiles(value) {
  let files = [];
  try { files = JSON.parse(value || '[]'); } catch (_) { return ''; }
  if (!Array.isArray(files) || !files.length) return '';
  return `<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px;">${files.map(file => {
    const storedName = String(file.storedName || '').replace(/[^a-zA-Z0-9._-]/g, '');
    return storedName ? `<a class="btn btn-outline btn-sm" href="/api/files/download/${encodeURIComponent(storedName)}" target="_blank" rel="noopener">查看设计图 · ${escHtml(file.name || '附件')}</a>` : '';
  }).join('')}</div>`;
}

async function loadDesignerTargetMonth(month) {
  if (!/^\d{4}-\d{2}$/.test(month || '')) return;
  try {
    const rows = await apiGet('/performance/designer-targets?month=' + encodeURIComponent(month));
    EMIE.adminState.designerTargets = Array.isArray(rows) ? rows : [];
    const body = document.getElementById('designerTargetBody');
    if (body) body.innerHTML = renderDesignerTargetRows(EMIE.adminState.designerTargets);
  } catch (e) { alert('目标加载失败：' + e.message); }
}

async function saveDesignerTarget(index) {
  const item = (EMIE.adminState.designerTargets || [])[index];
  const monthKey = document.getElementById('designerTargetMonth')?.value;
  if (!item || !monthKey) return;
  try {
    await apiPut('/performance/designer-targets/' + encodeURIComponent(item.userId), { monthKey, targetPoints: Number(document.getElementById('dt_points_' + index)?.value || 0) });
    await loadDesignerTargetMonth(monthKey);
  } catch (e) { alert('目标保存失败：' + e.message); }
}

async function createPointRule() {
  const ruleCode = (window.prompt('规则编号（字母、数字、下划线或短横线）') || '').trim().toUpperCase();
  if (!ruleCode) return;
  const category = (window.prompt('规则分类', 'GENERAL') || 'GENERAL').trim();
  const points = Number(window.prompt('基础积分', '10'));
  const description = (window.prompt('规则说明', '') || '').trim();
  try {
    await apiPost('/points/rules', { ruleCode, category, points, description, countInPerformance: true });
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { alert('新增失败：' + e.message); }
}

async function deletePointRule(code) {
  if (!confirm(`确定移除积分规则 ${code} 吗？历史任务快照和积分台账不会变化。`)) return;
  try { await apiDelete('/points/rules/' + encodeURIComponent(code)); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { alert('移除失败：' + e.message); }
}

async function savePointRule(code, index) {
  try {
    await apiPut('/points/rules/' + encodeURIComponent(code), { points: Number(document.getElementById('pr_points_' + index).value), category: document.getElementById('pr_category_' + index).value.trim(), qualityBonusThreshold: Number(document.getElementById('pr_threshold_' + index).value), qualityBonusRatio: Number(document.getElementById('pr_ratio_' + index).value), qualityTopThreshold: Number(document.getElementById('pr_top_threshold_' + index).value), qualityTopRatio: Number(document.getElementById('pr_top_ratio_' + index).value), maxTotalMultiplier: Number(document.getElementById('pr_cap_' + index).value), description: document.getElementById('pr_desc_' + index).value.trim(), enabled: document.getElementById('pr_enabled_' + index).checked, countInPerformance: document.getElementById('pr_performance_' + index).checked });
    alert('积分规则已保存');
  } catch (e) { alert('保存失败：' + e.message); }
}

async function savePointDifficulty(code, index) {
  try {
    await apiPut('/points/difficulties/' + encodeURIComponent(code), { multiplier: Number(document.getElementById('pd_multiplier_' + index).value), description: document.getElementById('pd_desc_' + index).value.trim(), enabled: document.getElementById('pd_enabled_' + index).checked });
    alert('难度档位已保存');
  } catch (e) { alert('保存失败：' + e.message); }
}

async function saveStandardPointConfig(index) {
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
    alert('标准积分配置已保存');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { alert('保存失败：' + e.message); }
}

async function saveMonthlyPerformanceConfig(index) {
  const current = index >= 0 ? (EMIE.adminState.pointMonths || [])[index] : null;
  const now = new Date();
  const defaultMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const monthKey = current ? document.getElementById('mp_month_' + index)?.value : defaultMonth;
  const targetPoints = current ? Number(document.getElementById('mp_target_' + index)?.value) : Number(window.prompt(`${monthKey} 的目标积分`, '100') || 0);
  const multiplier = current ? Number(document.getElementById('mp_multiplier_' + index)?.value) : Number(window.prompt(`${monthKey} 的绩效系数`, '1') || 1);
  const salesRaw = current ? document.getElementById('mp_sales_' + index)?.value : window.prompt(`${monthKey} 公司销售额（万元，留空使用手动系数）`, '');
  const salesAmount = salesRaw === '' || salesRaw === null ? null : Number(salesRaw);
  const supplyShortage = current ? document.getElementById('mp_shortage_' + index)?.checked : window.confirm('该月是否存在企划供单不足？');
  if (!monthKey) return;
  try {
    await apiPut('/performance/monthly', { id: current?.id || null, monthKey, targetPoints, multiplier, salesAmount, supplyShortage });
    alert('月度绩效配置已保存');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { alert('保存失败：' + e.message); }
}

async function syncFeishuSalesPerformance(button) {
  const resultEl = document.getElementById('feishuSalesSyncResult');
  if (button) { button.disabled = true; button.textContent = '同步中…'; }
  if (resultEl) resultEl.textContent = '正在读取飞书销售数据…';
  try {
    const result = await apiPost('/performance/sales/sync', {});
    const syncMessage = result?.success === false ? (result.message || '同步失败，已保留上次结果') : `同步完成 · ${result.month || ''} · 有效订单 ${result.included ?? 0} 条 · 销售额 ${Number(result.salesAmount || 0).toLocaleString()} · 跳过 ${result.skipped ?? 0} 条`;
    if (resultEl) resultEl.textContent = syncMessage;
    await renderAdminPoints(document.getElementById('adminContent'));
    const refreshedResult = document.getElementById('feishuSalesSyncResult');
    if (refreshedResult) refreshedResult.textContent = syncMessage;
  } catch (e) { if (resultEl) resultEl.textContent = '同步失败：' + (e.message || '请检查飞书销售表配置'); }
  finally { if (button) { button.disabled = false; button.textContent = '↻ 同步销售额'; } }
}

async function reviewPointAppeal(id, approve) {
  const comment = window.prompt(approve ? '请输入复核通过说明' : '请输入驳回说明');
  if (!comment) return;
  let adjustmentPoints = null;
  if (approve) {
    const raw = window.prompt('请输入本次调账积分（增加填正数，扣减填负数，不调账填0）', '0');
    if (raw === null) return;
    adjustmentPoints = Number(raw);
    if (!Number.isInteger(adjustmentPoints)) { alert('调账积分必须是整数'); return; }
  }
  try { await apiPost(`/point-governance/appeals/${id}/admin-review`, { decision: approve ? 'APPROVE' : 'REJECT', comment, adjustmentPoints }); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { alert('处理失败：' + e.message); }
}

async function createPoPointProject() {
  if (document.getElementById('poPointProjectModal')) return;
  const designers = EMIE.adminState.designerTargets || [];
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'poPointProjectModal';
  modal.innerHTML = `<div class="modal" style="max-width:540px;">
    <div class="modal-header"><div class="modal-header-left"><div class="modal-title">新增 PO 产品</div><div class="modal-subtitle">为产品指定负责设计师及月度固定积分</div></div><button class="modal-close" data-emie-onclick="closeM('poPointProjectModal')">✕</button></div>
    <div class="modal-body">
      <div class="form-group"><label class="form-label">产品名称</label><input class="form-input" id="poProjectName" maxlength="100" placeholder="请输入产品名称"></div>
      <div class="form-group"><label class="form-label">PO 设计师</label><select class="form-input" id="poProjectOwner"><option value="">请选择设计师</option>${designers.map(item => `<option value="${escHtml(item.userId)}" data-name="${escHtml(item.userName || item.userId)}">${escHtml(item.userName || item.userId)}</option>`).join('')}</select>${designers.length ? '' : '<div style="font-size:12px;color:var(--danger);margin-top:6px;">暂无在职设计师，请先在用户管理中添加设计师。</div>'}</div>
      <div class="form-group"><label class="form-label">每月固定积分</label><input class="form-input" type="number" min="0" step="1" value="30" id="poProjectMonthlyPoints"></div>
      <div id="poProjectError" style="display:none;color:var(--danger);font-size:13px;"></div>
    </div>
    <div class="modal-footer"><button class="btn btn-outline" data-emie-onclick="closeM('poPointProjectModal')">取消</button><button class="btn btn-primary" data-emie-onclick="submitPoPointProjectForm(this)" ${designers.length ? '' : 'disabled'}>确认新增</button></div>
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
  const comment = window.prompt(approve ? '请输入确认说明（可简填）' : '请输入驳回原因', approve ? '确认完成本月履职' : '');
  if (comment === null || (!approve && !comment.trim())) return;
  try { await apiPost(`/point-governance/po/progress/${id}/review`, { approve, comment }); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { alert('处理失败：' + e.message); }
}

async function archivePointMonth() {
  const month = window.prompt('请输入需要归档的月份（YYYY-MM）'); if (!month) return;
  if (!window.confirm(`归档 ${month} 后将不可修改，确认继续？`)) return;
  try { await apiPost(`/point-governance/archives/${encodeURIComponent(month)}/archive`, {}); alert('月度积分已归档'); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { alert('归档失败：' + e.message); }
}

async function configurePointSkills() {
  const userId = window.prompt('请输入成员用户 ID'); if (!userId) return;
  let current = [];
  try {
    const data = await apiGet('/points/skills/' + encodeURIComponent(userId.trim()));
    current = JSON.parse(data.skillsJson || '[]');
  } catch (e) { alert('读取失败：' + e.message); return; }
  const raw = window.prompt('请输入能力标签（逗号分隔，最多20项）', current.join('、'));
  if (raw === null) return;
  const skills = raw.split(/[,，、]/).map(value => value.trim()).filter(Boolean);
  try { await apiPut('/points/skills/' + encodeURIComponent(userId.trim()), { skills }); alert('能力标签已保存'); }
  catch (e) { alert('保存失败：' + e.message); }
}

async function configureMarketEligibility() {
  const userId = window.prompt('请输入成员用户 ID'); if (!userId) return;
  let current; try { current = await apiGet('/points/market-eligibility/' + encodeURIComponent(userId.trim())); } catch (e) { return alert('读取失败：' + e.message); }
  const until = window.prompt('暂停到期时间（YYYY-MM-DDTHH:mm，留空立即恢复）', current.suspendedUntil ? String(current.suspendedUntil).slice(0,16) : ''); if (until === null) return;
  const reason = until ? window.prompt('请输入暂停原因', current.reason || '无故退单/长期占单/多次延期') : '恢复开放接单资格'; if (reason === null) return;
  const violationCount = until ? Number(window.prompt('累计违规次数', String(Number(current.violationCount || 0) + 1)) || 0) : Number(current.violationCount || 0);
  try { await apiPut('/points/market-eligibility/' + encodeURIComponent(userId.trim()), { suspendedUntil: until || null, reason, violationCount }); alert(until ? '已暂停该成员开放接单资格' : '已恢复该成员开放接单资格'); }
  catch (e) { alert('保存失败：' + e.message); }
}

async function preparePointArchive() {
  const month = window.prompt('请输入需要自动生成归档草稿的月份（YYYY-MM）'); if (!month) return;
  try { await apiPost(`/point-governance/archives/${encodeURIComponent(month)}/prepare`, {}); alert('已根据积分、个人标准和供单状态生成草稿'); await renderAdminPoints(document.getElementById('adminContent')); }
  catch (e) { alert('生成失败：' + e.message); }
}
async function reviewSelfProposal(id,approve){const comment=prompt(approve?'请输入立项意见':'请输入驳回原因');if(!comment)return;try{await apiPost(`/point-program/proposals/${id}/review`,{approve,comment});await renderAdminPoints(document.getElementById('adminContent'));}catch(e){alert('处理失败：'+e.message);}}
async function createTaskForecast(){const monthKey=prompt('预告月份（YYYY-MM）');if(!monthKey)return;const title=prompt('预计任务名称');if(!title)return;const description=prompt('任务说明')||'';const pointRuleCode=(prompt('建议积分规则','S1')||'').toUpperCase();const estimatedCount=Number(prompt('预计数量','1')||1);try{await apiPost('/point-program/forecasts',{monthKey,title,description,pointRuleCode,estimatedCount});await renderAdminPoints(document.getElementById('adminContent'));}catch(e){alert('保存失败：'+e.message);}}
async function publishTaskForecast(id){if(!confirm('发布后将对设计师可见，确认发布？'))return;try{await apiPost(`/point-program/forecasts/${id}/publish`,{});await renderAdminPoints(document.getElementById('adminContent'));}catch(e){alert('发布失败：'+e.message);}}

async function savePointArchiveDraft() {
  const month = window.prompt('归档月份（YYYY-MM）'); if (!/^\d{4}-\d{2}$/.test(month || '')) { if (month) alert('月份格式应为 YYYY-MM'); return; }
  const userId = window.prompt('成员用户 ID'); if (!userId) return;
  const earnedPoints = Number(window.prompt('该月实际获得积分', '0')); if (!Number.isFinite(earnedPoints)) return alert('实际积分格式错误');
  const targetPoints = Number(window.prompt('该月目标积分', '100')); if (!Number.isFinite(targetPoints) || targetPoints < 0) return alert('目标积分格式错误');
  const suppliedPoints = Number(window.prompt('该月供单积分', '0')); if (!Number.isFinite(suppliedPoints)) return alert('供单积分格式错误');
  const quarterlyAveragePoints = Number(window.prompt('季度平均积分（归档快照）', String(earnedPoints))); if (!Number.isFinite(quarterlyAveragePoints)) return alert('季度平均积分格式错误');
  const protectionEnabled = window.confirm('是否启用供单不足保护？');
  try {
    await apiPut(`/point-governance/archives/${encodeURIComponent(month)}/${encodeURIComponent(userId.trim())}`, { earnedPoints, targetPoints, suppliedPoints, protectionEnabled, quarterlyAveragePoints });
    alert('归档草稿已保存');
    await renderAdminPoints(document.getElementById('adminContent'));
  } catch (e) { alert('保存失败：' + e.message); }
}


EMIE.registerActions({
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
  renderAdminPoints,
  savePointRule,
  createPointRule, deletePointRule, loadDesignerTargetMonth, saveDesignerTarget,
  savePointDifficulty,
  saveStandardPointConfig,
  saveMonthlyPerformanceConfig,
  syncFeishuSalesPerformance,
  reviewPointAppeal,
  createPoPointProject,
  submitPoPointProjectForm,
  reviewPoProgress,
  configurePointSkills,
  configureMarketEligibility,
  preparePointArchive,
  reviewSelfProposal, createTaskForecast, publishTaskForecast,
  savePointArchiveDraft,
  archivePointMonth,
});

EMIE.registerModule('adminScoring', {
  renderAdminScoring,
  roleColor,
  updateScoringPct,
  saveScoringWeights,
  resetScoringWeights,
  renderAdminPoints,
  savePointRule,
  createPointRule, deletePointRule, loadDesignerTargetMonth, saveDesignerTarget,
  savePointDifficulty,
  saveStandardPointConfig,
  saveMonthlyPerformanceConfig,
  syncFeishuSalesPerformance,
  reviewPointAppeal,
  createPoPointProject,
  submitPoPointProjectForm,
  reviewPoProgress,
  configurePointSkills,
  configureMarketEligibility,
  preparePointArchive,
  reviewSelfProposal, createTaskForecast, publishTaskForecast,
  savePointArchiveDraft,
  archivePointMonth,
});
