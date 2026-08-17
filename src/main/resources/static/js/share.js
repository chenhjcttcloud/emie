/* ==================== 公开分享页交互（/share/**） ====================
 * 配合 CSP default-src 'self'：不依赖内联事件，改用 document 级事件委托。
 * 当前仅用于密码页：表单提交时隐藏上一次的错误提示。
 * 版本号：页面内 <script src="/js/share.js?v=N"> 手动维护。
 */
(function () {
  'use strict';

  document.addEventListener('submit', function (event) {
    const form = event.target;
    if (!form || !form.hasAttribute('data-pw-form')) return;
    const error = document.getElementById('pwErr');
    if (error) error.style.display = 'none';
  });
})();
