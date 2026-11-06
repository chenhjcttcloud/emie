const EMIE = window.EMIE;
const escHtml = (...args) => EMIE.actions.escHtml(...args);
const scores = [
  ['completionScore', '设计任务完成率', 70, 150],
  ['collaborationScore', '协作能力', 10, 100],
  ['attendanceScore', '工作态度和考勤', 10, 100],
  ['weeklyReportScore', '周报考核', 10, 100],
];
const scoreCriteria = {
  completionScore: '设计师月度所得积分 ÷ 设计师目标积分 × 100，最高 150 分。目标积分未配置时暂不能评分。',
  collaborationScore: '0–50 分：推卸责任、不愿配合解决问题。\n51–70 分：沟通基本顺畅，但不主动解决问题。\n71–89 分：做好部门内外沟通，及时解决问题。\n90–100 分：积极主动解决协作障碍，并提出解决方法。',
  attendanceScore: '当月无迟到、早退、缺席、缺卡或未报批外出：100 分。\n发生 1–3 次：70–90 分；4 次：60 分；5 次及以上：0–50 分。\n考勤弄虚作假：第一次该项扣 50 分，第二次及以上该项不得分。',
  weeklyReportScore: '依据周报提交与完成情况按百分制评价。无正当理由未按节点提交周报，额外扣总绩效 5–10 分；导致总分低于 60 分时，按参考表视为不合格。',
};
const records = new Map();
const names = new Map();
const growthRecords = new Map();
const growthKeys = { collaborationScore: 'collaboration360', weeklyReportScore: 'weeklyQuality' };
const growthLabels = { collaborationScore: '360 协同分', weeklyReportScore: '周报质量分' };
const growthStatuses = {
  published: '已发布', not_published: '尚未发布', pending_ai: '等待 AI 分析', no_reports: '暂无周报',
  rescoring: '重评中', rescore_failed: '重评失败', invalid_result: '评分数据待核实',
  not_configured: '尚未启用成长周报评分', unavailable: '成长周报评分暂不可用',
  missing_feishu_user_id: '飞书身份尚未补齐', duplicate_feishu_user_id: '飞书身份重复，请先核对人员资料',
  not_found: '成长周报中未找到对应人员', name_mismatch: '双方姓名核对不符', identity_mismatch: '人员身份未匹配',
};

