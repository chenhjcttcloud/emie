# 弹窗浏览器回归

这组检查加载真实 EMIE 页面及其 `core-ui.js`、`app.css`，只向页面注入隔离的
`e2e-*` 弹窗，不创建或修改业务数据。

```bash
cd scripts/modal-e2e
npm ci
npx playwright install chromium   # CI/Linux 首次运行需要
npm test
```

默认测试 `http://127.0.0.1:8080`，可通过环境变量调整：

```bash
EMIE_BASE_URL=http://127.0.0.1:8080 MODAL_E2E_REPEAT=10 npm test
```

Mac 本机优先使用已安装的 Google Chrome；也可用 `CHROME_BIN` 指定浏览器。
失败时截图、trace 和错误信息写入 `test-results/modal-e2e/`。用
`npx playwright show-trace test-results/modal-e2e/run-1-trace.zip` 查看 trace。

覆盖范围：统一关闭按钮、Esc 与强制弹窗策略、遮罩选择性关闭、焦点锁定与恢复、
嵌套弹窗只关闭顶层，以及低高度桌面和移动视口的正文滚动与操作区可达性。
