import assert from 'node:assert/strict';

// Test genuine project-detail markup using isolated project data and file actions.
export async function runFileOverviewTimeChecks(page) {
  const viewport = page.viewportSize();
  const fileRoute = '**/api/files/**/time-*';
  const serveImage = route => route.fulfill({ contentType: 'image/gif',
    body: Buffer.from('R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==', 'base64') });
  await page.route(fileRoute, serveImage);
  await page.setViewportSize({ width: 1280, height: 1000 });
  await page.evaluate(() => {
    const e = window.EMIE;
    const actionKeys = ['apiGet', 'hasPermission', 'previewImage', 'openFilePreview', 'showDownloadOptions'];
    const stateKeys = ['currentRole', 'currentUserId', 'authUser'];
    const file = (name, createdAt) => ({ name, storedName: `time-${name}`, url: `/api/files/download/time-${name}`,
      size: 42, ...(createdAt ? { createdAt } : {}) });
    const list = (...files) => JSON.stringify(files);
    const task = (id, name, fields) => ({ id, name, status: 'pending', workflowStage: 'design', assigneeRole: 'designer',
      designerId: 'time-designer', designerName: '时间测试设计师', plannedDate: '2027-01-10',
      createdAt: '2035-01-01T10:00:00', updatedAt: '2036-01-01T10:00:00',
      rejectionRecords: [], deliveryVersions: [], ...fields });
    const shared = file('多源同文件.pdf', '2027-01-01T00:00:05+08:00');
    const correction = file('更正成果.pdf');
    const tasks = [
      task(75201, '跨年设计', {
        submittedForReviewAt: '2040-01-01T10:00:00',
        uploadedFiles: [shared, file('仅上传文件.pdf', '2027-01-01T00:00:01'),
          file('无效时间.pdf', 'not-a-time'), file('未记录时间.pdf')],
        attachmentsJson: list(shared, file('仅上传文件.pdf')),
        pendingChangeRequestedAt: '2027-01-01T00:00:06+08:00',
        pendingChangeAttachmentsJson: list(file('当前修改要求.pdf')),
        deliveryVersions: [
          { id: 75301, versionNo: 1, submissionType: 'initial', submittedAt: '2026-12-31T23:59:59+08:00',
            attachmentsJson: list(shared, file('初次成果.pdf')) },
          { id: 75303, versionNo: 3, submissionType: 'correction', submittedAt: '2027-01-01T00:00:04+08:00',
            attachmentsJson: list(shared, correction) },
          { id: 75302, versionNo: 2, submissionType: 'redelivery', submittedAt: '2027-01-01T00:00:03+08:00',
            attachmentsJson: list(shared, file('同秒甲.pdf'), file('同秒乙.pdf')),
            changeRequestedAt: '2027-01-01T00:00:02+08:00', changeAttachmentsJson: list(file('历史修改要求.pdf')) },
        ],
        rejectionRecords: [{ attemptNo: 1, rejectedDeliveryVersionId: 75302,
          reviewedAt: '2027-01-01T00:00:05+08:00', submittedAt: '2027-01-01T00:00:03+08:00',
          attachmentsJson: list(shared, file('驳回前提交.pdf')), rejectionAttachmentsJson: list(file('驳回说明.pdf')) }],
      }),
      task(75205, '时间缺失组', { uploadedFiles: [file('组内未知甲.pdf'), file('组内未知乙.pdf', '2027-99-99T99:99:99')] }),
      task(75206, '同秒组甲', { uploadedFiles: [file('同秒组甲.pdf', '2026-01-01T10:00:00+08:00')] }),
      task(75203, '旧年成果', { status: 'delivered', submittedForReviewAt: '2026-12-31T23:59:58+08:00',
        uploadedFiles: [file('旧版成果.pdf', '2026-12-01T10:00:00+08:00')], attachmentsJson: list(file('旧版成果.pdf')) }),
      task(75207, '同秒组乙', { uploadedFiles: [file('同秒组乙.pdf', '2026-01-01T10:00:00+08:00')] }),
      task(75202, '最新上传组', { uploadedFiles: [file('最新文件.pdf', '2027-01-02T00:00:00Z'),
        file('最新预览.png', '2027-01-02T00:00:00Z')] }),
      task(75204, '跨组引用', { attachmentsJson: list(file('多源同文件.pdf'), correction) }),
    ];
    const project = { id: 75101, name: '文件时间排序回归项目', productName: '文件总览时间验证', type: 'regular',
      status: 'in_progress', statusLabel: '进行中', statusCls: 'badge-progress',
      plannerId: 'time-planner', plannerName: '时间测试企划', deadline: '2027-01-10',
      createdAt: '2026-01-01T10:00:00', updatedAt: '2041-01-01T10:00:00', tasks, logs: [] };
    const fixture = window.fileOverviewTimeFixture = {
      originalActions: Object.fromEntries(actionKeys.map(key => [key, e.actions[key]])),
      originalState: Object.fromEntries(stateKeys.map(key => [key, e.state[key]])),
      originalDetail: e.projectState.currentProjectDetail,
      appDisplay: document.getElementById('appContainer').style.display,
      loginDisplay: document.getElementById('loginPage').style.display,
      project, snapshot: JSON.stringify(project), previews: [], downloads: [], reads: [],
    };
    e.state.currentRole = 'designer'; e.state.currentUserId = 'time-designer';
    e.state.authUser = { userId: 'time-designer', role: 'designer', name: '时间测试设计师' };
    e.actions.hasPermission = () => false;
    e.actions.apiGet = async url => {
      fixture.reads.push(url);
      if (url === '/projects/75101') return project;
      return fixture.originalActions.apiGet(url);
    };
    e.actions.previewImage = (url, name) => { fixture.previews.push([new URL(url, location.origin).pathname, name]); };
    e.actions.openFilePreview = (url, name) => { fixture.previews.push([url, name]); };
    e.actions.showDownloadOptions = (...args) => { fixture.downloads.push(args); };
    document.getElementById('appContainer').style.display = '';
    document.getElementById('loginPage').style.display = 'none';
  });
  const detail = page.locator('#projectDetailModal');
  const overview = detail.locator('.project-file-overview');
  const groups = overview.locator('.project-file-group');
  const group = id => overview.locator(`.project-file-group[data-file-task-id="${id}"]`);
  const row = (id, name) => group(id).locator('.project-file-row').filter({ hasText: name });
  const names = id => group(id).locator('.project-file-row-title > strong').allTextContents();
  const expectTime = async (id, name, time, kind, label) => {
    const fileRow = row(id, name);
    assert.equal(await fileRow.getAttribute('data-file-time-kind'), kind, name);
    assert.equal(Number(await fileRow.getAttribute('data-file-timestamp')), Date.parse(time), name);
    assert.equal(await fileRow.getAttribute('data-file-time'), time, name);
    const displayed = fileRow.locator('time.project-file-time');
    assert.equal(await displayed.getAttribute('datetime'), new Date(time).toISOString(), name);
    assert.equal(await displayed.textContent(), label, name);
  };
  const settle = () => page.evaluate(async () => {
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    await Promise.all(document.getAnimations().filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime))
      .map(animation => animation.finished.catch(() => {})));
  });
  try {
    await page.evaluate(() => window.EMIE.actions.openProjectDetail(75101));
    await detail.waitFor({ state: 'visible' });
    assert.deepEqual(await groups.evaluateAll(nodes => nodes.map(node => node.dataset.fileTaskId)),
      ['75202', '75201', '75204', '75203', '75206', '75207', '75205'],
      '按组内最新真实文件时间排序，跨年与同秒分组顺序稳定，未知时间组最后');
    assert.equal(await groups.first().getAttribute('open'), '', '默认打开最新文件组');
    assert.equal(await groups.nth(1).getAttribute('open'), null);
    assert.deepEqual(await names(75201), ['当前修改要求.pdf', '驳回说明.pdf', '多源同文件.pdf', '更正成果.pdf',
      '同秒甲.pdf', '同秒乙.pdf', '驳回前提交.pdf', '历史修改要求.pdf', '仅上传文件.pdf', '初次成果.pdf', '无效时间.pdf', '未记录时间.pdf']);
    await expectTime(75201, '多源同文件.pdf', '2027-01-01T00:00:04+08:00', 'submitted', '提交于 2027-01-01 00:00:04');
    await expectTime(75201, '更正成果.pdf', '2027-01-01T00:00:04+08:00', 'submitted', '提交于 2027-01-01 00:00:04');
    await expectTime(75201, '当前修改要求.pdf', '2027-01-01T00:00:06+08:00', 'submitted', '提交于 2027-01-01 00:00:06');
    await expectTime(75201, '历史修改要求.pdf', '2027-01-01T00:00:02+08:00', 'submitted', '提交于 2027-01-01 00:00:02');
    await expectTime(75201, '驳回说明.pdf', '2027-01-01T00:00:05+08:00', 'submitted', '提交于 2027-01-01 00:00:05');
    await expectTime(75201, '驳回前提交.pdf', '2027-01-01T00:00:03+08:00', 'submitted', '提交于 2027-01-01 00:00:03');
    await expectTime(75201, '初次成果.pdf', '2026-12-31T23:59:59+08:00', 'submitted', '提交于 2026-12-31 23:59:59');
    await expectTime(75203, '旧版成果.pdf', '2026-12-31T23:59:58+08:00', 'submitted', '提交于 2026-12-31 23:59:58');
    await expectTime(75202, '最新文件.pdf', '2027-01-02T00:00:00Z', 'uploaded', '上传于 2027-01-02 08:00:00');
    const uploadOnly = row(75201, '仅上传文件.pdf');
    assert.equal(await uploadOnly.getAttribute('data-file-time-kind'), 'uploaded');
    assert.equal(await uploadOnly.locator('time').textContent(), '上传于 2027-01-01 00:00:01', '后端无时区时间按北京时间显示');
    await expectTime(75204, '多源同文件.pdf', '2027-01-01T00:00:05+08:00', 'uploaded', '上传于 2027-01-01 00:00:05');
    for (const [id, name] of [[75201, '无效时间.pdf'], [75201, '未记录时间.pdf'], [75204, '更正成果.pdf'],
      [75205, '组内未知甲.pdf'], [75205, '组内未知乙.pdf']]) {
      const unknown = row(id, name);
      assert.equal(await unknown.getAttribute('data-file-time-kind'), 'unknown', name);
      assert.equal(await unknown.getAttribute('data-file-timestamp'), '', name);
      assert.equal(await unknown.locator('.project-file-time').textContent(), '时间未记录', name);
      assert.equal(await unknown.locator('time').count(), 0, '无效或缺失时间不得制造datetime');
    }
    const unknownNames = await names(75205);
    assert.deepEqual(unknownNames, ['组内未知甲.pdf', '组内未知乙.pdf']);
    assert.equal(await page.evaluate(() => {
      const fixture = window.fileOverviewTimeFixture;
      fixture.project.tasks.forEach(task => window.EMIE.actions.collectTaskOverviewFiles(task));
      window.EMIE.actions.renderProjectDetailContent(fixture.project);
      return JSON.stringify(fixture.project) === fixture.snapshot;
    }), true, '收集和排序保留项目、子任务、交付和源文件原数组');

    await row(75202, '最新预览.png').getByRole('button', { name: /预览/ }).click();
    await row(75202, '最新文件.pdf').getByRole('button', { name: /预览/ }).click();
    await row(75202, '最新文件.pdf').getByRole('button', { name: /下载/ }).click();
    assert.deepEqual(await page.evaluate(() => window.fileOverviewTimeFixture.previews),
      [[`/api/files/download/${encodeURIComponent('time-最新预览.png')}`, '最新预览.png'],
        [`/api/files/download/${encodeURIComponent('time-最新文件.pdf')}`, '最新文件.pdf']]);
    assert.deepEqual(await page.evaluate(() => window.fileOverviewTimeFixture.downloads.at(-1)),
      [`/api/files/download/${encodeURIComponent('time-最新文件.pdf')}`, '最新文件.pdf', 42]);
    assert.deepEqual(await page.evaluate(() => window.fileOverviewTimeFixture.reads), ['/projects/75101']);

    await settle();
    await overview.screenshot({ path: '/tmp/emie-file-overview-times-desktop.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await settle();
    assert(await overview.evaluate(node => {
      const bounds = node.getBoundingClientRect();
      return bounds.left >= -1 && bounds.right <= innerWidth + 1 && node.scrollWidth <= node.clientWidth + 1;
    }), '文件总览及时间适配390px宽度');
    for (const fileRow of await groups.first().locator('.project-file-row').all()) {
      assert(await fileRow.evaluate(node => node.scrollWidth <= node.clientWidth + 1), '时间和操作按钮不得产生横向溢出');
    }
    await overview.screenshot({ path: '/tmp/emie-file-overview-times-mobile.png' });
  } finally {
    await page.evaluate(() => document.getElementById('projectDetailModal')?.remove());
    if (viewport) await page.setViewportSize(viewport);
    await page.unroute(fileRoute, serveImage);
    await page.evaluate(() => {
      const e = window.EMIE;
      const fixture = window.fileOverviewTimeFixture;
      Object.assign(e.actions, fixture.originalActions);
      Object.assign(e.state, fixture.originalState);
      e.projectState.currentProjectDetail = fixture.originalDetail;
      document.getElementById('appContainer').style.display = fixture.appDisplay;
      document.getElementById('loginPage').style.display = fixture.loginDisplay;
      delete window.fileOverviewTimeFixture;
    });
  }
  process.stdout.write('  file-overview-real-times-sort-fallback-shared-files-stability-and-mobile ok\n');
}
