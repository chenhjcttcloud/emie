const EMIE = window.EMIE;
const renderRoleSwitcher = (...args) => EMIE.actions.renderRoleSwitcher(...args);
const apiGet = (...args) => EMIE.actions.apiGet(...args);
const renderSidebar = (...args) => EMIE.actions.renderSidebar(...args);
const render = (...args) => EMIE.actions.render(...args);

function notifySystemVersion(version) {
  if (!version) return;
  // 版本确认状态按标签页隔离，避免一个标签页刷新后让其他旧标签页漏掉提示。
  const key = 'emie_system_version_seen';
  const previous = sessionStorage.getItem(key);
  sessionStorage.setItem(key, version);
  if (!previous || previous === version || document.getElementById('systemVersionNotice')) return;
  const notice = document.createElement('div');
  notice.id = 'systemVersionNotice';
  notice.style.cssText = 'position:fixed;bottom:16px;left:16px;z-index:10000;max-width:360px;pointer-events:none;';
  notice.innerHTML = `<div style="pointer-events:auto;background:#fff;border:1px solid #BFDBFE;border-left:4px solid var(--primary);border-radius:10px;box-shadow:0 8px 24px rgba(15,23,42,.16);padding:12px 14px;display:flex;align-items:center;gap:12px;"><div style="flex:1;min-width:0;"><div style="font-weight:700;font-size:13px;color:#1E3A8A;margin-bottom:3px;">系统已更新</div><div style="font-size:12px;color:var(--gray-500);line-height:1.5;">当前操作不会被打断。完成后点击刷新即可使用最新版本。</div></div><button class="btn btn-primary btn-sm" data-emie-onclick="forceRefreshForVersion()" style="white-space:nowrap;">立即刷新</button><button class="modal-close" aria-label="稍后刷新" data-emie-onclick="document.getElementById('systemVersionNotice')?.remove()" style="position:static;width:24px;height:24px;">✕</button></div>`;
  document.body.appendChild(notice);
}

function connectVersionStream() {
  if (!window.EventSource || EMIE.state.versionStream) return;
  const stream = new EventSource('/api/admin/version/stream');
  stream.addEventListener('version', event => notifySystemVersion(event.data));
  stream.onerror = () => {
    stream.close();
    EMIE.state.versionStream = null;
    const retryDelay = Math.min(60_000, Math.max(10_000, (EMIE.state.versionStreamRetry || 0) * 2 || 10_000));
    EMIE.state.versionStreamRetry = retryDelay;
    setTimeout(connectVersionStream, retryDelay);
  };
  stream.onopen = () => { EMIE.state.versionStreamRetry = 0; };
  EMIE.state.versionStream = stream;
}

/** 清理 WebView 可控缓存后刷新；不清理 token/localStorage，避免打断用户当前登录。 */
async function forceRefreshForVersion() {
  const button = document.querySelector('#systemVersionNotice .btn-primary');
  if (button) { button.disabled = true; button.textContent = '正在刷新…'; }
  try {
    if (window.caches?.keys) {
      const keys = await caches.keys();
      await Promise.all(keys.map(key => caches.delete(key)));
    }
    if (navigator.serviceWorker?.getRegistrations) {
      const registrations = await navigator.serviceWorker.getRegistrations();
      await Promise.all(registrations.map(registration => registration.unregister()));
    }
  } catch (e) {
    console.warn('清理页面缓存失败，继续强制刷新:', e);
  }
  let refreshVersion = String(Date.now());
  try {
    const response = await fetch('/api/admin/public-config?versionCheck=' + Date.now(), { cache: 'no-store' });
    if (response.ok) {
      const currentVersion = (await response.json())['system.version'];
      if (currentVersion) refreshVersion = currentVersion;
    }
  } catch (e) {
    console.warn('读取最新系统版本失败，使用时间戳刷新:', e);
  }
  const url = new URL(window.location.href);
  url.searchParams.set('__emie_version', refreshVersion);
  window.location.replace(url.toString());
}

async function checkSystemVersion() {
  try {
    const r = await fetch('/api/admin/public-config?versionCheck=' + Date.now(), { cache: 'no-store' });
    if (r.ok) notifySystemVersion((await r.json())['system.version']);
  } catch (e) { /* 版本检查失败不影响当前操作 */ }
}

