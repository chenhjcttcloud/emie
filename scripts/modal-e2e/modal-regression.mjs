import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';

const baseURL = process.env.EMIE_BASE_URL || 'http://127.0.0.1:8080';
const repeat = Number.parseInt(process.env.MODAL_E2E_REPEAT || '1', 10);
const artifactRoot = path.resolve(process.env.MODAL_E2E_ARTIFACTS || 'test-results/modal-e2e');
const configuredChrome = process.env.CHROME_BIN;
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

async function executablePath() {
  if (configuredChrome) return configuredChrome;
  try {
    await fs.access(macChrome);
    return macChrome;
  } catch {
    return undefined;
  }
}

function modalMarkup({ id, title, close = true, backdropCloses = false, tall = false }) {
  return `
    <div class="modal-overlay" id="${id}" data-test-backdrop-closes="${backdropCloses}">
      <div class="modal"${close ? '' : ` aria-label="${title}"`}>
        ${close ? `<div class="modal-header">
          <div class="modal-header-left"><div class="modal-title">${title}</div></div>
          <button class="modal-close">✕</button>
        </div>` : ''}
        <div class="modal-body">
          <button type="button">第一个操作</button>
          <input aria-label="弹窗输入框">
          ${tall ? '<div style="height:900px">长内容</div>' : ''}
        </div>
        <div class="modal-footer"><button type="button">最后一个操作</button></div>
      </div>
    </div>`;
}

async function addModal(page, options) {
  const markup = modalMarkup(options);
  await page.evaluate(({ markup, id, backdropCloses }) => {
    const host = document.createElement('div');
    host.innerHTML = markup.trim();
    const overlay = host.firstElementChild;
    if (backdropCloses) {
      overlay.addEventListener('click', event => {
        if (event.target === overlay) overlay.remove();
      });
    }
    const close = overlay.querySelector('.modal-close');
    close?.addEventListener('click', () => overlay.remove());
    document.body.appendChild(overlay);
  }, { markup, id: options.id, backdropCloses: options.backdropCloses });
  const dialog = page.getByRole('dialog', { name: options.title });
  await dialog.waitFor({ state: 'visible' });
  return dialog;
}

async function removeTestModals(page) {
  await page.evaluate(async () => {
    document.querySelectorAll('.modal-overlay[id^="e2e-"]').forEach(node => node.remove());
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
}

async function pressEscape(page) {
  await page.evaluate(() => {
    document.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape',
      code: 'Escape',
      bubbles: true,
      cancelable: true,
    }));
  });
}