function validScore(value) { return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100; }
function finalGrowthScore(growth, key) {
  const value = growth?.[growthKeys[key]];
  return growth?.matchStatus === 'matched' && value?.isFinal === true && value.status === 'published' && validScore(value.score) ? value.score : null;
}
function growthConfigured(growth) {
  if (growth?.configured === false || growth?.matchStatus === 'not_configured') return false;
  if (growth?.configured === true) return true;
  return !!(growth && typeof growth.matchStatus === 'string' && growth.weeklyQuality && growth.collaboration360);
}
function growthStatus(growth, key) {
  if (finalGrowthScore(growth, key) != null) return growthStatuses.published;
  const state = growth?.matchStatus && growth.matchStatus !== 'matched'
    ? growth.matchStatus : growth?.[growthKeys[key]]?.status;
  return growthStatuses[state] || '评分数据待核实';
}
function renderGrowthScores(growth) {
  if (!growthConfigured(growth)) return '';
  return `<section class="designer-growth-scores" aria-label="成长周报评分来源"><p class="admin-section-hint"><strong>成长周报 OS · 最新来源</strong><br>月度评分会自动带入已发布分数，保存后计入本月绩效。</p><div class="designer-evaluation-results" style="grid-template-columns:repeat(2,minmax(0,1fr));">${Object.keys(growthKeys).map(key => {
    const source = growth[growthKeys[key]], value = finalGrowthScore(growth, key);
    const previous = key === 'weeklyReportScore' && growth.matchStatus === 'matched' && validScore(source?.previousScore) ? source.previousScore : null;
    return `<div class="designer-evaluation-result"><span>${growthLabels[key]}</span><strong>${value == null ? '—' : value.toFixed(2)}${value == null ? '' : '<small> 分</small>'}</strong><small>${escHtml(growthStatus(growth, key))}</small>${previous == null ? '' : `<small>旧分参考：${previous.toFixed(2)} 分</small>`}${source?.updatedAt ? `<small>来源更新 ${escHtml(String(source.updatedAt).replace('T', ' '))}</small>` : ''}</div>`;
  }).join('')}</div></section>`;
}
function growthFallback(cached, failure) {
  const missingEndpoint = /(?:failed:\s*404|not found)/i.test(String(failure?.message || ''));
  if (cached?.configured === false || (!growthConfigured(cached) && missingEndpoint)) return { configured: false, matchStatus: 'not_configured' };
  return { configured: true, matchStatus: 'unavailable', message: '成长周报评分暂不可用，请稍后重试' };
}
function normalizedGrowth(value, cached) {
  if (value && typeof value === 'object' && !Array.isArray(value) && typeof value.matchStatus === 'string') {
    return { ...value, configured: value.matchStatus !== 'not_configured' };
  }
  // Older local builds and UI fixtures may not expose the optional integration.
  return growthConfigured(cached) ? { configured: true, matchStatus: 'unavailable' } : { configured: false, matchStatus: 'not_configured' };
}
function growthFieldNote(growth, key, savedValue) {
  const value = finalGrowthScore(growth, key);
  if (value != null) return `已从成长周报 OS 自动读取。${validScore(savedValue) && savedValue !== value ? `本月原保存 ${savedValue.toFixed(2)} 分；保存后更新为最新来源分。` : ''}`;
  const status = growthStatus(growth, key);
  return validScore(savedValue)
    ? `沿用本月已保存分 ${savedValue.toFixed(2)} 分；成长周报当前状态：${status}。`
    : `成长周报当前状态：${status}。取得已发布分数后才能保存本月评分。`;
}
function evaluationCriteria(key, growth) {
  return growthConfigured(growth) && Object.hasOwn(growthKeys, key)
    ? `读取成长周报 OS 已发布的${growthLabels[key]}，按本系统既有 10% 权重计入绩效。`
    : scoreCriteria[key];
}
function savedGrowthReason(saved, key, growth) {
  if (!growthConfigured(growth) || !Object.hasOwn(growthKeys, key)) return '';
  const reason = saved[key === 'collaborationScore' ? 'collaborationReason' : 'weeklyReportReason'];
  return reason ? `<p class="designer-evaluation-criteria">${escHtml(reason)}</p>` : '';
}

function savedEvaluationBasis(saved, key, growth, readOnly) {
  const reason = saved[key.replace(/Score$/, 'Reason')];
  if (readOnly && reason) return `<p class="designer-evaluation-criteria">${escHtml(reason)}</p>`;
  return `<p class="designer-evaluation-criteria">${escHtml(evaluationCriteria(key, growth))}</p>${savedGrowthReason(saved, key, growth)}${key === 'completionScore' && reason ? `<p class="designer-evaluation-criteria">${escHtml(reason)}</p>` : ''}`;
}