async function initApp() {
  // 检查飞书 SSO 回调
  await checkFeishuCallback();

  // 飞书客户端内自动静默登录（WebView 环境）
  if (typeof tt !== 'undefined' && tt.login) {
    try {
      const loginRes = await new Promise((resolve, reject) => {
        tt.login({ success: resolve, fail: reject });
      });
      if (loginRes && loginRes.code) {
        const r = await fetch('/api/auth/feishu/auto-login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ code: loginRes.code })
        });
        if (r.ok) {
          const data = await r.json();
          localStorage.setItem('design_pm_token', data.token);
          localStorage.setItem('design_pm_user', JSON.stringify(data.user));
          // 继续走正常登录流程
        }
      }
    } catch(e) {
      console.warn('飞书静默登录失败，回退到普通登录:', e);
    }
  }

  // 检查是否有保存的 token
  const token = localStorage.getItem('design_pm_token');
  if (token) {
    try {
      const r = await fetch('/api/auth/me', { headers: { 'X-Auth-Token': token } });
      if (r.ok) {
        EMIE.state.authUser = await r.json();
        // 如果有原始用户信息（模拟模式），用回原始用户判断权限
        EMIE.state.originalUser = EMIE.state.authUser.originalUserId
          ? { userId: EMIE.state.authUser.originalUserId, role: EMIE.state.authUser.originalRole }
          : { ...EMIE.state.authUser };
        // 恢复上次浏览的页面（默认工作台）
        EMIE.state.currentView = localStorage.getItem('design_pm_lastView') || 'dashboard';
        EMIE.adminState.currentTab = localStorage.getItem('design_pm_lastAdminTab') || 'dashboard';
        EMIE.state.cache = { orders: [] };
        // 重置 viewport 缩放
        const vp = document.querySelector('meta[name="viewport"]');
        if (vp) vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0';
        showApp();
        // 渲染完成后滚动到顶部
        setTimeout(() => { window.scrollTo(0, 0); document.documentElement.scrollTop = 0; }, 100);
        return;
      }
    } catch(e) {}
  }
  // token 无效或不存在，显示登录页
  showLogin();
}

function showLogin() {
  document.getElementById('loginPage').style.display = '';
  document.getElementById('appContainer').style.display = 'none';
  document.getElementById('loginError').style.display = 'none';
  document.documentElement.dataset.appReady = 'login';
  // 加载公开配置（如登录页背景）
  loadPublicConfig();
}

// 加载公开配置到登录页
async function loadPublicConfig() {
  try {
    const r = await fetch('/api/admin/public-config');
    if (r.ok) {
      const cfg = await r.json();
      // 更新页面标题
      if (cfg['app.title']) document.title = cfg['app.title'] + ' - EMIE';
      // 登录页背景
      if (cfg['login.bg']) {
        const loginContainer = document.querySelector('.login-container');
        if (loginContainer) {
          loginContainer.style.backgroundImage = `url(${cfg['login.bg']})`;
          loginContainer.style.backgroundSize = 'cover';
          loginContainer.style.backgroundPosition = 'center';
        }
      } else if (cfg['login.bgColor']) {
        const loginContainer = document.querySelector('.login-container');
        if (loginContainer) loginContainer.style.backgroundColor = cfg['login.bgColor'];
      }
      // 系统标题和副标题
      if (cfg['app.title']) {
        document.querySelector('.login-title').textContent = cfg['app.title'];
      }
      if (cfg['app.subtitle']) {
        document.querySelector('.login-subtitle').textContent = cfg['app.subtitle'];
      }
      // Logo（图片优先，无图片则用 emoji）
      const logoEl = document.querySelector('.login-logo');
      if (logoEl) {
        if (cfg['app.logo']) {
          const logoSrc = String(cfg['app.logo']).startsWith('/api/files/download/admin/') ? '/favicon.ico' : cfg['app.logo'];
          logoEl.innerHTML = `<img src="${logoSrc}" style="height:48px;width:auto;" alt="logo">`;
        } else if (cfg['app.logoEmoji']) {
          logoEl.textContent = cfg['app.logoEmoji'];
        }
      }
      // 飞书 SSO 按钮（始终显示，点击时再校验配置）
      const feishuWrap = document.getElementById('feishuLoginWrap');
      if (feishuWrap) {
        feishuWrap.style.display = 'block';
      }
    }
  } catch(e) {
    console.warn('加载公开配置失败:', e);
  }
}

