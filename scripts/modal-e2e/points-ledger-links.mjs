import assert from 'node:assert/strict';
import path from 'node:path';

// Use real ledger/detail renderers and simulated reads; never change business data.
export async function runPointsLedgerLinkChecks(page, artifactRoot) {
  await page.locator('#designerEvaluationModal').waitFor({ state: 'hidden' });
  const viewport = page.viewportSize();
  await page.evaluate(async () => {
    const e = window.EMIE;
    const main = document.getElementById('mainContent');
    const nodes = document.createDocumentFragment();
    while (main.firstChild) nodes.appendChild(main.firstChild);
    window.pointsLedgerLinkMocks = {
      nodes, get: e.actions.apiGet, post: e.actions.apiPost, put: e.actions.apiPut, delete: e.actions.apiDelete,
      role: e.state.currentRole, userId: e.state.currentUserId, permissions: e.state.permissions,
      authUser: e.state.authUser, designerTasks: e.dashboardState.designerTaskCache,
      projectDetail: e.projectState.currentProjectDetail,
      appDisplay: document.getElementById('appContainer').style.display,
      loginDisplay: document.getElementById('loginPage').style.display,
    };
    e.state.currentRole = 'designer'; e.state.currentUserId = 'ledger-links-designer';
    e.state.authUser = { userId: 'ledger-links-designer', role: 'designer', name: '关联测试设计师' };
    e.state.permissions = ['page.points.view', 'project.view', 'subtask.view'];
    e.dashboardState.designerTaskCache = [];
    e.projectState.currentProjectDetail = null;
    document.getElementById('appContainer').style.display = '';
    document.getElementById('loginPage').style.display = 'none';
    const f = window.pointsLedgerLinkFixture = { reads: [], writes: [] };
    const projectId = 81001;
    const task = (id, name) => ({
      id, projectId, name, projectName: '关联测试项目', projectType: 'regular', status: 'completed',
      workflowStage: 'design', assigneeRole: 'designer', designerId: 'ledger-links-designer',
      designerName: '关联测试设计师', plannerId: 'ledger-links-planner', plannerName: '测试企划',
      plannedDate: '2026-11-04', actualDate: '2026-11-04', createdAt: '2026-11-01T10:00:00',
      pointRuleCode: 'A1', basePointSnapshot: 12, details: '关联任务的原始说明',
      rejectionRecords: [], deliveryVersions: [],
    });
    const tasks = [task(82001, '包装主视觉设计'), task(82002, '包装修改'), task(82003, '详情页排版')];
    const project = {
      id: projectId, name: '关联测试项目', productName: '关联测试产品', type: 'regular',
      status: 'in_progress', statusLabel: '进行中', statusCls: 'badge-progress',
      plannerId: 'ledger-links-planner', plannerName: '测试企划',
      createdAt: '2026-11-01T10:00:00', updatedAt: '2026-11-05T10:00:00',
      deadline: '2026-11-20', tasks, logs: [],
    };
    const requirement = {
      id: 83001, projectCode: 'NEED-83001', productName: '界面送审需求',
      designerId: 'ledger-links-designer', designerName: '关联测试设计师',
      ownerId: 'ledger-links-planner', plannerId: 'ledger-links-planner',
      status: 'completed', statusLabel: '已完成', deadline: '2026-11-04',
      createdAt: '2026-11-01T10:00:00', productRequirements: '核对需求积分对应的原始需求。',
      referenceImagesJson: '[]', attachmentsJson: '[]',
      deliveryReferenceImagesJson: '[]', deliveryAttachmentsJson: '[]',
      ownerAccepted: true, plannerAccepted: true,
    };
    const ledgers = Array.from({ length: 11 }, (_, index) => ({
      id: index + 1, subTaskId: index === 0 ? 82001 : index === 2 ? 82999 : index === 3 ? 82003 : 82002,
      userId: 'ledger-links-designer', ruleCode: index === 0 ? 'A1:BASE' : 'A3:QUALITY',
      points: index === 0 ? 12 : 3, accountingMonth: '2026-11',
      createdAt: `2026-11-05T10:00:${String(index).padStart(2, '0')}`,
    }));
    const adjustments = [
      { id: 1, sourceType: 'DESIGN_REQUIREMENT', sourceId: 83001, ruleCode: 'A10', ruleDescription: '界面设计',
        reason: '送审需求交付入账', points: 6, accountingMonth: '2026-11', createdAt: '2026-11-05T11:00:00' },
      { id: 2, sourceType: 'MANUAL', sourceId: 83001, reason: '管理员手动校准，不关联业务',
        points: -1, accountingMonth: '2026-11', createdAt: '2026-11-05T11:10:00' },
    ];
    const associations = Object.fromEntries(ledgers.filter(item => item.id !== 3).map(item => [`L-${item.id}`, {
      projectId, projectName: '关联测试项目', subTaskId: item.subTaskId,
      subTaskName: tasks.find(value => value.id === item.subTaskId).name,
    }]));
    associations['L-4'] = { ...associations['L-4'],
      projectName: '超长项目名称'.repeat(14) + ' <img src=x onerror=alert(1)>',
      subTaskName: '超长子任务名称'.repeat(12) + ' <img src=x onerror=alert(1)>',
    };
    associations['A-1'] = { requirementId: 83001, requirementName: '界面送审需求' };
    e.actions.apiGet = async url => {
      f.reads.push(url);
      const parsed = new URL(url, location.origin);
      if (parsed.pathname === '/points/me') {
        const selected = Number(parsed.searchParams.get('page') || 0);
        const rows = ledgers.slice(selected * 10, (selected + 1) * 10);
        const keys = [...rows.map(item => `L-${item.id}`), ...adjustments.map(item => `A-${item.id}`)];
        return { balance: 47, ledger: rows, ledgerPage: selected, ledgerPages: 2, ledgerTotal: 11,
          adjustmentLedger: adjustments, ledgerAssociations: Object.fromEntries(keys.filter(key => associations[key]).map(key => [key, associations[key]])) };
      }
      if (parsed.pathname === '/points/rules') return [
        { ruleCode: 'A1', description: '包装整套设计', category: 'A', points: 12 },
        { ruleCode: 'A3', description: '包装修改', category: 'A', points: 3 },
      ];
      if (['/point-governance/appeals', '/point-governance/archives', '/performance/leaderboard'].includes(parsed.pathname)) return [];
      if (parsed.pathname === '/point-governance/po/me') return { projects: [], progress: [] };
      if (parsed.pathname === '/performance/preview') return { points: 47, targetPoints: 100, attainmentRate: 0.47 };
      if (parsed.pathname === '/projects/81001') return project;
      if (parsed.pathname === '/design-requirements/83001') return requirement;
      throw new Error('Unexpected ledger link API: ' + url);
    };
    for (const action of ['apiPost', 'apiPut', 'apiDelete']) {
      e.actions[action] = async (...args) => { f.writes.push({ action, args }); throw new Error('Ledger link checks must remain read-only'); };
    }
    await e.actions.renderPointsView(main);
  });
  const main = page.locator('#mainContent');
  const card = main.locator('.points-ledger-card');
  const action = name => card.locator(`[data-emie-action="click:points-ledger-${name}"]`);
  const close = async id => {
    await page.evaluate(id => EMIE.actions.closeM(id), id);
    await page.locator('#' + id).waitFor({ state: 'hidden' });
  };
  try {
    assert.equal(await card.locator('thead th').count(), 5, '保留五列明细结构');
    const firstTask = action('task').filter({ hasText: '包装主视觉设计' });
    const firstRow = card.locator('tbody tr').filter({ has: page.locator('[data-emie-action="click:points-ledger-task"][data-task-id="82001"]') });
    const detail = firstRow.locator('td').nth(1);
    assert.match(await detail.innerText(), /A1/);
    assert.match(await detail.innerText(), /包装整套设计/);
    assert.match(await detail.innerText(), /关联测试项目/);
    assert.match(await detail.innerText(), /包装主视觉设计/);
    assert.equal(await detail.locator('.points-ledger-detail').count(), 1);
    assert.equal(await detail.locator('.points-ledger-relations').count(), 1, '业务关联与规则在同一明细格展示');
    assert.equal(await firstTask.getAttribute('data-project-id'), '81001');
    assert.equal(await firstTask.getAttribute('data-task-id'), '82001');
    const deleted = card.locator('tbody tr').filter({ hasText: '任务 #82999' });
    assert.equal(await deleted.count(), 1);
    assert.equal(await deleted.locator('.points-ledger-relations button').count(), 0, '失效关联不产生错误跳转');
    const manual = card.locator('tbody tr').filter({ hasText: '管理员手动校准，不关联业务' });
    assert.equal(await manual.locator('.points-ledger-relations button').count(), 0,
      '人工调账 sourceId 与需求 ID 相同也不关联到需求');
    assert.equal(await card.locator('img').count(), 0, '关联名称按文本转义');
    assert.match(await card.innerText(), /<img src=x onerror=alert\(1\)>/);
    assert.equal(await page.evaluate(() => pointsLedgerLinkFixture.reads.some(url =>
      url.startsWith('/projects/') || url.startsWith('/design-requirements/'))), false,
    '列表直接使用随积分返回的关联信息，不逐行查询业务');

    await firstRow.locator('[data-emie-action="click:points-ledger-project"]').click();
    await page.locator('#projectDetailModal').waitFor({ state: 'visible' });
    assert.match(await page.locator('#projectDetailModal .modal-title').innerText(), /#81001/);
    assert.match(await page.locator('#projectDetailModal').innerText(), /关联测试产品/);
    await close('projectDetailModal');

    assert.equal(await page.evaluate(() => EMIE.dashboardState.designerTaskCache.length), 0);
    await firstTask.click();
    await page.locator('#projectSubTaskDetailModal').waitFor({ state: 'visible' });
    assert.match(await page.locator('#projectSubTaskDetailModal .modal-title').innerText(), /包装主视觉设计/);
    assert.match(await page.locator('#projectSubTaskDetailModal').innerText(), /#82001/);
    assert.match(await page.locator('#projectSubTaskDetailModal').innerText(), /关联任务的原始说明/);
    await close('projectSubTaskDetailModal');
    await close('projectDetailModal');

    await action('requirement').click();
    await page.locator('#designRequirementDetailModal').waitFor({ state: 'visible' });
    assert.match(await page.locator('#designRequirementDetailModal').innerText(), /NEED-83001/);
    assert.match(await page.locator('#designRequirementDetailModal').innerText(), /界面送审需求/);
    await close('designRequirementDetailModal');

    await page.setViewportSize({ width: 1440, height: 1100 });
    await card.screenshot({ path: path.join(artifactRoot, 'points-ledger-links-desktop.png'), animations: 'disabled' });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await card.evaluate(element => {
      const box = element.getBoundingClientRect();
      return box.left >= -1 && box.right <= innerWidth + 1 && element.scrollWidth <= element.clientWidth + 1;
    }), '手机端积分表格滚动应限制在卡片内部');
    await card.screenshot({ path: path.join(artifactRoot, 'points-ledger-links-mobile.png'), animations: 'disabled' });

    await card.locator('[data-emie-action="click:points-ledger-page"][data-page="1"]').click();
    await page.waitForFunction(() => document.querySelector('.points-ledger-card .points-page-indicator b')?.textContent === '2');
    assert.equal(await action('task').filter({ hasText: '包装主视觉设计' }).count(), 0);
    assert.equal(await action('task').filter({ hasText: '包装修改' }).count(), 1, '翻页后仍展示该页子任务关联');
    assert.equal(await action('task').first().getAttribute('data-task-id'), '82002');
    await action('task').first().click();
    await page.locator('#projectSubTaskDetailModal').waitFor({ state: 'visible' });
    assert.match(await page.locator('#projectSubTaskDetailModal').innerText(), /#82002/);
    await close('projectSubTaskDetailModal');
    await close('projectDetailModal');
    assert.deepEqual(await page.evaluate(() => pointsLedgerLinkFixture.reads.filter(url =>
      url.startsWith('/projects/') || url.startsWith('/design-requirements/'))),
    ['/projects/81001', '/projects/81001', '/design-requirements/83001', '/projects/81001']);
    assert.deepEqual(await page.evaluate(() => pointsLedgerLinkFixture.writes), []);
    console.log('  points-ledger-project-task-requirement-links-pagination-and-mobile ok');
  } finally {
    for (const id of ['projectSubTaskDetailModal', 'projectDetailModal', 'designRequirementDetailModal']) await close(id);
    const previous = card.locator('[data-emie-action="click:points-ledger-page"][data-page="0"]');
    if (await previous.count()) {
      await previous.click();
      await page.waitForFunction(() => document.querySelector('.points-ledger-card .points-page-indicator b')?.textContent === '1');
    }
    if (viewport) await page.setViewportSize(viewport);
    await page.evaluate(() => {
      const e = window.EMIE, saved = window.pointsLedgerLinkMocks;
      e.actions.apiGet = saved.get; e.actions.apiPost = saved.post; e.actions.apiPut = saved.put; e.actions.apiDelete = saved.delete;
      e.state.currentRole = saved.role; e.state.currentUserId = saved.userId; e.state.permissions = saved.permissions;
      e.state.authUser = saved.authUser; e.dashboardState.designerTaskCache = saved.designerTasks;
      e.projectState.currentProjectDetail = saved.projectDetail;
      document.getElementById('mainContent').replaceChildren(saved.nodes);
      document.getElementById('appContainer').style.display = saved.appDisplay;
      document.getElementById('loginPage').style.display = saved.loginDisplay;
    });
  }
}
