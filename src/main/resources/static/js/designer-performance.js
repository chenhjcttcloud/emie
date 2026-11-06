const EMIE = window.EMIE;
const esc = value => EMIE.actions.escHtml(value);
const points = value => Number(value || 0).toFixed(2);
const currentMonth = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
};
let personalRequest = 0;

async function renderDesignerPersonalPerformance(container) {
  if (!container || EMIE.state.currentRole !== 'designer') return;
  container.innerHTML = `<section id="designerPersonalPerformance" class="personal-performance">
    <header class="personal-performance-header"><div><span class="personal-performance-eyebrow">MY PERFORMANCE · 仅本人可见</span><h2>我的设计绩效</h2><p>查看你的积分、目标进度与月度评价</p></div>
      <div class="designer-performance-period"><label class="form-label">查看方式<select class="form-select" id="personalPerformanceScope" data-emie-action="change:personal-performance-scope"><option value="month">按月</option><option value="year">按年</option></select></label><label class="form-label"><span id="personalPerformancePeriodLabel">月份</span><input class="form-input" id="personalPerformancePeriod" type="month" value="${currentMonth()}" data-emie-action="change:personal-performance-period"></label></div>
    </header><div id="personalPerformanceBody" aria-live="polite"></div></section>`;
  await reloadPersonalPerformance();
}

function renderPersonalReport(report, account) {
  const period = report.month;
  const annual = period.length === 4;
  const row = (report.designers || []).find(item => String(item.userId) === String(EMIE.state.currentUserId));
  if (!row) return '<div class="card empty-state">暂无个人绩效数据，请稍后重试或联系管理员核对账号。</div>';
  const completion = row.completionPreview;
  const target = Number(completion?.targetPoints || 0);
  const earned = Number(row.score || 0);
  const rate = target > 0 ? earned * 100 / target : null;
  const evaluated = (row.evaluations || []).filter(item => item.totalScore != null).length;
  const categories = Object.entries(row.categoryCounts || {});
  return `<div class="personal-performance-stats">
      <article><span>${annual ? '全年' : '当月'}归属积分</span><strong>${points(earned)}</strong><small>含归属所选${annual ? '年份' : '月份'}的积分调账</small></article>
      <article><span>${annual ? '已评分月份' : '目标积分'}</span><strong>${annual ? `${evaluated}<small> / 12</small>` : target > 0 ? points(target) : '待配置'}</strong><small>${annual ? '平均绩效只统计已评分月份' : '由管理员配置个人目标'}</small></article>
      <article><span>完成任务与需求</span><strong>${Number(row.completedCount || 0)}<small> 项</small></strong><small>按完成时间统计</small></article>
      <article><span>累计积分</span><strong>${account ? points(account.balance) : '—'}</strong><small>${account ? '所有月份积分，含调账' : '累计积分暂不可用，可刷新重试'}</small></article>
    </div>
    ${annual ? '' : `<section class="card personal-performance-progress"><div><h3>目标完成进度</h3><strong>${rate == null ? '目标积分待配置' : `${rate.toFixed(1)}%`}</strong></div>${rate == null ? '<p>目标积分配置后，将显示完成进度与任务完成率评分。</p>' : `<progress role="progressbar" max="100" value="${Math.min(100, Math.max(0, rate))}" aria-label="目标完成进度" aria-valuenow="${Math.min(100, Math.max(0, rate))}" aria-valuemin="0" aria-valuemax="100" aria-valuetext="${rate.toFixed(1)}%"></progress><p>已获得 ${points(earned)} / 目标 ${points(target)} 积分${rate >= 100 ? ' · 已达成目标' : ` · 距目标还差 ${points(target - earned)} 积分`}</p>`}</section>`}
    <section class="card personal-performance-evaluation"><div class="card-header"><div><h3>${esc(row.designer)} · ${esc(period)} 绩效评价</h3><p class="admin-section-hint">完成率占 70%，协作、考勤和周报各占 10%；周报额外扣分直接从总分扣除。</p></div><span class="personal-performance-readonly">个人查看</span></div>${EMIE.actions.renderDesignerEvaluationSummary(row, period, { readOnly: true })}</section>
    <section class="card personal-performance-deliveries"><div class="card-header"><div><h3>我的完成事项</h3><p class="admin-section-hint">${annual ? '本年' : '本月'}完成的任务与需求；每项显示你在该事项累计获得的积分，可能包含其他月份。</p></div><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:personal-performance-points">查看积分明细 →</button></div>
      ${categories.length ? `<div class="personal-performance-categories">${categories.map(([label, count]) => `<span>${esc(label)} <strong>${Number(count)}</strong></span>`).join('')}</div>` : ''}
      <div class="designer-performance-tasks">${(row.tasks || []).map(task => `<article class="designer-performance-task"><div class="designer-performance-task-head"><div><strong>${esc(task.name)}</strong><small>${esc(task.project || '')} · ${esc(task.category || '未分类')} · ${esc(String(task.completedAt || '').replace('T', ' '))}</small></div><div><span>累计 ${points(task.score)} 积分</span></div></div></article>`).join('') || '<div class="empty-state">所选时间内暂无完成任务或需求，积分以实际入账记录为准。</div>'}</div>
    </section>`;
}