async function showApp() {
  // 登录回调、自动登录和用户连续点击可能同时触发渲染；同一时刻只允许一个首屏初始化流程。
  if (EMIE.state.showAppInProgress) return;
  EMIE.state.showAppInProgress = true;
  try {
    if (isPendingUser(EMIE.state.authUser)) {
      showPendingApproval();
      return;
    }

    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('appContainer').style.display = '';
    const hamburger = document.getElementById('hamburgerBtn');
    const sidebar = document.getElementById('sidebarContainer');
    if (hamburger) hamburger.style.display = '';
    if (sidebar) sidebar.style.display = '';

    // 加载公开配置更新头部
    try {
      const r = await fetch('/api/admin/public-config');
      if (r.ok) {
        const cfg = await r.json();
        if (cfg['app.title']) document.title = cfg['app.title'] + ' - EMIE';
        notifySystemVersion(cfg['system.version']);
        const logoEl = document.querySelector('.logo');
        if (logoEl) {
          if (cfg['app.logo']) {
            const logoSrc = String(cfg['app.logo']).startsWith('/api/files/download/admin/') ? '/favicon.ico' : cfg['app.logo'];
            logoEl.innerHTML = `<img src="${logoSrc}" style="height:32px;width:auto;vertical-align:middle;margin-right:8px;" alt="logo"><span>${cfg['app.title'] || '产品管理系统'}</span><small class="app-version">v${cfg['system.version'] || '—'}</small>`;
          } else if (cfg['app.logoEmoji']) {
            logoEl.innerHTML = `${cfg['app.logoEmoji']} ${cfg['app.title'] || '产品管理系统'}<small class="app-version">v${cfg['system.version'] || '—'}</small><span>${cfg['app.subtitle'] || 'Product Management'}</span>`;
          } else {
            logoEl.innerHTML = `🎨 ${cfg['app.title'] || '产品管理系统'}<small class="app-version">v${cfg['system.version'] || '—'}</small><span>${cfg['app.subtitle'] || 'Product Management'}</span>`;
          }
        }
      }
    } catch(e) { /* ignore */ }
    checkSystemVersion();
    // 已打开的页面通过短轮询感知新版本；切回页面时另行立即检查。
    if (!EMIE.state.versionCheckTimer) EMIE.state.versionCheckTimer = setInterval(checkSystemVersion, 10000);
    if (!EMIE.state.versionFocusBound) {
      window.addEventListener('focus', checkSystemVersion);
      document.addEventListener('visibilitychange', () => { if (!document.hidden) checkSystemVersion(); });
      EMIE.state.versionFocusBound = true;
    }

    document.getElementById('userDisplay').textContent = `${EMIE.state.authUser.name}（${roleLabel(EMIE.state.authUser.role)}）`;
    EMIE.state.currentRole = ['promotion', 'product_promotion', 'product-promotion'].includes(String(EMIE.state.authUser.role || '').toLowerCase())
      ? 'promotion' : String(EMIE.state.authUser.role || '').toLowerCase();
    EMIE.state.currentUserId = EMIE.state.authUser.userId;

    // 基础数据并行加载：单个接口失败只影响对应功能，不阻塞首屏。
    const [categories, compliance, priceRanges, ipOptions, departments, users, capabilities] = await Promise.all([
      apiGet('/categories').catch(() => []),
      apiGet('/compliance').catch(() => []),
      apiGet('/price-ranges').catch(() => []),
      apiGet('/ip-options').catch(() => []),
      apiGet('/departments').catch(() => []),
      apiGet('/users').catch(() => ({})),
      apiGet('/auth/permissions').catch(() => null)
    ]);
    EMIE.state.categories = categories;
    EMIE.state.complianceItems = compliance;
    EMIE.state.priceRanges = priceRanges;
    EMIE.state.ipOptions = ipOptions;
    EMIE.state.departments = departments;
    EMIE.state.users = users;
    EMIE.state.permissions = Array.isArray(capabilities?.permissions) ? capabilities.permissions : null;
    EMIE.state.permissionScopes = capabilities?.scopes && typeof capabilities.scopes === 'object'
      ? capabilities.scopes : {};
    EMIE.state.permissionVersion = capabilities?.permissionVersion ?? null;
    EMIE.state.permissionMode = capabilities?.mode || 'unavailable';
    // 用户列表加载后重新渲染切换器（否则下拉选项为空）
    await renderRoleSwitcher();
    renderSidebar();
    render();
    openNotificationDeepLink();
    document.documentElement.dataset.appReady = 'authenticated';
  } finally {
    EMIE.state.showAppInProgress = false;
  }
}