function renderDesignerEvaluationSummary(row, period, { readOnly = false } = {}) {
  const annual = period.length === 4;
  const saved = row.evaluations || [];
  names.set(row.userId, row.designer);
  if (!annual && row.growthScores) growthRecords.set(`${period}:${row.userId}`, row.growthScores);
  const growth = !annual ? row.growthScores : null;
  const months = annual
    ? Array.from({ length: 12 }, (_, index) => `${period}-${String(index + 1).padStart(2, '0')}`)
    : [period];
  months.forEach(month => records.set(`${month}:${row.userId}`, saved.find(item => item.monthKey === month) || null));
  const evaluated = saved.filter(item => item.totalScore != null);
  const averageCents = evaluated.length ? evaluated.reduce((sum, item) => sum + Math.round(Number(item.totalScore) * 100), 0) / evaluated.length : null;
  const total = averageCents == null ? null : Math.sign(averageCents) * Math.round(Math.abs(averageCents)) / 100;
  const preview = row.completionPreview;
  const completionPreview = !annual && preview ? `<div class="designer-evaluation-preview">设计任务完成率：<strong>${preview.completionScore == null ? '待配置目标积分' : Number(preview.completionScore).toFixed(1) + ' 分'}</strong><small>月度积分 ${Number(preview.earnedPoints || 0).toFixed(2)} ÷ 目标积分 ${Number(preview.targetPoints || 0)}</small>${saved.length && preview.completionScore != null && Number(saved[0].completionScore) !== Number(preview.completionScore) ? `<small>${readOnly ? '当前积分与已保存评分有变化，月度评价以管理员确认的评分为准。' : '当前计算结果与已保存评分不同，请重新保存月度评分。'}</small>` : ''}</div>` : '';
  const button = month => readOnly ? '' : `<button type="button" class="btn btn-outline btn-sm" data-emie-action="click:designer-evaluation-edit" data-user-id="${escHtml(row.userId)}" data-month="${escHtml(month)}">${records.get(`${month}:${row.userId}`) ? '修改评分' : '月度评分'}</button>`;
  return `<section class="designer-evaluation-summary ${total == null ? 'is-pending' : 'is-evaluated'}"><div class="designer-evaluation-heading"><strong>${annual ? '年度平均绩效' : '综合绩效'}：${total == null ? '待评分' : total.toFixed(2) + ' 分'}</strong><span class="designer-evaluation-status">${total == null ? '待评分' : '已评分'}</span>${annual ? `<small>已评分 ${evaluated.length}/12 个月，平均分仅统计已评分月份</small>` : button(period)}</div>${completionPreview}${readOnly ? '' : renderGrowthScores(growth)}${annual ? `<details><summary>查看各月评分</summary><div class="designer-evaluation-months">${months.map(month => {
    const record = records.get(`${month}:${row.userId}`);
    return `<div><span>${escHtml(month)}</span><strong>${record?.totalScore == null ? '待评分' : Number(record.totalScore).toFixed(2) + ' 分'}</strong>${button(month)}</div>`;
  }).join('')}</div></details>` : saved.length ? `<div class="designer-evaluation-results">${scores.map(([key, label, weight]) => `<div class="designer-evaluation-result"><span>${label}</span><strong>${Number(saved[0][key]).toFixed(2)}<small> 分</small></strong><small>权重 ${weight}%</small></div>`).join('')}</div><details class="designer-evaluation-basis"><summary>查看评分依据</summary>${scores.map(([key, label]) => `<div><strong>${label}</strong>${savedEvaluationBasis(saved[0], key, growth, readOnly)}</div>`).join('')}</details><small>周报额外扣分：${Number(saved[0].weeklyReportDeduction || 0).toFixed(2)} 分</small>${saved[0].comment ? `<p class="designer-evaluation-comment-text">${escHtml(saved[0].comment)}</p>` : ''}<small>更新于 ${escHtml(String(saved[0].updatedAt || '').replace('T', ' '))}</small>` : `<p class="admin-section-hint">${readOnly ? '管理员尚未完成本月评分，评分确认后将在这里显示各项得分与评价。' : growthConfigured(growth) ? '完成率自动计算，360协同分和周报质量分从成长周报 OS 自动读取；考勤与额外扣分按月填写。' : '完成率由系统计算；管理员每月填写协作、考勤和周报三项评价。'}</p>`}</section>`;
}