async function reloadPersonalPerformance() {
  const root = document.getElementById('designerPersonalPerformance');
  const input = root?.querySelector('#personalPerformancePeriod');
  const body = root?.querySelector('#personalPerformanceBody');
  if (!input || !body || EMIE.state.currentRole !== 'designer') return;
  const request = ++personalRequest;
  const renderId = EMIE.state.renderId;
  const period = input.value;
  const userId = EMIE.state.currentUserId;
  if (!/^\d{4}(-(?:0[1-9]|1[0-2]))?$/.test(period) || Number(period.slice(0, 4)) < 1) {
    body.innerHTML = '<div class="card empty-state" role="alert">请选择有效的月份或年份。</div>';
    return;
  }
  body.innerHTML = '<div class="card empty-state" role="status">正在加载你的积分与绩效…</div>';
  const [report, account] = await Promise.allSettled([
    EMIE.actions.apiGet('/performance/my-report?period=' + encodeURIComponent(period)),
    EMIE.actions.apiGet('/points/me?size=1'),
  ]);
  if (request !== personalRequest || EMIE.state.renderId !== renderId || !root.isConnected || input.value !== period
      || EMIE.state.currentRole !== 'designer' || EMIE.state.currentUserId !== userId) return;
  if (report.status === 'rejected') {
    body.innerHTML = `<div class="card empty-state" role="alert"><p>个人绩效加载失败：${esc(report.reason?.message || '请稍后重试')}</p><button type="button" class="btn btn-outline btn-sm" data-emie-action="click:personal-performance-retry">重新加载</button></div>`;
    return;
  }
  body.innerHTML = renderPersonalReport(report.value, account.status === 'fulfilled' ? account.value : null);
}

function changePersonalPerformanceScope() {
  const input = document.getElementById('personalPerformancePeriod');
  if (!input) return;
  const annual = document.getElementById('personalPerformanceScope').value === 'year';
  const year = input.value.slice(0, 4) || currentMonth().slice(0, 4);
  input.type = annual ? 'number' : 'month';
  if (annual) { input.min = '1'; input.max = '9999'; input.step = '1'; }
  else { input.removeAttribute('min'); input.removeAttribute('max'); input.removeAttribute('step'); }
  input.value = annual ? year : year + currentMonth().slice(4);
  document.getElementById('personalPerformancePeriodLabel').textContent = annual ? '年份' : '月份';
  return reloadPersonalPerformance();
}

EMIE.registerActions({ renderDesignerPersonalPerformance });
EMIE.actions.registerEventAction('personal-performance-period', () => reloadPersonalPerformance());
EMIE.actions.registerEventAction('personal-performance-scope', () => changePersonalPerformanceScope());
EMIE.actions.registerEventAction('personal-performance-retry', () => reloadPersonalPerformance());
EMIE.actions.registerEventAction('personal-performance-points', () => EMIE.actions.navigate('points'));
EMIE.registerModule('designerPerformance', { renderDesignerPersonalPerformance });