function openNotificationDeepLink() {
  const params = new URLSearchParams(window.location.search);
  const projectId = Number(params.get('projectId'));
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || EMIE.state.notificationDeepLinkOpened) return;
  EMIE.state.notificationDeepLinkOpened = true;
  window.history.replaceState({}, document.title, window.location.pathname);
  setTimeout(() => EMIE.actions.openProjectDetail(projectId), 0);
}

function isPendingUser(user) {
  return !!user && (user.status === 'pending' || user.role === 'pending');
}

function showPendingApproval() {
  document.getElementById('loginPage').style.display = 'none';
  document.getElementById('appContainer').style.display = '';

  const hamburger = document.getElementById('hamburgerBtn');
  const sidebar = document.getElementById('sidebarContainer');
  if (hamburger) hamburger.style.display = 'none';
  if (sidebar) sidebar.style.display = 'none';

  const user = EMIE.state.authUser || {};
  document.getElementById('userDisplay').textContent = `${user.name || '飞书用户'}（待分配角色）`;
  EMIE.state.currentRole = 'pending';
  EMIE.state.currentUserId = user.userId || '';

  const main = document.getElementById('mainContent');
  if (main) {
    main.innerHTML = `
      <div class="empty" style="max-width:620px;margin:8vh auto;padding:48px 28px;background:#fff;border:1px solid var(--gray-200);border-radius:16px;box-shadow:var(--shadow-sm);">
        <div class="empty-icon" style="font-size:52px;">⏳</div>
        <h2 style="margin:12px 0 8px;">账号等待授权</h2>
        <p style="color:var(--gray-500);line-height:1.8;margin:0 auto 24px;max-width:440px;">
          你的公司飞书身份已验证，系统账号也已创建。管理员分配部门和角色后，即可查看项目和业务数据。
        </p>
        <button class="btn btn-primary" data-emie-onclick="refreshPendingAccess()">重新登录检查权限</button>
      </div>`;
  }
  document.documentElement.dataset.appReady = 'pending';
}

async function refreshPendingAccess() {
  const token = localStorage.getItem('design_pm_token');
  if (token) {
    await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Auth-Token': token } }).catch(() => {});
  }
  localStorage.removeItem('design_pm_token');
  localStorage.removeItem('design_pm_user');
  handleFeishuLogin();
}

// ===== 密码可见性切换 =====
function togglePassword(inputId, el) {
  const input = document.getElementById(inputId);
  if (!input) return;
  const isPassword = input.type === 'password';
  input.type = isPassword ? 'text' : 'password';
  const open = el.querySelector('.eye-open');
  const closed = el.querySelector('.eye-closed');
  if (open && closed) {
    open.style.display = isPassword ? 'none' : '';
    closed.style.display = isPassword ? '' : 'none';
  }
}

