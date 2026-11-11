const EMIE = window.EMIE;
const esc = value => EMIE.actions.escHtml(String(value ?? '未记录'));
const time = value => value ? esc(EMIE.actions.fmtDT(value)) : '未记录';
const sources = { TASK_BASE: '子任务基础分', TASK_REVISION: '子任务修改', TASK_MANUAL: '旧任务手动验收', TASK_QUALITY: '历史质量分', DESIGN_REQUIREMENT: '设计需求', MATERIAL_MARKET: '素材采纳', MATERIAL_IDEA_PUBLISH: '创意单发布奖励', PO_PROGRESS: 'PO进度', MANUAL: '手动调整', APPEAL: '积分异议', TASK_WITHDRAWAL: '退单扣分' };
const issues = { MISSING_LEDGER: '确认后未入账', AMOUNT_MISMATCH: '入账分值不符', UNEXPECTED_LEDGER: '无积分轮出现流水', EARLY_LEDGER: '确认前入账', RECIPIENT_MISMATCH: '积分接收人不符', RULE_MISMATCH: '入账规则不符' };
const filters = ['personKeyword', 'from', 'to', 'source', 'keyword', 'sign'];
let requestId = 0;

async function renderAdminPointLogs(container) {
  const f = { ...EMIE.adminState.pointLogFilters };
  if (f.userId) { f.personKeyword ||= f.userId; delete f.userId; }
  EMIE.adminState.pointLogFilters = f;
  container.innerHTML = `<div class="point-log-page"><form class="admin-log-filter-panel point-log-filters" data-emie-action="submit:point-logs-query"><div class="admin-log-filter-title"><div><strong>积分筛选</strong><small>支持人员账号、名称模糊查询，可组合其他条件筛选</small></div></div><div class="admin-log-filter-fields">
    <label class="point-log-person-filter"><span>人员账号 / 名称</span><input class="form-input" type="search" maxlength="100" id="pointLog-personKeyword" value="${esc(f.personKeyword || '')}" placeholder="输入账号或人员名称"></label>
    <label><span>开始日期</span><input class="form-input" type="date" id="pointLog-from" value="${esc(f.from || '')}"></label>
    <label><span>结束日期</span><input class="form-input" type="date" id="pointLog-to" value="${esc(f.to || '')}"></label>
    <label><span>来源</span><select class="form-input" id="pointLog-source"><option value="">全部来源</option>${Object.entries(sources).map(([key, label]) => `<option value="${key}" ${f.source === key ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
    <label><span>规则 / 说明</span><input class="form-input" id="pointLog-keyword" value="${esc(f.keyword || '')}" placeholder="规则或业务关键词"></label>
    <label><span>积分方向</span><select class="form-input" id="pointLog-sign"><option value="">全部</option><option value="positive" ${f.sign === 'positive' ? 'selected' : ''}>正积分</option><option value="negative" ${f.sign === 'negative' ? 'selected' : ''}>负积分</option></select></label>
    <div class="point-log-filter-actions"><button type="submit" class="btn btn-primary btn-sm">查询</button><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:point-logs-reset">重置</button></div>
    </div></form><section class="point-log-card" id="pointLogResults" aria-label="积分流水" aria-live="polite"></section><section class="point-log-card" id="pointAuditResults" aria-label="子任务积分核对" aria-live="polite"></section></div>`;
  await loadPointLogs();
}
function pagination(result, kind) {
  const page = Number(result.page || 0), totalPages = Math.max(1, Number(result.totalPages || 0));
  return `<nav class="project-pagination point-log-pagination" aria-label="${kind === 'logs' ? '积分流水' : '子任务积分核对'}分页"><span>共 ${Number(result.total || 0)} 条</span><div class="point-log-pagination-controls"><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:point-logs-page" data-kind="${kind}" data-page="${page - 1}" ${page <= 0 ? 'disabled' : ''}>上一页</button><span>第 ${page + 1} / ${totalPages} 页</span><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:point-logs-page" data-kind="${kind}" data-page="${page + 1}" ${page + 1 >= totalPages ? 'disabled' : ''}>下一页</button></div></nav>`;
}
function business(item) {
  const label = esc(item.businessName || item.taskName || (item.businessId ? `#${item.businessId}` : '未记录'));
  if (item.projectId) return `<button class="btn btn-link btn-sm" data-emie-action="click:point-logs-business" data-project-id="${Number(item.projectId)}">${label}</button>`;
  if (item.businessType === 'design_requirement' && item.businessId) return `<button class="btn btn-link btn-sm" data-emie-action="click:point-logs-business" data-requirement-id="${Number(item.businessId)}">${label}</button>`;
  return label;
}
async function loadPointLogs() {
  const target = document.getElementById('pointLogResults');
  const auditTarget = document.getElementById('pointAuditResults');
  if (!target || !auditTarget) return;
  const id = ++requestId;
  const f = EMIE.adminState.pointLogFilters || {};
  const params = new URLSearchParams({ page: EMIE.adminState.pointLogPage || 0, size: 20 });
  filters.forEach(key => { if (f[key]) params.set(key, f[key]); });
  const audit = new URLSearchParams({ page: EMIE.adminState.pointAuditPage || 0, size: 20 });
  ['personKeyword', 'from', 'to'].forEach(key => { if (f[key]) audit.set(key, f[key]); });
  target.innerHTML = '<div class="loading">加载积分日志…</div>';
  auditTarget.innerHTML = '<div class="loading">核对中…</div>';
  const results = await Promise.allSettled([EMIE.actions.apiGet(`/points/logs?${params}`), EMIE.actions.apiGet(`/points/audit?${audit}`)]);
  if (id !== requestId || !target.isConnected) return;
  if (results[0].status === 'rejected') target.innerHTML = `<div class="empty">积分日志加载失败：${esc(results[0].reason.message)}</div>`;
  else {
    const r = results[0].value;
    target.innerHTML = `<div class="point-log-card-head"><h4>积分流水</h4><span>按入账时间倒序</span></div><div class="point-log-summary"><div class="is-positive"><span>正积分</span><strong>${Number(r.summary?.positive || 0)}</strong></div><div class="is-negative"><span>负积分</span><strong>${Number(r.summary?.negative || 0)}</strong></div><div><span>净积分</span><strong>${Number(r.summary?.net || 0)}</strong></div></div><div class="admin-log-table-wrap"><table><thead><tr><th>入账时间</th><th>人员</th><th>来源 / 业务</th><th>规则</th><th>积分</th><th>操作者</th><th>提交时间</th><th>确认时间 / 确认人</th><th>详情</th></tr></thead><tbody>${(r.items || []).map(item => `<tr><td>${time(item.createdAt)}</td><td class="point-log-person"><strong>${esc(item.userName)}</strong><small>${esc(item.userId)}</small></td><td>${esc(sources[item.source] || item.source)}<br>${business(item)}</td><td>${esc(item.ruleCode)}<br>${esc(item.ruleDescription)}</td><td class="point-log-amount ${Number(item.points) > 0 ? 'is-positive' : Number(item.points) < 0 ? 'is-negative' : ''}">${Number(item.points) > 0 ? '+' : ''}${Number(item.points)}</td><td>${esc(item.createdByName || (item.createdBy ? `#${item.createdBy}` : null))}</td><td>${time(item.submittedAt)}</td><td>${time(item.confirmedAt)}<small class="point-log-secondary">${esc(item.confirmedByName || (item.confirmedBy ? `#${item.confirmedBy}` : null))}</small></td><td><details><summary>查看</summary><div>流水：${esc(item.id)}</div><div>原因：${esc(item.reason)}</div><div>提交版本：${esc(item.deliveryVersionNo)}</div><div>追踪：${item.tracking === 'tracked' ? '已关联提交版本' : '未关联提交版本'}</div></details></td></tr>`).join('') || '<tr><td class="point-log-empty" colspan="9">暂无积分记录</td></tr>'}</tbody></table></div>${pagination(r, 'logs')}`;
  }
  if (results[1].status === 'rejected') auditTarget.innerHTML = `<div class="empty">积分核对失败：${esc(results[1].reason.message)}</div>`;
  else {
    const r = results[1].value;
    auditTarget.innerHTML = `<div class="point-log-card-head"><h4>子任务积分核对</h4><span>按人员及提交日期核对</span></div><p class="point-log-audit-note">无积分轮没有流水属于正常。历史未跟踪版本 <strong>${Number(r.historicalCount || 0)}</strong> 条，无法核对。</p><div class="admin-log-table-wrap"><table><thead><tr><th>人员</th><th>任务 / 版本</th><th>异常</th><th>应发积分 / 规则</th><th>提交时间</th><th>确认时间 / 确认人</th></tr></thead><tbody>${(r.items || []).map(item => `<tr><td class="point-log-person"><strong>${esc(item.userName)}</strong><small>${esc(item.userId)}</small></td><td>${business(item)} · 第 ${Number(item.versionNo)} 版</td><td>${esc(issues[item.issue] || item.issue)}</td><td>${Number(item.expectedPoints)} / ${esc(item.ruleCode)}</td><td>${time(item.submittedAt)}</td><td>${time(item.confirmedAt)} / ${esc(item.confirmedBy ? `#${item.confirmedBy}` : null)}</td></tr>`).join('') || '<tr><td class="point-log-empty" colspan="6">未发现积分异常</td></tr>'}</tbody></table></div>${pagination(r, 'audit')}`;
  }
}
EMIE.actions.registerEventAction('point-logs-query', async event => {
  event.preventDefault();
  EMIE.adminState.pointLogFilters = Object.fromEntries(filters.map(key => [key, document.getElementById(`pointLog-${key}`)?.value.trim() || '']));
  EMIE.adminState.pointLogPage = EMIE.adminState.pointAuditPage = 0;
  await loadPointLogs();
});
EMIE.actions.registerEventAction('point-logs-reset', async () => {
  EMIE.adminState.pointLogFilters = {};
  EMIE.adminState.pointLogPage = EMIE.adminState.pointAuditPage = 0;
  await renderAdminPointLogs(document.getElementById('adminContent'));
});
EMIE.actions.registerEventAction('point-logs-page', async (_event, el) => {
  EMIE.adminState[el.dataset.kind === 'audit' ? 'pointAuditPage' : 'pointLogPage'] = Math.max(0, Number(el.dataset.page));
  await loadPointLogs();
});
EMIE.actions.registerEventAction('point-logs-business', (_event, el) => el.dataset.projectId ? EMIE.actions.openProjectDetail(Number(el.dataset.projectId)) : EMIE.actions.openDesignRequirementDetail(Number(el.dataset.requirementId)));
EMIE.registerModule('adminPointLogs', { renderAdminPointLogs });
