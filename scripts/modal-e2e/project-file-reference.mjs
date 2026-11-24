import assert from 'node:assert/strict';

// Real form, picker and upload-list renderers; all project reads and writes are isolated.
export async function runProjectFileReferenceChecks(page) {
  const viewport = page.viewportSize();
  const image = Buffer.from('R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==', 'base64');
  const fileRoute = '**/api/files/**/ref-picker-*';
  const servePreview = route => route.fulfill({ contentType: 'image/gif', body: image });
  await page.route(fileRoute, servePreview);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(() => {
    const e = window.EMIE;
    const actionKeys = ['apiGet', 'apiPost', 'apiDelete', 'uploadFile', 'refreshAfterMutation', 'hasPermission', 'showSystemAlert', 'showDownloadOptions'];
    const stateKeys = ['users', 'currentRole', 'currentUserId', 'authUser'];
    const projectKeys = ['subTaskRefImages', 'subTaskAttachments', 'currentProjectDetail', 'formModified'];
    const file = (number, extension) => ({ name: `${extension === 'png' ? '引用图片' : '引用附件'}${number}.${extension}`,
      storedName: `ref-picker-${number}.${extension}`, url: `/api/files/download/ref-picker-${number}.${extension}`, size: 42 });
    const list = (...files) => JSON.stringify(files);
    const project = { id: 74101, name: '引用文件项目', referenceImagesJson: list(file(1, 'png'), file(2, 'png')),
      attachmentsJson: list(file(1, 'pdf')), tasks: [{ id: 74201, name: '现有包装', assigneeRole: 'designer',
        referenceImagesJson: list(file(1, 'png'), file(3, 'png')), attachmentsJson: list(file(2, 'pdf')),
        uploadedFiles: [file(7, 'png'), file(5, 'pdf'), { name: '恶意文件.pdf', url: 'javascript:alert(1)' }],
        deliveryVersions: [{ versionNo: 1, referenceImagesJson: list(file(4, 'png')), attachmentsJson: list(file(3, 'pdf')) }],
        rejectionRecords: [{ attemptNo: 1, rejectionReferenceImagesJson: list(file(5, 'png')),
          rejectionAttachmentsJson: list(file(4, 'pdf')) }], pendingChangeReferenceImagesJson: list(file(6, 'png')),
        pendingChangeAttachmentsJson: list(file(5, 'pdf')),
      }] };
    const fixture = window.projectFileReferenceFixture = {
      originalActions: Object.fromEntries(actionKeys.map(key => [key, e.actions[key]])),
      originalState: Object.fromEntries(stateKeys.map(key => [key, e.state[key]])),
      originalProjectState: Object.fromEntries(projectKeys.map(key => [key, e.projectState[key]])),
      project, projectSnapshot: JSON.stringify(project), reads: [], writes: [], deletes: [], uploads: [], alerts: [], downloads: [], mode: 'normal',
      draft: sessionStorage.getItem('design_pm_subtask_draft_74101'),
    };
    sessionStorage.removeItem('design_pm_subtask_draft_74101');
    e.state.users = { ...e.state.users, designer: [{ userId: 'reference-designer', name: '引用测试设计师' }] };
    e.state.currentRole = 'planner'; e.state.currentUserId = 'reference-planner';
    e.state.authUser = { userId: 'reference-planner', name: '引用测试企划', role: 'planner' };
    e.actions.hasPermission = () => true;
    e.actions.refreshAfterMutation = async () => {};
    e.actions.showSystemAlert = message => { fixture.alerts.push(message); };
    e.actions.showDownloadOptions = (...args) => { fixture.downloads.push(args); };
    e.actions.apiGet = async url => {
      fixture.reads.push(url);
      if (url === '/points/rules') return [{ ruleCode: 'REF_A', category: '引用测试', description: '引用测试任务', points: 1 }];
      if (url === '/projects/74101' || url === '/projects/74102') {
        if (fixture.mode === 'error') throw new Error('模拟项目读取失败');
        if (fixture.mode === 'hold') return new Promise(resolve => { fixture.releaseProject = resolve; });
        if (fixture.mode === 'legacy') return { id: 74101, tasks: [], attachmentsJson: JSON.stringify([
          { name: '原始路径文件.pdf', url: '/uploads/原始路径文件.pdf', size: 42 },
          { name: '同名回退文件.pdf', url: '/api/files/download-by-original?name=%E5%90%8C%E5%90%8D%E5%9B%9E%E9%80%80%E6%96%87%E4%BB%B6.pdf', size: 42 },
          { name: '稳定历史文件.pdf', url: '/api/files/download/ref-picker-legacy-stable.pdf', size: 42 },
          { name: '同名规范.pdf', storedName: 'ref-picker-project-a.pdf', url: '/api/files/download/ref-picker-project-a.pdf', size: 42 },
          { name: '同名规范.pdf', storedName: 'ref-picker-project-b.pdf', url: '/api/files/download/ref-picker-project-b.pdf', size: 42 },
        ]) };
        if (fixture.mode === 'empty' || url === '/projects/74102') return { id: Number(url.split('/').at(-1)), tasks: [] };
        if (fixture.mode === 'mismatch') return { ...fixture.project, id: 74999 };
        return fixture.project;
      }
      return fixture.originalActions.apiGet(url);
    };
    e.actions.apiPost = async (url, body) => { fixture.writes.push({ url, body }); };
    e.actions.apiDelete = async url => { fixture.deletes.push(url); };
    e.actions.uploadFile = async file => { fixture.uploads.push(file.name); throw new Error('引用不得重新上传'); };
  });
  const add = page.locator('#addSubTaskModal');
  const picker = page.locator('#projectFilePickerModal');
  const items = picker.locator('label.project-file-picker-item');
  const search = picker.getByRole('searchbox', { name: '搜索项目文件' });
  const row = name => items.filter({ hasText: name });
  const open = async kind => {
    await add.locator(`[data-emie-action="click:task-reference-project-files"][data-kind="${kind}"]`).click();
    await picker.waitFor({ state: 'visible' });
  };
  const confirm = async () => {
    await picker.locator('[data-emie-action="click:project-file-picker-confirm"]').click();
    await picker.waitFor({ state: 'detached' });
  };
  const closePicker = async () => {
    await picker.locator('[data-emie-action="click:project-file-picker-close"]').first().click();
    await picker.waitFor({ state: 'detached' });
  };
  const attemptCheck = async checkbox => {
    if (!(await checkbox.isDisabled())) await checkbox.click();
  };
  try {
    await page.evaluate(() => window.EMIE.actions.addSubTask(74101));
    await add.waitFor({ state: 'visible' });
    await add.locator('[name="name"]').fill('引用项目文件的新子任务');
    await add.locator('[name="workflowStage"]').selectOption('design');
    await add.locator('[name="plannedDate"]').fill(new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10));
    await add.locator('[name="designerId"]').selectOption('reference-designer');
    await add.locator('[data-point-rule-code="REF_A"]').click();
    await open('reference');
    await items.first().waitFor();
    assert.equal(await items.count(), 7, '图片模式汇总并去重项目、子任务及历次记录中的图片');
    assert.doesNotMatch(await picker.locator('[data-project-file-list]').innerText(), /引用附件|恶意文件/);
    assert.match(await row('引用图片1.png').innerText(), /项目|参考/);
    await search.fill('引用图片1');
    await row('引用图片1.png').getByRole('checkbox').check();
    await search.fill('现有包装');
    assert(await items.count() >= 1, '支持根据子任务来源搜索');
    await search.clear();
    assert.equal(await row('引用图片1.png').getByRole('checkbox').isChecked(), true, '搜索切换保留多选');
    const keyboardChoice = row('引用图片2.png').getByRole('checkbox');
    await keyboardChoice.focus();
    await page.keyboard.press('Space');
    assert.equal(await keyboardChoice.isChecked(), true, '支持键盘勾选引用文件');
    assert.equal(await keyboardChoice.evaluate(input => document.activeElement === input), true, '勾选重绘后保留键盘焦点');
    for (let index = 3; index <= 6; index += 1) await row(`引用图片${index}.png`).getByRole('checkbox').check();
    await attemptCheck(row('引用图片7.png').getByRole('checkbox'));
    assert.equal(await picker.locator('input[type="checkbox"]:checked').count(), 6, '图片引用遵守6张上限');
    await confirm();
    assert.equal(await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').count(), 6);
    await open('reference');
    await items.first().waitFor();
    assert.equal(await row('引用图片1.png').getByRole('checkbox').isDisabled(), true, '已引用文件不能重复添加');
    await closePicker();
    await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').first().click();
    assert.equal(await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').count(), 5);

    await open('attachment');
    await items.first().waitFor();
    assert.equal(await row('引用附件5.pdf').count(), 1, '当前修改附件和文件归档按存储标识去重');
    assert.doesNotMatch(await picker.locator('[data-project-file-list]').innerText(), /恶意文件/);
    for (let index = 1; index <= 5; index += 1) await row(`引用附件${index}.pdf`).getByRole('checkbox').check();
    await attemptCheck(row('引用图片1.png').getByRole('checkbox'));
    assert.equal(await picker.locator('input[type="checkbox"]:checked').count(), 5, '附件引用遵守5个上限');
    await confirm();
    assert.equal(await add.locator('#createAttachmentList .remove-file').count(), 5);
    await add.locator('#createAttachmentList .file-item').filter({ hasText: '引用附件5.pdf' }).locator('.remove-file').click();
    await open('attachment');
    await row('引用附件5.pdf').getByRole('checkbox').waitFor();
    assert.equal(await row('引用附件5.pdf').getByRole('checkbox').isDisabled(), false, '移除引用后原文件仍可再次引用');
    await row('引用附件5.pdf').getByRole('checkbox').check();
    await confirm();
    assert.equal(await page.evaluate(() => window.projectFileReferenceFixture.uploads.length), 0);
    assert.equal(await page.evaluate(() => window.projectFileReferenceFixture.deletes.length), 0);
    assert.equal(await page.evaluate(() => JSON.stringify(window.projectFileReferenceFixture.project)
      === window.projectFileReferenceFixture.projectSnapshot), true, '移除或重新引用不修改源项目文件');
    await add.locator('[data-emie-action="click:task-submit-add"]').click();
    await add.waitFor({ state: 'detached' });
    const sent = await page.evaluate(() => window.projectFileReferenceFixture.writes.at(-1));
    assert.equal(sent.url, '/projects/74101/tasks');
    assert.deepEqual(JSON.parse(sent.body.referenceImagesJson).map(file => file.storedName).sort(),
      Array.from({ length: 5 }, (_, index) => `ref-picker-${index + 2}.png`));
    assert.deepEqual(JSON.parse(sent.body.attachmentsJson).map(file => file.storedName).sort(),
      Array.from({ length: 5 }, (_, index) => `ref-picker-${index + 1}.pdf`));
    assert(JSON.parse(sent.body.attachmentsJson).every(file => file.url === `/api/files/download/${file.storedName}`));

    // A nested picker is cancellable without losing the parent form or its focus.
    await page.evaluate(() => window.EMIE.actions.addSubTask(74101));
    await add.waitFor({ state: 'visible' });
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'legacy'; });
    await open('attachment');
    await row('稳定历史文件.pdf').getByRole('checkbox').waitFor();
    assert.equal(await items.count(), 3, '只保留具有稳定存储标识的历史文件');
    assert.doesNotMatch(await picker.locator('[data-project-file-list]').innerText(), /原始路径文件|同名回退文件/,
      '原始文件名回退不能保证项目归属，不提供为引用候选');
    assert.equal(await row('同名规范.pdf').count(), 2, '相同显示名但不同存储标识应保留为两个文件');
    await row('稳定历史文件.pdf').getByRole('checkbox').check();
    await row('同名规范.pdf').getByRole('checkbox').nth(0).check();
    await row('同名规范.pdf').getByRole('checkbox').nth(1).check();
    await confirm();
    const originalUrl = '/api/files/download/ref-picker-legacy-stable.pdf';
    assert.equal(await page.evaluate(() => window.EMIE.projectState.subTaskAttachments[0].url), originalUrl,
      '稳定历史URL-only文件引用后保持正确下载地址');
    const legacyDownload = add.locator('#createAttachmentList .file-item').filter({ hasText: '稳定历史文件.pdf' })
      .locator('[data-emie-action="click:detail-file-download"]');
    assert.equal(await legacyDownload.getAttribute('data-file-url'), originalUrl);
    await legacyDownload.click();
    assert.deepEqual(await page.evaluate(() => window.projectFileReferenceFixture.downloads.at(-1)),
      [originalUrl, '稳定历史文件.pdf', 42]);
    await open('attachment');
    await row('稳定历史文件.pdf').getByRole('checkbox').waitFor();
    assert.equal(await row('稳定历史文件.pdf').getByRole('checkbox').isDisabled(), true,
      '稳定历史URL-only文件重复打开时仍识别为已引用');
    assert.deepEqual(await row('同名规范.pdf').getByRole('checkbox').evaluateAll(inputs => inputs.map(input => input.disabled)), [true, true]);
    await closePicker();
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'normal'; });
    await add.locator('[name="name"]').fill('取消引用后保留的表单');
    await open('reference');
    await items.first().waitFor();
    await row('引用图片1.png').getByRole('checkbox').check();
    await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })));
    await picker.waitFor({ state: 'detached' });
    assert.equal(await add.locator('[name="name"]').inputValue(), '取消引用后保留的表单');
    assert.equal(await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').count(), 0, '取消不添加临时勾选');
    await page.waitForFunction(() => document.activeElement?.closest('#addSubTaskModal'));
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'error'; });
    await open('attachment');
    await picker.locator('[data-project-file-message]').filter({ hasText: /失败/ }).waitFor();
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'normal'; });
    await picker.locator('[data-emie-action="click:project-file-picker-retry"]').click();
    await items.first().waitFor();
    await closePicker();
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'empty'; });
    await open('attachment');
    await picker.locator('[data-project-file-message]').filter({ hasText: /暂无|没有|无可/ }).waitFor();
    assert.equal(await items.count(), 0);
    await closePicker();
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'mismatch'; });
    await open('attachment');
    await picker.locator('[data-project-file-message]').filter({ hasText: /失败|不匹配|项目/ }).waitFor();
    assert.equal(await items.count(), 0, '不接受其他项目的文件响应');
    await closePicker();

    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'normal'; });
    await open('reference');
    await items.first().waitFor();
    await page.evaluate(() => { document.getElementById('addSubTaskModal').dataset.projectId = '74102'; });
    await picker.waitFor({ state: 'detached' });
    await page.evaluate(() => { document.getElementById('addSubTaskModal').dataset.projectId = '74101'; });
    assert.equal(await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').count(), 0,
      '父表单切换项目后取消原选择器');
    await page.setViewportSize({ width: 390, height: 667 });
    await open('attachment');
    await items.first().waitFor();
    await picker.getByRole('dialog').evaluate(node => Promise.all(node.getAnimations().map(animation => animation.finished.catch(() => {}))));
    const bounds = await picker.getByRole('dialog').boundingBox();
    assert(bounds.x >= -1 && bounds.x + bounds.width <= 391, '引用选择器适配手机宽度');
    assert(await picker.getByRole('dialog').evaluate(node => node.scrollWidth <= node.clientWidth + 1));
    await closePicker();
    await page.setViewportSize({ width: 1280, height: 800 });

    // A late read from a closed parent must not contaminate a later project form.
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'hold'; });
    await open('reference');
    await page.waitForFunction(() => typeof window.projectFileReferenceFixture.releaseProject === 'function');
    await page.evaluate(() => window.EMIE.actions.closeM('addSubTaskModal', true));
    await add.waitFor({ state: 'detached' });
    await picker.waitFor({ state: 'detached' });
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'empty'; });
    await page.evaluate(() => window.EMIE.actions.addSubTask(74101));
    await add.waitFor({ state: 'visible' });
    await open('reference');
    await picker.locator('[data-project-file-message]').filter({ hasText: /暂无|没有|无可/ }).waitFor();
    await page.evaluate(() => {
      const fixture = window.projectFileReferenceFixture;
      fixture.releaseProject(fixture.project);
    });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await items.count(), 0, '同项目的新表单不能被旧表单的迟到读取覆盖');
    await closePicker();
    await page.evaluate(() => window.EMIE.actions.closeM('addSubTaskModal', true));
    await add.waitFor({ state: 'detached' });
    await page.evaluate(() => { window.projectFileReferenceFixture.mode = 'normal'; });
    await page.evaluate(() => window.EMIE.actions.addSubTask(74102));
    await add.waitFor({ state: 'visible' });
    await open('reference');
    await picker.locator('[data-project-file-message]').filter({ hasText: /暂无|没有|无可/ }).waitFor();
    assert.equal(await items.count(), 0, '新项目表单不能沿用旧项目的迟到响应');
    assert.equal(await add.locator('#createRefImageList [data-emie-action="click:upload-remove-file"]').count(), 0);
    assert.equal(await page.evaluate(() => window.projectFileReferenceFixture.uploads.length), 0);
    assert.equal(await page.evaluate(() => window.projectFileReferenceFixture.deletes.length), 0);
    await closePicker();
  } finally {
    if (viewport) await page.setViewportSize(viewport);
    await page.unroute(fileRoute, servePreview);
    await page.evaluate(() => {
      const e = window.EMIE;
      const fixture = window.projectFileReferenceFixture;
      fixture.releaseProject?.({ id: 74101, tasks: [] });
      ['projectFilePickerModal', 'addSubTaskModal'].forEach(id => document.getElementById(id)?.remove());
      Object.assign(e.actions, fixture.originalActions);
      Object.assign(e.state, fixture.originalState);
      Object.assign(e.projectState, fixture.originalProjectState);
      if (fixture.draft == null) sessionStorage.removeItem('design_pm_subtask_draft_74101');
      else sessionStorage.setItem('design_pm_subtask_draft_74101', fixture.draft);
      delete window.projectFileReferenceFixture;
    });
  }
  process.stdout.write('  project-file-reference-filter-search-dedup-limits-save-cancel-errors-and-stale-parent ok\n');
}
