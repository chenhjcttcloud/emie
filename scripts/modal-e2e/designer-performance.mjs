import assert from 'node:assert/strict';
import path from 'node:path';

// Real frontend modules with simulated reads only; no business data is changed.
export async function runDesignerPersonalPerformanceChecks(page, artifactRoot) {
  const viewport = page.viewportSize();
  await page.locator('#designerEvaluationModal').waitFor({ state: 'hidden' });
  await page.evaluate(async () => {
    const e = window.EMIE;
    window.personalPerformanceMocks = {
      get: e.actions.apiGet, put: e.actions.apiPut, navigate: e.actions.navigate,
      permission: e.actions.hasPermission, role: e.state.currentRole, userId: e.state.currentUserId,
    };
    e.state.currentRole = 'designer';
    e.state.currentUserId = 'personal-designer';
    e.actions.hasPermission = permission => permission === 'page.points.view';
    window.personalPerformanceFixture = { mode: 'normal', reads: [], writes: [], navigations: [] };
    const f = window.personalPerformanceFixture;
    const evaluation = monthKey => ({
      userId: 'personal-designer', monthKey, completionScore: 150, collaborationScore: 80,
      attendanceScore: 85, weeklyReportScore: 90, weeklyReportDeduction: 5,
      totalScore: 125.5, updatedAt: '2026-11-05T10:30:00',
      completionReason: '月度所得积分 165 ÷ 目标积分 100 × 100，最高 150 分。',
      collaborationReason: '成长周报 OS · 360协同；来源=weighted_30_70',
      weeklyReportReason: '成长周报 OS · 周报质量；来源=automatic_report',
      comment: f.mode === 'xss' ? '<img src=x onerror=alert(1)>' : '保持节奏，继续优化交付质量。',
    });
    window.personalPerformanceReport = period => {
      const annual = period.length === 4;
      const pending = f.mode === 'empty';
      const score = pending ? 0 : f.mode === 'stale' ? (period.endsWith('-08') ? 808 : 209) : 165;
      return { month: period, designers: [{
        userId: 'personal-designer', designer: f.mode === 'xss' ? '<img src=x onerror=alert(1)>' : '测试设计师',
        score, completedCount: pending ? 0 : 1, categoryCounts: pending ? {} : { 包装设计: 1 },
        tasks: pending ? [] : [{ id: 719, kind: 'task', name: f.mode === 'xss' ? '<img src=x onerror=alert(1)>' : '包装主视觉设计',
          project: '测试项目', category: '包装设计', completedAt: '2026-11-03T15:30:00', score: 180 }],
        completionPreview: annual ? null : { earnedPoints: score, targetPoints: f.mode === 'no-target' ? 0 : 100,
          completionScore: f.mode === 'no-target' ? null : Math.min(150, score),
          completionReason: f.mode === 'no-target' ? '目标积分未配置，暂无法计算完成率评分。' : '月度所得积分 ÷ 目标积分 × 100，最高 150 分。' },
        evaluations: pending ? [] : annual ? [
          { ...evaluation(period + '-01'), totalScore: 100.01 },
          { ...evaluation(period + '-02'), totalScore: 100 },
        ] : [evaluation(period)],
      }] };
    };
    e.actions.apiGet = async url => {
      f.reads.push(url);
      if (url.startsWith('/points/me')) return { userId: 'personal-designer', balance: 487.25 };
      if (!url.startsWith('/performance/my-report?')) throw new Error('Unexpected personal performance API: ' + url);
      const period = new URL(url, location.origin).searchParams.get('period');
      if (f.mode === 'error') throw new Error('模拟加载失败，请重试');
      const report = window.personalPerformanceReport(period);
      if (f.mode === 'stale' && period === '2026-08') {
        return new Promise(resolve => { f.resolveOld = () => resolve(report); });
      }
      return report;
    };
    e.actions.apiPut = async (...args) => { f.writes.push(args); throw new Error('Personal performance must be read-only'); };
    e.actions.navigate = view => { f.navigations.push(view); };
    await e.loadViewModules('designer-performance');
    const host = document.createElement('div');
    host.id = 'personalPerformanceTestHost';
    host.style.cssText = 'width:100%;max-width:1200px;margin:0 auto;padding:20px;box-sizing:border-box';
    document.body.append(host);
    await e.actions.renderDesignerPersonalPerformance(host);
  });
  const host = page.locator('#personalPerformanceTestHost');
  const root = host.locator('#designerPersonalPerformance');
  const changePeriod = async period => {
    await root.locator('#personalPerformancePeriod').fill(period);
    await root.locator('#personalPerformancePeriod').dispatchEvent('change');
  };
  try {
    assert.equal(await page.evaluate(() => EMIE.actions.canNavigateTo('designer-performance')), true,
      '设计师可凭个人积分权限进入绩效页面');
    assert.match(await root.innerText(), /165\.00/);
    assert.match(await root.innerText(), /487\.25/);
    assert.match(await root.innerText(), /165(?:\.0+)?\s*%/);
    assert.equal(await root.getByRole('progressbar').getAttribute('aria-valuenow'), '100',
      '超额完成时保留真实比例，进度条限定在 100%');
    assert.match(await root.innerText(), /125\.50/);
    assert.match(await root.innerText(), /包装主视觉设计/);
    assert.equal(await root.locator('[data-emie-action="click:designer-evaluation-edit"], .designer-target-settings, [data-emie-action="click:designer-evaluation-save"]').count(), 0,
      '个人绩效不能出现评分或目标配置入口');
    assert.equal(await root.locator('.designer-evaluation-result').count(), 4);
    await root.locator('.designer-evaluation-basis summary').click();
    assert.match(await root.locator('.designer-evaluation-basis').innerText(), /成长周报 OS · 360协同/);
    assert.match(await root.locator('.designer-evaluation-basis').innerText(), /来源=automatic_report/);
    assert.doesNotMatch(await root.locator('.designer-evaluation-basis').innerText(), /推卸责任、不愿配合/);
    await root.locator('.designer-evaluation-basis summary').click();
    assert.equal(await page.evaluate(() => personalPerformanceFixture.reads.every(url =>
      url.startsWith('/performance/my-report?') || url.startsWith('/points/me'))), true,
    '个人绩效只调用本人数据接口');

    await page.setViewportSize({ width: 1440, height: 1100 });
    await host.screenshot({ path: path.join(artifactRoot, 'designer-personal-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await root.evaluate(element => {
      const box = element.getBoundingClientRect();
      return box.left >= -1 && box.right <= innerWidth + 1 && element.scrollWidth <= element.clientWidth + 1;
    }), '个人绩效页面在 390px 手机宽度内完整显示');
    await host.screenshot({ path: path.join(artifactRoot, 'designer-personal-mobile.png') });

    await root.locator('[data-emie-action="click:personal-performance-points"]').click();
    assert.deepEqual(await page.evaluate(() => personalPerformanceFixture.navigations), ['points']);
    await page.evaluate(() => { personalPerformanceFixture.mode = 'xss'; });
    await changePeriod('2026-10');
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('<img src=x'));
    assert.equal(await root.locator('img').count(), 0, '姓名、任务名和评价按文本转义');

    await page.evaluate(() => { personalPerformanceFixture.mode = 'no-target'; });
    await changePeriod('2026-09');
    await page.waitForFunction(() => /未配置|待配置|尚未设置|待设置/.test(document.querySelector('#designerPersonalPerformance')?.textContent || ''));
    assert.doesNotMatch(await root.innerText(), /NaN|Infinity/);

    await page.evaluate(() => { personalPerformanceFixture.mode = 'empty'; });
    await changePeriod('2026-07');
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('待评分'));
    assert.match(await root.innerText(), /0\.00/);
    assert.match(await root.innerText(), /暂无.*(?:任务|需求|完成|事项)/);

    await page.evaluate(() => { personalPerformanceFixture.mode = 'normal'; });
    await root.locator('#personalPerformanceScope').selectOption('year');
    await root.locator('#personalPerformancePeriod').fill('2026');
    await root.locator('#personalPerformancePeriod').dispatchEvent('change');
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('已评分 2/12'));
    assert.match(await root.innerText(), /年度平均绩效：100\.01/);
    await root.locator('.designer-evaluation-summary summary').click();
    assert.equal(await root.locator('.designer-evaluation-months > div').count(), 12);
    assert.equal(await root.locator('.designer-evaluation-months > div').filter({ hasText: '待评分' }).count(), 10);
    assert.equal(await root.locator('[data-emie-action="click:designer-evaluation-edit"]').count(), 0);

    await root.locator('#personalPerformanceScope').selectOption('month');
    await page.evaluate(() => { personalPerformanceFixture.mode = 'error'; });
    await changePeriod('2026-06');
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('模拟加载失败'));
    await page.evaluate(() => { personalPerformanceFixture.mode = 'normal'; });
    await root.locator('[data-emie-action="click:personal-performance-retry"]').click();
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('165.00'));

    await page.evaluate(() => { personalPerformanceFixture.mode = 'stale'; });
    await changePeriod('2026-08');
    await page.waitForFunction(() => typeof personalPerformanceFixture.resolveOld === 'function');
    await changePeriod('2026-09');
    await page.waitForFunction(() => document.querySelector('#designerPersonalPerformance')?.textContent.includes('209.00'));
    await page.evaluate(async () => {
      personalPerformanceFixture.resolveOld();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    assert.equal(await root.locator('#personalPerformancePeriod').inputValue(), '2026-09');
    assert.match(await root.innerText(), /209\.00/);
    assert.doesNotMatch(await root.innerText(), /808\.00/);
    assert.equal(await page.evaluate(() => personalPerformanceFixture.writes.length), 0);
    console.log('  designer-personal-points-readonly-period-errors-and-stale-requests ok');
  } finally {
    if (viewport) await page.setViewportSize(viewport);
    await page.evaluate(() => {
      const e = window.EMIE, saved = window.personalPerformanceMocks;
      e.actions.apiGet = saved.get; e.actions.apiPut = saved.put; e.actions.navigate = saved.navigate;
      e.actions.hasPermission = saved.permission; e.state.currentRole = saved.role; e.state.currentUserId = saved.userId;
      document.getElementById('personalPerformanceTestHost')?.remove();
    });
  }
}
