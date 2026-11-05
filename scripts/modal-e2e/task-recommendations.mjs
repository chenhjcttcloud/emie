import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), 'UI 模拟回归仅允许本地测试地址');
const screenshot = process.env.EMIE_RECOMMENDATION_SCREENSHOT || '/tmp/emie-task-recommendations-ui.png';
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
let executablePath = process.env.CHROME_BIN;
if (!executablePath) { try { await fs.access(macChrome); executablePath = macChrome; } catch { /* use Playwright browser */ } }
const browser = await chromium.launch({ headless: true, executablePath });
const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
const page = await context.newPage();
const errors = [], httpRequests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => {
  const url = new URL(request.url());
  if (url.pathname.startsWith('/api/')) httpRequests.push(url.pathname);
});

// All API traffic is intercepted. This script never reads or changes real business records.
await page.route('**/api/**', async route => {
  if (new URL(route.request().url()).pathname.startsWith('/api/files/')) {
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="160"><rect width="240" height="160" fill="#e8eee3"/><circle cx="120" cy="80" r="45" fill="#73825b"/></svg>' });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  await page.route('**/js/*.js*', async route => {
    const name = path.basename(new URL(route.request().url()).pathname);
    await route.fulfill({ contentType: 'text/javascript', body: await fs.readFile(new URL(`../../src/main/resources/static/js/${name}`, import.meta.url), 'utf8') });
  });
  await page.route('**/css/*.css*', async route => {
    const name = path.basename(new URL(route.request().url()).pathname);
    await route.fulfill({ contentType: 'text/css', body: await fs.readFile(new URL(`../../src/main/resources/static/css/${name}`, import.meta.url), 'utf8') });
  });
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready]').waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.EMIE?.modules?.dashboardDesigner && window.EMIE?.modules?.projectDetail && window.EMIE?.modules?.businessAssistant);
  await page.evaluate(() => {
    const e = window.EMIE;
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    const marker = document.createElement('div'); marker.id = 'recommendationSimulationMarker';
    marker.textContent = 'UI 模拟 · 子任务详情（已移除推荐）';
    marker.style.cssText = 'position:fixed;top:12px;left:12px;z-index:900;padding:9px 14px;background:#fff6dc;border:1px solid #ead8a4;border-radius:8px;color:#856424;font:12px sans-serif';
    document.body.appendChild(marker);
    e.state.currentUserId = 'ui-simulation-designer';
    e.state.authUser = { userId: 'ui-simulation-designer', name: 'UI模拟设计师' };
    const task = {
      id: 91001, projectId: 92001, name: 'UI模拟 · 春日系列手机挂链设计', projectName: 'UI模拟 · 森林伙伴产品企划',
      details: '延续自然、温暖的配色与手绘风格，结合小动物、野餐和植物元素。', deliverables: 'UI模拟 · 已完成挂链设计初稿',
      status: 'accepted', designerId: 'ui-simulation-designer', designerName: 'UI模拟设计师', publisherName: 'UI模拟产品企划', plannedDate: '2026-11-20',
      referenceImagesJson: JSON.stringify([{ storedName: 'ui-simulation-reference.png', name: 'UI模拟参考图.png', size: 1024 }]),
      attachmentsJson: JSON.stringify([{ storedName: 'ui-simulation-delivery.pdf', name: 'UI模拟交付说明.pdf', size: 2048 }]),
      rejectionRecords: [], deliveryVersions: [],
    };
    e.dashboardState.designerTaskCache = [task];
    e.projectState.currentProjectDetail = { id: task.projectId, name: task.projectName, tasks: [task] };
    const f = window.recommendationFixture = { task, gets: [], posts: [] };
    e.actions.apiGet = async url => {
      f.gets.push(url);
      if (url === '/image-library') return [];
      throw new Error(`UI模拟禁止访问真实接口：${url}`);
    };
    e.actions.apiPost = async (url, body) => {
      f.posts.push({ url, body });
      throw new Error(`UI模拟禁止修改真实接口：${url}`);
    };
    document.getElementById('mainContent').innerHTML = '<h1>UI 模拟 · 子任务详情</h1><p>本页面不读取、不写入真实任务或图档。</p>';
  });

  const entryPoints = [
    { id: 'publishedSubTaskDetailModal', action: 'openPublishedSubTaskDetail', close: 'designer-detail-close' },
    { id: 'projectSubTaskDetailModal', action: 'openProjectSubTaskDetail', close: 'detail-subtask-close' },
  ];
  for (const role of ['designer', 'planner', 'admin']) {
    for (const entry of entryPoints) {
      for (const closeFrom of ['.modal-header', '.modal-footer']) {
        await page.evaluate(({ role, action }) => {
          const e = window.EMIE; e.state.currentRole = role;
          const id = window.recommendationFixture.task.id;
          if (action === 'openProjectSubTaskDetail') e.actions[action](null, id);
          else e.actions[action](id);
        }, { role, action: entry.action });
        const detail = page.locator(`#${entry.id}`);
        await detail.waitFor({ state: 'visible' });
        const text = await detail.innerText();
        assert.match(text, /子任务详情 · UI模拟 · 春日系列手机挂链设计/);
        assert.match(text, /延续自然、温暖的配色与手绘风格/);
        assert.match(text, /UI模拟交付说明\.pdf/);
        assert.match(text, /UI模拟 · 已完成挂链设计初稿/);
        assert.doesNotMatch(text, /AI\s*推荐|推荐图档|更新推荐/);
        assert.equal(await detail.locator('[data-task-recommendations], .task-recommendations, .task-recommendation-card').count(), 0);
        const imageSelector = `#${entry.id} img[alt="UI模拟参考图.png"]`;
        await page.locator(imageSelector).scrollIntoViewIfNeeded();
        await page.waitForFunction(selector => document.querySelector(selector)?.dataset.authLoaded === 'true', imageSelector);
        assert.equal(await detail.locator('[data-emie-action="click:detail-file-download"][data-file-name="UI模拟交付说明.pdf"]').count(), 1);
        if (role === 'designer' && entry.id === 'publishedSubTaskDetailModal' && closeFrom === '.modal-header') {
          await page.evaluate(async () => {
            const animations = document.getAnimations().filter(animation => animation.effect?.getComputedTiming().endTime !== Infinity);
            await Promise.all(animations.map(animation => animation.finished.catch(() => {})));
          });
          await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });
        }
        await detail.locator(`${closeFrom} [data-emie-action="click:${entry.close}"]`).click();
        await detail.waitFor({ state: 'detached' });
      }
    }
  }

  for (const role of ['designer', 'admin']) {
    await page.evaluate(async role => { const e = window.EMIE; e.state.currentRole = role; await e.actions.renderImageLibrary(document.getElementById('mainContent')); }, role);
    assert.equal(await page.locator('[data-emie-action="click:visual-ai-settings-open"]').count(), 0, `retired image-analysis settings must not appear for ${role}`);
    assert.equal(await page.locator('#visualAiSettingsModal').count(), 0);
  }
  assert.equal(await page.evaluate(() => typeof window.EMIE.actions.openVisualAiSettings), 'undefined');
  assert.equal(await page.locator('[data-task-recommendations]').count(), 0);
  const actionRequests = await page.evaluate(() => [...window.recommendationFixture.gets, ...window.recommendationFixture.posts.map(entry => entry.url)]);
  assert.deepEqual(actionRequests.filter(url => /image-recommendations|visual-ai-config/.test(url)), [], 'details must not call recommendation or retired AI-setting actions');
  assert.deepEqual(httpRequests.filter(url => /image-recommendations|visual-ai-config/.test(url)), [], 'details must not request recommendation or retired AI-setting endpoints');
  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  console.log(`subtask detail browser checks passed: recommendations removed; UI simulation screenshot: ${screenshot}`);
} catch (error) {
  await page.screenshot({ path: '/tmp/emie-task-recommendations-failure.png', fullPage: true }).catch(() => {});
  throw error;
} finally {
  await context.close(); await browser.close();
}