async function openDesignerEvaluation(button) {
  const previous = document.getElementById('designerEvaluationModal');
  EMIE.actions.closeM('designerEvaluationModal');
  previous?.remove();
  const userId = button.dataset.userId;
  const month = button.dataset.month;
  const saved = records.get(`${month}:${userId}`);
  const overlay = document.createElement('div');
  overlay.id = 'designerEvaluationModal';
  overlay.className = 'modal-overlay designer-evaluation-overlay';
  overlay.innerHTML = '<div class="modal" style="max-width:720px;"><div class="modal-header"><div class="modal-title">准备月度评价</div><button type="button" class="modal-close" aria-label="关闭" data-emie-action="click:designer-evaluation-close">✕</button></div><div class="modal-body"><p role="status">正在读取任务完成率…</p></div></div>';
  document.body.appendChild(overlay);
  button.disabled = true;
  let completion, growth;
  const cachedGrowth = growthRecords.get(`${month}:${userId}`);
  try {
    [completion, growth] = await Promise.all([
      EMIE.actions.apiGet(`/performance/designer-evaluations/completion?month=${encodeURIComponent(month)}&userId=${encodeURIComponent(userId)}`),
      EMIE.actions.apiGet(`/performance/growth-scores/${encodeURIComponent(month)}/${encodeURIComponent(userId)}`)
        .then(value => normalizedGrowth(value, cachedGrowth))
        .catch(failure => growthFallback(cachedGrowth, failure)),
    ]);
  } catch (failure) {
    if (overlay.isConnected) overlay.querySelector('.modal-body').textContent = failure.message || '任务完成率加载失败，请关闭后重试';
    return;
  } finally { button.disabled = false; }
  if (!overlay.isConnected) return;
  const automatic = completion.completionScore != null;
  const managed = growthConfigured(growth);
  growthRecords.set(`${month}:${userId}`, growth);
  overlay.innerHTML = `<div class="modal" style="max-width:720px;"><div class="modal-header"><div><div class="designer-evaluation-eyebrow">设计师绩效 · 月度评价</div><div class="modal-title">${escHtml(names.get(userId) || userId)} <span>${escHtml(month)}</span></div><p class="admin-section-hint">${managed ? '完成率自动计算，360协同分和周报质量分自动读取；考勤与额外扣分按月填写。' : '完成率自动计算，其余三项按月综合评价。'}</p></div><button type="button" class="modal-close" aria-label="关闭" data-emie-action="click:designer-evaluation-close">✕</button></div><form class="modal-body" id="designerEvaluationForm" data-growth-managed="${managed}"><div class="designer-evaluation-fields">${scores.map(([key, label, weight, max]) => {
    const isAutomatic = key === 'completionScore';
    const external = managed && Object.hasOwn(growthKeys, key);
    const latest = external ? finalGrowthScore(growth, key) : null;
    const retained = validScore(saved?.[key]) ? saved[key] : null;
    const value = isAutomatic ? (automatic ? Number(completion.completionScore) : '') : external ? (latest ?? retained ?? '') : saved?.[key] == null ? '' : Number(saved[key]);
    const criteria = evaluationCriteria(key, growth);
    return `<section class="designer-evaluation-field"><div class="designer-evaluation-field-head"><label class="form-label" for="evaluation-${key}">${external ? growthLabels[key] : label}</label><span class="designer-evaluation-weight">占 ${weight}%</span></div><div class="designer-evaluation-input"><input class="form-input" id="evaluation-${key}" aria-describedby="criteria-${key}" name="${key}" type="number" min="0" max="${max}" step="0.01" required ${isAutomatic || external ? 'readonly' : ''} value="${value}" placeholder="${external ? '等待已发布分数' : `0–${max}`}"><span>分</span></div><p class="designer-evaluation-criteria" id="criteria-${key}">${escHtml(criteria)}</p>${isAutomatic ? `<p class="designer-evaluation-calculation">${escHtml(completion.completionReason || '')}</p>` : external ? `<p class="designer-evaluation-calculation" data-growth-note="${key}">${escHtml(growthFieldNote(growth, key, retained))}</p>` : ''}</section>`;
  }).join('')}</div><div class="designer-evaluation-bottom"><section class="designer-evaluation-deduction"><label class="form-label" for="evaluation-deduction">周报额外扣分</label><div class="designer-evaluation-input"><input class="form-input" id="evaluation-deduction" name="weeklyReportDeduction" type="number" min="0" max="10" step="0.01" required value="${Number(saved?.weeklyReportDeduction || 0)}"><span>分</span></div><p class="admin-section-hint">未按节点提交扣 5–10 分，否则填 0；直接从总分扣除。</p></section><div class="designer-evaluation-calculation">${automatic ? `月度所得积分 ${Number(completion.earnedPoints)}，目标积分 ${Number(completion.targetPoints)}；完成率评分 ${Number(completion.completionScore)} 分（最高 150 分）。` : '请先在设计师目标积分中配置大于 0 的目标，才能计算并保存本月评分。'}</div><label class="form-label designer-evaluation-comment">整体评价 <span>可选</span><textarea class="form-input" name="comment" maxlength="2000" rows="3" placeholder="记录本月评价或改进建议">${escHtml(saved?.comment || '')}</textarea></label><p id="designerEvaluationTotal" role="status"></p><p id="designerEvaluationError" role="alert"></p></div></form><div class="modal-footer"><button type="button" class="btn btn-outline" data-emie-action="click:designer-evaluation-close">取消</button><button type="button" class="btn btn-primary" data-emie-action="click:designer-evaluation-save">保存月度评分</button></div></div>`;
  const form = overlay.querySelector('form');
  form.addEventListener('input', () => previewDesignerEvaluationTotal(form));
  form.addEventListener('submit', event => { event.preventDefault(); saveDesignerEvaluation(overlay.querySelector('[data-emie-action="click:designer-evaluation-save"]')); });
  overlay.dataset.userId = userId;
  overlay.dataset.month = month;
  if (saved?.version != null) overlay.dataset.version = String(saved.version);
  previewDesignerEvaluationTotal(form);
  if (!automatic) overlay.querySelector('[data-emie-action="click:designer-evaluation-save"]').disabled = true;
}

