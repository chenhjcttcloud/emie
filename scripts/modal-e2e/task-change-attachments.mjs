import assert from 'node:assert/strict';

// Exercise the real upload, detail and history UI without writing business data.
export async function runTaskChangeAttachmentChecks(page) {
  const initialViewport = page.viewportSize();
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(() => {
    const e = window.EMIE;
    const actionKeys = ['apiGet', 'apiPost', 'uploadFile', 'refreshAfterMutation', 'showSystemAlert', 'showDownloadOptions'];
    const stateKeys = ['currentRole', 'currentUserId', 'authUser'];
    const projectKeys = ['currentProjectDetail', 'changeBonusImages', 'changeBonusAttachments', 'uploadingCount'];
    const fixture = window.changeAttachmentFixture = {
      originalActions: Object.fromEntries(actionKeys.map(key => [key, e.actions[key]])),
      originalState: Object.fromEntries(stateKeys.map(key => [key, e.state[key]])),
      originalProjectState: Object.fromEntries(projectKeys.map(key => [key, e.projectState[key]])),
      designerTasks: e.dashboardState.designerTaskCache,
      writes: [], alerts: [], uploads: [], downloads: [], holdUpload: true,
      task: { id: 73201, projectId: 73101, name: '附件回归子任务', supportsChangeTiers: true },
    };
    e.state.currentRole = 'planner';
    e.state.currentUserId = 'attachment-planner';
    e.state.authUser = { userId: 'attachment-planner', name: '附件测试企划', role: 'planner' };
    e.projectState.uploadingCount = 0;
    e.actions.apiGet = async url => url === '/projects/73101' ? { tasks: [fixture.task] }
      : url === '/points/rules' ? [] : fixture.originalActions.apiGet(url);
    e.actions.apiPost = async (url, body) => { fixture.writes.push({ url, body }); };
    e.actions.refreshAfterMutation = async () => {};
    e.actions.showSystemAlert = message => { fixture.alerts.push(message); };
    e.actions.showDownloadOptions = (...args) => { fixture.downloads.push(args); };
    e.actions.uploadFile = async file => {
      fixture.uploads.push(file.name);
      if (fixture.holdUpload) await new Promise(resolve => { fixture.releaseUpload = resolve; });
      return { name: file.name, storedName: `change-${file.name}`, size: file.size,
        url: `/api/files/download/change-${file.name}` };
    };
  });
  const modal = page.locator('#taskChangeBonusModal');
  const files = modal.locator('#changeBonusAttachmentList .file-item');
  const pdf = { name: '修改说明.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 attachment regression') };
  try {
    await page.evaluate(() => window.EMIE.actions.openTaskChangeBonus(73101, 73201));
    await modal.waitFor({ state: 'visible' });
    const input = modal.locator('#changeBonusAttachmentInput');
    assert.match(await input.getAttribute('accept'), /\.pdf/);
    assert.match(await input.getAttribute('accept'), /\.docx/);
    assert.match(await input.getAttribute('accept'), /\.zip/);
    await modal.locator('[name="reason"]').fill('按照附件调整包装');
    await input.setInputFiles(pdf);
    await page.waitForFunction(() => window.EMIE.projectState.uploadingCount === 1);
    await modal.getByRole('button', { name: '发起修改', exact: true }).click();
    assert.equal(await page.evaluate(() => window.changeAttachmentFixture.writes.length), 0,
      '附件尚未上传完时不得发起修改');
    assert.match(await page.evaluate(() => window.changeAttachmentFixture.alerts.at(-1)), /文件正在上传中/);
    await page.evaluate(() => {
      window.changeAttachmentFixture.holdUpload = false;
      window.changeAttachmentFixture.releaseUpload();
    });
    await files.first().waitFor();
    await files.first().locator('[data-emie-action="click:upload-remove-file"]').click();
    assert.equal(await files.count(), 0, '移除附件同步清空列表');
    await input.setInputFiles(pdf);
    await files.first().waitFor();
    await input.evaluate(input => {
      const transfer = new DataTransfer();
      transfer.items.add(new File(['change notes'], '补充要求.docx', {
        type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      }));
      input.closest('.upload-area').dispatchEvent(new DragEvent('drop', {
        bubbles: true, cancelable: true, dataTransfer: transfer,
      }));
    });
    await page.waitForFunction(() => window.EMIE.projectState.changeBonusAttachments.length === 2
      && window.EMIE.projectState.uploadingCount === 0);
    assert.equal(await files.count(), 2, '拖放附件与选择文件使用相同列表');
    await input.setInputFiles(Array.from({ length: 4 }, (_, index) => ({
      name: `超限附件${index}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('%PDF-limit'),
    })));
    assert.match(await page.evaluate(() => window.changeAttachmentFixture.alerts.at(-1)), /最多上传5个/);
    assert.equal(await files.count(), 2, '超限选择不应影响现有附件');
    await modal.getByRole('button', { name: '发起修改', exact: true }).click();
    await modal.waitFor({ state: 'detached' });
    const sent = await page.evaluate(() => window.changeAttachmentFixture.writes.at(-1));
    assert.equal(sent.url, '/projects/73101/subtasks/73201/change-bonus');
    assert.deepEqual(JSON.parse(sent.body.changeAttachmentsJson).map(file => file.name), ['修改说明.pdf', '补充要求.docx']);
    assert.equal(sent.body.changeReferenceImagesJson, '[]', '附件不能混入参考图片');
    assert.equal(sent.body.changeTier, 'small');

    // Legacy scoring uses the same attachment lifecycle and starts with a clean list.
    await page.evaluate(() => { window.changeAttachmentFixture.task.supportsChangeTiers = false; });
    await page.evaluate(() => window.EMIE.actions.openTaskChangeBonus(73101, 73201));
    await modal.waitFor({ state: 'visible' });
    assert.equal(await files.count(), 0, '再次打开不带入上次附件');
    await modal.locator('[name="reason"]').fill('历史任务补充修改要求');
    await modal.locator('#changeBonusAttachmentInput').setInputFiles(pdf);
    await files.first().waitFor();
    await modal.getByRole('button', { name: '发起修改', exact: true }).click();
    await modal.waitFor({ state: 'detached' });
    const legacy = await page.evaluate(() => window.changeAttachmentFixture.writes.at(-1).body);
    assert.equal(legacy.withPoints, false);
    assert.deepEqual(JSON.parse(legacy.changeAttachmentsJson).map(file => file.name), ['修改说明.pdf']);

    await page.evaluate(() => {
      const e = window.EMIE;
      const attachment = (name, storedName) => ({ name, storedName, size: 42,
        url: `/api/files/download/${storedName}` });
      const task = { ...window.changeAttachmentFixture.task, status: 'revision_requested',
        designerId: 'attachment-designer', designerName: '附件测试设计师', assigneeRole: 'designer',
        projectName: '修改附件回归项目', plannedDate: '2026-11-30', rejectionRecords: [],
        reviewComments: '请根据本轮附件修改', pendingChangeBonusRequestId: 'pending-attachment-request',
        pendingChangeAttachmentsJson: JSON.stringify([attachment('当前修改要求.docx', 'current-change.docx')]),
        deliveryVersions: [{ id: 73302, versionNo: 2, submissionType: 'redelivery', submissionOrigin: 'revision',
          revisionNo: 1, changeReason: '上一轮修改依据', deliverables: '上一轮成果',
          changeAttachmentsJson: JSON.stringify([attachment('历史修改要求.pdf', 'history-change.pdf')]) },
        { id: 73301, versionNo: 1, submissionType: 'initial', submissionOrigin: 'initial', deliverables: '首次成果' }],
      };
      e.state.currentRole = 'designer'; e.state.currentUserId = 'attachment-designer';
      e.state.authUser = { userId: 'attachment-designer', role: 'designer', name: '附件测试设计师' };
      e.dashboardState.designerTaskCache = [task];
      e.projectState.currentProjectDetail = { id: task.projectId, status: 'in_progress', tasks: [task] };
    });
    for (const entry of ['published', 'project']) {
      await page.evaluate(entry => entry === 'published'
        ? window.EMIE.actions.openPublishedSubTaskDetail(73201, { readOnly: true })
        : window.EMIE.actions.openProjectSubTaskDetail(null, 73201), entry);
      const detail = page.locator(entry === 'published' ? '#publishedSubTaskDetailModal' : '#projectSubTaskDetailModal');
      await detail.waitFor({ state: 'visible' });
      const feedback = detail.locator('.task-detail-feedback');
      assert.match(await feedback.innerText(), /当前修改要求\.docx/);
      await feedback.locator('[data-emie-action="click:detail-file-download"]').click();
      assert.deepEqual(await page.evaluate(() => window.changeAttachmentFixture.downloads.at(-1)),
        ['/api/files/download/current-change.docx', '当前修改要求.docx', 42]);
      const latestRound = detail.locator('.task-detail-history .delivery-round').first();
      await latestRound.locator(':scope > summary').click();
      const requirement = latestRound.locator('.is-requirement');
      assert.match(await requirement.innerText(), /历史修改要求\.pdf/);
      assert.doesNotMatch(await requirement.innerText(), /当前修改要求/,
        '历史修改依据不能被下一次发起修改的附件覆盖');
      await requirement.locator('[data-emie-action="click:detail-file-download"]').click();
      assert.deepEqual(await page.evaluate(() => window.changeAttachmentFixture.downloads.at(-1)),
        ['/api/files/download/history-change.pdf', '历史修改要求.pdf', 42]);
      await detail.locator('.modal-header .modal-close').click();
      await detail.waitFor({ state: 'detached' });
    }
  } finally {
    if (initialViewport) await page.setViewportSize(initialViewport);
    await page.evaluate(() => {
      const e = window.EMIE;
      const fixture = window.changeAttachmentFixture;
      fixture.releaseUpload?.();
      Object.assign(e.actions, fixture.originalActions);
      Object.assign(e.state, fixture.originalState);
      Object.assign(e.projectState, fixture.originalProjectState);
      e.dashboardState.designerTaskCache = fixture.designerTasks;
      ['taskChangeBonusModal', 'publishedSubTaskDetailModal', 'projectSubTaskDetailModal'].forEach(id => document.getElementById(id)?.remove());
      delete window.changeAttachmentFixture;
    });
  }
  process.stdout.write('  task-change-attachments-upload-delete-drop-limit-and-history ok\n');
}