async function handleLogin(event) {
  event.preventDefault();
  const id = document.getElementById('loginId').value.trim();
  const pwd = document.getElementById('loginPassword').value;
  const errEl = document.getElementById('loginError');

  if (!id || !pwd) {
    errEl.textContent = '请输入用户名和密码';
    errEl.style.display = '';
    return;
  }

  try {
    const r = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, password: pwd }),
    });
    const data = await r.json();
    if (!r.ok) {
      errEl.textContent = data.error || '登录失败';
      errEl.style.display = '';
      return;
    }
    // 登录成功
    localStorage.setItem('design_pm_token', data.token);
    EMIE.state.authUser = { userId: data.userId, name: data.name, role: data.role, title: data.title };
    EMIE.state.originalUser = { ...EMIE.state.authUser };
    // 重置 viewport 缩放（修复 iOS 输入框放大后不恢复的问题）
    const vp = document.querySelector('meta[name="viewport"]');
    if (vp) vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0';
    // 重置视图状态，避免不同账号切换时看到上任用户的页面
    EMIE.state.currentView = 'dashboard';
    EMIE.adminState.currentTab = 'dashboard';
    EMIE.state.cache = { orders: [] };
    showApp();
    setTimeout(() => { window.scrollTo(0, 0); document.documentElement.scrollTop = 0; }, 100);
  } catch (e) {
    errEl.textContent = '网络错误，请重试';
    errEl.style.display = '';
  }
}

// ==================== 飞书 SSO 登录 ====================
function handleFeishuLogin() {
  fetch('/api/auth/feishu/config').then(r => r.json()).then(cfg => {
    if (cfg.enabled !== 'true' || !cfg.appId) {
      alert('飞书登录暂未开启');
      return;
    }
    const redirectUri = window.location.origin + '/api/auth/feishu/callback';
    const url = 'https://open.feishu.cn/open-apis/authen/v1/index'
      + '?redirect_uri=' + encodeURIComponent(redirectUri)
      + '&app_id=' + cfg.appId
      + '&state=' + encodeURIComponent(cfg.state || '');
    window.location.href = url;
  }).catch(() => alert('获取飞书配置失败'));
}

// 检测飞书 SSO 回调
async function checkFeishuCallback() {
  const params = new URLSearchParams(window.location.search);
  const ticket = params.get('sso_ticket');
  const error = params.get('sso_error');
  if (error) {
    alert('飞书登录失败: ' + error);
    window.history.replaceState({}, document.title, window.location.pathname);
    return false;
  }
  if (!ticket) return false;
  const response = await fetch('/api/auth/feishu/exchange', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ticket })
  });
  if (!response.ok) { alert('飞书登录票据已失效，请重新登录'); return false; }
  const data = await response.json();
  localStorage.setItem('design_pm_token', data.token);
  localStorage.setItem('design_pm_user', JSON.stringify(data.user || {}));
  window.history.replaceState({}, document.title, window.location.pathname);
  // 只保存回调 token，由 initApp 统一调用 /me 并启动一次 showApp，避免并发首屏渲染。
  return true;
}

function handleLogout() {
  // 关闭所有弹窗
  document.querySelectorAll('.modal-overlay').forEach(el => el.remove());
  const token = localStorage.getItem('design_pm_token');
  if (token) {
    fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Auth-Token': token } }).catch(() => {});
  }
  localStorage.removeItem('design_pm_token');
  localStorage.removeItem('design_pm_create_draft');
  EMIE.state.authUser = null;
  EMIE.state.originalUser = null;
  EMIE.state.currentView = 'dashboard';
  EMIE.adminState.currentTab = 'dashboard';
  EMIE.state.cache = { orders: [] };
  if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
  if (idleDisplayTimer) { clearTimeout(idleDisplayTimer); idleDisplayTimer = null; }
  idleLastActive = 0;
  const idleEl = document.getElementById('idleCountdown');
  if (idleEl) idleEl.style.display = 'none';
  showLogin();
}

// ==================== 空闲自动登出 ====================
const IDLE_TIMEOUT_MS = 60 * 60 * 1000; // 60分钟
let idleTimer = null;
let idleLastActive = 0;
let idleDisplayTimer = null;

function updateIdleDisplay() {
  if (idleDisplayTimer) { clearTimeout(idleDisplayTimer); idleDisplayTimer = null; }
  const el = document.getElementById('idleCountdown');
  if (!el) return;
  if (!localStorage.getItem('design_pm_token') || !idleLastActive) {
    el.style.display = 'none';
    return;
  }
  const elapsed = Date.now() - idleLastActive;
  const remaining = Math.max(0, Math.ceil((IDLE_TIMEOUT_MS - elapsed) / 1000));
  el.style.display = '';
  el.textContent = '⏱ ' + remaining + 's';
  if (remaining > 0) idleDisplayTimer = setTimeout(updateIdleDisplay, 500);
}

function resetIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer);
  if (idleDisplayTimer) { clearTimeout(idleDisplayTimer); idleDisplayTimer = null; }
  const token = localStorage.getItem('design_pm_token');
  if (!token) {
    idleLastActive = 0;
    const el = document.getElementById('idleCountdown');
    if (el) { el.style.display = 'none'; el.textContent = ''; }
    return;
  }
  idleLastActive = Date.now();
  // 60分钟超时不需要显示倒计时，等超时后弹窗即可

  idleTimer = setTimeout(() => {
    idleTimer = null;
    const t = localStorage.getItem('design_pm_token');
    if (t) fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Auth-Token': t } }).catch(() => {});
    localStorage.removeItem('design_pm_token');
    localStorage.removeItem('design_pm_create_draft');
    EMIE.state.authUser = null;
    EMIE.state.currentView = 'dashboard';
    EMIE.adminState.currentTab = 'dashboard';
    EMIE.state.cache = { orders: [] };
    // 弹窗提示，点击确定后跳转登录页
    showIdleLogoutModal();
  }, IDLE_TIMEOUT_MS);
}

function showIdleLogoutModal() {
  // 移除已存在的弹窗
  const old = document.getElementById('idleLogoutModal');
  if (old) old.remove();

  const overlay = document.createElement('div');
  overlay.id = 'idleLogoutModal';
  overlay.className = 'modal-overlay';
  overlay.style.zIndex = '10000';
  overlay.innerHTML = `
    <div class="modal" style="max-width:400px;text-align:center;padding:40px 30px;">
      <div style="font-size:48px;margin-bottom:16px;">⏰</div>
      <div style="font-size:18px;font-weight:600;color:var(--gray-800);margin-bottom:8px;">登录超时</div>
      <p style="font-size:14px;color:var(--gray-500);margin-bottom:24px;">长时间未操作，已自动退出登录<br>请重新登录系统</p>
      <button class="btn btn-primary btn-lg" data-emie-onclick="closeIdleLogoutModal()" style="width:100%;justify-content:center;padding:10px 0;">确 定</button>
    </div>
  `;
  document.body.appendChild(overlay);
}

function closeIdleLogoutModal() {
  const overlay = document.getElementById('idleLogoutModal');
  if (overlay) overlay.remove();
  showLogin();
}

function startIdleMonitor() {
  resetIdleTimer();
  if (!EMIE.state.idleMonitorInited) {
    const events = ['mousedown','mousemove','keydown','scroll','touchstart','click'];
    events.forEach(ev => document.addEventListener(ev, () => resetIdleTimer(), { passive: true }));
    EMIE.state.idleMonitorInited = true;
  }
}

function roleLabel(r) {
  const raw = String(r || '');
  const lower = raw.toLowerCase();
  const normalized = ['promotion', 'product_promotion', 'product-promotion'].includes(lower) ? 'promotion' : lower;
  return { sales: '需求方/销售', planner: '产品企划', designer: '设计师', supplychain: '供应链', promotion: '产品推广', admin: '管理员', pending: '待分配角色' }[normalized] || raw;
}


EMIE.registerActions({
  initApp,
  showLogin,
  loadPublicConfig,
  showApp,
  showPendingApproval,
  refreshPendingAccess,
  togglePassword,
  handleLogin,
  handleFeishuLogin,
  checkFeishuCallback,
  handleLogout,
  updateIdleDisplay,
  resetIdleTimer,
  showIdleLogoutModal,
  closeIdleLogoutModal,
  startIdleMonitor,
  openNotificationDeepLink,
  forceRefreshForVersion,
  roleLabel,
});

EMIE.registerModule('coreAuth', {
  initApp,
  showLogin,
  loadPublicConfig,
  showApp,
  showPendingApproval,
  refreshPendingAccess,
  togglePassword,
  handleLogin,
  handleFeishuLogin,
  checkFeishuCallback,
  handleLogout,
  startIdleMonitor,
  openNotificationDeepLink,
  forceRefreshForVersion,
  closeIdleLogoutModal,
  roleLabel,
});