function previewDesignerEvaluationTotal(form) {
  const deductionInput = form.elements.weeklyReportDeduction;
  const deduction = Number(deductionInput.value);
  deductionInput.setCustomValidity(deduction === 0 || (deduction >= 5 && deduction <= 10) ? '' : '周报额外扣分请填 0 或 5–10 分');
  const ready = deductionInput.value !== '' && deductionInput.validity.valid && scores.every(([key]) => form.elements[key].value !== '' && form.elements[key].validity.valid);
  const weightedCents = ready ? Math.round(scores.reduce((sum, [key, , weight]) => sum + Math.round(Number(form.elements[key].value) * 100) * weight, 0) / 100) : null;
  const total = ready ? (weightedCents - Math.round(deduction * 100)) / 100 : null;
  form.querySelector('#designerEvaluationTotal').textContent = total == null ? '填完全部评分后显示预计总分' : `预计加权总分：${total.toFixed(2)} 分`;
  const blocked = form.elements.completionScore.value === '' || (form.dataset.growthManaged === 'true'
    && Object.keys(growthKeys).some(key => form.elements[key].value === '' || !form.elements[key].validity.valid));
  form.closest('.modal')?.querySelector('[data-emie-action="click:designer-evaluation-save"]')?.toggleAttribute('disabled', blocked);
}

async function saveDesignerEvaluation(button) {
  const overlay = document.getElementById('designerEvaluationModal');
  const form = overlay?.querySelector('form');
  if (!form || !form.reportValidity() || button.disabled) return;
  const error = form.querySelector('#designerEvaluationError');
  error.textContent = '';
  button.disabled = true;
  try {
    const body = Object.fromEntries(scores.map(([key]) => [key, Number(form.elements[key].value)]));
    body.weeklyReportDeduction = Number(form.elements.weeklyReportDeduction.value);
    body.comment = form.elements.comment.value;
    body.version = overlay.dataset.version == null ? null : Number(overlay.dataset.version);
    await EMIE.actions.apiPut(`/performance/designer-evaluations/${encodeURIComponent(overlay.dataset.month)}/${encodeURIComponent(overlay.dataset.userId)}`, body);
    if (document.getElementById('designerEvaluationModal') === overlay) EMIE.actions.closeM('designerEvaluationModal');
    await EMIE.actions.reloadDesignerMonthlyReport();
  } catch (failure) {
    error.textContent = failure.message || '评分保存失败，请稍后重试';
  } finally { button.disabled = false; }
}

EMIE.registerActions({ renderDesignerEvaluationSummary });
EMIE.actions.registerEventAction('designer-evaluation-edit', (_event, button) => openDesignerEvaluation(button));
EMIE.actions.registerEventAction('designer-evaluation-close', () => EMIE.actions.closeM('designerEvaluationModal'));
EMIE.actions.registerEventAction('designer-evaluation-save', (_event, button) => saveDesignerEvaluation(button));
EMIE.registerModule('designerEvaluation', { renderDesignerEvaluationSummary });
