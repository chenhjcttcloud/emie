import assert from 'node:assert/strict';

// Reuse the actual new-task picker and submit flow with isolated project reads.
export async function runProjectFilePickerTimeChecks(page) {
  const viewport = page.viewportSize();
  const imageRoute = '**/api/files/**/picker-time-*';
  const serveImage = route => route.fulfill({ contentType: 'image/gif',
    body: Buffer.from('R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==', 'base64') });
  await page.route(imageRoute, serveImage);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.evaluate(() => {
    const e = window.EMIE;
    const actionKeys = ['apiGet', 'apiPost', 'uploadFile', 'hasPermission', 'refreshAfterMutation'];
    const stateKeys = ['users', 'currentRole', 'currentUserId', 'authUser'];
    const projectKeys = ['subTaskRefImages', 'subTaskAttachments', 'formModified'];
    const file = (name, createdAt) => ({ name, storedName: `picker-time-${name}`, size: 42,
      url: `/api/files/download/picker-time-${name}`, ...(createdAt ? { createdAt } : {}) });
    const list = (...files) => JSON.stringify(files);
    const shared = file('shared.pdf', '2029-01-01T00:00:00+08:00');
    const sharedImage = file('shared.png', '2030-01-01T00:00:00+08:00');
    const project = { id: 76101, name: '引用选择器时间回归项目',
      referenceImagesJson: list(file('new.png', '2027-01-04T00:00:00+08:00'), sharedImage,
        file('old.png', '2026-12-31T23:59:59+08:00'), file('unknown.png')),
      attachmentsJson: list(shared, file('upload.pdf', '2027-01-03T00:00:00+08:00'),
        file('old.pdf', '2026-12-31T23:59:59+08:00'), file('unknown.pdf'), file('invalid.pdf', 'invalid'), file('project-map.pdf')),
      projectFileUploadTimes: { 'picker-time-project-map.pdf': '2027-01-03T00:00:01+08:00',
        'picker-time-shared.pdf': '2031-01-01T00:00:00+08:00', 'picker-time-orphan.pdf': '2032-01-01T00:00:00+08:00' },
      tasks: [{ id: 76201, name: '首轮包装', assigneeRole: 'designer',
        uploadedFiles: [file('tie-a.pdf'), file('tie-b.pdf')],
        pendingChangeRequestedAt: '2027-01-01T00:00:06+08:00', pendingChangeAttachmentsJson: list(file('current-change.pdf')),
        deliveryVersions: [{ versionNo: 1, submittedAt: '2027-01-01T00:00:01+08:00',
          referenceImagesJson: list(sharedImage), attachmentsJson: list(shared, file('tie-a.pdf'), file('tie-b.pdf')),
          changeRequestedAt: '2027-01-01T00:00:02+08:00', changeAttachmentsJson: list(file('history-change.pdf')) },
        { versionNo: 2, submissionType: 'correction', submittedAt: '2027-01-01T00:00:04+08:00',
          attachmentsJson: list(shared, file('correction.pdf')) },
        { versionNo: 3, submittedAt: '2027-01-01T00:00:01.100+08:00', attachmentsJson: list(file('ms-early.pdf')) }],
        rejectionRecords: [{ reviewedAt: '2027-01-01T00:00:05+08:00', rejectionAttachmentsJson: list(shared, file('rejection.pdf')) }],
      }, { id: 76202, name: '跨组修改', assigneeRole: 'designer', deliveryVersions: [
        { versionNo: 1, submittedAt: '2027-01-02T00:00:00+08:00', attachmentsJson: list(shared), referenceImagesJson: list(sharedImage) },
        { versionNo: 2, submittedAt: '2026-12-31T18:00:00Z', attachmentsJson: list(file('offset.pdf')) },
        { versionNo: 3, submittedAt: '2027-01-01T00:00:01.900+08:00', attachmentsJson: list(file('ms-late.pdf')) },
      ] }] };
    const fixture = window.projectFilePickerTimeFixture = {
      originalActions: Object.fromEntries(actionKeys.map(key => [key, e.actions[key]])),
      originalState: Object.fromEntries(stateKeys.map(key => [key, e.state[key]])),
      originalProjectState: Object.fromEntries(projectKeys.map(key => [key, e.projectState[key]])),
      appDisplay: document.getElementById('appContainer').style.display,
      loginDisplay: document.getElementById('loginPage').style.display,
      project, snapshot: JSON.stringify(project), reads: [], writes: [], uploads: [],
      draft: sessionStorage.getItem('design_pm_subtask_draft_76101'),
    };
    sessionStorage.removeItem('design_pm_subtask_draft_76101');
    e.state.users = { ...e.state.users, designer: [{ userId: 'picker-time-designer', name: '时间测试设计师' }] };
    e.state.currentRole = 'planner'; e.state.currentUserId = 'picker-time-planner';
    e.state.authUser = { userId: 'picker-time-planner', role: 'planner', name: '时间测试企划' };
    e.actions.hasPermission = () => true;
    e.actions.refreshAfterMutation = async () => {};
    e.actions.apiGet = async url => {
      fixture.reads.push(url);
      if (url === '/projects/76101') return project;
      if (url === '/points/rules') return [{ ruleCode: 'PICKER_TIME', category: '时间回归', description: '时间回归任务', points: 1 }];
      return fixture.originalActions.apiGet(url);
    };
    e.actions.apiPost = async (url, body) => { fixture.writes.push({ url, body }); };
    e.actions.uploadFile = async file => { fixture.uploads.push(file.name); throw new Error('引用已有文件不得上传'); };
    document.getElementById('appContainer').style.display = '';
    document.getElementById('loginPage').style.display = 'none';
  });
  const add = page.locator('#addSubTaskModal');
  const picker = page.locator('#projectFilePickerModal');
  const items = picker.locator('label.project-file-picker-item');
  const row = name => items.filter({ has: page.locator('strong').filter({ hasText: new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) });
  const names = () => items.locator('.project-file-picker-info > strong').allTextContents();
  const search = picker.locator('#projectFilePickerSearch');
  const settle = () => page.evaluate(async () => {
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    await Promise.all(document.getAnimations().filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime))
      .map(animation => animation.finished.catch(() => {})));
  });
  const open = async kind => {
    await add.locator(`[data-emie-action="click:task-reference-project-files"][data-kind="${kind}"]`).click();
    await picker.waitFor({ state: 'visible' });
    await items.first().waitFor();
  };
  const confirm = async () => {
    await picker.locator('[data-emie-action="click:project-file-picker-confirm"]').click();
    await picker.waitFor({ state: 'detached' });
  };
  const close = async () => {
    await picker.locator('[data-emie-action="click:project-file-picker-close"]').first().click();
    await picker.waitFor({ state: 'detached' });
  };
  const expectTime = async (name, value, kind, text) => {
    const candidate = row(name);
    assert.equal(await candidate.getAttribute('data-file-time'), value, name);
    assert.equal(await candidate.getAttribute('data-file-time-kind'), kind, name);
    assert.equal(Number(await candidate.getAttribute('data-file-timestamp')), Date.parse(value), name);
    assert.equal(await candidate.locator('time.project-file-time').getAttribute('datetime'), new Date(value).toISOString(), name);
    assert.equal(await candidate.locator('.project-file-time').textContent(), text, name);
  };
  try {
    await page.evaluate(() => window.EMIE.actions.addSubTask(76101));
    await add.waitFor({ state: 'visible' });
    await add.locator('[name="name"]').fill('按时间引用项目文件');
    await add.locator('[name="workflowStage"]').selectOption('design');
    await add.locator('[name="plannedDate"]').fill(new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10));
    await add.locator('[name="designerId"]').selectOption('picker-time-designer');
    await add.locator('[data-point-rule-code="PICKER_TIME"]').click();
    await open('reference');
    assert.deepEqual(await names(), ['new.png', 'shared.png', 'old.png', 'unknown.png'], '图片候选按最新时间排序，未知最后');
    await expectTime('new.png', '2027-01-04T00:00:00+08:00', 'uploaded', '上传于 2027-01-04 00:00:00');
    await expectTime('shared.png', '2027-01-02T00:00:00+08:00', 'submitted', '提交于 2027-01-02 00:00:00');
    await row('new.png').getByRole('checkbox').check();
    await row('shared.png').getByRole('checkbox').check();
    await confirm();
    await open('attachment');
    const originalNames = await names();
    assert.deepEqual(originalNames.filter(name => name.endsWith('.pdf')), ['project-map.pdf', 'upload.pdf', 'shared.pdf', 'offset.pdf',
      'current-change.pdf', 'rejection.pdf', 'correction.pdf', 'history-change.pdf', 'ms-late.pdf', 'ms-early.pdf', 'tie-a.pdf', 'tie-b.pdf',
      'old.pdf', 'unknown.pdf', 'invalid.pdf'], '附件跨年、offset、修改和驳回时间正确排序，精确到毫秒且完全同时间保持原顺序');
    assert.equal(originalNames.includes('orphan.pdf'), false, '上传时间metadata中的孤立ID不能生成新候选');
    const stamps = await items.evaluateAll(nodes => nodes.map(node => node.dataset.fileTimestamp === '' ? null : Number(node.dataset.fileTimestamp)));
    const firstUnknown = stamps.indexOf(null);
    assert(firstUnknown >= 0 && stamps.slice(firstUnknown).every(value => value === null), '未知时间候选全部排最后');
    const known = stamps.filter(value => value !== null);
    assert(known.every((value, index) => index === 0 || known[index - 1] >= value));
    await expectTime('shared.pdf', '2027-01-02T00:00:00+08:00', 'submitted', '提交于 2027-01-02 00:00:00');
    assert.equal(await row('shared.pdf').count(), 1, '跨项目资料和各子任务来源汇总成一个候选');
    assert.match(await row('shared.pdf').innerText(), /首轮包装/);
    assert.match(await row('shared.pdf').innerText(), /跨组修改/);
    await expectTime('upload.pdf', '2027-01-03T00:00:00+08:00', 'uploaded', '上传于 2027-01-03 00:00:00');
    await expectTime('project-map.pdf', '2027-01-03T00:00:01+08:00', 'uploaded', '上传于 2027-01-03 00:00:01');
    await expectTime('ms-late.pdf', '2027-01-01T00:00:01.900+08:00', 'submitted', '提交于 2027-01-01 00:00:01');
    await expectTime('ms-early.pdf', '2027-01-01T00:00:01.100+08:00', 'submitted', '提交于 2027-01-01 00:00:01');
    await expectTime('offset.pdf', '2026-12-31T18:00:00Z', 'submitted', '提交于 2027-01-01 02:00:00');
    await expectTime('current-change.pdf', '2027-01-01T00:00:06+08:00', 'submitted', '提交于 2027-01-01 00:00:06');
    await expectTime('rejection.pdf', '2027-01-01T00:00:05+08:00', 'submitted', '提交于 2027-01-01 00:00:05');
    await expectTime('correction.pdf', '2027-01-01T00:00:04+08:00', 'submitted', '提交于 2027-01-01 00:00:04');
    await expectTime('history-change.pdf', '2027-01-01T00:00:02+08:00', 'submitted', '提交于 2027-01-01 00:00:02');
    for (const name of ['unknown.png', 'unknown.pdf', 'invalid.pdf']) {
      assert.equal(await row(name).getAttribute('data-file-time-kind'), 'unknown', name);
      assert.equal(await row(name).getAttribute('data-file-timestamp'), '', name);
      assert.equal(await row(name).locator('.project-file-time').textContent(), '时间未记录', name);
      assert.equal(await row(name).locator('time').count(), 0, name);
    }
    await row('shared.pdf').getByRole('checkbox').check();
    await search.fill('跨组修改');
    const filtered = await names();
    assert.deepEqual(filtered, originalNames.filter(name => ['shared.pdf', 'shared.png', 'offset.pdf', 'ms-late.pdf'].includes(name)),
      '来源搜索保持候选原有时间顺序');
    await search.fill('upload.pdf');
    await row('upload.pdf').getByRole('checkbox').check();
    await search.clear();
    assert.deepEqual(await names(), originalNames, '清空搜索恢复相同时间顺序');
    assert.equal(await row('shared.pdf').getByRole('checkbox').isChecked(), true, '跨搜索保留此前勾选');

    await settle();
    await picker.screenshot({ path: '/tmp/emie-project-file-picker-times-desktop.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await settle();
    assert(await picker.getByRole('dialog').evaluate(node => {
      const rect = node.getBoundingClientRect();
      return rect.left >= -1 && rect.right <= innerWidth + 1 && node.scrollWidth <= node.clientWidth + 1;
    }), '带时间的文件候选适配390px视口');
    assert(await items.first().evaluate(node => node.scrollWidth <= node.clientWidth + 1));
    await picker.screenshot({ path: '/tmp/emie-project-file-picker-times-mobile.png' });
    await page.setViewportSize({ width: 1280, height: 900 });
    await confirm();
    await open('attachment');
    assert.equal(await row('shared.pdf').getByRole('checkbox').isDisabled(), true);
    assert.equal(await row('upload.pdf').getByRole('checkbox').isDisabled(), true);
    await expectTime('shared.pdf', '2027-01-02T00:00:00+08:00', 'submitted', '提交于 2027-01-02 00:00:00');
    await close();
    await add.locator('[data-emie-action="click:task-submit-add"]').click();
    await add.waitFor({ state: 'detached' });
    const sent = await page.evaluate(() => window.projectFilePickerTimeFixture.writes.at(-1));
    assert.equal(sent.url, '/projects/76101/tasks');
    for (const [field, expectedNames] of [['referenceImagesJson', ['new.png', 'shared.png']], ['attachmentsJson', ['upload.pdf', 'shared.pdf']]]) {
      const files = JSON.parse(sent.body[field]);
      assert.deepEqual(files.map(file => file.name), expectedNames);
      for (const file of files) {
        assert.deepEqual(Object.keys(file).sort(), ['name', 'size', 'storedName', 'url'], '引用提交不混入展示用时间字段');
        assert.equal(file.storedName, `picker-time-${file.name}`);
        assert.equal(file.url, `/api/files/download/picker-time-${file.name}`);
      }
    }
    assert.equal(await page.evaluate(() => JSON.stringify(window.projectFilePickerTimeFixture.project)
      === window.projectFilePickerTimeFixture.snapshot), true, '排序和合并不修改项目原始文件数组');
    assert.deepEqual(await page.evaluate(() => window.projectFilePickerTimeFixture.uploads), []);
  } finally {
    await page.evaluate(() => ['projectFilePickerModal', 'addSubTaskModal'].forEach(id => document.getElementById(id)?.remove()));
    if (viewport) await page.setViewportSize(viewport);
    await page.unroute(imageRoute, serveImage);
    await page.evaluate(() => {
      const e = window.EMIE;
      const fixture = window.projectFilePickerTimeFixture;
      Object.assign(e.actions, fixture.originalActions);
      Object.assign(e.state, fixture.originalState);
      Object.assign(e.projectState, fixture.originalProjectState);
      document.getElementById('appContainer').style.display = fixture.appDisplay;
      document.getElementById('loginPage').style.display = fixture.loginDisplay;
      if (fixture.draft == null) sessionStorage.removeItem('design_pm_subtask_draft_76101');
      else sessionStorage.setItem('design_pm_subtask_draft_76101', fixture.draft);
      delete window.projectFilePickerTimeFixture;
    });
  }
  process.stdout.write('  project-file-picker-times-merge-sort-search-select-submit-and-mobile ok\n');
}
