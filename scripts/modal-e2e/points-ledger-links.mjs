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
      previewImage: e.actions.previewImage, showDownloadOptions: e.actions.showDownloadOptions,
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
    const file = (storedName, name) => ({ storedName, name, size: 1200 });
    const plannerImage = { ...file('overview-setup.png', '最初企划参考图.png'), plannerUpload: true };
    const currentImage = file('overview-current.png', '当前交付图.png');
    const oldDesign = file('overview-old.ai', '首轮源文件.ai');
    const changeImage = { ...file('overview-change.png', '修改标注.png'), plannerUpload: true };
    const rejectionFile = { ...file('overview-feedback.docx', '驳回说明 <补充>.docx'), plannerUpload: true };
    tasks[0].uploadedFiles = [plannerImage, currentImage, changeImage, rejectionFile, { url: 'javascript:alert(1)' }];
    tasks[0].referenceImagesJson = JSON.stringify([currentImage]);
    tasks[0].pendingChangeReferenceImagesJson = JSON.stringify([changeImage]);
    tasks[0].deliveryVersions = [
      { id: 92002, versionNo: 2, submissionType: 'redelivery', submittedByRole: 'designer', referenceImagesJson: JSON.stringify([currentImage]) },
      { id: 92001, versionNo: 1, submissionType: 'initial', attachmentsJson: JSON.stringify([oldDesign]) },
    ];
    tasks[0].rejectionRecords = [{
      attemptNo: 1, reason: '请调整包装对比度', reviewerRole: 'planner', rejectedDeliveryVersionId: 92001,
      attachmentsJson: JSON.stringify([oldDesign]),
      rejectionAttachmentsJson: JSON.stringify([rejectionFile]),
    }];
    tasks[1].uploadedFiles = [file('overview-other.pdf', '第二个子任务附件.pdf')];
    tasks[1].deliveryVersions = [{
      id: 92003, versionNo: 1, submissionType: 'initial', submittedByRole: 'supplychain',
      attachmentsJson: JSON.stringify([file('overview-supplychain.pdf', '供应链交付附件.pdf')]),
    }];
    const project = {
      id: projectId, name: '关联测试项目', productName: '关联测试产品', type: 'regular',
      status: 'in_progress', statusLabel: '进行中', statusCls: 'badge-progress',
      plannerId: 'ledger-links-planner', plannerName: '测试企划',
      createdAt: '2026-11-01T10:00:00', updatedAt: '2026-11-05T10:00:00',
      deadline: '2026-11-20', tasks, logs: [],
      attachmentsJson: JSON.stringify([file('overview-project.pdf', '项目本身的附件.pdf')]),
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
    e.actions.previewImage = (url, name, _gallery, _index, sourceLabel) => {
      f.preview = new URL(url, location.origin).pathname;
      f.previewName = name;
      f.previewSource = sourceLabel;
    };
    e.actions.showDownloadOptions = url => { f.download = new URL(url, location.origin).pathname; };
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
    assert.deepEqual(await card.locator('thead th').allTextContents(), ['来源', '项目', '子任务', '归属月', '积分', '入账时间']);
    const firstTask = action('task').filter({ hasText: '包装主视觉设计' });
    const firstRow = card.locator('tbody tr').filter({ has: page.locator('[data-emie-action="click:points-ledger-task"][data-task-id="82001"]') });
    const taskCells = firstRow.locator('td');
    assert.deepEqual((await taskCells.allTextContents()).map(text => text.trim()),
      ['任务 #82001', '关联测试项目', '包装主视觉设计', '2026-11', '+12', '2026-11-05 10:00:00']);
    assert.equal(await taskCells.nth(1).getByRole('button', { name: '关联测试项目' }).count(), 1);
    assert.equal(await taskCells.nth(2).getByRole('button', { name: '包装主视觉设计' }).count(), 1);
    assert.equal(await firstTask.getAttribute('data-project-id'), '81001');
    assert.equal(await firstTask.getAttribute('data-task-id'), '82001');
    const deleted = card.locator('tbody tr').filter({ hasText: '任务 #82999' });
    assert.equal(await deleted.count(), 1);
    assert.deepEqual((await deleted.locator('td').allTextContents()).slice(1, 3), ['—', '—']);
    assert.equal(await deleted.locator('button').count(), 0, '失效关联不产生错误跳转');
    const manual = card.locator('tbody tr').filter({ hasText: '管理员调账' });
    assert.deepEqual((await manual.locator('td').allTextContents()).slice(1, 3), ['—', '—']);
    assert.equal(await manual.locator('button').count(), 0,
      '人工调账 sourceId 与需求 ID 相同也不关联到需求');
    const requirementRow = card.locator('tbody tr').filter({ hasText: '设计/送审需求' });
    assert.deepEqual((await requirementRow.locator('td').allTextContents()).slice(1, 3), ['—', '—']);
    assert.equal(await requirementRow.locator('td').first().getByRole('button', { name: '界面送审需求' }).count(), 1);
    assert.equal(await card.locator('img').count(), 0, '关联名称按文本转义');
    assert.match(await card.innerText(), /<img src=x onerror=alert\(1\)>/);
    assert.equal(await page.evaluate(() => pointsLedgerLinkFixture.reads.some(url =>
      url.startsWith('/projects/') || url.startsWith('/design-requirements/'))), false,
    '列表直接使用随积分返回的关联信息，不逐行查询业务');

    await firstRow.locator('[data-emie-action="click:points-ledger-project"]').click();
    await page.locator('#projectDetailModal').waitFor({ state: 'visible' });
    assert.match(await page.locator('#projectDetailModal .modal-title').innerText(), /#81001/);
    assert.match(await page.locator('#projectDetailModal').innerText(), /关联测试产品/);
    const overview = page.locator('#projectDetailModal .project-file-overview');
    assert.match(await overview.locator('.detail-section-title').innerText(), /文件总览\s*7 个文件/);
    assert.match(await overview.evaluate(element => element.nextElementSibling?.textContent), /产品档案资料/);
    const fileGroups = overview.locator('.project-file-group');
    assert.equal(await fileGroups.count(), 2);
    assert.equal(await fileGroups.first().locator('.project-file-row').count(), 5, '每个文件在同一子任务内只显示一次');
    const fileRow = name => overview.locator('.project-file-row').filter({ hasText: name });
    const sourceLabels = name => fileRow(name).locator('.project-file-source').allTextContents();
    assert.deepEqual(await sourceLabels('最初企划参考图.png'), ['企划参考']);
    assert.deepEqual(await sourceLabels('当前交付图.png'), ['设计师交付']);
    assert.deepEqual(await sourceLabels('首轮源文件.ai'), ['设计师交付']);
    assert.deepEqual(await sourceLabels('修改标注.png'), ['修改参考']);
    assert.deepEqual(await sourceLabels('驳回说明 <补充>.docx'), ['驳回资料']);
    assert.match(await fileRow('首轮源文件.ai').innerText(), /交付第 1 版/);
    assert.doesNotMatch(await overview.innerText(), /项目本身的附件|javascript:/);
    await fileGroups.nth(1).locator('summary').click();
    assert.match(await fileGroups.nth(1).innerText(), /第二个子任务附件\.pdf/);
    assert.deepEqual(await sourceLabels('第二个子任务附件.pdf'), []);
    assert.deepEqual(await sourceLabels('供应链交付附件.pdf'), ['交付成果']);
    await fileGroups.nth(1).locator('summary').click();
    await fileRow('最初企划参考图.png').getByRole('button', { name: /预览/ }).click();
    assert.deepEqual(await page.evaluate(() => [
      pointsLedgerLinkFixture.preview, pointsLedgerLinkFixture.previewName, pointsLedgerLinkFixture.previewSource,
    ]), ['/api/files/download/overview-setup.png', '最初企划参考图.png', '企划参考']);
    const currentFile = fileRow('当前交付图.png');
    await currentFile.getByRole('button', { name: /预览/ }).click();
    await currentFile.getByRole('button', { name: /下载/ }).click();
    assert.deepEqual(await page.evaluate(() => [pointsLedgerLinkFixture.preview, pointsLedgerLinkFixture.download]),
      ['/api/files/download/overview-current.png', '/api/files/download/overview-current.png']);
    assert.equal(await page.evaluate(() => pointsLedgerLinkFixture.previewSource), '设计师交付');
    await overview.screenshot({ path: path.join(artifactRoot, 'project-file-overview.png'), animations: 'disabled' });
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
      e.actions.previewImage = saved.previewImage; e.actions.showDownloadOptions = saved.showDownloadOptions;
      e.state.currentRole = saved.role; e.state.currentUserId = saved.userId; e.state.permissions = saved.permissions;
      e.state.authUser = saved.authUser; e.dashboardState.designerTaskCache = saved.designerTasks;
      e.projectState.currentProjectDetail = saved.projectDetail;
      document.getElementById('mainContent').replaceChildren(saved.nodes);
      document.getElementById('appContainer').style.display = saved.appDisplay;
      document.getElementById('loginPage').style.display = saved.loginDisplay;
    });
  }
}
