const EMIE = window.EMIE;
const esc = value => EMIE.actions.escHtml(String(value ?? '未记录'));
const time = value => value ? esc(EMIE.actions.fmtDT(value)) : '未记录';
const sources = { TASK_BASE: '子任务基础分', TASK_REVISION: '子任务修改', TASK_QUALITY: '历史质量分', DESIGN_REQUIREMENT: '设计需求', MATERIAL_MARKET: '素材采纳', PO_PROGRESS: 'PO进度', MANUAL: '手动调整', APPEAL: '积分异议', TASK_WITHDRAWAL: '退单扣分' };
const issues = { MISSING_LEDGER: '确认后未入账', AMOUNT_MISMATCH: '入账分值不符', UNEXPECTED_LEDGER: '无积分轮出现流水', EARLY_LEDGER: '确认前入账', RECIPIENT_MISMATCH: '积分接收人不符', RULE_MISMATCH: '入账规则不符' };
const filters = ['userId', 'from', 'to', 'source', 'keyword', 'sign'];
let requestId = 0;

async function renderAdminPointLogs(container) {
  const f = EMIE.adminState.pointLogFilters || {};
  container.innerHTML = `<div class="admin-log-filter-panel"><div class="admin-log-filter-title"><strong>积分筛选</strong></div><div class="admin-log-filter-fields">
    <label><span>人员账号</span><input class="form-input" type="text" maxlength="100" id="pointLog-userId" value="${esc(f.userId || '')}" placeholder="如 designer_zhang"></label>
    <label><span>开始日期</span><input class="form-input" type="date" id="pointLog-from" value="${esc(f.from || '')}"></label>
    <label><span>结束日期</span><input class="form-input" type="date" id="pointLog-to" value="${esc(f.to || '')}"></label>
    <label><span>来源</span><select class="form-input" id="pointLog-source"><option value="">全部来源</option>${Object.entries(sources).map(([key, label]) => `<option value="${key}" ${f.source === key ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
    <label><span>规则 / 说明</span><input class="form-input" id="pointLog-keyword" value="${esc(f.keyword || '')}" placeholder="规则或业务关键词"></label>
    <label><span>积分方向</span><select class="form-input" id="pointLog-sign"><option value="">全部</option><option value="positive" ${f.sign === 'positive' ? 'selected' : ''}>正积分</option><option value="negative" ${f.sign === 'negative' ? 'selected' : ''}>负积分</option></select></label>
    <button class="btn btn-primary btn-sm" data-emie-action="click:point-logs-query">查询</button><button class="btn btn-outline btn-sm" data-emie-action="click:point-logs-reset">重置</button>
    </div></div><div id="pointLogResults" aria-live="polite"></div><div id="pointAuditResults" aria-live="polite"></div>`;
  await loadPointLogs();
}
function pagination(result, kind) {
  return `<div class="project-pagination"><span>共 ${Number(result.total || 0)} 条 · 第 ${Number(result.page || 0) + 1} / ${Math.max(1, Number(result.totalPages || 0))} 页</span><button class="btn btn-outline btn-sm" data-emie-action="click:point-logs-page" data-kind="${kind}" data-page="${result.page - 1}" ${result.page <= 0 ? 'disabled' : ''}>上一页</button><button class="btn btn-outline btn-sm" data-emie-action="click:point-logs-page" data-kind="${kind}" data-page="${result.page + 1}" ${result.page + 1 >= result.totalPages ? 'disabled' : ''}>下一页</button></div>`;
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
  ['userId', 'from', 'to'].forEach(key => { if (f[key]) audit.set(key, f[key]); });
  target.innerHTML = '<div class="loading">加载积分日志…</div>';
  auditTarget.innerHTML = '<div class="loading">核对中…</div>';
  const results = await Promise.allSettled([EMIE.actions.apiGet(`/points/logs?${params}`), EMIE.actions.apiGet(`/points/audit?${audit}`)]);
  if (id !== requestId || !target.isConnected) return;
  if (results[0].status === 'rejected') target.innerHTML = `<div class="empty">积分日志加载失败：${esc(results[0].reason.message)}</div>`;
  else {
    const r = results[0].value;
    target.innerHTML = `<div style="display:flex;gap:24px;flex-wrap:wrap;margin:16px 0;"><span>正积分 <strong>${Number(r.summary?.positive || 0)}</strong></span><span>负积分 <strong>${Number(r.summary?.negative || 0)}</strong></span><span>净积分 <strong>${Number(r.summary?.net || 0)}</strong></span></div><div class="admin-log-table-wrap"><table><thead><tr><th>入账时间</th><th>人员</th><th>来源 / 业务</th><th>规则</th><th>积分</th><th>操作者</th><th>提交时间</th><th>确认时间</th><th>详情</th></tr></thead><tbody>${(r.items || []).map(item => `<tr><td>${time(item.createdAt)}</td><td>${esc(item.userName)}<small> #${esc(item.userId)}</small></td><td>${esc(sources[item.source] || item.source)}<br>${business(item)}</td><td>${esc(item.ruleCode)}<br>${esc(item.ruleDescription)}</td><td>${Number(item.points) > 0 ? '+' : ''}${Number(item.points)}</td><td>${esc(item.createdByName || (item.createdBy ? `#${item.createdBy}` : null))}</td><td>${time(item.submittedAt)}</td><td>${time(item.confirmedAt)}</td><td><details><summary>查看</summary><div>流水：${esc(item.id)}</div><div>原因：${esc(item.reason)}</div><div>提交版本：${esc(item.deliveryVersionNo)}</div><div>追踪：${item.tracking === 'tracked' ? '已关联提交版本' : '未关联提交版本'}</div></details></td></tr>`).join('') || '<tr><td colspan="9">暂无积分记录</td></tr>'}</tbody></table></div>${pagination(r, 'logs')}`;
  }
  if (results[1].status === 'rejected') auditTarget.innerHTML = `<div class="empty">积分核对失败：${esc(results[1].reason.message)}</div>`;
  else {
    const r = results[1].value;
    auditTarget.innerHTML = `<h4 style="margin-top:24px;">子任务积分核对</h4><p style="color:var(--gray-500);font-size:12px;">核对人员及日期范围内的子任务提交版本；无积分轮没有流水属于正常。历史未跟踪版本 ${Number(r.historicalCount || 0)} 条，无法核对。</p><div class="admin-log-table-wrap"><table><thead><tr><th>人员</th><th>任务 / 版本</th><th>异常</th><th>应发积分 / 规则</th><th>提交时间</th><th>确认时间 / 确认人</th></tr></thead><tbody>${(r.items || []).map(item => `<tr><td>${esc(item.userName)}</td><td>${business(item)} · 第 ${Number(item.versionNo)} 版</td><td>${esc(issues[item.issue] || item.issue)}</td><td>${Number(item.expectedPoints)} / ${esc(item.ruleCode)}</td><td>${time(item.submittedAt)}</td><td>${time(item.confirmedAt)} / ${esc(item.confirmedBy ? `#${item.confirmedBy}` : null)}</td></tr>`).join('') || '<tr><td colspan="6">未发现积分异常</td></tr>'}</tbody></table></div>${pagination(r, 'audit')}`;
  }
}
EMIE.actions.registerEventAction('point-logs-query', async () => {
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