async function runChecks(page) {
  await page.setViewportSize({ width: 1280, height: 800 });

  // Legacy templates receive the same accessible header close affordance.
  await page.evaluate(() => {
    const overlay = document.createElement('div');
    overlay.id = 'e2e-normalized';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = '<button class="modal-close-float">✕</button><div class="modal"><div class="modal-header"><div class="modal-title">统一关闭按钮</div></div><div class="modal-body">内容</div></div>';
    overlay.querySelector('button').addEventListener('click', () => overlay.remove());
    document.body.appendChild(overlay);
  });
  const normalized = page.getByRole('dialog', { name: '统一关闭按钮' });
  await normalized.waitFor();
  const normalizedClose = normalized.getByRole('button', { name: '关闭弹窗' });
  await normalizedClose.click();
  await normalized.waitFor({ state: 'detached' });
  process.stdout.write('  close-control ok\n');

  // Escape closes only the topmost dismissible dialog and restores focus.
  await page.evaluate(() => {
    const opener = document.createElement('button');
    opener.id = 'e2e-opener';
    opener.textContent = '打开弹窗';
    document.body.appendChild(opener);
    opener.focus();
  });
  const parent = await addModal(page, { id: 'e2e-parent', title: '父弹窗', close: true });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-parent') !== null, '父弹窗打开后应接管焦点');
  const child = await addModal(page, { id: 'e2e-child', title: '子弹窗', close: true });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-child') !== null, '子弹窗打开后应接管焦点');
  await pressEscape(page);
  await child.waitFor({ state: 'detached' });
  await parent.waitFor({ state: 'visible' });
  await assertEventually(page, () => document.activeElement?.closest('#e2e-parent') !== null, '关闭子弹窗后焦点应回到父弹窗');
  await pressEscape(page);
  await parent.waitFor({ state: 'detached' });
  await assertEventually(page, () => document.activeElement?.id === 'e2e-opener', '关闭父弹窗后焦点应回到触发按钮');
  process.stdout.write('  escape-nesting-focus-restore ok\n');

  // Mandatory dialogs without a close control cannot be bypassed with Escape.
  const mandatory = await addModal(page, { id: 'e2e-mandatory', title: '必选操作', close: false });
  await pressEscape(page);
  await mandatory.waitFor({ state: 'visible' });
  await removeTestModals(page);
  process.stdout.write('  mandatory-dialog ok\n');

  // Backdrop dismissal is opt-in; clicks inside never count as backdrop clicks.
  const guarded = await addModal(page, { id: 'e2e-guarded', title: '保护表单', close: true, backdropCloses: false });
  await page.locator('#e2e-guarded').click({ position: { x: 2, y: 2 } });
  await guarded.waitFor({ state: 'visible' });
  await removeTestModals(page);
  const dismissible = await addModal(page, { id: 'e2e-dismissible', title: '轻量预览', close: true, backdropCloses: true });
  await dismissible.getByRole('button', { name: '第一个操作' }).click();
  await dismissible.waitFor({ state: 'visible' });
  await page.locator('#e2e-dismissible').click({ position: { x: 2, y: 2 } });
  await dismissible.waitFor({ state: 'detached' });
  process.stdout.write('  backdrop-policy ok\n');

  // Tab and Shift+Tab wrap inside the active dialog.
  const focusDialog = await addModal(page, { id: 'e2e-focus', title: '焦点锁定', close: true });
  // Wait for the modal normalizer's requestAnimationFrame focus handoff before
  // exercising the keyboard loop, otherwise that pending handoff can race this check.
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const close = focusDialog.getByRole('button', { name: '关闭弹窗' });
  await close.focus();
  await assertEventually(page, () => document.activeElement?.getAttribute('aria-label') === '关闭弹窗', '焦点锁定测试应先聚焦关闭按钮');
  await page.evaluate(() => document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Tab', code: 'Tab', shiftKey: true, bubbles: true, cancelable: true,
  })));
  await assertEventually(page, () => document.activeElement?.textContent === '最后一个操作', 'Shift+Tab 应从首元素回到末元素');
  await page.evaluate(() => document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Tab', code: 'Tab', bubbles: true, cancelable: true,
  })));
  await assertEventually(page, () => document.activeElement?.getAttribute('aria-label') === '关闭弹窗', 'Tab 应从末元素回到首元素');
  await removeTestModals(page);
  process.stdout.write('  focus-trap ok\n');

  // Low-height desktop and mobile layouts retain reachable header/body/footer.
  for (const viewport of [{ width: 1024, height: 420 }, { width: 390, height: 667 }]) {
    await page.setViewportSize(viewport);
    const responsive = await addModal(page, { id: 'e2e-responsive', title: '响应式弹窗', close: true, tall: true });
    const box = await responsive.boundingBox();
    assert(box, '响应式弹窗应可见');
    assert(box.y >= 0 && box.y + box.height <= viewport.height + 1, `弹窗应限制在 ${viewport.width}x${viewport.height} 视口内`);
    await responsive.getByRole('button', { name: '关闭弹窗' }).waitFor({ state: 'visible' });
    await responsive.getByRole('button', { name: '最后一个操作' }).waitFor({ state: 'visible' });
    const overflow = await responsive.locator('.modal-body').evaluate(node => node.scrollHeight > node.clientHeight);
    assert.equal(overflow, true, '长内容应在弹窗正文内部滚动');
    await removeTestModals(page);
  }
  process.stdout.write('  responsive-layout ok\n');
}

async function assertEventually(page, predicate, message) {
  await page.waitForFunction(predicate, undefined, { timeout: 2_000 }).catch(() => {
    throw new assert.AssertionError({ message });
  });
}

await fs.mkdir(artifactRoot, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: await executablePath() });
let failed = false;
try {
  for (let run = 1; run <= repeat; run += 1) {
    const context = await browser.newContext();
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    const page = await context.newPage();
    const browserErrors = [];
    page.on('pageerror', error => browserErrors.push(error.message));
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
      await page.locator('html[data-app-ready]').waitFor({ timeout: 15_000 });
      await runChecks(page);
      assert.deepEqual(browserErrors, [], `页面脚本错误: ${browserErrors.join('; ')}`);
      await context.tracing.stop();
      process.stdout.write(`modal_e2e run=${run}/${repeat} ok\n`);
    } catch (error) {
      failed = true;
      const prefix = path.join(artifactRoot, `run-${run}`);
      await page.screenshot({ path: `${prefix}.png`, fullPage: true }).catch(() => {});
      await context.tracing.stop({ path: `${prefix}-trace.zip` }).catch(() => {});
      await fs.writeFile(`${prefix}-error.txt`, `${error.stack || error}\nURL: ${page.url()}\n`).catch(() => {});
      console.error(`modal_e2e run=${run}/${repeat} failed: ${error.message}`);
      break;
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}

if (failed) process.exitCode = 1;
