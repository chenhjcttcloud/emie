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
const errors = [];
page.on('pageerror', error => errors.push(error.message));

function simulationImage(index) {
  const backgrounds = ['#f5ead7', '#e8eee3', '#f1e4e7', '#e4edf0', '#f3e8c8', '#e7e4f0', '#e7efe9', '#f4e6de'];
  const accent = ['#73825b', '#b57b5f', '#8d779c', '#578d95', '#cd9a4a', '#8783a0', '#6d9981', '#b87f65'][index % 8];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="480" viewBox="0 0 480 480"><rect width="480" height="480" fill="${backgrounds[index % 8]}"/><rect x="52" y="52" width="376" height="376" rx="188" fill="#fff" fill-opacity=".32"/><path d="M68 368Q240 330 412 368" fill="none" stroke="${accent}" stroke-width="4"/><path d="M109 341V173m0 0-39 57m39-57 38 57m-38-17-30 46m30-46 31 46M372 345V200m0 0-30 48m30-48 31 48" fill="none" stroke="${accent}" stroke-width="7" stroke-linecap="round"/><ellipse cx="245" cy="302" rx="69" ry="64" fill="#faf7f0"/><ellipse cx="218" cy="185" rx="18" ry="56" fill="#faf7f0" transform="rotate(-12 218 185)"/><ellipse cx="269" cy="185" rx="18" ry="56" fill="#faf7f0" transform="rotate(12 269 185)"/><ellipse cx="244" cy="249" rx="59" ry="49" fill="#faf7f0"/><circle cx="225" cy="246" r="4" fill="#594b42"/><circle cx="261" cy="246" r="4" fill="#594b42"/><path d="M239 262h10l-5 6z" fill="#bf8f85"/><path d="M218 298h53v46h-53z" fill="${accent}"/><path d="M218 305h53" stroke="#fff" stroke-opacity=".5" stroke-width="2"/><circle cx="160" cy="125" r="14" fill="${accent}" fill-opacity=".5"/><circle cx="321" cy="120" r="5" fill="${accent}"/><path d="M326 307l19-25 19 25-19 25z" fill="${accent}" fill-opacity=".7"/><text x="240" y="405" text-anchor="middle" fill="${accent}" font-family="sans-serif" font-size="14" letter-spacing="5">FOREST COLLECTION ${index + 1}</text></svg>`;
}

// All API traffic is intercepted. This script never reads or changes real business records.
await page.route('**/api/**', async route => {
  const url = new URL(route.request().url());
  if (url.pathname.startsWith('/api/files/')) {
    const index = Number(url.pathname.match(/simulation-(\d+)/)?.[1] || 1);
    return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: simulationImage(index - 1) });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
});
if (process.env.EMIE_E2E_LOCAL_STATIC === '1') {
  await page.route('**/js/*.js*', async route => {
    const name = path.basename(new URL(route.request().url()).pathname);
    await route.fulfill({ contentType: 'text/javascript', body: await fs.readFile(path.resolve('src/main/resources/static/js', name), 'utf8') });
  });
  await page.route('**/css/*.css*', async route => {
    const name = path.basename(new URL(route.request().url()).pathname);
    await route.fulfill({ contentType: 'text/css', body: await fs.readFile(path.resolve('src/main/resources/static/css', name), 'utf8') });
  });
}

try {
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await page.locator('html[data-app-ready]').waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => window.EMIE?.modules?.taskRecommendations && window.EMIE?.modules?.businessAssistant);
  await page.evaluate(() => {
    const e = window.EMIE;
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    const marker = document.createElement('div'); marker.id = 'recommendationSimulationMarker';
    marker.textContent = 'UI 模拟 · 所有图片与数据仅用于界面验证';
    marker.style.cssText = 'position:fixed;top:12px;left:12px;z-index:900;padding:9px 14px;background:#fff6dc;border:1px solid #ead8a4;border-radius:8px;color:#856424;font:12px sans-serif;box-shadow:0 2px 10px #0000000a';
    document.body.appendChild(marker);
    e.state.currentRole = 'designer'; e.state.currentUserId = 'ui-simulation-designer';
    e.state.authUser = { userId: 'ui-simulation-designer', name: 'UI模拟设计师' };
    const names = ['森林野餐 · 主视觉', '森林伙伴 · 角色组合', '自然日记 · 春日配色', '林间散步 · 连续纹样', '午后野餐 · 包装应用', '森林来信 · 标签设计', '手绘小屋 · 元素合集', '户外假日 · 场景插画', 'UI模拟 · 额外图档'];
    const items = names.map((name, index) => ({ id: index + 1, libraryItemId: index + 1, name, ipName: '森林伙伴', subOptions: ['春日系列'], reasons: [index % 2 ? '图档标签与需求相关：自然、野餐' : '同系列：森林伙伴 / 春日系列', '与任务的挂链设计需求相关'], score: 90 - index, analysisStatus: 'DISABLED', images: [{ storedName: `ui-simulation-${index + 1}.${index === 1 ? 'ai' : 'png'}`, name: `UI模拟-${index + 1}.${index === 1 ? 'ai' : 'png'}`, size: 1024 }, ...(index === 2 ? [{ storedName: 'ui-simulation-33.png', name: 'UI模拟-补充预览.png', size: 2048 }] : [])] }));
    const task = { id: 91001, projectId: 92001, name: 'UI模拟 · 春日系列手机挂链设计', projectName: 'UI模拟 · 森林伙伴产品企划', details: '为森林伙伴春日系列设计手机挂链。延续自然、温暖的配色与手绘风格，结合小动物、野餐和植物元素，寻找可复用的主视觉与纹样。', status: 'accepted', designerId: 'ui-simulation-designer', designerName: 'UI模拟设计师', publisherName: 'UI模拟产品企划', plannedDate: '2026-11-20', referenceImagesJson: null, attachmentsJson: null, rejectionRecords: [], deliveryVersions: [] };
    e.dashboardState.designerTaskCache = [task];
    e.projectState.currentProjectDetail = { id: task.projectId, name: task.projectName, tasks: [task] };
    const f = window.recommendationFixture = { task, items, associated: [], gets: [], posts: [], previews: [], downloads: [], pending: [], mode: 'ready' };
    f.payload = () => ({ items: f.mode === 'empty' ? [] : items, linkedItems: [], associatedLibraryItemIds: [...f.associated], canAssociate: true, status: 'METADATA', aiConfigured: false, analysisPending: false, generatedAt: new Date().toISOString() });
    e.actions.apiGet = async url => {
      f.gets.push(url);
      if (url.includes('/image-recommendations')) {
        if (f.mode === 'pending') return new Promise(resolve => f.pending.push(resolve));
        if (f.mode === 'error') throw new Error('UI模拟 · 推荐服务暂时不可用');
        return f.payload();
      }
      if (url === '/image-library') return [];
      throw new Error(`UI模拟禁止访问真实接口：${url}`);
    };
    e.actions.apiPost = async (url, body) => {
      f.posts.push({ url, body });
      if (url.includes('/associate')) {
        const id = Number(url.match(/\/(\d+)\/associate$/)?.[1]);
        if (!f.associated.includes(id)) f.associated.push(id);
        await new Promise(resolve => setTimeout(resolve, 50));
        return f.payload();
      }
      throw new Error(`UI模拟禁止修改真实接口：${url}`);
    };
    e.actions.previewImage = (...args) => f.previews.push({ type: 'image', args });
    e.actions.openFilePreview = (...args) => f.previews.push({ type: 'file', args });
    e.actions.doDirectDownload = (...args) => f.downloads.push(args);
    document.getElementById('mainContent').innerHTML = '<h1>UI 模拟 · 任务图档推荐</h1><p>本页面不读取、不写入真实任务或图档。</p>';
    e.actions.openPublishedSubTaskDetail(task.id);
  });

  let detail = page.locator('#publishedSubTaskDetailModal');
  await detail.locator('.task-recommendation-card').first().waitFor();
  assert.equal(await detail.locator('.task-recommendations-grid > .task-recommendation-card').count(), 8, 'defaults to at most eight recommended library items');
  assert.match(await detail.innerText(), /图档标签与需求相关/);
  assert.match(await detail.innerText(), /同系列/);
  assert.equal(await page.evaluate(() => window.recommendationFixture.gets[0]), '/projects/92001/subtasks/91001/image-recommendations?limit=8');
  // A narrow drawer uses two columns; scroll each card into view to exercise lazy images.
  const covers = detail.locator('.task-recommendation-cover>img');
  for (let index = 0; index < await covers.count(); index++) {
    await covers.nth(index).scrollIntoViewIfNeeded();
    await page.waitForFunction(index => document.querySelectorAll('#publishedSubTaskDetailModal .task-recommendation-cover>img')[index]?.dataset.authLoaded === 'true', index);
  }
  await detail.locator('.modal-body').evaluate(body => { body.scrollTop = 0; });
  // The API fixture resolves before the real drawer's entrance transition finishes.
  // Wait for finite page animations and a paint so visual QA sees its stable state.
  await page.evaluate(async () => {
    const animations = document.getAnimations().filter(animation => animation.effect?.getComputedTiming().endTime !== Infinity);
    await Promise.all(animations.map(animation => animation.finished.catch(() => {})));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });

  const first = detail.locator('.task-recommendation-card').nth(0);
  await first.getByRole('button', { name: '预览', exact: true }).click();
  await first.getByRole('button', { name: '下载', exact: true }).click();
  await detail.locator('.task-recommendation-card').nth(1).getByRole('button', { name: '预览', exact: true }).click();
  assert.deepEqual(await page.evaluate(() => window.recommendationFixture.previews.map(entry => entry.type)), ['image', 'file']);
  assert.match(await page.evaluate(() => window.recommendationFixture.downloads[0][0]), /^\/api\/files\/download\/ui-simulation-1\.png/);
  await detail.locator('.task-recommendation-card').nth(2).getByRole('button', { name: '下载', exact: true }).click();
  await page.locator('#taskRecommendationFilesModal').waitFor();
  await page.locator('#taskRecommendationFilesModal [data-emie-action="click:task-recommendation-file-download"]').nth(1).click();
  assert.match(await page.evaluate(() => window.recommendationFixture.downloads[1][0]), /ui-simulation-33\.png/);
  await page.locator('#taskRecommendationFilesModal [data-emie-action="click:task-recommendation-files-close"]').first().click();

  await first.locator('[data-emie-action="click:task-recommendation-associate"]').evaluate(button => { button.click(); button.click(); });
  await detail.getByText('已关联到此任务，重新打开任务后仍可查看。').waitFor();
  assert.equal(await page.evaluate(() => window.recommendationFixture.posts.filter(entry => entry.url.includes('/associate')).length), 1);
  await detail.locator('.task-recommendation-card').nth(0).getByRole('button', { name: '✓ 已关联任务', exact: true }).waitFor();
  const getCount = await page.evaluate(() => window.recommendationFixture.gets.length);
  await page.waitForTimeout(1200);
  assert.equal(await page.evaluate(() => window.recommendationFixture.gets.length), getCount, 'recommendations must not poll after rendering');
  await detail.locator('[data-emie-action="click:designer-detail-close"]').first().click();
  await detail.waitFor({ state: 'detached' });

  await page.evaluate(() => { const f = window.recommendationFixture; window.EMIE.actions.openProjectSubTaskDetail(null, f.task.id); });
  detail = page.locator('#projectSubTaskDetailModal');
  await detail.locator('.task-recommendation-card').first().waitFor();
  assert.equal(await detail.locator('.task-recommendation-card').count(), 8);
  assert.equal(await page.evaluate(() => window.recommendationFixture.gets.at(-1)), '/projects/92001/subtasks/91001/image-recommendations?limit=8');
  await detail.locator('[data-emie-action="click:detail-subtask-close"]').first().click(); await detail.waitFor({ state: 'detached' });

  await page.evaluate(() => { const f = window.recommendationFixture; f.mode = 'pending'; window.EMIE.actions.openPublishedSubTaskDetail(f.task.id); window.closedRecommendationSection = document.querySelector('#publishedSubTaskDetailModal [data-task-recommendations]'); window.closedRecommendationHtml = window.closedRecommendationSection.innerHTML; });
  await page.locator('#publishedSubTaskDetailModal .task-recommendations-loading').waitFor();
  await page.locator('#publishedSubTaskDetailModal [data-emie-action="click:designer-detail-close"]').first().click();
  await page.locator('#publishedSubTaskDetailModal').waitFor({ state: 'detached' });
  await page.evaluate(() => { const f = window.recommendationFixture; f.mode = 'ready'; f.pending.shift()(f.payload()); });
  await page.waitForTimeout(50);
  assert.equal(await page.evaluate(() => window.closedRecommendationSection.innerHTML === window.closedRecommendationHtml), true, 'closed details must not receive delayed content');
  assert.equal(await page.locator('[data-task-recommendations]').count(), 0);

  await page.evaluate(async () => { const e = window.EMIE; e.state.currentRole = 'designer'; await e.actions.renderImageLibrary(document.getElementById('mainContent')); });
  assert.equal(await page.locator('[data-emie-action="click:visual-ai-settings-open"]').count(), 0);
  assert.equal(await page.evaluate(() => typeof window.EMIE.actions.openVisualAiSettings), 'undefined');
  await page.evaluate(async () => { const e = window.EMIE; e.state.currentRole = 'admin'; await e.actions.renderImageLibrary(document.getElementById('mainContent')); });
  assert.equal(await page.locator('[data-emie-action="click:visual-ai-settings-open"]').count(), 0, 'retired image-analysis settings must not appear for admins');
  assert.equal(await page.locator('#visualAiSettingsModal').count(), 0);
  assert.equal(await page.evaluate(() => window.recommendationFixture.gets.some(url => url.includes('/visual-ai-config'))), false);
  assert.equal(await page.evaluate(() => window.recommendationFixture.posts.some(entry => entry.url.includes('/visual-ai-config'))), false);

  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  console.log(`task recommendation browser checks passed; UI simulation screenshot: ${screenshot}`);
} catch (error) {
  await page.screenshot({ path: '/tmp/emie-task-recommendations-failure.png', fullPage: true }).catch(() => {});
  throw error;
} finally {
  await context.close(); await browser.close();
}
