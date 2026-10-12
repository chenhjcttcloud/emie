const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/js/admin-scoring.js', 'utf8');
const exporter = source.slice(source.indexOf('async function exportDesignerMonthlyReport('), source.indexOf('function filterAdminPointRules('));

async function check(name, initial, regenerate, generated, polled, expectedPost, expectedDownload) {
  const calls = [];
  const alerts = [];
  const text = {};
  const progress = { hidden: true, querySelector: selector => text[selector] ||= { style: {} } };
  const button = { textContent: '导出', setAttribute() {}, removeAttribute() {} };
  let downloads = 0;
  let reads = 0;
  const context = vm.createContext({
    document: {
      getElementById: id => id === 'designerPerformanceMonth' ? { value: '2026-09' } : progress,
      createElement: () => ({ click: () => downloads++ }),
    },
    localStorage: { getItem: () => 'test-token' },
    window: { EMIE: { actions: { showSystemAlert: message => alerts.push(message) } } },
    URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
    setTimeout: callback => { callback(); },
    fetch: async (url, options) => {
      assert.equal(options.headers['X-Auth-Token'], 'test-token');
      assert.equal(options.credentials, 'same-origin');
      const method = options.method || 'GET';
      calls.push({ url, method });
      const status = method === 'POST' ? generated : reads++ === 0 ? initial : polled;
      return { ok: true, json: async () => status, blob: async () => Buffer.from('zip') };
    },
  });
  vm.runInContext(exporter, context);
  await context.exportDesignerMonthlyReport(button, regenerate);
  assert.equal(calls.filter(call => call.method === 'POST').length, expectedPost, name);
  assert.equal(downloads, expectedDownload, name);
  assert.equal(calls.filter(call => call.url.endsWith('/zip')).length, expectedDownload, name);
  assert.equal(button.disabled, false, name);
  assert.equal(alerts.length, expectedDownload ? 0 : 1, name);
  if (expectedDownload && initial.generatedAt && !regenerate) {
    assert.match(text['[data-export-message]'].textContent, /2026-10-01/);
  }
}

(async () => {
  const saved = { status: 'READY', progress: 100, generatedAt: '2026-10-01 02:30:00' };
  const running = { status: 'RUNNING', progress: 20 };
  await check('已有快照直接下载', saved, false, null, null, 0, 1);
  await check('没有快照后台生成', { status: 'NOT_STARTED' }, false, running, saved, 1, 1);
  await check('主动更新重新生成', saved, true, running, saved, 1, 1);
  await check('生成中继续等待', running, false, null, saved, 0, 1);
  await check('重新生成失败仍可下载旧快照', { ...saved, status: 'FAILED' }, false, null, null, 0, 1);
  await check('本次生成失败不伪装成功', saved, true, running, { status: 'FAILED', message: '生成失败' }, 1, 0);
  console.log('Performance export: 6 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
