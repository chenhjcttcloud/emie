// ==================== 产品管理系统 - 应用逻辑 ====================

const API = '/api';

// ==================== API 层（带 Token） ====================
function authHeaders() {
  const t = localStorage.getItem('design_pm_token');
  return t ? { 'Content-Type': 'application/json', 'X-Auth-Token': t } : { 'Content-Type': 'application/json' };
}

async function apiGet(url) {
  const r = await fetch(API + url, { headers: authHeaders() });
  if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
  if (!r.ok) throw new Error(`GET ${url} failed: ${r.status}`);
  return r.json();
}

async function apiPost(url, data) {
  const r = await fetch(API + url, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data),
  });
  if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
  if (!r.ok) {
    const err = await r.json().catch(() => ({}));
    throw new Error(err.error || `POST ${url} failed: ${r.status}`);
  }
  return r.json();
}

// 文件上传（XMLHttpRequest 流式上传，支持进度条）
function uploadFile(file, onProgress) {
  // 客户端前置检查：限制 200MB
  const MAX_BYTES = 200 * 1024 * 1024;
  if (file.size > MAX_BYTES) {
    return Promise.reject(new Error('文件大小超过限制（最大 200MB），当前文件 ' + (file.size / 1024 / 1024).toFixed(1) + 'MB'));
  }
  return new Promise((resolve, reject) => {
    const fd = new FormData();
    fd.append('file', file);
    const token = localStorage.getItem('design_pm_token');
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/files/upload', true);
    if (token) xhr.setRequestHeader('X-Auth-Token', token);
    xhr.upload.onprogress = function(e) {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onload = function() {
      if (xhr.status === 401) { handleLogout(); reject(new Error('登录已过期')); return; }
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)); } catch(e) { reject(new Error('解析失败')); }
      } else {
        try { const err = JSON.parse(xhr.responseText); reject(new Error(err.error || '上传失败')); }
        catch(e) { reject(new Error('上传失败 (' + xhr.status + ')')); }
      }
    };
    xhr.onerror = function() { reject(new Error('网络错误')); };
    xhr.send(fd);
  });
}

async function apiPut(url, data) {
  const r = await fetch(API + url, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify(data),
  });
  if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
  if (!r.ok) {
    let msg = `PUT ${url} failed`;
    try { const err = await r.json(); if (err.error) msg = err.error; } catch(e) {}
    throw new Error(msg);
  }
  return r.json();
}

// ===== 全局防连点 =====

/** 正在异步打开中的弹窗 ID 集合（防 await 期间重复点击） */
const _modalOpening = new Set();

/** 安全尝试打开弹窗：检查是否已存在或正在打开 */
function tryOpenModal(id) {
  if (document.getElementById(id) || _modalOpening.has(id)) return false;
  _modalOpening.add(id);
  return true;
}

/** 弹窗打开完成（成功或失败都要调） */
function doneOpenModal(id) {
  _modalOpening.delete(id);
}

/** 检查是否有任何弹窗已打开 */
function isModalOpen() {
  return !!document.querySelector('.modal-overlay');
}

/** 提交按钮防连点包装器：禁用按钮 → 执行 → 恢复 */
async function submitGuard(btn, handler) {
  if (!btn || btn.disabled) return;
  btn.disabled = true;
  const orig = btn.textContent;
  btn.textContent = '⏳...';
  btn.style.opacity = '0.5';
  try {
    await handler();
  } catch (e) {
    console.error(e);
  } finally {
    btn.disabled = false;
    btn.textContent = orig;
    btn.style.opacity = '';
  }
}

async function apiDelete(url) {
  const r = await fetch(API + url, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
  if (!r.ok) {
    const err = await r.json().catch(() => ({}));
    throw new Error(err.error || `DELETE ${url} failed: ${r.status}`);
  }
  return r.json();
}

// ==================== 认证系统 ====================
let AUTH_USER = null; // { userId, name, role, title }
let ORIGINAL_USER = null; // 登录时的原始用户（切换视角时不变）
let currentRole = '';
let currentUserId = '';
let currentView = 'dashboard';
let currentFilter = 'all';
let USERS = {};
let CATEGORIES = [];
let COMPLIANCE_ITEMS = [];
let PRICE_RANGES = [];
let DEPARTMENTS = [];
let APP_CACHE = { orders: [] };

// ==================== SWR 缓存（Stale While Revalidate） ====================
const SWR_CACHE = {};
const SWR_TTL = 30000; // 缓存有效期 30 秒

/** SWR 缓存获取：先返回缓存（如有），后台静默刷新 */
async function swrFetch(key, fetcher, ttl = SWR_TTL) {
  const now = Date.now();
  const cached = SWR_CACHE[key];

  // 有缓存且在有效期内
  if (cached && now - cached.timestamp < ttl) {
    // 如果已超过静默刷新阈值（5秒），后台刷新
    if (now - cached.timestamp > 5000) {
      fetcher().then(data => { SWR_CACHE[key] = { data, timestamp: Date.now() }; }).catch(() => {});
    }
    return cached.data;
  }

  // 无缓存或已过期
  const data = await fetcher();
  SWR_CACHE[key] = { data, timestamp: Date.now() };
  return data;
}

/** 清除 SWR 缓存（操作后调用） */
function clearSWRCache(keys) {
  if (keys) {
    keys.forEach(k => delete SWR_CACHE[k]);
  } else {
    Object.keys(SWR_CACHE).forEach(k => delete SWR_CACHE[k]);
  }
}

async function initApp() {
  // 检查飞书 SSO 回调
  checkFeishuCallback();

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
        AUTH_USER = await r.json();
        // 如果有原始用户信息（模拟模式），用回原始用户判断权限
        ORIGINAL_USER = AUTH_USER.originalUserId
          ? { userId: AUTH_USER.originalUserId, role: AUTH_USER.originalRole }
          : { ...AUTH_USER };
        // 恢复上次浏览的页面（默认工作台）
        currentView = localStorage.getItem('design_pm_lastView') || 'dashboard';
        currentAdminTab = localStorage.getItem('design_pm_lastAdminTab') || 'dashboard';
        APP_CACHE = { orders: [] };
        // 重置 viewport 缩放
        const vp = document.querySelector('meta[name="viewport"]');
        if (vp) vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0';
        showApp();
        startIdleMonitor();
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
  document.getElementById('registerPage').style.display = 'none';
  document.getElementById('appContainer').style.display = 'none';
  document.getElementById('loginError').style.display = 'none';
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
          logoEl.innerHTML = `<img src="${cfg['app.logo']}" style="height:48px;width:auto;" alt="logo">`;
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

function showRegister() {
  document.getElementById('loginPage').style.display = 'none';
  document.getElementById('registerPage').style.display = '';
  document.getElementById('appContainer').style.display = 'none';
  document.getElementById('registerError').style.display = 'none';
  refreshCaptcha();
}

function showLoginPage() {
  showLogin();
}

// 图形验证码
function refreshCaptcha() {
  const key = 'reg_' + Date.now();
  document.getElementById('captchaImg').dataset.key = key;
  document.getElementById('captchaImg').src = '/api/captcha/image?key=' + key + '&t=' + Date.now();
}

// 发送邮箱验证码
function sendEmailCode() {
  alert('邮箱验证码功能已下线，请直接输入图形验证码后注册');
}

// 注册
async function handleRegister(event) {
  event.preventDefault();
  const errEl = document.getElementById('registerError');
  errEl.style.display = 'none';

  const data = {
    id: document.getElementById('regId').value.trim(),
    name: document.getElementById('regName').value.trim(),
    role: document.getElementById('regRole').value,
    phone: document.getElementById('regPhone').value.trim(),
    email: document.getElementById('regEmail').value.trim(),
    captchaKey: document.getElementById('captchaImg').dataset.key,
    captchaCode: document.getElementById('regCaptcha').value.trim(),
    password: document.getElementById('regPassword').value,
  };

  // ========== 前端校验 ==========
  if (!data.id || !data.name || !data.phone || !data.email || !data.captchaCode || !data.password) {
    errEl.textContent = '请填写所有必填项'; errEl.style.display = ''; return;
  }
  if (!/^[a-zA-Z0-9_]{3,30}$/.test(data.id)) {
    errEl.textContent = '用户ID限3-30位英文/数字/下划线'; errEl.style.display = ''; return;
  }
  if (data.name.length < 1 || data.name.length > 20 || /[<>"'\\]/.test(data.name)) {
    errEl.textContent = '姓名限1-20字，不含特殊字符'; errEl.style.display = ''; return;
  }
  if (!/^1\d{10}$/.test(data.phone)) {
    errEl.textContent = '请输入正确的11位手机号'; errEl.style.display = ''; return;
  }
  if (!/^[\w.-]+@[\w.-]+\.\w{2,}$/.test(data.email)) {
    errEl.textContent = '邮箱格式不正确'; errEl.style.display = ''; return;
  }
  if (!/^\d{4}$/.test(data.captchaCode)) {
    errEl.textContent = '图形验证码为4位数字'; errEl.style.display = ''; return;
  }
  if (data.password.length < 6 || data.password.length > 30) {
    errEl.textContent = '密码长度6-30位'; errEl.style.display = ''; return;
  }

  try {
    const r = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    const result = await r.json();
    if (!r.ok) {
      errEl.textContent = result.error || '注册失败';
      errEl.style.display = '';
      refreshCaptcha();
      return;
    }
    // 注册成功，自动登录
    localStorage.setItem('design_pm_token', result.token);
    AUTH_USER = { userId: result.userId, name: result.name, role: result.role, title: result.title };
    showApp();
  } catch (e) {
    errEl.textContent = '网络错误，请重试';
    errEl.style.display = '';
    refreshCaptcha();
  }
}

async function showApp() {
  document.getElementById('loginPage').style.display = 'none';
  document.getElementById('appContainer').style.display = '';

  // 加载公开配置更新头部
  try {
    const r = await fetch('/api/admin/public-config');
    if (r.ok) {
      const cfg = await r.json();
      if (cfg['app.title']) document.title = cfg['app.title'] + ' - EMIE';
      const logoEl = document.querySelector('.logo');
      if (logoEl) {
        if (cfg['app.logo']) {
          logoEl.innerHTML = `<img src="${cfg['app.logo']}" style="height:32px;width:auto;vertical-align:middle;margin-right:8px;" alt="logo"><span>${cfg['app.title'] || '产品管理系统'}</span>`;
        } else if (cfg['app.logoEmoji']) {
          logoEl.innerHTML = `${cfg['app.logoEmoji']} ${cfg['app.title'] || '产品管理系统'}<span>${cfg['app.subtitle'] || 'Product Management'}</span>`;
        } else {
          logoEl.innerHTML = `🎨 ${cfg['app.title'] || '产品管理系统'}<span>${cfg['app.subtitle'] || 'Product Management'}</span>`;
        }
      }
    }
  } catch(e) { /* ignore */ }

  document.getElementById('userDisplay').textContent = `${AUTH_USER.name}（${roleLabel(AUTH_USER.role)}）`;
  currentRole = AUTH_USER.role;
  currentUserId = AUTH_USER.userId;

  // 渲染角色切换器（admin 可用）
  renderRoleSwitcher();

  // 加载用户列表（用于下拉框）
  try { USERS = await apiGet('/users'); } catch(e) { USERS = {}; }
  // 加载产品类目列表
  try { CATEGORIES = await apiGet('/categories'); } catch(e) { CATEGORIES = []; }
  // 加载合规处罚列表
  try { COMPLIANCE_ITEMS = await apiGet('/compliance'); } catch(e) { COMPLIANCE_ITEMS = []; }
  // 加载参考零售价列表
  try { PRICE_RANGES = await apiGet('/price-ranges'); } catch(e) { PRICE_RANGES = []; }
  try { DEPARTMENTS = await apiGet('/departments'); } catch(e) { DEPARTMENTS = []; }
  // 初始加载时也刷新用户列表（包含部门分配信息）
  try { USERS = await apiGet('/users'); } catch(e) {}
  // 用户列表加载后重新渲染切换器（否则下拉选项为空）
  renderRoleSwitcher();
  renderSidebar();
  render();
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
    AUTH_USER = { userId: data.userId, name: data.name, role: data.role, title: data.title };
    ORIGINAL_USER = { ...AUTH_USER };
    // 重置 viewport 缩放（修复 iOS 输入框放大后不恢复的问题）
    const vp = document.querySelector('meta[name="viewport"]');
    if (vp) vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0';
    // 重置视图状态，避免不同账号切换时看到上任用户的页面
    currentView = 'dashboard';
    currentAdminTab = 'dashboard';
    APP_CACHE = { orders: [] };
    showApp();
    startIdleMonitor();
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
      + '&state=' + Date.now();
    window.location.href = url;
  }).catch(() => alert('获取飞书配置失败'));
}

// 检测飞书 SSO 回调
function checkFeishuCallback() {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('sso_token');
  const error = params.get('sso_error');
  if (error) {
    alert('飞书登录失败: ' + error);
    window.history.replaceState({}, document.title, window.location.pathname);
    return;
  }
  if (token) {
    const userId = params.get('userId') || '';
    const userName = params.get('userName') || '';
    const role = params.get('role') || '';
    localStorage.setItem('design_pm_token', token);
    localStorage.setItem('design_pm_user', JSON.stringify({ userId, userName, role }));
    window.history.replaceState({}, document.title, window.location.pathname);
    showApp();
  }
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
  AUTH_USER = null;
  ORIGINAL_USER = null;
  currentView = 'dashboard';
  currentAdminTab = 'dashboard';
  APP_CACHE = { orders: [] };
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
    AUTH_USER = null;
    currentView = 'dashboard';
    currentAdminTab = 'dashboard';
    APP_CACHE = { orders: [] };
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
      <button class="btn btn-primary btn-lg" onclick="closeIdleLogoutModal()" style="width:100%;justify-content:center;padding:10px 0;">确 定</button>
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
  if (!window._idleMonitorInited) {
    const events = ['mousedown','mousemove','keydown','scroll','touchstart','click'];
    events.forEach(ev => document.addEventListener(ev, () => resetIdleTimer(), { passive: true }));
    window._idleMonitorInited = true;
  }
}

function roleLabel(r) {
  return { sales: '需求方/销售', planner: '产品企划', designer: '设计师', supplychain: '供应链', admin: '管理员' }[r] || r;
}

// ==================== 用户视角切换（天花板版） ====================
const ROLE_LABELS = { admin: '管理员', sales: '销售', planner: '企划', designer: '设计师', supplychain: '供应链' };
const ROLE_COLORS = { admin: 'admin', sales: 'sales', planner: 'planner', designer: 'designer', supplychain: 'supplychain' };

function renderRoleSwitcher() {
  const headerRight = document.querySelector('.header-right');
  const old = document.getElementById('identitySwitcher');
  if (old) old.remove();
  // 仅 admin 显示切换
  if (!ORIGINAL_USER || (ORIGINAL_USER.role !== 'admin')) return;
  if (!USERS || Object.keys(USERS).length === 0) return;

  // 构建所有用户列表
  const allUsers = [];
  const roleOrder = ['sales', 'planner', 'designer', 'supplychain', 'admin'];
  for (const role of roleOrder) {
    const users = USERS[role];
    if (!users || users.length === 0) continue;
    for (const u of users) {
      allUsers.push({ userId: u.userId, name: u.name, role });
    }
  }

  const isSwitched = currentUserId !== ORIGINAL_USER.userId;
  const initial = AUTH_USER.name.charAt(0);
  const roleKey = ROLE_COLORS[AUTH_USER.role] || 'admin';

  const container = document.createElement('div');
  container.id = 'identitySwitcher';
  container.className = 'identity-switcher';
  container.innerHTML = `
    <button class="identity-trigger" onclick="toggleIdentityPanel(event)" aria-label="切换用户视角" title="切换用户视角">
      <span class="identity-avatar role-${roleKey}">${initial}</span>
      <span class="identity-info">
        <span class="identity-name">${AUTH_USER.name}</span>
        <span class="identity-role-tag">${ROLE_LABELS[AUTH_USER.role] || AUTH_USER.role}</span>
      </span>
      <svg class="identity-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
    </button>
    <div class="identity-panel" id="identityPanel">
      <div class="identity-search">
        <svg class="identity-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
        <input type="text" id="identitySearchInput" placeholder="搜索用户..." oninput="filterUsers(this.value)" autocomplete="off">
      </div>
      <div class="identity-current">
        <span>当前身份</span>
        <span class="identity-current-line"></span>
      </div>
      <div class="identity-list" id="identityUserList">
        ${renderUserList(allUsers)}
      </div>
      ${isSwitched ? `
      <div style="padding:4px 10px 10px;">
        <button class="identity-back-btn" onclick="switchToUser('${ORIGINAL_USER.userId}')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
          返回我的视角
        </button>
      </div>` : ''}
    </div>
  `;

  const userDisplay = document.getElementById('userDisplay');
  if (userDisplay && userDisplay.nextSibling) {
    headerRight.insertBefore(container, userDisplay.nextSibling);
  } else {
    headerRight.appendChild(container);
  }
}

function renderUserList(users) {
  const groups = {};
  for (const u of users) {
    if (!groups[u.role]) groups[u.role] = [];
    groups[u.role].push(u);
  }
  const roleOrder = ['sales', 'planner', 'designer', 'supplychain', 'admin'];
  let html = '';
  for (const role of roleOrder) {
    const group = groups[role];
    if (!group || group.length === 0) continue;
    const label = ROLE_LABELS[role] || role;
    const dotClass = 'g-' + (ROLE_COLORS[role] || role);
    html += `<div class="identity-group" data-role="${role}">
      <div class="identity-group-label">
        <span class="identity-group-dot ${dotClass}"></span>${label}
      </div>`;
    for (const u of group) {
      const isActive = u.userId === currentUserId;
      const avatarInitial = u.name.charAt(0);
      const uClass = 'u-' + (ROLE_COLORS[u.role] || u.role);
      const rClass = 'r-' + (ROLE_COLORS[u.role] || u.role);
      html += `<div class="identity-user${isActive ? ' active' : ''}" data-userid="${u.userId}" onclick="switchToUser('${u.userId}')">
        <span class="identity-user-avatar ${uClass}">${avatarInitial}</span>
        <span class="identity-user-info">
          <div class="identity-user-name">${escHtml(u.name)}</div>
          <div class="identity-user-id">${escHtml(u.userId)}</div>
        </span>
        <span class="identity-user-role ${rClass}">${label}</span>
        <svg class="identity-user-check${isActive ? ' show' : ''}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>
      </div>`;
    }
    html += '</div>';
  }
  return html || '<div class="identity-empty">暂无用户</div>';
}

async function switchToUser(targetUserId) {
  if (!targetUserId || targetUserId === currentUserId) return;
  closeIdentityPanel();
  try {
    const result = await apiPost('/auth/impersonate', { userId: targetUserId });
    AUTH_USER = { userId: result.userId, name: result.name, role: result.role, title: result.title };
    currentRole = result.role;
    currentUserId = result.userId;
    document.getElementById('userDisplay').textContent = `${AUTH_USER.name}（${roleLabel(AUTH_USER.role)}）`;
    renderRoleSwitcher();
    renderSidebar();
    render();
    currentView = 'dashboard';
  } catch (e) {
    alert(e.message || '切换失败');
  }
}

function toggleIdentityPanel(event) {
  event.stopPropagation();
  const panel = document.getElementById('identityPanel');
  const chevron = document.querySelector('.identity-chevron');
  if (!panel) return;
  const isOpen = panel.classList.contains('open');
  if (isOpen) {
    closeIdentityPanel();
  } else {
    panel.classList.add('open');
    chevron.classList.add('open');
    // 清除搜索
    const input = document.getElementById('identitySearchInput');
    if (input) { input.value = ''; filterUsers(''); }
    // 点击外部关闭
    setTimeout(() => document.addEventListener('click', closeIdentityPanel, { once: true }), 0);
  }
}

function closeIdentityPanel() {
  const panel = document.getElementById('identityPanel');
  const chevron = document.querySelector('.identity-chevron');
  if (panel) panel.classList.remove('open');
  if (chevron) chevron.classList.remove('open');
}

function filterUsers(query) {
  const q = query.toLowerCase().trim();
  const items = document.querySelectorAll('.identity-user');
  const groups = document.querySelectorAll('.identity-group');
  let hasVisible = false;
  items.forEach(item => {
    const name = item.querySelector('.identity-user-name')?.textContent.toLowerCase() || '';
    const id = item.querySelector('.identity-user-id')?.textContent.toLowerCase() || '';
    const match = !q || name.includes(q) || id.includes(q);
    item.classList.toggle('hidden', !match);
    if (match) hasVisible = true;
  });
  groups.forEach(g => {
    const visible = [...g.querySelectorAll('.identity-user')].some(el => !el.classList.contains('hidden'));
    g.classList.toggle('hidden', !visible);
  });
}

function getCurrentUserName() {
  if (currentUserId && USERS[currentRole]) {
    const u = USERS[currentRole].find(x => x.id === currentUserId);
    if (u) return u.name;
  }
  return USERS[currentRole]?.[0]?.name || '未知';
}

function getCurrentUserId() {
  if (currentUserId) return currentUserId;
  return USERS[currentRole]?.[0]?.userId || '';
}

function getUserName(id) {
  for (const arr of Object.values(USERS)) {
    const u = arr.find(x => x.userId === id);
    if (u) return u.name;
  }
  return id || '未知';
}

// ==================== 侧边栏 ====================
function renderSidebar() {
  const navs = [];

  if (currentRole === 'sales') {
    // 销售：工作台、全部项目、渠道定制单、公司常规品、待评分
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'scoring', icon: '⭐', label: '待评分', badge: 'badgeScoring' });
  } else if (currentRole === 'admin') {
    // 管理员：工作台 + 系统管理
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'tasks', icon: '📌', label: '我的子任务', badge: 'badgeMyTasks' });
    navs.push({ view: 'scoring', icon: '⭐', label: '待评分', badge: 'badgeScoring' });
    navs.push({ view: 'admin', icon: '⚙️', label: '系统管理', badge: '' });
  } else {
    // 其他角色：显示全部导航
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'orders', icon: '📋', label: '全部项目', badge: 'badgeTotal' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'regular', icon: '🏭', label: '公司常规品', badge: 'badgeRegular' });
    navs.push({ view: 'tasks', icon: '📌', label: '我的子任务', badge: 'badgeMyTasks' });
    navs.push({ view: 'scoring', icon: '⭐', label: '待评分', badge: 'badgeScoring' });
  }

  const sidebar = document.getElementById('sidebarContainer');
  if (!sidebar) return;

  sidebar.innerHTML = navs.map(n => `
    <button class="nav-item ${n.view === currentView ? 'active' : ''}" onclick="navigate('${n.view}')">
      <span class="nav-icon">${n.icon}</span>${n.label}
      ${n.badge ? `<span class="nav-badge" id="${n.badge}">0</span>` : ''}
    </button>
  `).join('');
}

// ==================== 导航 ====================
function navigate(view) {
  currentView = view;
  // 保存当前浏览页面（刷新后恢复）
  localStorage.setItem('design_pm_lastView', view);
  renderSidebar();
  render();
  closeMobileSidebar();
  // 移动端切换页面后回顶部（延迟确保渲染完成）
  setTimeout(() => {
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
  }, 50);
}

// ==================== 移动端侧栏 ====================
function toggleMobileSidebar() {
  const sidebar = document.getElementById('sidebarContainer');
  const overlay = document.getElementById('sidebarOverlay');
  if (!sidebar || !overlay) return;
  sidebar.classList.toggle('open');
  overlay.classList.toggle('open');
}

function closeMobileSidebar() {
  const sidebar = document.getElementById('sidebarContainer');
  const overlay = document.getElementById('sidebarOverlay');
  if (!sidebar || !overlay) return;
  sidebar.classList.remove('open');
  overlay.classList.remove('open');
}

// ==================== 状态标签 ====================
function getProjectStatusInfo(status) {
  return {
    draft: { label: '草稿', cls: 'badge-pending' },
    pending_planner: { label: '待企划接单', cls: 'badge-pending' },
    planner_accepted: { label: '企划已接单', cls: 'badge-progress' },
    in_progress: { label: '进行中', cls: 'badge-progress' },
    paused: { label: '已暂停', cls: 'badge-pending' },
    pending_terminate: { label: '终止确认中', cls: 'badge-rejected' },
    terminated: { label: '已终止', cls: 'badge-rejected' },
    completed: { label: '已完成', cls: 'badge-completed' },
    completed_pending_score: { label: '待评分', cls: 'badge-pending' },
  }[status] || { label: status, cls: '' };
}

function getTaskStatusInfo(status) {
  return {
    pending: { label: '待接单', cls: 'badge-pending', icon: '⏳' },
    accepted: { label: '设计中', cls: 'badge-progress', icon: '🎨' },
    delivered: { label: '待验收', cls: 'badge-pending', icon: '📤' },
    planner_approved: { label: '待评分', cls: 'badge-pending', icon: '⏳' },
    sales_approved: { label: '待确认', cls: 'badge-pending', icon: '⏳' },
    admin_approved: { label: '待确认', cls: 'badge-pending', icon: '⏳' },
    approved: { label: '已通过', cls: 'badge-completed', icon: '✅' },
    completed: { label: '已完成', cls: 'badge-completed', icon: '✅' },
    rejected: { label: '已驳回', cls: 'badge-rejected', icon: '↩️' },
  }[status] || { label: status, cls: '', icon: '❓' };
}

// ==================== 格式化 ====================
function formatDate(d) {
  if (!d) return '-';
  const m = d.match(/^\d{4}-\d{2}-\d{2}/);
  return m ? m[0] : d;
}

/** 渲染项目评分（带颜色） */
function renderScore(score) {
  if (score === null || score === undefined) return '<span style="color:var(--gray-300);">-</span>';
  const num = parseFloat(score);
  if (isNaN(num)) return '<span style="color:var(--gray-300);">-</span>';
  let color;
  if (num >= 8) color = '#3B6D11';
  else if (num >= 6) color = '#854F0B';
  else if (num >= 4) color = '#A32D2D';
  else color = 'var(--gray-400)';
  return `<span style="font-weight:600;color:${color};">${num.toFixed(1)}</span>`;
}

function fmtDT(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function fmtSize(s) {
  if (!s) return '';
  if (s < 1024) return s + 'B';
  if (s < 1048576) return (s / 1024).toFixed(1) + 'KB';
  return (s / 1048576).toFixed(1) + 'MB';
}

// 标记表单已修改
function formModified() { _formModified = true; }
function renderDatePicker(name, opts = {}) {
  const val = opts.value || '';
  const req = opts.required ? 'required' : '';
  const ph = opts.placeholder || 'yyyy-mm-dd';
  return `<div class="date-picker" style="position:relative;">
    <input type="text" class="form-input" name="${name}" value="${val}" ${req} placeholder="${ph}" autocomplete="off" style="min-height:38px;" oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">
    <button type="button" class="date-picker-btn" onclick="triggerDatePicker(this)" title="选择日期">📅</button>
  </div>`;
}

function triggerDatePicker(btn) {
  const textInput = btn.closest('.date-picker')?.querySelector('input[type="text"]');
  if (!textInput) return;

  const temp = document.createElement('input');
  temp.type = 'date';
  temp.min = new Date().toISOString().split('T')[0];
  const rect = btn.getBoundingClientRect();
  temp.style.cssText = 'position:fixed;left:' + (rect.left + window.scrollX) + 'px;top:' + (rect.top + window.scrollY) + 'px;width:40px;height:38px;opacity:0.01;z-index:9999;';
  btn.parentNode.appendChild(temp);

  temp.focus();
  if (typeof temp.showPicker === 'function') {
    temp.showPicker();
  } else {
    temp.click();
  }

  temp.addEventListener('change', function onChange() {
    temp.removeEventListener('change', onChange);
    if (temp.value) {
      textInput.value = temp.value;
      textInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    safeRemove(temp);
  });

  temp.addEventListener('blur', function onBlur() {
    setTimeout(() => safeRemove(temp), 300);
  });
}

function safeRemove(el) {
  if (el && el.parentNode) el.parentNode.removeChild(el);
}

function escHtml(s) {
  if (!s) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// ==================== Modal 工具 ====================
function closeM(id, force) {
  // 创建项目弹窗关闭前做草稿检查（提交成功后 force=true 跳过）
  if (id === 'createProjectModal' && !force) {
    // 只在实际修改了表单内容时才弹出保存提示
    if (_formModified) {
      showSaveConfirmModal();
      return;
    }
  }
  document.getElementById(id)?.remove();
  doneOpenModal(id);
}

// 安全创建模态框（防止重复点击出现多个）
function openModal(id) {
  if (isModalOpen()) return;
  const existing = document.getElementById(id);
  if (existing) { existing.remove(); }
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = id;
  document.body.appendChild(modal);
  return modal;
}

// 弹窗关闭确认
function showSaveConfirmModal() {
  if (isModalOpen()) return;
  const overlay = document.getElementById('createProjectModal');
  if (!overlay) return;
  const confirm = document.createElement('div');
  confirm.className = 'modal-overlay';
  confirm.id = 'saveConfirmOverlay';
  confirm.style.zIndex = '300';
  confirm.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header">
        <button class="modal-close" onclick="document.getElementById('saveConfirmOverlay')?.remove()">✕</button>
        <div class="modal-header-left"><div class="modal-title">💾 未保存的内容</div></div>
      </div>
      <div class="modal-body" style="text-align:center;padding:32px 24px;">
        <div style="font-size:40px;margin-bottom:12px;">📝</div>
        <p style="font-size:15px;color:var(--gray-700);margin-bottom:4px;">当前表单有已填写的内容</p>
        <p style="font-size:13px;color:var(--gray-500);">是否保存为草稿以便稍后继续？</p>
      </div>
      <div class="modal-footer" style="justify-content:center;gap:12px;">
        <button class="btn btn-outline" onclick="discardCreateDraft()" style="padding:10px 24px;">不保存</button>
        <button class="btn btn-primary" onclick="saveCreateDraft()" style="padding:10px 24px;">保存草稿</button>
      </div>
    </div>`;
  document.body.appendChild(confirm);
}

// 保存草稿
function saveCreateDraft() {
  const form = document.getElementById('createProjectForm');
  if (!form) return;
  const fd = new FormData(form);
  const draft = Object.fromEntries(fd.entries());
  draft._refImages = _createRefImages;
  draft._attachments = _createAttachments;
  draft._type = window._createProjectType || 'channel_custom';
  sessionStorage.setItem('design_pm_create_draft', JSON.stringify(draft));
  document.getElementById('saveConfirmOverlay')?.remove();
  closeM('createProjectModal', true); // force 关闭，不再弹出保存提示
}

// 丢弃草稿
function discardCreateDraft() {
  sessionStorage.removeItem('design_pm_create_draft');
  document.getElementById('saveConfirmOverlay')?.remove();
  closeM('createProjectModal', true); // force 关闭，不再弹出保存提示
}



function showLoading(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
}

// ==================== 主渲染 ====================
/** 操作后增量刷新（轻量版：不重拉全量列表） */
async function refreshAfterMutation(pid) {
  // 清除所有缓存
  APP_CACHE.orders = [];
  Object.keys(SWR_CACHE).forEach(k => delete SWR_CACHE[k]);

  // 并发：更新徽章 + 刷新视图
  await Promise.all([
    // 更新侧边栏徽章
    (async () => {
      try {
        const badgeStats = await apiGet(`/projects/badge-stats?role=${currentRole}&userId=${getCurrentUserId()}`);
        const elScoring = document.getElementById('badgeScoring');
        if (elScoring) elScoring.textContent = badgeStats.pendingScoreCount || 0;
        const elMyTasks = document.getElementById('badgeMyTasks');
        if (elMyTasks) elMyTasks.textContent = badgeStats.myTaskCount || 0;
        const badgeTotal = document.getElementById('badgeTotal');
        if (badgeTotal) badgeTotal.textContent = badgeStats.totalCount || 0;
        const badgeChannel = document.getElementById('badgeChannel');
        if (badgeChannel) badgeChannel.textContent = badgeStats.channelCount || 0;
        const badgeRegular = document.getElementById('badgeRegular');
        if (badgeRegular) badgeRegular.textContent = badgeStats.regularCount || 0;
      } catch(e) {}
    })(),

    // 按当前视图刷新
    (async () => {
      if (currentView === 'tasks' || currentView === 'scoring') {
        await render();
      } else if (pid) {
        try {
          const detail = await apiGet(`/projects/${pid}`);
          updateProjectRow(pid, detail);
          if (document.getElementById('projectDetailModal')) {
            const body = document.querySelector('#projectDetailModal .modal-body');
            const footer = document.getElementById('detailActions');
            if (body) body.innerHTML = renderProjectDetailContent(detail);
            if (footer) footer.innerHTML = renderProjectActions(detail);
          }
        } catch(e) {}
      }
    })(),
  ]);
}

/** 更新列表中单个项目的行数据 */
function updateProjectRow(pid, detail) {
  const container = document.getElementById('projectListContainer');
  if (!container) return;
  // 从缓存中找到对应的项目数据并更新
  if (APP_CACHE.currentFilterData) {
    const idx = APP_CACHE.currentFilterData.findIndex(o => o.id === pid);
    if (idx >= 0) {
      // 用最新的 detail 数据合并到列表缓存中
      const st = getProjectStatusInfo(detail.status);
      APP_CACHE.currentFilterData[idx] = {
        ...APP_CACHE.currentFilterData[idx],
        status: detail.status,
        statusLabel: st.label,
        statusCls: st.cls,
        taskCount: detail.tasks?.length || 0,
        approvedTaskCount: detail.tasks?.filter(t => t.status === 'completed' || t.status === 'approved' || t.status === 'sales_approved' || t.status === 'admin_approved').length || 0,
        progressPercent: detail.progressPercent || 0,
        plannerName: detail.plannerName,
        salesName: detail.salesName,
      };
      // 如果当前表格可见，只替换对应行
      const rows = container.querySelectorAll('tbody tr');
      const targetRow = Array.from(rows).find(r => r.cells[0]?.textContent?.trim() === `#${pid}`);
      if (targetRow) {
        // 只是重新渲染这一行
        const newRowHtml = renderProjectRow(APP_CACHE.currentFilterData[idx]);
        targetRow.outerHTML = newRowHtml;
      } else {
        // 如果找不到行，整表重绘（兜底）
        container.innerHTML = renderProjectTable(APP_CACHE.currentFilterData);
      }
    }
  }
}

/** 渲染单行项目 */
function renderProjectRow(o) {
  const st = getProjectStatusInfo(o.status);
  return `<tr style="cursor:pointer;">
    <td><strong>#${o.id}</strong></td>
    <td style="font-size:12px;">${o.type === 'channel_custom' ? '📦 渠道' : '🏭 常规'}</td>
    <td>${o.salesName || '-'}</td>
    <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
    <td>${o.productCategory || '-'}</td>
    <td>${o.targetMarket ? (() => { try { return JSON.parse(o.targetMarket).join('/'); } catch(e) { return o.targetMarket; } })() : '-'}</td>
    <td style="font-size:12px;">${o.priceRange || '-'}</td>
    <td style="font-size:12px;">${o.approvedTaskCount}/${o.taskCount}</td>
    <td style="font-size:12px;">${renderScore(o.score)}</td>
    <td style="font-size:12px;">${formatDate(o.deadline)}</td>
    <td><span class="badge ${st.cls}" style="font-size:11px;">${st.label}</span></td>
    <td style="white-space:nowrap;">
      <button class="btn btn-outline btn-sm" onclick="event.stopPropagation();openProjectDetail(${o.id})">查看</button>
      ${o.status === 'pending_planner' && currentRole === 'planner' ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();plannerAcceptFromList(${o.id})" style="color:var(--success);border-color:var(--success);margin-left:4px;">接单</button>` : ''}
      ${['planner_accepted','in_progress','paused'].includes(o.status) ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();${o.status === 'paused' ? `resumeProject(${o.id})` : `pauseProject(${o.id})`}" style="font-size:11px;margin-left:4px;color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};border-color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};">${o.status === 'paused' ? '▶ 继续' : '⏸ 暂停'}</button>` : ''}
    </td>
  </tr>`;
}

async function render() {
  const main = document.getElementById('mainContent');
  showLoading(main);

  // 页面导航时不清空缓存（依赖 mutation 操作清空），导航切换无需重新拉取
  // 首次加载或缓存为空时才拉取

  try {
    const role = currentRole;
    const uid = getCurrentUserId();

    // 预加载项目列表（使用缓存避免重复请求）
    let orders = APP_CACHE.orders;
    if (!orders || !orders.length) {
      try { orders = await apiGet(`/projects?role=${role}&userId=${uid}`); } catch(e) {}
      APP_CACHE.orders = orders || [];
    }

    // 更新基础徽章（销售角色没有 total/regular 徽章，需要判空）
    const elTotal = document.getElementById('badgeTotal');
    if (elTotal) elTotal.textContent = orders.length;
    const elChannel = document.getElementById('badgeChannel');
    if (elChannel) elChannel.textContent = orders.filter(x => x.type === 'channel_custom').length;
    const elRegular = document.getElementById('badgeRegular');
    if (elRegular) elRegular.textContent = orders.filter(x => x.type === 'regular').length;

    if (currentView === 'dashboard') {
      await renderDashboard(main, role, uid);
    } else if (currentView === 'orders') {
      await renderOrderList(main, null, role, uid);
    } else if (currentView === 'channel') {
      await renderOrderList(main, 'channel_custom', role, uid);
    } else if (currentView === 'regular') {
      await renderOrderList(main, 'regular', role, uid);
    } else if (currentView === 'tasks') {
      await renderMyTasks(main, role, uid);
    } else if (currentView === 'scoring') {
      await renderScoringView(main, role, uid);
    } else if (currentView === 'admin' || currentView === 'logs') {
      await renderAdmin(main, role, uid);
    }

    // 计算徽章（独立 try/catch，不影响主渲染）
    try {
      let pendingScoreCount = 0;
      let myTaskCount = 0;
      // 使用批量徽章统计 API，避免 N 次详情查询
      const badgeStats = await apiGet(`/projects/badge-stats?role=${role}&userId=${uid}`);
      pendingScoreCount = badgeStats.pendingScoreCount || 0;
      myTaskCount = badgeStats.myTaskCount || 0;

      // 我的子任务（企划/管理员：进行中的项目数）
      if (role !== 'sales' && role !== 'designer' && role !== 'supplychain') {
        myTaskCount = orders.filter(o =>
          o.status === 'in_progress' || o.status === 'planner_accepted'
        ).length;
      }
      const elScoring = document.getElementById('badgeScoring');
      if (elScoring) elScoring.textContent = pendingScoreCount;
      if (document.getElementById('badgeMyTasks')) {
        document.getElementById('badgeMyTasks').textContent = myTaskCount;
      }
    } catch (e) {
      console.error('徽章计算错误:', e);
    }
  } catch (e) {
    console.error('渲染错误:', e);
    main.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

// ==================== 徽章更新 ====================
async function updateBadges(role, uid) {
  try {
    const orders = await apiGet(`/projects?role=${role}&userId=${uid}`);

    if (role === 'designer' || role === 'supplychain') {
      // 设计师：只统计自己有关联任务的项目数
      let designerProjectCount = 0;
      let designerTaskCount = 0;
      let channelCount = 0;
      let regularCount = 0;
      for (const order of orders) {
        const detail = await apiGet(`/projects/${order.id}`);
        if (!detail.tasks) continue;
        const myTasks = detail.tasks.filter(t => t.designerId === uid);
        if (myTasks.length > 0) {
          designerProjectCount++;
          if (order.type === 'channel_custom') channelCount++;
          else regularCount++;
        }
        designerTaskCount += detail.tasks.filter(t =>
          t.designerId === uid && ['pending', 'accepted', 'rejected'].includes(t.status)
        ).length;
      }
      document.getElementById('badgeTotal').textContent = designerProjectCount;
      document.getElementById('badgeChannel').textContent = channelCount;
      document.getElementById('badgeRegular').textContent = regularCount;
      document.getElementById('badgeMyTasks').textContent = designerTaskCount;
    } else {
      document.getElementById('badgeTotal').textContent = orders.length;
      document.getElementById('badgeChannel').textContent = orders.filter(x => x.type === 'channel_custom').length;
      document.getElementById('badgeRegular').textContent = orders.filter(x => x.type === 'regular').length;
    }
    document.getElementById('badgeScoring').textContent = '0';

    // 计算待评分数量（与 dashboard 统计逻辑对齐）
    let pendingScoreCount = 0;
    for (const order of orders) {
      const detail = await apiGet(`/projects/${order.id}`);
      if (!detail.tasks) continue;
      for (const t of detail.tasks) {
        if (t.status === 'approved' && t.scoringRecords) {
          const needMe = detail.type === 'channel_custom'
            ? (role === 'sales' || role === 'planner')
            : (role === 'planner');
          if (!needMe) continue;
          const myRecord = t.scoringRecords.find(sr => sr.role === role);
          if (myRecord && myRecord.score == null) pendingScoreCount++;
        }
      }
    }
    const elScoring = document.getElementById('badgeScoring');
    if (elScoring) elScoring.textContent = pendingScoreCount;

    let myTasks = 0;
    if (currentRole === 'designer' || currentRole === 'supplychain') {
      const myId = getCurrentUserId();
      for (const order of orders) {
        const detail = await apiGet(`/projects/${order.id}`);
        if (!detail.tasks) continue;
        for (const t of detail.tasks) {
          if (t.designerId === myId && ['pending', 'accepted', 'rejected'].includes(t.status)) myTasks++;
        }
      }
    } else if (currentRole === 'sales') {
      // 销售：统计渠道定制项目中待处理的子任务数
      for (const order of orders) {
        if (order.type !== 'channel_custom') continue;
        const detail = await apiGet(`/projects/${order.id}`);
        if (!detail.tasks) continue;
        for (const t of detail.tasks) {
          if (['pending', 'accepted', 'rejected', 'delivered'].includes(t.status)) myTasks++;
        }
      }
    } else if (currentRole === 'planner') {
      // 企划：统计分配给自己的待处理子任务数
      const myId = getCurrentUserId();
      for (const order of orders) {
        const detail = await apiGet(`/projects/${order.id}`);
        if (!detail.tasks) continue;
        for (const t of detail.tasks) {
          if (t.designerId === myId && ['pending', 'accepted', 'rejected'].includes(t.status)) myTasks++;
        }
      }
    } else {
      document.getElementById('badgeMyTasks').textContent = myTasks;
    }
  } catch (e) {
    console.error('徽章更新失败:', e);
  }
}

// ==================== 工作台 ====================
async function renderDashboard(main, role, uid) {
  // 使用 SWR 缓存 + 聚合端点（1次API替代8次）
  const cacheKey = `dashboard_${role}_${uid}`;
  const data = await swrFetch(cacheKey,
    () => apiGet(`/dashboard/full?role=${role}&userId=${uid}`),
    30000
  );

  const orders = data.orders || [];
  const stats = data.stats || {};
  const roleStatus = data.roleStatus || {};
  DEPARTMENTS = data.departments || [];

  // 更新全局缓存
  APP_CACHE.orders = orders;

  const channel = orders.filter(o => o.type === 'channel_custom');
  const regular = orders.filter(o => o.type === 'regular');

  // 角色状态面板（使用聚合数据中的 roleStatus）
  let rolePanelsHtml = '';
  const myDept = DEPARTMENTS.find(d => d.headUserId === uid);

  if (currentRole === 'admin') {
    // 管理员：显示全部四个角色面板（直接从聚合数据渲染，不额外请求）
    const salesHtml = renderRolePanelFromData(roleStatus.sales || {}, 'sales');
    const plannerHtml = renderRolePanelFromData(roleStatus.planner || {}, 'planner');
    const supplyHtml = renderRolePanelFromData(roleStatus.supplychain || {}, 'supplychain');
    const designerHtml = renderRolePanelFromData(roleStatus.designer || {}, 'designer');
    rolePanelsHtml = salesHtml + plannerHtml + supplyHtml + designerHtml;
  } else if (currentRole === 'planner') {
    const deptId = myDept?.id || null;
    const plannerHtml = renderRolePanelFromData(roleStatus.planner || {}, 'planner', deptId, uid);
    const designerHtml = renderRolePanelFromData(roleStatus.designer || {}, 'designer');
    const supplyHtml = renderRolePanelFromData(roleStatus.supplychain || {}, 'supplychain');
    rolePanelsHtml = plannerHtml + designerHtml + supplyHtml;
  } else if (myDept) {
    rolePanelsHtml = renderRolePanelFromData(roleStatus[myDept.role] || {}, myDept.role, myDept.id, uid);
  }

  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">📊 工作台 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">— ${getCurrentUserName()}（${roleLabel(currentRole)}）</span></h2>
    <div class="stats-grid">
      <div class="stat-card" style="cursor:pointer" onclick="navigate('orders')"><div class="stat-icon blue">📁</div><div><div class="stat-value">${stats.totalProjects}</div><div class="stat-label">${currentRole === 'admin' ? '全部项目' : '我的项目'}</div></div></div>
      <div class="stat-card" style="cursor:pointer" onclick="navigate('tasks')"><div class="stat-icon blue">📌</div><div><div class="stat-value">${stats.allTasks}</div><div class="stat-label">子任务总数</div></div></div>
      <div class="stat-card" style="cursor:pointer" onclick="navigate('tasks')"><div class="stat-icon yellow">⏳</div><div><div class="stat-value">${stats.pendingTasks}</div><div class="stat-label">待处理子任务</div></div></div>
      <div class="stat-card" style="cursor:pointer" onclick="navigate('tasks')"><div class="stat-icon green">✅</div><div><div class="stat-value">${stats.approvedTasks}</div><div class="stat-label">已完成子任务</div></div></div>
    </div>
    ${currentRole === 'sales'
      ? `<div class="stats-grid sales-stats">
          <div class="stat-card" style="cursor:pointer" onclick="navigate('channel')"><div class="stat-icon blue">📦</div><div><div class="stat-value">${stats.channelProjects}</div><div class="stat-label">渠道定制单</div></div></div>
          <div class="stat-card" style="cursor:pointer" onclick="navigate('orders')"><div class="stat-icon yellow">🔄</div><div><div class="stat-value">${stats.inProgress}</div><div class="stat-label">进行中项目</div></div></div>
          <div class="stat-card" style="cursor:pointer" onclick="navigate('scoring')"><div class="stat-icon yellow">⭐</div><div><div class="stat-value">${stats.pendingScore}</div><div class="stat-label">待评分</div></div></div>
        </div>`
      : `<div class="stats-grid four-col-stats">
          <div class="stat-card" style="cursor:pointer" onclick="navigate('channel')"><div class="stat-icon blue">📦</div><div><div class="stat-value">${stats.channelProjects}</div><div class="stat-label">渠道定制单</div></div></div>
          <div class="stat-card" style="cursor:pointer" onclick="navigate('regular')"><div class="stat-icon green">🏭</div><div><div class="stat-value">${stats.regularProjects}</div><div class="stat-label">公司常规品</div></div></div>
          <div class="stat-card" style="cursor:pointer" onclick="navigate('orders')"><div class="stat-icon yellow">🔄</div><div><div class="stat-value">${stats.inProgress}</div><div class="stat-label">进行中项目</div></div></div>
          <div class="stat-card" style="cursor:pointer" onclick="navigate('scoring')"><div class="stat-icon yellow">⭐</div><div><div class="stat-value">${stats.pendingScore}</div><div class="stat-label">待评分</div></div></div>
        </div>`
    }
    ${rolePanelsHtml}
    ${orders.length === 0 ? `<div class="empty"><div class="empty-icon">📭</div><p>暂无您负责的项目</p></div>` : ''}
    ${renderProjectSummary(channel, '📦 渠道定制单')}
    ${renderProjectSummary(regular, '🏭 公司常规品')}
    <div id="dashboardWorkloadSection"></div>
  `;
  // 仅 admin 可见工作量概览
  if (currentRole === 'admin') {
    loadDashboardWorkloadSection();
  }
}

/** 在 dashboard 底部加载工作量看板 */
let dashboardWorkloadRange = 'month';
async function loadDashboardWorkloadSection() {
  const container = document.getElementById('dashboardWorkloadSection');
  if (!container) return;
  try {
    const data = await apiGet('/admin/workload/timeline?range=' + dashboardWorkloadRange);
    const summary = data._summary || {};

    const rangeOpts = [
      { k: 'day', l: '今日' }, { k: 'week', l: '本周' }, { k: 'month', l: '本月' },
      { k: 'quarter', l: '本季度' }, { k: 'half-year', l: '本半年' }, { k: 'year', l: '本年度' }
    ];

    const isWorker = r => r === 'designer' || r === 'supplychain';
    const roleOrder = ['sales', 'planner', 'designer', 'supplychain'];

    let html = `<div style="margin-top:24px;border-top:2px solid var(--gray-200);padding-top:20px;">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:16px;font-weight:600;color:#1f2937;">📊 工作量概览</span>
        <span style="font-size:12px;color:var(--gray-400);margin-left:4px;">时间范围:</span>
        ${rangeOpts.map(o => `
          <button onclick="switchDashWorkload('${o.k}')"
            style="padding:4px 12px;border-radius:6px;border:${o.k === dashboardWorkloadRange ? '2px solid #3370FF' : '1px solid var(--gray-200)'};
            background:${o.k === dashboardWorkloadRange ? '#E6F1FB' : '#fff'};
            color:${o.k === dashboardWorkloadRange ? '#1E40AF' : '#374151'};
            font-size:12px;cursor:pointer;">${o.l}</button>
        `).join('')}
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:16px;">
        <div class="stat-card" style="cursor:default;"><div class="stat-icon blue">📁</div><div><div class="stat-value">${summary.totalProjectsCreated}</div><div class="stat-label">新建项目</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon green">✅</div><div><div class="stat-value">${summary.totalProjectsCompleted}</div><div class="stat-label">完成项目</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon blue">📌</div><div><div class="stat-value">${summary.totalTasksAssigned}</div><div class="stat-label">新分任务</div></div></div>
        <div class="stat-card" style="cursor:default;"><div class="stat-icon green">✅</div><div><div class="stat-value">${summary.totalTasksCompleted}</div><div class="stat-label">完成任务</div></div></div>
      </div>`;

    for (const role of roleOrder) {
      const r = data[role];
      if (!r || !r.users || r.users.length === 0) continue;
      const w = isWorker(role);
      html += `<div class="card" style="margin-bottom:12px;">
        <div style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <div><span style="font-size:15px;margin-right:4px;">${r.icon||'👤'}</span><strong style="font-size:14px;">${r.label||role}</strong>
          <span style="font-size:12px;color:var(--gray-400);margin-left:6px;">${r.totalUsers} 人</span></div>
        </div>
        <div style="display:flex;padding:6px 16px;font-size:11px;color:var(--gray-400);border-bottom:1px solid var(--gray-100);">
          <div style="min-width:120px;">姓名</div>
          <div style="flex:1;display:flex;gap:12px;"><span style="width:50px;">${w?'新分配':'新建'}</span><span style="width:50px;">完成</span><span style="width:50px;">完成率</span></div>
        </div>`;
      for (const u of r.users) {
        const cr = u.created || u.assigned || 0;
        const cp = u.completed || 0;
        const rate = cr > 0 ? Math.round((cp / cr) * 100) + '%' : '-';
        html += `<div style="display:flex;align-items:center;padding:8px 16px;border-bottom:1px solid var(--gray-100);">
          <div style="min-width:120px;flex-shrink:0;">
            <div style="font-size:13px;font-weight:500;color:#1f2937;">${escHtml(u.name)}</div>
            <div style="font-size:11px;color:var(--gray-400);">${escHtml(u.userId)}</div>
          </div>
          <div style="flex:1;display:flex;gap:12px;align-items:center;">
            <span style="width:50px;font-size:13px;font-weight:600;">${cr}</span>
            <span style="width:50px;font-size:13px;font-weight:600;color:#065F46;">${cp}</span>
            <span style="width:50px;font-size:12px;color:${rate === '-' ? 'var(--gray-400)' : '#374151'};">${rate}</span>
          </div>
          <div style="flex:1;max-width:100px;background:var(--gray-200);border-radius:4px;height:6px;overflow:hidden;">
            <div style="background:#639922;width:${cr > 0 ? Math.min(100, (cp / cr) * 100) : 0}%;height:100%;border-radius:4px;"></div>
          </div>
        </div>`;
      }
      html += '</div>';
    }
    html += '</div>';
    container.innerHTML = html;
  } catch(e) { /* silently ignore - workload section is optional */ }
}

function switchDashWorkload(range) {
  dashboardWorkloadRange = range;
  loadDashboardWorkloadSection();
}

/** 通用角色状态面板（支持按部门分组）
 *  @param {string} role - 角色名
 *  @param {number|null} deptId - 可选，部门ID，只显示该部门成员
 *  @param {string|null} excludeUserId - 可选，排除某个用户ID（如部门负责人不显示自己）
 */
async function renderRolePanel(role, deptId, excludeUserId) {
  const roleEmoji = { sales: '💼', planner: '📋', supplychain: '🛒', designer: '👥' };
  const roleLabel_ = { sales: '销售', planner: '产品企划', supplychain: '供应链', designer: '设计师' };
  try {
    const status = await apiGet(`/projects/role-status?role=${role}`);
    let users = Object.values(status);

    // 排除指定用户（如部门负责人不显示自己）
    if (excludeUserId) {
      users = users.filter(u => u.id !== excludeUserId);
    }

    // 获取最新的用户数据（包含部门分配信息）
    let allUsersFlat;
    if (deptId || DEPARTMENTS.length > 0) {
      try {
        const freshUsers = await apiGet('/users');
        allUsersFlat = Object.values(freshUsers).flat();
        USERS = freshUsers; // 更新全局缓存
      } catch(e) {
        allUsersFlat = Object.values(USERS).flat();
      }
    } else {
      allUsersFlat = Object.values(USERS).flat();
    }
    // 如果指定了部门ID，只保留该部门的用户
    if (deptId) {
      users = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return userObj && String(userObj.departmentId) === String(deptId);
      });
    }
    const busy = users.filter(u => u.busy);
    const idle = users.filter(u => !u.busy);

    // 按部门分组（如果有组织架构）
    const roleDepts = DEPARTMENTS.filter(d => d.role === role && d.active);
    let bodyHtml = '';
    if (roleDepts.length > 0) {
      // 找出所有未分配部门的用户
      const unknownUsers = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return !userObj || !userObj.departmentId;
      });
      // 有部门：按部门分组展示
      for (const dept of roleDepts) {
        const deptUsers = users.filter(u => {
          const userObj = allUsersFlat.find(us => us.userId === u.id);
          return userObj && String(userObj.departmentId) === String(dept.id);
        });
        if (deptUsers.length > 0) {
          bodyHtml += `
            <div style="margin-bottom:12px;">
              <div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;padding:0 4px;">
                🏢 ${escHtml(dept.name)}
                ${dept.headUserId ? `<span style="font-weight:400;font-size:12px;color:var(--gray-400);">（负责人：${(() => { const h = users.find(u => u.id === dept.headUserId); return h ? h.name : '—'; })()})</span>` : ''}
              </div>
              <div class="designer-grid">${deptUsers.map(u => renderUserCard(u)).join('')}</div>
            </div>`;
        }
      }
      // 尾部显示未分配部门的人员
      if (unknownUsers.length > 0) {
        bodyHtml += `
          <div style="margin-bottom:12px;">
            <div style="font-size:13px;font-weight:600;color:var(--gray-400);margin-bottom:8px;padding:0 4px;">📋 未分配部门</div>
            <div class="designer-grid">${unknownUsers.map(u => renderUserCard(u)).join('')}</div>
          </div>`;
      }
    } else {
      // 无部门：平铺展示
      bodyHtml = `<div class="designer-grid">${users.map(u => renderUserCard(u)).join('')}</div>`;
    }

    return `<div class="designer-status-panel">
      <div class="card-header">
        <div class="card-title">${roleEmoji[role] || '👤'} ${roleLabel_[role] || role}状态看板</div>
        <div style="display:flex;gap:12px;font-size:13px;">
          <span><span class="badge badge-busy" style="margin-right:4px;">🟡</span>忙碌 ${busy.length}人</span>
          <span><span class="badge badge-idle" style="margin-right:4px;">🟢</span>空闲 ${idle.length}人</span>
        </div>
      </div>
      ${bodyHtml}
    </div>`;
  } catch (e) {
    return '';
  }
}

/** 从预取数据渲染角色面板（替代 API 调用） */
function renderRolePanelFromData(statusData, role, deptId, excludeUserId) {
  const roleEmoji = { sales: '💼', planner: '📋', supplychain: '🛒', designer: '👥' };
  const roleLabel_ = { sales: '销售', planner: '产品企划', supplychain: '供应链', designer: '设计师' };
  let users = Object.values(statusData);

  // 过滤部门
  if (deptId) {
    const allUsers = Object.values(USERS).flat();
    users = users.filter(u => {
      const userObj = allUsers.find(us => us.userId === u.id);
      return userObj && String(userObj.departmentId) === String(deptId);
    });
  }
  // 排除自己
  if (excludeUserId) {
    users = users.filter(u => u.id !== excludeUserId);
  }

  const busy = users.filter(u => u.busy);
  const idle = users.filter(u => !u.busy);

  // 按部门分组
  const roleDepts = DEPARTMENTS.filter(d => d.role === role && d.active);
  let bodyHtml = '';
  if (roleDepts.length > 0) {
    const allUsersFlat = Object.values(USERS).flat();
    const unknownUsers = users.filter(u => {
      const userObj = allUsersFlat.find(us => us.userId === u.id);
      return !userObj || !userObj.departmentId;
    });
    for (const dept of roleDepts) {
      const deptUsers = users.filter(u => {
        const userObj = allUsersFlat.find(us => us.userId === u.id);
        return userObj && String(userObj.departmentId) === String(dept.id);
      });
      if (deptUsers.length > 0) {
        bodyHtml += `<div style="margin-bottom:12px;">
          <div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;padding:0 4px;">
            🏢 ${escHtml(dept.name)}
            ${dept.headUserId ? `<span style="font-weight:400;font-size:12px;color:var(--gray-400);">（负责人：${(() => { const h = users.find(u => u.id === dept.headUserId); return h ? h.name : '—'; })()})</span>` : ''}
          </div>
          <div class="designer-grid">${deptUsers.map(u => renderUserCard(u)).join('')}</div>
        </div>`;
      }
    }
    if (unknownUsers.length > 0) {
      bodyHtml += `<div style="margin-bottom:12px;">
        <div style="font-size:13px;font-weight:600;color:var(--gray-400);margin-bottom:8px;padding:0 4px;">📋 未分配部门</div>
        <div class="designer-grid">${unknownUsers.map(u => renderUserCard(u)).join('')}</div>
      </div>`;
    }
  } else {
    bodyHtml = `<div class="designer-grid">${users.map(u => renderUserCard(u)).join('')}</div>`;
  }

  return `<div class="designer-status-panel">
    <div class="card-header">
      <div class="card-title">${roleEmoji[role] || '👤'} ${roleLabel_[role] || role}状态看板</div>
      <div style="display:flex;gap:12px;font-size:13px;">
        <span><span class="badge badge-busy" style="margin-right:4px;">🟡</span>忙碌 ${busy.length}人</span>
        <span><span class="badge badge-idle" style="margin-right:4px;">🟢</span>空闲 ${idle.length}人</span>
      </div>
    </div>
    ${bodyHtml}
  </div>`;
}

/** 渲染单个用户卡片 */
function renderUserCard(u) {
  // 忙碌的用户整个卡片可点击，弹出任务列表
  const clickAttr = u.busy ? `onclick="showUserTasksPopup('${u.id}','${u.name}')" style="cursor:pointer;"` : '';
  return `<div class="designer-card ${u.busy ? 'busy' : 'idle'}" ${clickAttr}>
    <div class="designer-avatar">${u.name.charAt(0)}</div>
    <div class="designer-info">
      <div class="designer-name">${u.name}</div>
      <div class="designer-title">${u.title}</div>
      <div class="designer-tasks">
        ${u.busy
          ? (u.activeTasks
            ? `进行中：${u.activeTasks.length}个子任务`
            : `进行中：${u.activeProjects.length}个项目`)
          : `🟢 空闲`}
      </div>
    </div>
  </div>`;
}

/** 弹出用户任务/项目列表 */
function showUserTasksPopup(userId, userName) {
  if (document.getElementById('userTasksPopup')) return;
  // 从页面上的角色状态数据中获取该用户的任务列表
  // 重新拉取角色状态数据
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'userTasksPopup';
  modal.onclick = function(e) { if (e.target === this) closeM('userTasksPopup'); };
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('userTasksPopup')">✕</button>
    <div class="modal" style="max-width:500px;">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📋 ${escHtml(userName)} 的进行中任务</div></div></div>
      <div class="modal-body" id="userTasksPopupBody">
        <div style="text-align:center;padding:20px;color:var(--gray-400);">加载中...</div>
      </div>
    </div>`;
  document.body.appendChild(modal);
  loadUserTasksPopup(userId);
}

async function loadUserTasksPopup(userId) {
  try {
    // 从 USERS 缓存中找到该用户的角色
    const allUsers = Object.values(USERS).flat();
    const userInfo = allUsers.find(u => u.userId === userId);
    let role = userInfo ? userInfo.role : 'designer';

    const data = await apiGet(`/projects/role-status?role=${role}`);
    const userData = Object.values(data).find(u => u.id === userId);
    if (!userData) {
      document.getElementById('userTasksPopupBody').innerHTML = '<div style="text-align:center;padding:20px;color:var(--gray-500);">暂无数据</div>';
      return;
    }
    const tasks = userData.activeTasks || [];
    const projects = userData.activeProjects || [];
    const body = document.getElementById('userTasksPopupBody');
    if (tasks.length === 0 && projects.length === 0) {
      body.innerHTML = '<div style="text-align:center;padding:20px;color:var(--success);">🟢 当前空闲，无进行中的任务</div>';
      return;
    }
    let html = '';
    if (tasks.length > 0) {
      html += `<div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-bottom:8px;">子任务（${tasks.length}）</div>
        <div style="display:flex;flex-direction:column;gap:6px;">`;
      tasks.forEach(t => {
        const statusLabels = { pending: '⏳ 待接单', accepted: '🔄 进行中', rejected: '↩️ 已驳回', delivered: '📤 已交付' };
        html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;cursor:pointer;" onclick="closeM('userTasksPopup');openProjectDetail(${t.projectId})">
          <span style="font-size:13px;">${escHtml(t.name)}</span>
          <span style="font-size:11px;color:var(--gray-500);">${statusLabels[t.status] || t.status}</span>
        </div>`;
      });
      html += `</div>`;
    }
    if (projects.length > 0) {
      html += `<div style="font-size:13px;font-weight:600;color:var(--gray-500);margin-top:12px;margin-bottom:8px;">项目（${projects.length}）</div>
        <div style="display:flex;flex-direction:column;gap:6px;">`;
      projects.forEach(p => {
        html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;cursor:pointer;" onclick="closeM('userTasksPopup');openProjectDetail(${p.id})">
          <span style="font-size:13px;">${escHtml(p.name)}</span>
          <span style="font-size:11px;color:var(--gray-500);">${p.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</span>
        </div>`;
      });
      html += `</div>`;
    }
    body.innerHTML = html;
  } catch(e) {
    document.getElementById('userTasksPopupBody').innerHTML = `<div style="text-align:center;padding:20px;color:var(--danger);">加载失败: ${e.message}</div>`;
  }
}

function renderProjectSummary(projects, title) {
  if (projects.length === 0) return '';
  const display = projects.slice(0, 5);
  return `
    <div class="type-section">
      <div class="card" style="padding:0;">
        <div style="padding:20px 20px 0 20px;">
          <div class="type-section-title">${title} <span class="count">共 ${projects.length} 个</span></div>
        </div>
        <div style="padding:0 20px 20px 20px;">
          <div class="table-wrap"><table>
        <thead><tr><th>项目编号</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>子任务数</th><th>进度</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>${display.map(o => {
          const st = getProjectStatusInfo(o.status);
          return `<tr style="cursor:pointer;">
            <td><strong>#${o.id}</strong></td>
            <td>${o.salesName || '-'}</td>
            <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
            <td>${o.productCategory || '-'}</td>
            <td>${o.targetMarket ? (() => { try { return JSON.parse(o.targetMarket).join('/'); } catch(e) { return o.targetMarket; } })() : '-'}</td>
            <td>${o.taskCount}（完成${o.approvedTaskCount}）</td>
            <td><div class="progress-bar" style="width:80px;"><div class="progress-fill" style="width:${o.progressPercent}%;"></div></div></td>
            <td>${renderScore(o.score)}</td>
            <td>${formatDate(o.deadline)}</td>
            <td><span class="badge ${st.cls}">${st.label}</span></td>
            <td><button class="btn btn-outline btn-sm" onclick="event.stopPropagation();openProjectDetail(${o.id})">查看</button></td>
          </tr>`;
        }).join('')}</tbody>
      </table></div>
      ${projects.length > 5 ? `<div style="text-align:center;margin-top:8px;"><button class="btn btn-outline btn-sm" onclick="navigate('${title.includes('渠道') ? 'channel' : 'regular'}')">查看全部 →</button></div>` : ''}
      </div></div></div>`;
}

// ==================== 项目列表 ====================
async function renderOrderList(main, type, role, uid) {
  // 使用缓存的项目列表
  let orders = APP_CACHE.orders;
  if (!orders || !orders.length) {
    const participating = role === 'designer' || role === 'supplychain' ? '&participating=true' : '';
    orders = await apiGet(`/projects?role=${role}&userId=${uid}${participating}`);
    APP_CACHE.orders = orders;
  }
  let title = '全部项目';
  if (type === 'channel_custom') { orders = orders.filter(o => o.type === 'channel_custom'); title = '📦 渠道定制单'; }
  else if (type === 'regular') { orders = orders.filter(o => o.type === 'regular'); title = '🏭 公司常规品'; }
  else title = '📋 全部项目';

  APP_CACHE.orders = orders;
  // 保存当前列表的完整数据用于筛选
  APP_CACHE.currentFilterData = [...orders];

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h2 style="font-size:20px;">${title} <span style="font-size:13px;color:var(--gray-400);font-weight:400;">（${orders.length} 个）</span></h2>
      <div style="display:flex;gap:8px;">
        ${currentRole === 'sales' && type === 'channel_custom' ? `<button class="btn btn-primary" onclick="openCreateProject('channel_custom')">➕ 新建渠道定制项目</button>` : ''}
        ${currentRole === 'planner' && type === 'regular' ? `<button class="btn btn-primary" onclick="openCreateProject('regular')">➕ 新建常规品设计项目</button>` : ''}
      </div>
    </div>
    <div class="filter-bar" style="margin-bottom:16px;">
      <select class="form-select" onchange="filterProjectList()" style="min-width:120px;" id="projectStatusFilter">
        <option value="all">全部状态</option>
        <option value="in_progress">进行中</option>
        <option value="completed">已完成</option>
        <option value="completed_pending_score">待评分</option>
        <option value="pending_planner">待企划接单</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索编号/描述..." oninput="filterProjectList()" style="min-width:180px;" id="searchInput">
      <input type="date" class="form-input" id="filterDateStart" style="min-width:130px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:12px;">~</span>
      <input type="date" class="form-input" id="filterDateEnd" style="min-width:130px;" title="结束日期">
      <button class="btn btn-primary btn-sm" onclick="filterProjectList()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" onclick="resetProjectFilters()">↺ 重置</button>
    </div>
    <div class="card" id="projectListContainer">${renderProjectTable(orders)}</div>
  `;
}

function renderProjectTable(orders) {
  if (!orders.length) return `<div class="empty" style="padding:40px;"><div class="empty-icon">📭</div><p>暂无项目</p></div>`;
  return `<div class="table-wrap"><table>
    <thead><tr><th>编号</th><th>类型</th><th>需求方</th><th>产品企划</th><th>产品类目</th><th>目标市场</th><th>价格</th><th>子任务</th><th>评分</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
    <tbody>${orders.map(o => renderProjectRow(o)).join('')}</tbody>
  </table></div>`;
}

function filterProjectList() {
  let filtered = APP_CACHE.currentFilterData || [...APP_CACHE.orders];

  // 状态筛选
  const currentFilter = document.getElementById('projectStatusFilter')?.value || 'all';
  if (currentFilter === 'in_progress') filtered = filtered.filter(o => o.status === 'in_progress' || o.status === 'planner_accepted');
  else if (currentFilter === 'completed') filtered = filtered.filter(o => o.status === 'completed');
  else if (currentFilter === 'completed_pending_score') filtered = filtered.filter(o => o.status === 'completed_pending_score');
  else if (currentFilter === 'pending_planner') filtered = filtered.filter(o => o.status === 'pending_planner');

  // 搜索
  const q = document.getElementById('searchInput')?.value?.toLowerCase();
  if (q) filtered = filtered.filter(o => String(o.id).includes(q) || (o.productRequirements || '').toLowerCase().includes(q));

  // 日期范围筛选（按 deadline）
  const dateStart = document.getElementById('filterDateStart')?.value;
  const dateEnd = document.getElementById('filterDateEnd')?.value;
  if (dateStart || dateEnd) {
    filtered = filtered.filter(o => {
      if (!o.deadline) return !dateStart && !dateEnd;
      if (dateStart && o.deadline < dateStart) return false;
      if (dateEnd && o.deadline > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('projectListContainer');
  if (c) c.innerHTML = renderProjectTable(filtered);
}

function resetProjectFilters() {
  const statusEl = document.getElementById('projectStatusFilter');
  const searchEl = document.getElementById('searchInput');
  const dateStartEl = document.getElementById('filterDateStart');
  const dateEndEl = document.getElementById('filterDateEnd');
  if (statusEl) statusEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterProjectList();
}

// ==================== 我的子任务（企划派发任务界面） ====================
async function renderMyTasks(main, role, uid) {
  if (role === 'designer' || role === 'supplychain' || role === 'planner') {
    // 设计师/供应链/企划: 展示分配给自己的子任务卡片
    await renderDesignerTasks(main, uid);
    return;
  }

  // 其他角色: 展示项目列表，方便查看和添加子任务
  let orders = await apiGet(`/projects?role=${role}&userId=${uid}`);
  // 只显示进行中的项目（需要有子任务操作的项目）
  if (role !== 'admin') {
    orders = orders.filter(o =>
      o.status === 'in_progress' || o.status === 'planner_accepted'
    );
  }

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">📌 子任务管理 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${orders.length} 个项目)</span></h2>
    </div>
    <div class="filter-bar">
      <select class="form-select" onchange="filterTaskProjects()" style="min-width:120px;" id="taskProjectFilter">
        <option value="all">全部项目</option>
        <option value="in_progress">进行中</option>
        <option value="planner_accepted">待添加子任务</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索项目编号/描述..." oninput="filterTaskProjects()" style="min-width:180px;" id="taskProjectSearch">
      <input type="date" class="form-input" id="taskProjectDateStart" style="min-width:130px;" title="要求日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="taskProjectDateEnd" style="min-width:130px;" title="要求日期止">
      <button class="btn btn-primary btn-sm" onclick="filterTaskProjects()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" onclick="resetTaskProjectFilters()">↺ 重置</button>
    </div>
    <div id="taskProjectContainer">${renderTaskProjectTable(orders)}</div>
  `;
  window._taskProjectsCache = orders;
}

function renderTaskProjectTable(orders) {
  if (!orders.length) return `<div class="empty"><div class="empty-icon">📭</div><p>${currentRole === 'admin' ? '暂无项目' : '暂无进行中的项目'}</p></div>`;
  return `<div class="card"><div class="table-wrap"><table>
    <thead><tr>
      <th>项目编号</th>
      <th>类型</th>
      <th>产品要求</th>
      <th>产品企划</th>
      <th>子任务</th>
      <th>要求时间</th>
      <th>状态</th>
      <th>操作</th>
    </tr></thead>
    <tbody>${orders.map(o => {
      const st = getProjectStatusInfo(o.status);
      return `<tr>
        <td><strong>#${o.id}</strong></td>
        <td>${o.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escHtml(o.productRequirements || '')}">${o.productRequirements || '-'}</td>
        <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
        <td>${o.approvedTaskCount}/${o.taskCount}</td>
        <td>${formatDate(o.deadline)}</td>
        <td><span class="badge ${st.cls}">${st.label}</span></td>
        <td>
          <button class="btn btn-primary btn-sm" onclick="openProjectDetail(${o.id})">📋 管理子任务</button>
          <button class="btn btn-outline btn-sm" onclick="openProjectDetail(${o.id})">查看</button>
        </td>
      </tr>`;
    }).join('')}</tbody>
  </table></div></div>`;
}

function filterTaskProjects() {
  const filter = document.getElementById('taskProjectFilter')?.value || 'all';
  const q = document.getElementById('taskProjectSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('taskProjectDateStart')?.value;
  const dateEnd = document.getElementById('taskProjectDateEnd')?.value;
  let list = window._taskProjectsCache || [];
  if (filter !== 'all') list = list.filter(o => o.status === filter);
  if (q) list = list.filter(o => String(o.id).includes(q) || (o.productRequirements || '').toLowerCase().includes(q));
  if (dateStart || dateEnd) {
    list = list.filter(o => {
      if (!o.deadline) return !dateStart && !dateEnd;
      if (dateStart && o.deadline < dateStart) return false;
      if (dateEnd && o.deadline > dateEnd) return false;
      return true;
    });
  }
  const c = document.getElementById('taskProjectContainer');
  if (c) c.innerHTML = renderTaskProjectTable(list);
}

function resetTaskProjectFilters() {
  const filterEl = document.getElementById('taskProjectFilter');
  const searchEl = document.getElementById('taskProjectSearch');
  const dateStartEl = document.getElementById('taskProjectDateStart');
  const dateEndEl = document.getElementById('taskProjectDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterTaskProjects();
}

// ==================== 待评分页面 ====================
async function renderScoringView(main, role, uid) {
  // 使用专用聚合端点（1 次 API 替代 N+1 次）
  const pendingItems = await swrFetch(`scoring_${role}_${uid}`,
    () => apiGet(`/scoring/pending?role=${role}&userId=${uid}`),
    15000
  );
  let pendingTasks = [];

  for (const item of pendingItems) {
    const t = {
      id: item.taskId,
      name: item.taskName,
      status: item.taskStatus,
      projectId: item.projectId,
      projectType: item.projectType,
      projectName: item.projectName,
      plannedDate: item.plannedDate,
      designerId: item.designerId,
      designerName: item.designerName,
      selfScore: item.selfScore,
      selfAesthetics: item.selfAesthetics,
      selfInnovation: item.selfInnovation,
      scoringRecords: item.scoringRecords || [],
      isPending: !!item.isPending,
    };
    pendingTasks.push(t);
  }

  // 待评分在前，已评分在后
  pendingTasks.sort((a, b) => {
    if (a.isPending && !b.isPending) return -1;
    if (!a.isPending && b.isPending) return 1;
    return 0;
  });

  const pendingCount = pendingTasks.filter(t => t.isPending).length;
  const doneCount = pendingTasks.filter(t => !t.isPending).length;

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">⭐ 评分中心 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">待评分 ${pendingCount} / 已评分 ${doneCount}</span></h2>
    </div>
    <div class="filter-bar">
      <select class="form-select" onchange="filterScoringView()" style="min-width:120px;" id="scoringFilter">
        <option value="all">全部</option>
        <option value="pending">待评分</option>
        <option value="done">已评分</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." oninput="filterScoringView()" style="min-width:180px;" id="scoringSearch">
      <input type="date" class="form-input" id="scoringDateStart" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="scoringDateEnd" style="min-width:130px;" title="计划完成日期止">
      <button class="btn btn-primary btn-sm" onclick="filterScoringView()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" onclick="resetScoringFilters()">↺ 重置</button>
    </div>
    <div id="scoringContainer">${renderScoringCards(pendingTasks)}</div>
  `;
  window._scoringCache = pendingTasks;
}

function filterScoringView() {
  const filter = document.getElementById('scoringFilter')?.value || 'all';
  const q = document.getElementById('scoringSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('scoringDateStart')?.value;
  const dateEnd = document.getElementById('scoringDateEnd')?.value;
  let list = window._scoringCache || [];

  if (filter === 'pending') list = list.filter(t => t.isPending);
  else if (filter === 'done') list = list.filter(t => !t.isPending);

  if (q) list = list.filter(t => String(t.projectId).includes(q) || (t.name || '').toLowerCase().includes(q) || (t.projectName || '').toLowerCase().includes(q));

  if (dateStart || dateEnd) {
    list = list.filter(t => {
      if (!t.plannedDate) return !dateStart && !dateEnd;
      if (dateStart && t.plannedDate < dateStart) return false;
      if (dateEnd && t.plannedDate > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('scoringContainer');
  if (c) c.innerHTML = renderScoringCards(list);
}

function resetScoringFilters() {
  const filterEl = document.getElementById('scoringFilter');
  const searchEl = document.getElementById('scoringSearch');
  const dateStartEl = document.getElementById('scoringDateStart');
  const dateEndEl = document.getElementById('scoringDateEnd');
  if (filterEl) filterEl.value = 'all';
  if (searchEl) searchEl.value = '';
  if (dateStartEl) dateStartEl.value = '';
  if (dateEndEl) dateEndEl.value = '';
  filterScoringView();
}

function renderScoringCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无需要评分的任务</p></div>`;
  return `<div class="card">
    ${tasks.map(t => {
      const isPending = t.isPending;
      const statusIcon = isPending ? '⏳' : '✅';
      const statusText = isPending ? '待评分' : '已评分';
      const statusCls = isPending ? 'badge-pending' : 'badge-completed';
      const allRoles = t.scoringRecords;
      return `<div class="subtask-card" style="border-left:3px solid ${isPending ? 'var(--warning)' : 'var(--success)'};">
        <div class="subtask-header">
          <div class="subtask-name">
            <span class="subtask-number" style="background:${isPending ? 'var(--warning)' : 'var(--success)'};">${statusIcon}</span>
            ${t.name}
          </div>
          <span class="badge ${statusCls}">${statusText}</span>
        </div>
        <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">
          📁 项目 #${t.projectId} ${t.projectType === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'} — ${t.projectName || ''}
          ${t.plannedDate ? ` · 📅 ${formatDate(t.plannedDate)}` : ''}
        </div>
        <div style="margin-top:8px;">
          <div style="font-size:12px;font-weight:600;color:var(--gray-600);margin-bottom:6px;">评分状态</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            ${allRoles.map(r => {
              const scored = r.score != null;
              return `<span style="padding:4px 10px;border-radius:6px;font-size:12px;background:${scored ? 'var(--success-light)' : 'var(--warning-light)'};color:${scored ? 'var(--success)' : 'var(--warning)'};">
                ${roleLabel(r.role)}: ${scored ? `✅ ${r.score}分` : '⏳ 待评分'}
              </span>`;
            }).join('')}
          </div>
        </div>
        <div class="subtask-actions" style="margin-top:10px;">
          ${t.isAdminView || t.isDesignerView ? '' : (isPending ? `<button class="btn btn-primary btn-sm" onclick="openScoring(${t.projectId},${t.id})">⭐ 立即评分</button>` : '')}
          <button class="btn btn-outline btn-sm" onclick="openProjectDetail(${t.projectId})">查看项目</button>
        </div>
      </div>`;
    }).join('')}
  </div>`;
}

// 设计师视角: 展示分配给自己的子任务卡片
async function renderDesignerTasks(main, uid) {
  // 企划可能作为子任务负责人而非项目负责人，需查全部项目
  const roleParam = currentRole === 'planner' ? 'admin' : currentRole;
  const orders = await apiGet(`/projects?role=${roleParam}&userId=${uid}`);
  let myTasks = [];
  const myId = uid;

  // 并行拉取所有项目详情
  const details = await Promise.all(
    orders.map(order => apiGet(`/projects/${order.id}`).catch(() => null))
  );

  for (const detail of details) {
    if (!detail || !detail.tasks) continue;
    for (const t of detail.tasks) {
      const isMine = t.designerId === myId;
      const isUnassigned = !t.designerId || t.designerId === '';
      if (isMine) {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30) });
      } else if (isUnassigned && t.status === 'pending') {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30), _unassigned: true });
      }
      if ((t.status === 'approved' || t.status === 'completed') && t.scoringRecords) {
        const needScore = t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        if (needScore && !myTasks.find(mt => mt.id === t.id)) {
          myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30) });
        }
      }
    }
  }

  main.innerHTML = `
    <h2 style="font-size:22px;margin-bottom:20px;">🎨 我的子任务 <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${myTasks.length})</span></h2>
    <div class="filter-bar">
      <select class="form-select" onchange="filterDesignerTasks()" style="min-width:120px;" id="designerTaskFilter">
        <option value="all">全部</option>
        <option value="unassigned">待认领</option>
        <option value="mine">我的任务</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索任务名/项目号..." oninput="filterDesignerTasks()" style="min-width:180px;" id="designerTaskSearch">
      <input type="date" class="form-input" id="designerTaskDateStart" onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期起">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="designerTaskDateEnd" onchange="filterDesignerTasks()" style="min-width:130px;" title="计划完成日期止">
    </div>
    <div id="designerTaskContainer">${renderDesignerTaskCards(myTasks)}</div>
  `;
  window._designerTaskCache = myTasks;
}

function filterDesignerTasks() {
  const filter = document.getElementById('designerTaskFilter')?.value || 'all';
  const q = document.getElementById('designerTaskSearch')?.value?.toLowerCase() || '';
  const dateStart = document.getElementById('designerTaskDateStart')?.value;
  const dateEnd = document.getElementById('designerTaskDateEnd')?.value;
  let list = window._designerTaskCache || [];

  if (filter === 'unassigned') list = list.filter(t => t._unassigned);
  else if (filter === 'mine') list = list.filter(t => !t._unassigned);

  if (q) list = list.filter(t => String(t.projectId).includes(q) || (t.name || '').toLowerCase().includes(q) || (t.projectName || '').toLowerCase().includes(q));

  if (dateStart || dateEnd) {
    list = list.filter(t => {
      if (!t.plannedDate) return !dateStart && !dateEnd;
      if (dateStart && t.plannedDate < dateStart) return false;
      if (dateEnd && t.plannedDate > dateEnd) return false;
      return true;
    });
  }

  const c = document.getElementById('designerTaskContainer');
  if (c) c.innerHTML = renderDesignerTaskCards(list);
}

function renderDesignerTaskCards(tasks) {
  if (!tasks.length) return `<div class="empty"><div class="empty-icon">🎉</div><p>暂无子任务</p></div>`;
  return `<div class="card">
      ${tasks.map(t => {
        const tsi = getTaskStatusInfo(t.status);
        const needScore = t.scoringRecords && t.scoringRecords.some(sr => sr.score == null && (sr.role === 'designer' || sr.role === 'supplychain'));
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} ${t.name}</div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">📁 项目 #${t.projectId}：${t.projectName}</div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 负责人：<strong>${t.designerName || '<span style="color:var(--warning);">待认领</span>'}</strong>${t.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${t.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : t.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : 'background:#FEF2F2;color:#DC2626;'}">${t.assigneeRole === 'supplychain' ? '供应链' : t.assigneeRole === 'planner' ? '企划' : '设计师'}</span>` : ''}</div>
            <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(t.plannedDate)}</strong></div>
            ${t.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(t.actualDate)}</strong></div>` : ''}
          </div>
          ${t.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:8px;">📝 ${t.details}</div>` : ''}
          ${t.reviewComments ? `<div class="review-box ${t.status === 'rejected' ? 'rejected' : 'approved'}">${t.status === 'rejected' ? '驳回意见' : '验收意见'}：${t.reviewComments}</div>` : ''}
          ${t.scoringRecords ? renderScoringMini(t) : ''}
          <div class="subtask-actions">
            ${t.status === 'pending' && !t._unassigned ? `<button class="btn btn-primary btn-sm" onclick="taskAccept(${t.projectId},${t.id})">✅ 接单</button>` : ''}
            ${t._unassigned ? `<button class="btn btn-success btn-sm" onclick="taskAccept(${t.projectId},${t.id})">📋 认领并接单</button>` : ''}
            ${t.status === 'accepted' ? `<button class="btn btn-primary btn-sm" onclick="taskDeliver(${t.projectId},${t.id})">📤 交付成果</button>` : ''}
            ${t.status === 'rejected' ? `<button class="btn btn-warning btn-sm" onclick="taskRedeliver(${t.projectId},${t.id})">📤 重新交付</button>` : ''}
            ${needScore ? `<button class="btn btn-warning btn-sm" onclick="openScoring(${t.projectId},${t.id})">⭐ 评分</button>` : ''}
            <button class="btn btn-outline btn-sm" onclick="openProjectDetail(${t.projectId})">查看项目</button>
          </div>
        </div>`;
      }).join('')}
    </div>`;
}

function renderScoringMini(task, isDone) {
  if (!task.scoringRecords || !task.scoringRecords.length) return '';
  const records = task.scoringRecords;
      const allScored = records.filter(r => r.score != null).length;
  let ta = 0, tw = 0;
  records.forEach(r => {
    if (r.score != null) {
      ta += r.score * r.weight;
      tw += r.weight;
    }
  });
  const overall = tw > 0 ? (ta / tw).toFixed(0) : null;

  if (isDone) {
    return `<div style="margin-top:10px;padding:12px;background:#DCFCE7;border-radius:8px;border:1px solid #86EFAC;">
      <div style="font-size:12px;font-weight:600;color:#166534;margin-bottom:6px;">⭐ 评分 (${allScored}/${records.length}人)</div>
      <div style="display:flex;gap:12px;flex-wrap:wrap;font-size:11px;">
        ${records.map(r => `<span style="background:#fff;padding:2px 8px;border-radius:4px;">${roleLabel(r.role)}: ${r.score != null ? `✅ ${r.score}分` : '<span style="color:var(--gray-400);">⏳ 待评</span>'}</span>`).join('')}
      </div>
      ${overall ? `<div style="margin-top:8px;text-align:center;"><span style="font-size:12px;color:#15803D;">加权综合：</span><span style="font-size:24px;font-weight:700;color:#16A34A;">${overall}分</span></div>` : ''}
    </div>`;
  }

  return `<div style="margin-top:10px;padding:12px;background:var(--primary-light);border-radius:8px;">
    <div style="font-size:12px;font-weight:600;color:var(--primary);margin-bottom:6px;">⭐ 评分 (${allScored}/${records.length}人)</div>
    <div style="display:flex;gap:12px;flex-wrap:wrap;font-size:11px;">
      ${records.map(r => `<span style="background:#fff;padding:2px 8px;border-radius:4px;">${roleLabel(r.role)}: ${r.score != null ? `✅ ${r.score}分` : '<span style="color:var(--gray-400);">⏳ 待评</span>'}</span>`).join('')}
    </div>
    ${overall ? `<div style="margin-top:8px;text-align:center;"><span style="font-size:12px;color:var(--gray-500);">加权综合：</span><span style="font-size:24px;font-weight:700;color:var(--primary);">${overall}分</span></div>` : ''}
  </div>`;
}

// ==================== 图片预览（Lightbox + 滚轮缩放 + 拖拽平移）====================
function previewImage(src, name) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.style.cssText = 'z-index:300;background:rgba(0,0,0,.85);overflow:hidden;';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };

  let scale = 1;
  const minScale = 0.25;
  const maxScale = 10;
  let isDragging = false;
  let startX = 0, startY = 0;
  let panX = 0, panY = 0;

  const imgWrap = document.createElement('div');
  imgWrap.style.cssText = 'width:100%;height:100%;display:flex;align-items:center;justify-content:center;cursor:grab;user-select:none;overflow:hidden;';
  imgWrap.onmousedown = function(e) {
    if (scale <= 1) return;
    isDragging = true;
    startX = e.clientX - panX;
    startY = e.clientY - panY;
    imgWrap.style.cursor = 'grabbing';
    e.preventDefault();
  };

  const img = document.createElement('img');
  img.src = src;
  img.alt = name || '预览';
  img.draggable = false;
  img.style.cssText = 'max-width:90vw;max-height:90vh;border-radius:8px;box-shadow:0 4px 30px rgba(0,0,0,.5);transition:transform .15s ease;transform:scale(1);pointer-events:none;';

  imgWrap.appendChild(img);

  const zoomLabel = document.createElement('div');
  zoomLabel.style.cssText = 'position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.6);color:#fff;padding:6px 16px;border-radius:20px;font-size:13px;z-index:310;pointer-events:none;user-select:none;';
  zoomLabel.textContent = '100%';

  overlay.onwheel = function(e) {
    e.preventDefault();
    const delta = e.deltaY > 0 ? -0.1 : 0.1;
    scale = Math.min(maxScale, Math.max(minScale, scale + delta));
    img.style.transform = 'scale(' + scale + ')';
    imgWrap.style.cursor = scale > 1 ? 'grab' : 'default';
    zoomLabel.textContent = Math.round(scale * 100) + '%';
    // 还原到 1x 时复位位置
    if (scale <= 1) { panX = 0; panY = 0; img.style.marginLeft = '0'; img.style.marginTop = '0'; }
  };

  // 全局鼠标移动和释放（防止拖出图片区域时丢失事件）
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);

  function onMove(e) {
    if (!isDragging) return;
    panX = e.clientX - startX;
    panY = e.clientY - startY;
    img.style.marginLeft = panX + 'px';
    img.style.marginTop = panY + 'px';
  }

  function onUp() {
    isDragging = false;
    imgWrap.style.cursor = scale > 1 ? 'grab' : 'default';
  }

  const closeBtn = document.createElement('button');
  closeBtn.innerHTML = '✕';
  closeBtn.onclick = function() { cleanup(); overlay.remove(); };
  closeBtn.style.cssText = 'position:fixed;top:20px;left:20px;width:40px;height:40px;border-radius:12px;border:none;background:rgba(255,255,255,.9);color:#333;font-size:20px;cursor:pointer;z-index:310;box-shadow:0 2px 12px rgba(0,0,0,.3);';

  function cleanup() {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
  }

  overlay.appendChild(closeBtn);
  overlay.appendChild(imgWrap);
  overlay.appendChild(zoomLabel);
  document.body.appendChild(overlay);
}

// 全局点击图片预览委托
document.addEventListener('click', function(e) {
  const img = e.target.closest('.img-clickable');
  if (img) {
    previewImage(img.src, img.alt || img.title || '');
  }
});

// ==================== 文件上传工具（multipart 流式 + 进度条）====================
let _createRefImages = [];
let _createAttachments = [];
let _formModified = false;

let _deliverImages = [];
let _deliverAttachments = [];
let _uploadingCount = 0;

function showProgressBar(containerId, fileName) {
  const c = document.getElementById(containerId);
  if (!c) return;
  const el = document.createElement('div');
  el.className = 'upload-progress';
  el.id = 'prog_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
  el.style.cssText = 'margin-top:6px;background:var(--gray-100);border-radius:6px;overflow:hidden;font-size:11px;';
  el.innerHTML = `<div style="display:flex;justify-content:space-between;padding:2px 8px;color:var(--gray-500);">
    <span>⏳ ${fileName}</span><span class="prog-pct">0%</span>
  </div><div style="height:4px;background:var(--gray-200);border-radius:2px;margin:0 8px 6px;">
    <div class="prog-bar" style="width:0%;height:100%;background:var(--primary);border-radius:2px;transition:width .3s;"></div>
  </div>`;
  c.parentNode.insertBefore(el, c.nextSibling);
  return el.id;
}

function updateProgress(progId, pct) {
  const el = document.getElementById(progId);
  if (!el) return;
  el.querySelector('.prog-pct').textContent = pct + '%';
  el.querySelector('.prog-bar').style.width = pct + '%';
  if (pct >= 100) {
    el.querySelector('span:first-child').textContent = '✅ ' + el.querySelector('span:first-child').textContent.slice(2);
  }
}

function removeProgress(progId) {
  const el = document.getElementById(progId);
  if (el) el.remove();
}

async function handleFileUpload(input, list, maxCount, typeLabel, isImage) {
  if (list.length + input.files.length > maxCount) {
    alert(typeLabel + '最多上传' + maxCount + '个');
    input.value = '';
    return;
  }
  const maxBytes = isImage ? 200 * 1024 * 1024 : 2 * 1024 * 1024 * 1024;
  const blocked = ['.sql', '.sh', '.bat', '.cmd', '.exe', '.dll', '.so', '.jar', '.war', '.php', '.asp', '.jsp', '.py', '.vbs', '.ps1', '.msi', '.reg', '.scr'];

  for (const f of input.files) {
    const ext = '.' + f.name.split('.').pop()?.toLowerCase();
    if (blocked.includes(ext)) { alert('不允许上传 ' + ext + ' 文件'); continue; }
    if (f.size > maxBytes) { alert('文件 ' + f.name + ' 超过大小限制'); continue; }

    const suffix = typeLabel.includes('交付') ? 'Deliver' : 'Create';
    const containerId = isImage ? (suffix + (isImage ? 'RefImageList' : 'AttachmentList')) : (suffix + (isImage ? 'RefImageList' : 'AttachmentList'));
    const barId = showProgressBar(
      isImage ? (suffix === 'Create' ? 'createRefImageList' : 'deliverImageList')
              : (suffix === 'Create' ? 'createAttachmentList' : 'deliverAttachmentList'),
      f.name
    );
    _uploadingCount++;

    try {
      const result = await uploadFile(f, (pct) => updateProgress(barId, pct));
      list.push({ name: result.name, url: result.url, size: result.size, storedName: result.storedName });
      renderFileList(list, typeLabel);
      formModified();
      setTimeout(() => removeProgress(barId), 1500);
    } catch (e) {
      removeProgress(barId);
      alert('上传失败: ' + f.name + ' - ' + e.message);
    }
    _uploadingCount--;
  }
  input.value = '';
}

function renderFileList(list, typeLabel) {
  const isImage = typeLabel.includes('参考图片');
  const suffix = typeLabel.includes('交付') ? 'Deliver' : 'Create';
  const containerId = isImage ? (suffix === 'Create' ? 'createRefImageList' : 'deliverImageList')
                              : (suffix === 'Create' ? 'createAttachmentList' : 'deliverAttachmentList');
  const c = document.getElementById(containerId);
  if (!c) return;
  if (!list.length) { c.innerHTML = ''; return; }

  // 为图片 URL 追加 token（<img> 标签无法发送 X-Auth-Token 头）
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => u + (u.includes('?') ? '&' : '?') + 'token=' + token;

  if (isImage) {
    c.innerHTML = `<div class="image-preview">${list.map((img, i) =>
      `<div style="position:relative;display:inline-block;">
        <img src="${authUrl(img.url)}" alt="${img.name}" class="img-clickable" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
        <button onclick="event.stopPropagation();showDownloadOptions('${escHtml(img.url)}','${escHtml(img.name)}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:20px;height:20px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
        <button style="position:absolute;top:-6px;right:-6px;width:20px;height:20px;border-radius:50%;border:none;background:var(--danger);color:#fff;font-size:12px;cursor:pointer;display:flex;align-items:center;justify-content:center;" onclick="removeFileItem('${suffix}',${i},${isImage})">✕</button>
      </div>`).join('')}</div>`;
  } else {
    c.innerHTML = list.map((f, i) =>
      `<div class="file-item"><span>📎 ${f.name}</span><span style="font-size:11px;color:var(--gray-400);">${fmtSize(f.size)}</span><button style="margin-left:4px;padding:2px 6px;border-radius:4px;background:var(--primary-light);color:var(--primary);font-size:12px;border:none;cursor:pointer;" onclick="showDownloadOptions('${escHtml(f.url)}','${escHtml(f.name)}',${f.size || 0})" title="下载选项">⬇</button><button class="remove-file" onclick="removeFileItem('${suffix}',${i},${isImage})">✕</button></div>`
    ).join('');
  }
}

function removeFileItem(suffix, idx, isImage) {
  if (suffix === 'Create') {
    if (isImage) _createRefImages.splice(idx, 1);
    else _createAttachments.splice(idx, 1);
  } else {
    if (isImage) _deliverImages.splice(idx, 1);
    else _deliverAttachments.splice(idx, 1);
  }
  renderFileList(isImage ? (suffix === 'Create' ? _createRefImages : _deliverImages) : (suffix === 'Create' ? _createAttachments : _deliverAttachments),
    isImage ? (suffix === 'Create' ? '参考图片' : '交付参考图片') : '附件');
}

function handleCreateRefImages(input) { handleFileUpload(input, _createRefImages, 9, '参考图片', true); }
function handleCreateAttachments(input) { handleFileUpload(input, _createAttachments, 5, '附件', false); }
function handleDeliverImages(input) { handleFileUpload(input, _deliverImages, 9, '交付参考图片', true); }
function handleDeliverAttachments(input) { handleFileUpload(input, _deliverAttachments, 5, '交付附件', false); }

// ==================== 产品类目 / 目标市场选择 ====================
function onCategoryChange(sel) {
  const wrapper = document.getElementById('categoryNoteWrapper');
  if (sel.value === '其他') {
    wrapper.style.display = 'block';
  } else {
    wrapper.style.display = 'none';
    const ta = wrapper.querySelector('textarea');
    if (ta) ta.value = '';
  }
  sel.closest('.form-group')?.querySelector('.field-error')?.remove();
  sel.style.borderColor = '';
  formModified();
}

function toggleMarket(el) {
  el.classList.toggle('selected');
  const selected = [];
  document.querySelectorAll('#marketChips .chip.selected').forEach(c => selected.push(c.dataset.value));
  document.getElementById('targetMarketInput').value = JSON.stringify(selected);
  formModified();
}

function toggleCompliance(el) {
  el.classList.toggle('selected');
  const selected = [];
  document.querySelectorAll('#complianceChips .chip.selected').forEach(c => selected.push(c.dataset.value));
  document.getElementById('complianceItemsInput').value = JSON.stringify(selected);
  formModified();
}

function togglePriceRange(el) {
  document.querySelectorAll('#priceRangeChips .chip').forEach(c => c.classList.remove('selected'));
  el.classList.add('selected');
  document.getElementById('priceRangeInput').value = el.dataset.value;
  formModified();
}

// 切换子任务负责人类型（设计师/供应链）
window.switchAssigneeType = function(prefix, role, el) {
  // 更新 radio 选中样式
  if (el) {
    document.querySelectorAll(`#addSubTaskForm .checkbox-item, #editSubTaskForm .checkbox-item`).forEach(c => c.classList.remove('checked'));
    el.classList.add('checked');
  }
  // 更新 hidden input
  const hidden = document.getElementById(prefix + 'SubTaskAssigneeRole');
  if (hidden) hidden.value = role;
  // 切换负责人下拉选项
  const sel = document.getElementById(prefix + 'SubTaskDesignerId');
  if (!sel) return;
  if (role === 'designer') {
    sel.innerHTML = '<option value="">请选择设计师</option>' +
      (USERS.designer || []).map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('');
  } else if (role === 'planner') {
    sel.innerHTML = (USERS.planner && USERS.planner.length
      ? '<option value="">请选择企划</option>' +
        USERS.planner.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
      : '<option value="">暂无企划人员</option>');
  } else {
    sel.innerHTML = (USERS.supplychain && USERS.supplychain.length
      ? '<option value="">请选择供应链</option>' +
        USERS.supplychain.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
      : '<option value="">暂无供应链人员</option>');
  }
};

// 编辑子任务时切换负责人类型
window.switchEditAssigneeType = function(role, el) {
  document.querySelectorAll('#editTaskForm .checkbox-item').forEach(c => c.classList.remove('checked'));
  el.classList.add('checked');
  document.getElementById('editSubTaskAssigneeRole').value = role;
  const sel = document.getElementById('editSubTaskDesignerId');
  if (!sel) return;
  if (role === 'designer') {
    sel.innerHTML = '<option value="">请选择设计师</option>' +
      (USERS.designer || []).map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('');
  } else if (role === 'planner') {
    sel.innerHTML = (USERS.planner && USERS.planner.length
      ? '<option value="">请选择企划</option>' +
        USERS.planner.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
      : '<option value="">暂无企划人员</option>');
  } else {
    sel.innerHTML = (USERS.supplychain && USERS.supplychain.length
      ? '<option value="">请选择供应链</option>' +
        USERS.supplychain.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
      : '<option value="">暂无供应链人员</option>');
  }
};


function openCreateProject(type) {
  if (isModalOpen()) return;
  if (document.getElementById('createProjectModal')) return;
  _formModified = false;
  window._createProjectType = type;

  const draftJson = sessionStorage.getItem('design_pm_create_draft');
  let draft = null;
  if (draftJson) {
    try { draft = JSON.parse(draftJson); } catch(e) {}
    if (draft && draft._type !== type) draft = null;
  }

  _createRefImages = draft?._refImages || [];
  _createAttachments = draft?._attachments || [];

  const defaultPlanner = draft?.plannerId || (currentRole === 'planner' ? currentUserId : '');
  const defaultSales = draft?.salesId || (currentRole === 'sales' ? currentUserId : '');

  const plannerOpts = `<option value="">请选择产品企划</option>` + USERS.planner.map(u =>
    `<option value="${u.userId}" ${defaultPlanner === u.userId ? 'selected' : ''}>${u.name} (${u.title})</option>`
  ).join('');
  const salesOpts = `<option value="">请选择需求方</option>` + USERS.sales.map(u =>
    `<option value="${u.userId}" ${defaultSales === u.userId ? 'selected' : ''}>${u.name} (${u.title})</option>`
  ).join('');
  const title = type === 'channel_custom' ? '新建渠道定制项目' : '新建常规品设计项目';

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'createProjectModal';
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('createProjectModal')">✕</button>
    <div class="modal modal-lg">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📝 ${title}</div></div></div>
      <div class="modal-body">
        <form id="createProjectForm">
          ${type === 'channel_custom' ? `
          <div class="form-group"><label class="form-label"><span class="required">*</span> 需求方（销售）</label>
            <select class="form-select" name="salesId" ${currentRole === 'sales' ? 'disabled' : ''} onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${salesOpts}</select>
            ${currentRole === 'sales' ? `<input type="hidden" name="salesId" value="${currentUserId}">` : ''}
          </div>` : ''}
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品企划</label>
            <select class="form-select" name="plannerId" ${currentRole === 'planner' ? 'disabled' : ''} onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${plannerOpts}</select>
            ${currentRole === 'planner' ? `<input type="hidden" name="plannerId" value="${currentUserId}">` : ''}
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品类目</label>
            <select class="form-select" name="productCategory" id="productCategorySelect" onchange="onCategoryChange(this)">
              <option value="">请选择产品类目</option>
              ${CATEGORIES.map(c => `<option value="${c.name}">${c.name}</option>`).join('')}
            </select>
            <div id="categoryNoteWrapper" style="display:none;margin-top:8px;">
              <textarea class="form-textarea" name="productCategoryNote" placeholder="请说明其他类目的具体内容..." oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()"></textarea>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 参考零售价</label>
            <div class="chip-group" id="priceRangeChips">
              ${PRICE_RANGES.map(p => `<span class="chip" data-value="${p.name}" onclick="togglePriceRange(this)">${p.name}</span>`).join('')}
            </div>
            <input type="hidden" name="priceRange" id="priceRangeInput" value="">
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 目标市场<span style="color:var(--gray-400);font-weight:400;margin-left:4px;">（可多选）</span></label>
            <div class="chip-group" id="marketChips">
              <span class="chip" data-value="国内" onclick="toggleMarket(this)">国内</span>
              <span class="chip" data-value="海外" onclick="toggleMarket(this)">海外</span>
            </div>
            <input type="hidden" name="targetMarket" id="targetMarketInput" value="">
            <div class="form-hint">可多选</div>
          </div>
          <div class="form-group"><label class="form-label">合规处罚<span style="color:var(--gray-400);font-weight:400;margin-left:4px;">（可多选，非必选）</span></label>
            <div class="chip-group" id="complianceChips">
              ${COMPLIANCE_ITEMS.map(c => `<span class="chip" data-value="${c.name}" onclick="toggleCompliance(this)">${c.name}</span>`).join('')}
            </div>
            <input type="hidden" name="complianceItems" id="complianceItemsInput" value="">
            <div class="form-hint">提醒产品企划关注相关供应商是否有相关资质</div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 要求完成时间</label>${renderDatePicker('deadline', {value: draft?.deadline || ''})}</div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 产品要求</label><textarea class="form-textarea" name="productRequirements" placeholder="产品的基本要求和目标..." oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${escHtml(draft?.productRequirements || '')}</textarea></div>
          <div class="form-group"><label class="form-label">细节描述（可选）</label><textarea class="form-textarea" name="description" placeholder="补充细节说明..." oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor='';formModified()">${escHtml(draft?.description || '')}</textarea></div>
        </form>

        <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片</div>
          <div class="upload-area" onclick="document.getElementById('createRefImageInput').click()">
            <div>📁 点击上传参考图片</div>
            <input type="file" id="createRefImageInput" multiple accept="image/*" style="display:none" onchange="handleCreateRefImages(this)">
          </div>
          <div class="file-list" id="createRefImageList"></div>
        </div>

        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">📎 附件</div>
          <div class="upload-area" onclick="document.getElementById('createAttachmentInput').click()">
            <div>📁 点击上传附件</div>
            <input type="file" id="createAttachmentInput" multiple style="display:none" onchange="handleCreateAttachments(this)">
          </div>
          <div class="file-list" id="createAttachmentList"></div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('createProjectModal')">取消</button>
        <button class="btn btn-primary" onclick="submitGuard(this,()=>submitCreateProject('${type}'))">创建项目</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  if (_createRefImages.length) renderFileList(_createRefImages, '参考图片');
  if (_createAttachments.length) renderFileList(_createAttachments, '附件');

  // 恢复草稿中的产品类目和目标市场
  if (draft && type === 'channel_custom') {
    if (draft.productCategory) {
      const sel = document.getElementById('productCategorySelect');
      if (sel) {
        sel.value = draft.productCategory;
        onCategoryChange(sel);
      }
    }
    if (draft.targetMarket) {
      try {
        const markets = JSON.parse(draft.targetMarket);
        markets.forEach(m => {
          const chip = document.querySelector(`#marketChips .chip[data-value="${m}"]`);
          if (chip) chip.classList.add('selected');
        });
        document.getElementById('targetMarketInput').value = draft.targetMarket;
      } catch(e) {}
    }
    if (draft.complianceItems) {
      try {
        const items = JSON.parse(draft.complianceItems);
        items.forEach(m => {
          const chip = document.querySelector(`#complianceChips .chip[data-value="${m}"]`);
          if (chip) chip.classList.add('selected');
        });
        document.getElementById('complianceItemsInput').value = draft.complianceItems;
      } catch(e) {}
    }
    if (draft.priceRange) {
      const chip = document.querySelector(`#priceRangeChips .chip[data-value="${draft.priceRange}"]`);
      if (chip) {
        chip.classList.add('selected');
        document.getElementById('priceRangeInput').value = draft.priceRange;
      }
    }
  }
}

async function submitCreateProject(type) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  // 清除之前的错误提示
  document.querySelectorAll('.field-error').forEach(el => el.remove());

  const fd = new FormData(document.getElementById('createProjectForm'));
  const data = Object.fromEntries(fd.entries());
  let hasError = false;

  // 验证必填字段
  function addFieldError(fieldName, msg) {
    hasError = true;
    // 找到对应表单组，追加红色提示
    const formGroup = document.querySelector(`[name="${fieldName}"]`)?.closest('.form-group');
    if (!formGroup) { alert(msg); return; }
    const old = formGroup.querySelector('.field-error');
    if (old) old.remove();
    const err = document.createElement('div');
    err.className = 'field-error';
    err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
    err.textContent = '❌ ' + msg;
    formGroup.appendChild(err);
    // 高亮输入框
    const input = formGroup.querySelector('.form-input, .form-select, .form-textarea');
    if (input) input.style.borderColor = 'var(--danger)';
  }

  function clearFieldHighlight() {
    document.querySelectorAll('.form-input, .form-select, .form-textarea').forEach(el => {
      el.style.borderColor = '';
    });
  }
  clearFieldHighlight();

  // 需求方（渠道定制单）
  if (type === 'channel_custom' && !data.salesId) addFieldError('salesId', '请选择需求方（销售）');

  // 产品企划（必选）
  if (!data.plannerId) addFieldError('plannerId', '请选择产品企划');

  // 产品类目
  const category = data.productCategory || '';
  if (!category) {
    addFieldError('productCategory', '请选择产品类目');
  } else if (category === '其他') {
    const note = data.productCategoryNote || '';
    if (!note.trim()) {
      addFieldError('productCategoryNote', '请补充其他类目的具体说明');
    }
  }

  // 参考零售价
  if (!data.priceRange) {
    addFieldError('priceRange', '请选择参考零售价');
  }

  // 目标市场
  const marketVal = data.targetMarket || '';
  if (!marketVal || marketVal === '[]') {
    addFieldError('targetMarket', '请选择目标市场');
  }

  // 要求完成时间
  if (!data.deadline) addFieldError('deadline', '请填写要求完成时间');

  // 验证日期格式和不能早于今天
  if (data.deadline) {
    const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
    if (!dateRegex.test(data.deadline) || isNaN(new Date(data.deadline).getTime())) {
      addFieldError('deadline', '日期格式不正确，请使用 yyyy-mm-dd（如：2026-07-15）');
    } else {
      const parts = data.deadline.split('-');
      const m = parseInt(parts[1]), day = parseInt(parts[2]);
      if (m < 1 || m > 12 || day < 1 || day > 31) {
        addFieldError('deadline', '日期超出有效范围（月份 1-12，日期 1-31）');
      } else {
        // 禁止选择早于今天的日期
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const selected = new Date(data.deadline);
        if (selected < today) {
          addFieldError('deadline', '要求完成时间不能早于今天');
        }
      }
    }
  }

  // 产品要求
  if (!data.productRequirements) addFieldError('productRequirements', '请填写产品要求');

  if (hasError) return;

  data.type = type;
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;

  // 组装参考图JSON（改用url引用，不再传base64）
  const refImgs = _createRefImages.map(img => ({ name: img.name, url: img.url, size: img.size, storedName: img.storedName }));
  data.referenceImagesJson = JSON.stringify(refImgs);

  // 组装附件JSON
  const atts = _createAttachments.map(a => ({ name: a.name, url: a.url, size: a.size, storedName: a.storedName }));
  data.attachmentsJson = JSON.stringify(atts);

  // 处理未指定的字段
  if (!data.plannerId) data.plannerId = '';
  if (!data.salesId) {
    data.salesId = currentRole === 'sales' ? currentUserId : '';
  }

  try {
    await apiPost('/projects', data);
    // 创建成功，清除草稿和缓存
    sessionStorage.removeItem('design_pm_create_draft');
    APP_CACHE.orders = []; // 清除项目列表缓存
    closeM('createProjectModal', true); // force=true 跳过保存提示
    // 创建渠道定制项目后跳转到渠道列表视图
    if (type === 'channel_custom') {
      currentView = 'channel';
    }
    render();
  } catch (e) {
    alert('创建失败: ' + e.message);
  }
}

// 日期字段内联错误提示
function showDateError(inputName, msg) {
  // 找到日期选择器容器，在下方插入红色提示
  const datePicker = document.querySelector(`.date-picker input[name="${inputName}"]`)?.closest('.date-picker');
  if (!datePicker) { alert(msg); return; }
  // 移除旧错误
  const old = datePicker.parentElement.querySelector('.field-error');
  if (old) old.remove();
  const err = document.createElement('div');
  err.className = 'field-error';
  err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
  err.textContent = '❌ ' + msg;
  datePicker.parentElement.appendChild(err);
}

// 页面关闭/刷新时清除草稿
window.addEventListener('beforeunload', function() {
  sessionStorage.removeItem('design_pm_create_draft');
});

// ==================== 项目详情 ====================
async function openProjectDetail(pid) {
  if (!tryOpenModal('projectDetailModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'projectDetailModal';
    modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('projectDetailModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-header-left"><div class="modal-title">${detail.type === 'channel_custom' ? '📦 渠道定制项目' : '🏭 常规品设计项目'} #${detail.id}</div></div>
        </div>
        <div class="modal-body">${renderProjectDetailContent(detail)}</div>
        <div class="modal-footer" id="detailActions">${renderProjectActions(detail)}</div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('projectDetailModal');
  } catch (e) {
    doneOpenModal('projectDetailModal');
    alert('加载失败: ' + e.message);
  }
}

function renderProjectDetailContent(detail) {
  const isChannel = detail.type === 'channel_custom';
  // 进度：approved/completed/sales_approved/admin_approved 算完成
  const totalTasks = detail.tasks.length;
  const doneStatuses = ['delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const doneTasks = detail.tasks.filter(t => {
    return doneStatuses.includes(t.status);
  }).length;
  const pct = totalTasks ? Math.round(doneTasks / totalTasks * 100) : 0;

  return `
    ${detail.complianceItems ? (() => { try {
      const items = JSON.parse(detail.complianceItems);
      return `<div style="background:#FEF3C7;border:1px solid #FDE68A;border-radius:8px;padding:12px 16px;margin-bottom:16px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:600;color:#92400E;white-space:nowrap;">⚠️ 合规处罚提醒</span>
        <span style="font-size:12px;color:#92400E;white-space:nowrap;">该产品涉及以下合规事项，请关注供应商资质：</span>
        ${items.map(i => `<span style="display:inline-block;padding:3px 10px;border-radius:10px;font-size:12px;font-weight:500;background:#FDE68A;color:#92400E;">${i}</span>`).join('')}
      </div>`;
    } catch(e) { return ''; } })() : ''}
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:20px;">
      <span class="badge ${detail.statusCls}" style="font-size:13px;padding:5px 14px;">${detail.statusLabel}</span>
      <span style="font-size:12px;color:var(--gray-400);">创建：${fmtDT(detail.createdAt)}</span>
      <span style="font-size:12px;color:var(--gray-400);">更新：${fmtDT(detail.updatedAt)}</span>
      ${detail.tasks.length > 0 ? `<div class="progress-bar" style="flex:1;max-width:200px;"><div class="progress-fill" style="width:${pct}%;"></div></div><span style="font-size:12px;color:var(--gray-500);">${pct}%</span>` : ''}
    </div>

    <div class="detail-section">
      <div class="detail-section-title">📋 项目信息</div>
      <div class="detail-grid">
        <div class="detail-item"><div class="detail-label">项目类型</div><div class="detail-value">${isChannel ? '渠道定制单' : '公司常规品'}</div></div>
        ${isChannel ? `<div class="detail-item"><div class="detail-label">需求方（销售）</div><div class="detail-value">${detail.salesName || '-'}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">产品企划</div><div class="detail-value">${detail.plannerName}</div></div>
        ${detail.productCategory ? `<div class="detail-item"><div class="detail-label">产品类目</div><div class="detail-value">${detail.productCategory}${detail.productCategory === '其他' && detail.productCategoryNote ? `（${detail.productCategoryNote}）` : ''}</div></div>` : ''}
        ${detail.priceRange ? `<div class="detail-item"><div class="detail-label">参考零售价</div><div class="detail-value">${detail.priceRange}</div></div>` : ''}
        ${detail.targetMarket ? `<div class="detail-item"><div class="detail-label">目标市场</div><div class="detail-value">${(() => { try { return JSON.parse(detail.targetMarket).join('、'); } catch(e) { return detail.targetMarket; } })()}</div></div>` : ''}
        ${detail.complianceItems ? `<div class="detail-item" style="grid-column:1/-1;"><div class="detail-label" style="color:var(--warning);font-weight:600;">⚠️ 合规处罚<span style="color:var(--gray-400);font-weight:400;font-size:12px;margin-left:6px;">提醒产品企划关注相关供应商是否有相关资质</span></div><div class="detail-value" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:4px;">${(() => { try { return JSON.parse(detail.complianceItems).map(i => `<span style="display:inline-block;padding:4px 12px;border-radius:12px;font-size:12px;font-weight:500;background:#FEF3C7;color:#92400E;border:1px solid #FDE68A;">⚠ ${i}</span>`).join(''); } catch(e) { return detail.complianceItems; } })()}</div></div>` : ''}
        <div class="detail-item"><div class="detail-label">要求完成时间</div><div class="detail-value">${formatDate(detail.deadline)}</div></div>
      </div>
      <div style="margin-top:8px;"><div class="detail-label">产品要求</div><div class="detail-value">${detail.productRequirements || '-'}</div></div>
      ${detail.description ? `<div style="margin-top:4px;"><div class="detail-label">细节描述</div><div class="detail-value">${detail.description}</div></div>` : ''}
      ${renderProjectReferenceImages(detail)}
      ${renderProjectAttachments(detail)}
    </div>

    <div class="detail-section">
      <div class="detail-section-title">
        📌 子任务列表
        <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${doneTasks}/${detail.tasks.length} 完成</span>
        ${(currentRole === 'planner') && (detail.status === 'planner_accepted' || detail.status === 'in_progress' || detail.status === 'completed' || detail.status === 'completed_pending_score') ? `<button class="btn btn-primary btn-sm" style="margin-left:auto;" onclick="addSubTask(${detail.id})">➕ 添加子任务</button>` : ''}
      </div>
      ${detail.tasks.length === 0 ? `<div class="empty" style="padding:30px;"><div class="empty-icon">📭</div><p>暂无子任务，等待产品企划添加</p></div>` : ''}
      ${detail.tasks.filter(t => {
        // 设计师只看设计师的任务，供应链只看供应链的任务
        if (currentRole === 'designer') return !t.assigneeRole || t.assigneeRole === 'designer';
        if (currentRole === 'supplychain') return t.assigneeRole === 'supplychain';
        return true;
      }).map((t, i) => renderSubTaskCard(detail, t, i)).join('')}
    </div>

    ${renderProjectScoringSummary(detail)}

    ${renderProjectPipeline(detail)}

    <div class="detail-section">
      <div class="detail-section-title">📜 操作日志</div>
      <div class="timeline">${detail.logs.map(l => `
        <div class="timeline-item"><div class="timeline-dot done"></div><div class="timeline-content"><div class="timeline-title">${cleanLogAction(l.action)}</div><div class="timeline-time">${renderLogLabel(l)} · ${fmtDT(l.time)}</div></div></div>
      `).join('')}</div>
    </div>`;
}

// 清理操作日志文本：删除末尾的（xxx）、（planner已评）等
function cleanLogAction(action) {
  if (!action) return '';
  return action.replace(/\s*（[^）]*）\s*$/, '').trim();
}

function renderLogLabel(l) {
  const roleName = l.role === 'sales' ? '销售' : l.role === 'planner' ? '产品企划' : l.role === 'designer' ? '设计师' : l.role === 'supplychain' ? '供应链' : l.role === 'admin' ? '管理员' : l.role;
  const isScore = l.action && l.action.includes('评分');
  // 评分日志特殊格式
  if (isScore) {
    // 提取角色名: "子任务评分：xxx（planner已评）"
    const match = l.action.match(/（(.+?)已评/);
    const scoreRole = match ? match[1] : l.role;
    const scoreRoleName = scoreRole === 'sales' ? '销售' : scoreRole === 'planner' ? '产品企划' : scoreRole === 'designer' ? '设计师' : scoreRole === 'supplychain' ? '供应链' : scoreRole;
    return `（${scoreRoleName}：${l.user} 已评）`;
  }
  return `（${roleName}：${l.user}）`;
}

function renderSubTaskCard(detail, task, idx) {
  const tsi = getTaskStatusInfo(task.status);
  const isPlanner = currentRole === 'planner';
  const myTask = ['designer', 'supplychain', 'planner'].includes(currentRole) && task.designerId === getCurrentUserId();
  const needScore = task.scoringRecords && task.scoringRecords.some(sr => sr.score == null && sr.role === currentRole);
  const doneStatuses = ['delivered', 'planner_approved', 'sales_approved', 'admin_approved', 'completed'];
  const isDone = doneStatuses.includes(task.status);

  return `<div class="subtask-card${isDone ? ' completed' : ''}">
    <div class="subtask-header">
      <div class="subtask-name"><span class="subtask-number">${idx + 1}</span> ${task.name}</div>
      <span class="badge ${tsi.cls}">${tsi.label}</span>
    </div>
    <div class="subtask-meta">
      <div class="subtask-meta-item">👤 负责人：<strong>${task.designerName || '待分配'}</strong>${task.assigneeRole ? `<span style="display:inline-block;margin-left:6px;padding:1px 6px;border-radius:8px;font-size:10px;font-weight:500;${task.assigneeRole === 'supplychain' ? 'background:#F0FDFA;color:#0D9488;' : task.assigneeRole === 'planner' ? 'background:#EFF6FF;color:#1D4ED8;' : 'background:#FEF2F2;color:#DC2626;'}">${task.assigneeRole === 'supplychain' ? '供应链' : task.assigneeRole === 'planner' ? '企划' : '设计师'}</span>` : ''}</div>
      <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(task.plannedDate)}</strong></div>
      ${task.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(task.actualDate)}</strong></div>` : ''}
    </div>
    ${task.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:6px;">📝 ${task.details}</div>` : ''}
    ${task.referenceImagesJson ? renderSubTaskImages(task.referenceImagesJson) : ''}
    ${task.attachmentsJson ? renderTaskAttachments(task.attachmentsJson) : ''}

    ${task.status === 'delivered' || task.status === 'planner_approved' || task.status === 'sales_approved' || task.status === 'admin_approved' || task.status === 'approved' || task.status === 'completed' || task.status === 'rejected' ? `
    <div class="subtask-deliver">
      ${task.deliverables ? `<div class="detail-item"><div class="detail-label">交付成果</div><div class="detail-value">${task.deliverables}</div></div>` : ''}
    </div>` : ''}

    ${task.reviewComments ? `<div class="review-box ${task.status === 'rejected' ? 'rejected' : 'approved'}"><strong>${task.status === 'rejected' ? '驳回意见' : '验收意见'}：</strong>${task.reviewComments}</div>` : ''}

    ${task.scoringRecords && ['planner_approved', 'sales_approved', 'admin_approved', 'approved', 'completed'].includes(task.status) ? renderScoringMini(task, isDone) : ''}

    <div class="subtask-actions">
      ${/* 企划验收（首轮）：常规品直接通过；渠道定制单进入企划确认状态 */''}
      ${isPlanner && task.status === 'delivered' ? `
        <button class="btn btn-success btn-sm" onclick="taskApprove(${detail.id},${task.id},'${detail.type}')">✅ 验收通过</button>
        <button class="btn btn-danger btn-sm" onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${/* 渠道定制单：销售第二轮确认 */''}
      ${currentRole === 'sales' && detail.type === 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" onclick="taskApprove(${detail.id},${task.id},'channel_custom')">✅ 销售确认通过</button>
        <button class="btn btn-danger btn-sm" onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${currentRole === 'admin' && detail.type !== 'channel_custom' && task.status === 'planner_approved' ? `
        <button class="btn btn-success btn-sm" onclick="taskApprove(${detail.id},${task.id},'regular')">✅ 管理确认通过</button>
        <button class="btn btn-danger btn-sm" onclick="taskReject(${detail.id},${task.id})">↩️ 驳回</button>
      ` : ''}
      ${myTask && task.status === 'pending' ? `<button class="btn btn-primary btn-sm" onclick="taskAccept(${detail.id},${task.id})">✅ 接单</button>` : ''}
      ${myTask && task.status === 'accepted' ? `<button class="btn btn-primary btn-sm" onclick="taskDeliver(${detail.id},${task.id})">📤 交付成果</button>` : ''}
      ${myTask && task.status === 'rejected' ? `<button class="btn btn-warning btn-sm" onclick="taskRedeliver(${detail.id},${task.id})">📤 重新交付</button>` : ''}
      ${isPlanner && detail.status !== 'paused' && (task.status === 'pending' || task.status === 'accepted') ? `
        <button class="btn btn-outline btn-sm" onclick="editTask(${detail.id},${task.id})">✏️ 编辑</button>
        <button class="btn btn-outline btn-sm" onclick="deleteTask(${detail.id},${task.id})" style="color:var(--danger);border-color:var(--danger);">🗑️ 删除</button>
      ` : ''}
      ${needScore ? `<button class="btn btn-warning btn-sm" onclick="openScoring(${detail.id},${task.id})">⭐ 评分</button>` : ''}
    </div>
  </div>`;
}

function renderProjectActions(detail) {
  let actions = '';
  const canManageProject = currentRole === 'planner' || currentRole === 'sales' || currentRole === 'admin';

  if (currentRole === 'planner' && detail.status === 'pending_planner' && detail.type === 'channel_custom') {
    actions += `<button class="btn btn-primary" onclick="plannerAcceptProject(${detail.id})">✅ 接单</button>`;
  }

  if (canManageProject) {
    const activeStatuses = ['pending_planner', 'planner_accepted', 'in_progress'];

    // 终止按钮（含暂停状态，暂停也可终止）
    if (activeStatuses.includes(detail.status) || detail.status === 'paused') {
      actions += `<button class="btn btn-danger btn-sm" onclick="terminateProject(${detail.id})">终止项目</button>`;
    }
    // 终止确认中 - 对方可以确认
    if (detail.status === 'pending_terminate') {
      actions += `<button class="btn btn-danger btn-sm" onclick="terminateProject(${detail.id})">确认终止</button>`;
      actions += `<button class="btn btn-outline btn-sm" onclick="cancelTerminate(${detail.id})" style="color:var(--gray-600);border-color:var(--gray-300);">↩️ 取消终止</button>`;
    }

    // 暂停 / 继续
    if (activeStatuses.includes(detail.status)) {
      actions += `<button class="btn btn-outline btn-sm" onclick="pauseProject(${detail.id})" style="color:var(--primary);border-color:var(--primary);">暂停</button>`;
    }
    if (detail.status === 'paused') {
      actions += `<button class="btn btn-outline btn-sm" onclick="resumeProject(${detail.id})" style="color:var(--success);border-color:var(--success);">继续</button>`;
    }
  }

  if (detail.status === 'terminated') {
    actions += `<span style="color:var(--danger);font-size:13px;font-weight:600;">⛔ 该项目已终止，无法进行任何操作</span>`;
  }
  // 管理员可永久删除项目
  if (currentRole === 'admin') {
    actions += `<button class="btn btn-danger btn-sm" onclick="deleteProject(${detail.id})" title="永久删除项目和所有关联数据">🗑️ 删除</button>`;
  }
  actions += `<button class="btn btn-outline btn-sm" onclick="shareProject(${detail.id})">🔗 分享</button>`;
  actions += `<button class="btn btn-outline" onclick="closeM('projectDetailModal')">关闭</button>`;
  return actions;
}

// 飞书兼容的确认弹窗
function showConfirmDialog(message, onConfirm, confirmText, cancelText) {
  if (document.getElementById('confirmDialogOverlay')) return;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'confirmDialogOverlay';
  overlay.style.zIndex = '300';
  overlay.innerHTML = `
    <div class="modal" style="max-width:380px;">
      <div class="modal-header">
        <button class="modal-close" onclick="this.closest('.modal-overlay').remove()">✕</button>
        <div class="modal-header-left"><div class="modal-title">⚠️ 确认操作</div></div>
      </div>
      <div class="modal-body" style="text-align:center;padding:28px 20px;">
        <p style="font-size:14px;color:var(--gray-700);margin:0;line-height:1.6;">${message}</p>
      </div>
      <div class="modal-footer" style="justify-content:center;gap:12px;padding:12px 20px;">
        <button class="btn btn-outline" onclick="this.closest('.modal-overlay').remove()" style="padding:8px 20px;">${cancelText || '取消'}</button>
        <button class="btn btn-danger" id="confirmDialogOk" style="padding:8px 20px;">${confirmText || '确定'}</button>
      </div>
    </div>`;
  overlay.querySelector('#confirmDialogOk').onclick = function() { overlay.remove(); onConfirm(); };
  document.body.appendChild(overlay);
}

async function terminateProject(pid) {
  showConfirmDialog('确定要终止该项目吗？终止后项目将无法恢复。', async () => {
    try {
      await apiPost(`/projects/${pid}/terminate`, { currentUser: getCurrentUserName(), currentRole: currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function cancelTerminate(pid) {
  showConfirmDialog('确定要取消终止吗？', async () => {
    try {
      await apiPost(`/projects/${pid}/cancel-terminate`, { currentUser: getCurrentUserName(), currentRole: currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function deleteProject(pid) {
  showConfirmDialog('⚠️ 确定要永久删除项目 #' + pid + ' 吗？<br>此操作不可恢复！<br>子任务、日志、评分记录将一并删除。', async () => {
    try {
      await apiDelete(`/projects/${pid}`);
      closeM('projectDetailModal');
      APP_CACHE.orders = [];
      Object.keys(SWR_CACHE).forEach(k => delete SWR_CACHE[k]);
      render();
    } catch(e) { alert('删除失败: ' + e.message); }
  });
}

async function pauseProject(pid) {
  showConfirmDialog('确定要暂停该项目吗？暂停期间无法进行任何操作。', async () => {
    try {
      await apiPost(`/projects/${pid}/pause`, { currentUser: getCurrentUserName(), currentRole: currentRole });
      await refreshAfterMutation(pid);
    } catch(e) { alert('操作失败: ' + e.message); }
  });
}

async function resumeProject(pid) {
  try {
    await apiPost(`/projects/${pid}/resume`, { currentUser: getCurrentUserName(), currentRole: currentRole });
    await refreshAfterMutation(pid);
  } catch(e) { alert('操作失败: ' + e.message); }
}

// 渲染项目参考图片
function renderProjectReferenceImages(detail) {
  if (!detail.referenceImagesJson) return '';
  let imgs;
  try { imgs = JSON.parse(detail.referenceImagesJson); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => u + (u.includes('?') ? '&' : '?') + 'token=' + token;
  return `<div style="margin-top:8px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${authUrl(img.url)}" alt="${img.name || ''}" title="${img.name || ''}" class="img-clickable" loading="lazy" decoding="async" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
          <button onclick="event.stopPropagation();showDownloadOptions('${img.url}','${escHtml(img.name || 'image.png')}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>`).join('')}
    </div></div>`;
}

/* 子任务参考图 */
function renderSubTaskImages(jsonStr) {
  if (!jsonStr) return '';
  let imgs;
  try { imgs = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => u + (u.includes('?') ? '&' : '?') + 'token=' + token;
  return `<div style="margin-top:8px;padding-left:4px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${authUrl(img.url)}" alt="${img.name || ''}" class="img-clickable" loading="lazy" decoding="async" style="width:60px;height:60px;object-fit:cover;border-radius:4px;border:1px solid var(--gray-200);cursor:pointer;">
          <button onclick="event.stopPropagation();showDownloadOptions('${img.url}','${escHtml(img.name || 'image.png')}',${img.size || 0})" title="下载选项" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
      </div>`).join('')}
    </div></div>`;
}

// 渲染项目附件
function renderProjectAttachments(detail) {
  if (!detail.attachmentsJson) return '';
  let atts;
  try { atts = JSON.parse(detail.attachmentsJson); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';
  return `<div style="margin-top:8px;"><div class="detail-label">📎 附件</div>
    ${atts.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
      <span>📎</span><span class="attachment-name" style="flex:1;">${a.name}</span>
      ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
      <button onclick="showDownloadOptions('${a.url}','${escHtml(a.name)}',${a.size || 0})" style="padding:2px 8px;border-radius:4px;background:var(--primary-light);color:var(--primary);font-size:12px;white-space:nowrap;border:none;cursor:pointer;">⬇ 下载</button>
    </div>`).join('')}
    </div>`;
}

// 渲染子任务交付附件
function renderTaskAttachments(jsonStr) {
  if (!jsonStr) return '';
  let atts;
  try { atts = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!atts || !atts.length) return '';

  // 分离图片和附件文件
  const images = atts.filter(a => a.name && a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));
  const files = atts.filter(a => !a.name || !a.name.match(/\.(png|jpe?g|gif|webp|svg|bmp)$/i));

  let html = '';
  const token = localStorage.getItem('design_pm_token');
  const authUrl = u => u + (u.includes('?') ? '&' : '?') + 'token=' + token;
  // 图片预览
  if (images.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">🖼️ 交付图片</div>
      <div class="image-preview" style="margin-top:4px;">
        ${images.map(img => `<div style="position:relative;display:inline-block;">
            <img src="${authUrl(img.url)}" alt="${img.name || ''}" class="img-clickable" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
            <button onclick="event.stopPropagation();showDownloadOptions('${img.url}','${escHtml(img.name || 'image.png')}',${img.size || 0})" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;border:none;cursor:pointer;">⬇</button>
        </div>`).join('')}
      </div></div>`;
  }
  // 附件下载
  if (files.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">📎 交付附件</div>
      ${files.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
        <span>📎</span><span class="attachment-name" style="flex:1;">${a.name}</span>
        ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
        <button onclick="showDownloadOptions('${a.url}','${escHtml(a.name)}',${a.size || 0})" style="padding:2px 8px;border-radius:4px;background:var(--primary-light);color:var(--primary);font-size:12px;white-space:nowrap;border:none;cursor:pointer;">⬇ 下载</button>
      </div>`).join('')}
      </div>`;
  }
  return html;
}

function renderProjectScoringSummary(detail) {
  const approvedTasks = detail.tasks.filter(t => ['approved', 'completed', 'sales_approved', 'admin_approved'].includes(t.status) && t.scoringRecords && t.scoringRecords.length > 0);
  if (approvedTasks.length === 0) return '';
  const allScoredTasks = approvedTasks.filter(t => t.scoringRecords.every(sr => sr.score != null));

  let html = `<div class="detail-section"><div class="detail-section-title">⭐ 项目评分汇总 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${allScoredTasks.length}/${approvedTasks.length} 已完成</span></div>`;
  approvedTasks.forEach((task, i) => {
    const records = task.scoringRecords;
    let ta = 0, tw = 0;
    records.forEach(r => {
      if (r.score != null) {
        ta += r.score * r.weight;
        tw += r.weight;
      }
    });
    const final = tw > 0 ? (ta / tw).toFixed(0) : null;
    html += `<div style="display:flex;align-items:center;gap:12px;padding:8px 12px;background:var(--gray-50);border-radius:6px;margin-bottom:6px;font-size:13px;">
      <span style="font-weight:600;">#${i + 1} ${task.name}</span>
      <span style="flex:1;"></span>
      ${final ? `<span style="font-size:16px;font-weight:700;color:var(--primary);">${final}分</span>` : `<span style="color:var(--gray-400);">评分中…</span>`}
    </div>`;
  });
  html += `</div>`;
  return html;
}

// ===== 项目进度管道 =====
function renderProjectPipeline(detail) {
  const isChannel = detail.type === 'channel_custom';
  const tasks = detail.tasks || [];

  // 定义一个管道的 5 个阶段
  const stages = isChannel
    ? [
        { key: 'create', label: '创建项目', detail: '销售 ' + (detail.salesName || '') },
        { key: 'accept', label: '企划接单', detail: detail.plannerName || '' },
        { key: 'execute', label: '子任务执行', detail: '' },
        { key: 'planner_score', label: '企划评分', detail: '' },
        { key: 'sales_confirm', label: '销售确认', detail: '' },
      ]
    : [
        { key: 'create', label: '创建项目', detail: '销售 ' + (detail.salesName || '') },
        { key: 'accept', label: '企划接单', detail: detail.plannerName || '' },
        { key: 'execute', label: '子任务执行', detail: '' },
        { key: 'planner_score', label: '企划评分', detail: '' },
        { key: 'admin_confirm', label: '管理确认', detail: '' },
      ];

  // 计算各阶段状态: done / current / pending / error
  const status = detail.status;
  const taskStatuses = tasks.map(t => t.status);

  function stageState(key) {
    switch (key) {
      case 'create':
        return 'done';
      case 'accept':
        return !['pending_planner'].includes(status) ? 'done' : 'current';
      case 'execute': {
        if (taskStatuses.length === 0) return status === 'completed' ? 'done' : 'current';
        const allDelivered = taskStatuses.every(s => ['delivered','planner_approved','sales_approved','admin_approved','completed'].includes(s));
        if (allDelivered) return 'done';
        const anyActive = taskStatuses.some(s => ['accepted','delivered'].includes(s));
        return anyActive ? 'current' : 'pending';
      }
      case 'planner_score': {
        if (taskStatuses.length === 0) return 'pending';
        const allScored = taskStatuses.every(s => ['planner_approved','sales_approved','admin_approved','completed'].includes(s));
        if (allScored) return 'done';
        const anyDelivered = taskStatuses.some(s => s === 'delivered');
        return anyDelivered ? 'current' : 'pending';
      }
      case 'sales_confirm':
      case 'admin_confirm': {
        if (taskStatuses.length === 0) return 'pending';
        const targetStatus = key === 'sales_confirm' ? 'sales_approved' : 'admin_approved';
        const allDone = taskStatuses.every(s => s === 'completed');
        if (allDone) return 'done';
        const awaitingConfirm = taskStatuses.some(s => s === 'planner_approved');
        return awaitingConfirm ? 'current' : 'pending';
      }
    }
    return 'pending';
  }

  // 构建下一步提示
  function getNextHint() {
    if (['terminated', 'pending_terminate'].includes(status)) {
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⛔', title: '项目已终止', text: '该项目已被终止，无法继续操作。' };
    }
    if (status === 'paused') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '⏸️', title: '项目已暂停', text: '点击"继续"按钮可恢复项目。' };
    }
    if (status === 'completed') {
      return { color: '#3B6D11', bg: '#EAF3DE', border: '#C0DD97', icon: '🎉', title: '项目已完成', text: '所有子任务已验收评分完毕。' };
    }

    if (status === 'pending_planner') {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待企划接单', text: '产品企划 ' + (detail.plannerName || '待指定') + ' 需要先接单才能开始工作。' };
    }

    // 子任务层面分析
    const pendingTasks = tasks.filter(t => t.status === 'pending');
    const unassignedTasks = tasks.filter(t => t.status === 'pending' && (!t.designerId || t.designerId === ''));
    const acceptedTasks = tasks.filter(t => t.status === 'accepted');
    const deliveredTasks = tasks.filter(t => t.status === 'delivered');
    const plannerApprovedTasks = tasks.filter(t => t.status === 'planner_approved');
    const rejectedTasks = tasks.filter(t => t.status === 'rejected');

    if (rejectedTasks.length > 0) {
      const names = rejectedTasks.map(t => t.name).join('、');
      return { color: '#E24B4A', bg: '#FCEBEB', border: '#F7C1C1', icon: '⚠️', title: '有子任务被驳回', text: '子任务「' + names + '」需要设计师重新交付。' };
    }
    // 优先级：已企划评分 → 已交付 → 执行中 → 待分配
    if (plannerApprovedTasks.length > 0) {
      const names = plannerApprovedTasks.map(t => t.name).join('、');
      const confirmer = isChannel ? '销售' : '管理';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待' + confirmer + '确认', text: '子任务「' + names + '」企划已评分通过，等待' + confirmer + '确认。' };
    }
    if (deliveredTasks.length > 0) {
      const names = deliveredTasks.map(t => t.name).join('、');
      const role = isChannel ? '企划' : '企划';
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待验收评分', text: '子任务「' + names + '」已交付，等待' + role + '验收评分。' };
    }
    if (acceptedTasks.length > 0) {
      const names = acceptedTasks.map(t => t.name).join('、');
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待交付', text: '子任务「' + names + '」正在执行中，等待子任务负责人交付成果。' };
    }
    if (unassignedTasks.length > 0) {
      return { color: '#854F0B', bg: '#FAEEDA', border: '#FAC775', icon: '💡', title: '等待分配子任务', text: '还有 ' + unassignedTasks.length + ' 个子任务未指派，请先指派负责人。' };
    }
    return null;
  }

  // 生成管道圆点 HTML
  const stageHtml = stages.map((s, i) => {
    const st = stageState(s.key);
    const isLast = i === stages.length - 1;
    let dotStyle, labelColor, detailColor;
    if (st === 'done') {
      dotStyle = 'background:#3B6D11;';
      labelColor = 'color:#3B6D11;';
      detailColor = 'color:var(--color-text-tertiary);';
    } else if (st === 'current') {
      dotStyle = 'background:#EF9F27;box-shadow:0 0 0 4px #FAEEDA;';
      labelColor = 'color:#854F0B;font-weight:600;';
      detailColor = 'color:#854F0B;';
    } else if (st === 'error') {
      dotStyle = 'background:#E24B4A;';
      labelColor = 'color:#A32D2D;';
      detailColor = 'color:#A32D2D;';
    } else {
      dotStyle = 'background:var(--color-border-tertiary);';
      labelColor = 'color:var(--color-text-tertiary);';
      detailColor = 'color:var(--color-text-tertiary);';
    }
    const connector = !isLast ? `<div style="position:absolute;top:14px;left:56%;right:-16px;height:3px;background:${st === 'done' ? '#3B6D11' : 'var(--color-border-tertiary)'};z-index:-1;"></div>` : '';
    const dotInner = st === 'done' ? '<span style="color:#fff;font-size:11px;">✓</span>' : st === 'current' ? '<span style="color:#fff;font-size:12px;">●</span>' : '';
    return `<div style="flex:1;text-align:center;position:relative;">
      <div style="width:28px;height:28px;border-radius:50%;margin:0 auto 6px;display:flex;align-items:center;justify-content:center;${dotStyle}">${dotInner}</div>
      <div style="font-size:11px;${labelColor}">${s.label}</div>
      <div style="font-size:10px;${detailColor};margin-top:2px;">${st === 'done' ? '已完成' : st === 'current' ? '进行中' : '待进行'}${s.detail ? ' · ' + s.detail : ''}</div>
      ${connector}
    </div>`;
  }).join('');

  const hint = getNextHint();
  const hintHtml = hint ? `
    <div style="margin-top:16px;background:${hint.bg};border-radius:8px;padding:12px 16px;border:0.5px solid ${hint.border};">
      <div style="display:flex;align-items:center;gap:8px;">
        <span style="font-size:16px;">${hint.icon}</span>
        <div>
          <div style="font-size:12px;font-weight:500;color:${hint.color};">${hint.title}</div>
          <div style="font-size:12px;color:${hint.color};opacity:0.85;">${hint.text}</div>
        </div>
      </div>
    </div>` : '';

  return `<div class="detail-section">
    <div class="detail-section-title">🔵 项目进度 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${isChannel ? '渠道定制单' : '公司常规品'}</span></div>
    <div style="padding:20px 8px 8px;">
      <div style="display:flex;gap:0;">${stageHtml}</div>
      ${hintHtml}
    </div>
    <div style="margin-top:8px;font-size:11px;color:var(--color-text-tertiary);display:flex;gap:16px;padding:0 4px;">
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#3B6D11;vertical-align:middle;margin-right:4px;"></span>已完成</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#EF9F27;vertical-align:middle;margin-right:4px;"></span>进行中</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:var(--color-border-tertiary);vertical-align:middle;margin-right:4px;"></span>待进行</span>
      <span><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:#E24B4A;vertical-align:middle;margin-right:4px;"></span>异常</span>
    </div>
  </div>`;
}
async function plannerAcceptProject(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: currentRole, userId: getCurrentUserId() });
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// 从列表直接接单（不需要打开弹窗）
async function plannerAcceptFromList(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: currentRole, userId: getCurrentUserId() });
    APP_CACHE.orders = [];
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// ==================== 添加 / 编辑子任务 ====================
let _subTaskRefImages = [];
let _subTaskAttachments = [];

function handleSubTaskRefImages(input) {
  handleFileUpload(input, _subTaskRefImages, 9, '参考图片', true);
}
function handleSubTaskAttachments(input) {
  handleFileUpload(input, _subTaskAttachments, 9, '附件', false);
}

function addSubTask(pid) {
  if (document.getElementById('addSubTaskModal')) return;
  // 清理可能残留的其他弹窗，避免遮挡
  document.querySelectorAll('.modal-overlay').forEach(el => {
    if (el.id !== 'projectDetailModal' && el.id !== 'addSubTaskModal') el.remove();
  });
  _subTaskRefImages = [];
  _subTaskAttachments = [];

  const designerOpts = `<option value="">请选择设计师</option>` +
    USERS.designer.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('');
  const supplychainOpts = USERS.supplychain && USERS.supplychain.length
    ? `<option value="">请选择供应链</option>` +
      USERS.supplychain.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
    : `<option value="">暂无供应链人员</option>`;
  const plannerOpts = USERS.planner && USERS.planner.length
    ? `<option value="">请选择企划</option>` +
      USERS.planner.map(u => `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('')
    : `<option value="">暂无企划人员</option>`;

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'addSubTaskModal';
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('addSubTaskModal')">✕</button>
    <div class="modal modal-lg">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">➕ 添加子任务</div></div></div>
      <div class="modal-body">
        <form id="addSubTaskForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 子任务名称</label><input type="text" class="form-input" name="name" required placeholder="如：首页Banner设计、详情页布局..." oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''"></div>
          <div class="form-row">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划要求完成时间</label>${renderDatePicker('plannedDate', {required:true})}</div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
            <div style="display:flex;gap:16px;">
              <label class="checkbox-item checked" style="cursor:pointer;" onclick="switchAssigneeType('add', 'designer', this)">
                <input type="radio" name="assigneeRole" value="designer" checked onchange="switchAssigneeType('add', 'designer')" style="display:none;"> 👨‍🎨 设计师
              </label>
              <label class="checkbox-item" style="cursor:pointer;" onclick="switchAssigneeType('add', 'supplychain', this)">
                <input type="radio" name="assigneeRole" value="supplychain" onchange="switchAssigneeType('add', 'supplychain')" style="display:none;"> 🛒 供应链
              </label>
              <label class="checkbox-item" style="cursor:pointer;" onclick="switchAssigneeType('add', 'planner', this)">
                <input type="radio" name="assigneeRole" value="planner" onchange="switchAssigneeType('add', 'planner')" style="display:none;"> 📋 企划
              </label>
            </div>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 指派子任务负责人</label>
            <select class="form-select" name="designerId" id="addSubTaskDesignerId" required onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''">${designerOpts}</select>
            <input type="hidden" name="assigneeRole" id="addSubTaskAssigneeRole" value="designer">
          </div>
          <div class="form-group"><label class="form-label">细节要求说明</label><textarea class="form-textarea" name="details" placeholder="子任务的具体要求说明..." oninput="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''"></textarea></div>
        </form>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片（可选）</div>
          <div class="upload-area" onclick="document.getElementById('subTaskRefImageInput').click()">
            <div>📁 点击上传参考图片</div>
            <input type="file" id="subTaskRefImageInput" multiple accept="image/*" style="display:none" onchange="handleSubTaskRefImages(this)">
          </div>
          <div class="file-list" id="createRefImageList"></div>
        </div>
        <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
          <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
          <div class="upload-area" onclick="document.getElementById('subTaskAttachmentInput').click()">
            <div>📁 点击上传附件</div>
            <input type="file" id="subTaskAttachmentInput" multiple style="display:none" onchange="handleSubTaskAttachments(this)">
          </div>
          <div class="file-list" id="createAttachmentList"></div>
        </div>
        <div style="margin-top:8px;padding:10px;background:var(--warning-light);border-radius:8px;font-size:12px;color:#92400E;">
          💡 提示：可多次添加子任务。所有子任务完成后，项目才算完成。
        </div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('addSubTaskModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitAddSubTask('${pid}'))">确认添加</button></div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitAddSubTask(pid) {
  if (!pid) { alert('项目ID无效'); return; }
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  // 清除之前错误
  document.querySelectorAll('#addSubTaskForm .field-error').forEach(el => el.remove());
  document.querySelectorAll('#addSubTaskForm .form-input, #addSubTaskForm .form-select, #addSubTaskForm .form-textarea').forEach(el => el.style.borderColor = '');

  function showError(name, msg) {
    const input = document.querySelector(`#addSubTaskForm [name="${name}"]`);
    if (!input) return;
    const group = input.closest('.form-group');
    if (!group) return;
    const err = document.createElement('div');
    err.className = 'field-error';
    err.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px;';
    err.textContent = '❌ ' + msg;
    group.appendChild(err);
    input.style.borderColor = 'var(--danger)';
  }

  const fd = new FormData(document.getElementById('addSubTaskForm'));
  const data = Object.fromEntries(fd.entries());
  let hasErr = false;

  if (!data.name) { showError('name', '请填写子任务名称'); hasErr = true; }
  if (!data.plannedDate) { showError('plannedDate', '请选择计划完成时间'); hasErr = true; }
  else if (!/^\d{4}-\d{2}-\d{2}$/.test(data.plannedDate) || isNaN(new Date(data.plannedDate).getTime())) {
    showError('plannedDate', '日期格式不正确（yyyy-mm-dd）');
    hasErr = true;
  } else {
    const parts = data.plannedDate.split('-');
    const m = parseInt(parts[1]), day = parseInt(parts[2]);
    if (m < 1 || m > 12 || day < 1 || day > 31) {
      showError('plannedDate', '日期超出有效范围');
      hasErr = true;
    } else {
      // 禁止选择早于今天的日期
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const selected = new Date(data.plannedDate);
      if (selected < today) {
        showError('plannedDate', '计划完成时间不能早于今天');
        hasErr = true;
      }
    }
  }

  // 子任务负责人：未指定可以提交，但"请选择子任务负责人"不允许
  const designerSel = document.querySelector('#addSubTaskForm [name="designerId"]');
  if (designerSel && designerSel.selectedIndex === 0) {
    showError('designerId', '请选择子任务负责人或未指定');
    hasErr = true;
  }
  if (hasErr) return;

  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  // 始终提交图片和附件列表
  data.referenceImagesJson = JSON.stringify(_subTaskRefImages.map(img => ({name: img.name, url: img.url, size: img.size, storedName: img.storedName})));
  data.attachmentsJson = JSON.stringify(_subTaskAttachments.map(a => ({name: a.name, url: a.url, size: a.size, storedName: a.storedName})));

  try {
    await apiPost(`/projects/${pid}/tasks`, data);
    closeM('addSubTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('添加失败: ' + (e.message || '未知错误'));
  }
}

function editTask(pid, tid) {
  if (!tryOpenModal('editTaskModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) { doneOpenModal('editTaskModal'); return; }
    // 加载现有图片和附件
    _editTaskRefImages = [];
    _editTaskAttachments = [];
    if (task.referenceImagesJson) {
      try { _editTaskRefImages = JSON.parse(task.referenceImagesJson); } catch(e) {}
    }
    if (task.attachmentsJson) {
      try { _editTaskAttachments = JSON.parse(task.attachmentsJson); } catch(e) {}
    }

    const designerOpts = task.assigneeRole === 'planner'
      ? (USERS.planner && USERS.planner.length
          ? '<option value="">请选择企划</option>' +
            USERS.planner.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${u.name} (${u.title})</option>`).join('')
          : '<option value="">暂无企划人员</option>')
      : task.assigneeRole === 'supplychain'
        ? (USERS.supplychain && USERS.supplychain.length
            ? '<option value="">请选择供应链</option>' +
              USERS.supplychain.map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${u.name} (${u.title})</option>`).join('')
            : '<option value="">暂无供应链人员</option>')
        : '<option value="">请选择设计师</option>' +
          (USERS.designer || []).map(u => `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${u.name} (${u.title})</option>`).join('');

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'editTaskModal';
    modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('editTaskModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑子任务</div></div></div>
        <div class="modal-body">
          <form id="editTaskForm">
            <div class="form-group"><label class="form-label">子任务名称</label><input type="text" class="form-input" name="name" value="${escHtml(task.name)}"></div>
            <div class="form-row">
              <div class="form-group"><label class="form-label">计划完成时间</label>${renderDatePicker('plannedDate', {value: task.plannedDate || ''})}</div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 负责人类型</label>
              <div style="display:flex;gap:16px;">
                <label class="checkbox-item ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''}" style="cursor:pointer;" onclick="switchEditAssigneeType('designer', this)">
                  <input type="radio" name="assigneeRole" value="designer" ${task.assigneeRole === 'designer' || !task.assigneeRole ? 'checked' : ''} style="display:none;"> 👨‍🎨 设计师
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'supplychain' ? 'checked' : ''}" style="cursor:pointer;" onclick="switchEditAssigneeType('supplychain', this)">
                  <input type="radio" name="assigneeRole" value="supplychain" ${task.assigneeRole === 'supplychain' ? 'checked' : ''} style="display:none;"> 🛒 供应链
                </label>
                <label class="checkbox-item ${task.assigneeRole === 'planner' ? 'checked' : ''}" style="cursor:pointer;" onclick="switchEditAssigneeType('planner', this)">
                  <input type="radio" name="assigneeRole" value="planner" ${task.assigneeRole === 'planner' ? 'checked' : ''} style="display:none;"> 📋 企划
                </label>
              </div>
            </div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 指派子任务负责人</label>
              <select class="form-select" name="designerId" id="editSubTaskDesignerId">${designerOpts}</select>
              <input type="hidden" name="assigneeRole" id="editSubTaskAssigneeRole" value="${task.assigneeRole || 'designer'}">
            </div>
            <div class="form-group"><label class="form-label">细节要求说明</label><textarea class="form-textarea" name="details">${escHtml(task.details)}</textarea></div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 参考图片（可选）</div>
            <div class="upload-area" onclick="document.getElementById('editRefImageInput').click()">
              <div>📁 点击上传参考图片</div>
              <input type="file" id="editRefImageInput" multiple accept="image/*" style="display:none" onchange="handleEditRefImages(this)">
            </div>
            <div class="file-list" id="createRefImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 附件（可选）</div>
            <div class="upload-area" onclick="document.getElementById('editAttachmentInput').click()">
              <div>📁 点击上传附件</div>
              <input type="file" id="editAttachmentInput" multiple style="display:none" onchange="handleEditAttachments(this)">
            </div>
            <div class="file-list" id="createAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('editTaskModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitEditTask('${pid}','${tid}'))">保存修改</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('editTaskModal');
    // 渲染现有文件
    if (_editTaskRefImages.length) renderFileList(_editTaskRefImages, '编辑参考图片');
    if (_editTaskAttachments.length) renderFileList(_editTaskAttachments, '编辑附件');
  }).catch(() => doneOpenModal('editTaskModal'));
}

let _editTaskRefImages = [];
let _editTaskAttachments = [];
function handleEditRefImages(input) { handleFileUpload(input, _editTaskRefImages, 9, '编辑参考图片', true); }
function handleEditAttachments(input) { handleFileUpload(input, _editTaskAttachments, 9, '编辑附件', false); }

async function submitEditTask(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('editTaskForm'));
  const data = Object.fromEntries(fd.entries());

  // 验证计划时间不能早于今天
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  if (data.plannedDate && /^\d{4}-\d{2}-\d{2}$/.test(data.plannedDate)) {
    const selected = new Date(data.plannedDate);
    if (selected < today) {
      alert('计划完成时间不能早于今天');
      return;
    }
  }

  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  // 始终提交当前图片和附件列表（包含已有的和新上传的）
  data.referenceImagesJson = JSON.stringify(_editTaskRefImages.map(img => ({name: img.name, url: img.url, size: img.size, storedName: img.storedName})));
  data.attachmentsJson = JSON.stringify(_editTaskAttachments.map(a => ({name: a.name, url: a.url, size: a.size, storedName: a.storedName})));

  try {
    await apiPut(`/projects/${pid}/tasks/${tid}`, data);
    closeM('editTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('编辑失败: ' + e.message);
  }
}

async function deleteTask(pid, tid) {
  if (!confirm('确定要删除这个子任务吗？此操作不可恢复。')) return;
  try {
    await apiDelete(`/projects/${pid}/tasks/${tid}`);
    closeM('editTaskModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('删除失败: ' + e.message);
  }
}

// ==================== 任务工作流 ====================
async function taskAccept(pid, tid) {
  if (document.getElementById('taskAcceptModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskAcceptModal';
    modal.innerHTML = `
      <button class="modal-close-float" onclick="closeM('taskAcceptModal')">✕</button>
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✅ 接单：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:12px;color:var(--gray-500);">负责人：<strong>${task.designerName || '未指定'}</strong></p>
          <form id="taskAcceptForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划完成时间</label>${renderDatePicker('plannedDate', {required:true, value: task.plannedDate || ''})}</div>
          </form>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskAcceptModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitTaskAccept(${pid},${tid}))">确认接单</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskDeliverModal');
  } catch (e) {
    doneOpenModal('taskDeliverModal');
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskAccept(pid, tid) {
  const fd = new FormData(document.getElementById('taskAcceptForm'));
  const plannedDate = fd.get('plannedDate');
  if (!plannedDate) { alert('请选择计划完成时间'); return; }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(plannedDate) || isNaN(new Date(plannedDate).getTime())) {
    alert('日期格式不正确（yyyy-mm-dd）');
    return;
  }
  const today = new Date(); today.setHours(0, 0, 0, 0);
  if (new Date(plannedDate) < today) {
    alert('计划完成时间不能早于今天');
    return;
  }

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/accept`, {
      plannedDate,
      currentUser: getCurrentUserName(),
      currentRole: currentRole,
      designerUserId: getCurrentUserId(),
    });
    closeM('taskAcceptModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

async function taskDeliver(pid, tid) {
  if (!tryOpenModal('taskDeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    _deliverImages = [];
    _deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskDeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('taskDeliverModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 交付：${task.name}</div></div></div>
        <div class="modal-body">
          <form id="taskDeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required placeholder="描述交付的设计成果..." style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" oninput="validateScoreInput(this)">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 交付参考图</div>
            <div class="upload-area" onclick="document.getElementById('deliverImageInput').click()">
              <div>📁 点击上传图片</div>
              <input type="file" id="deliverImageInput" multiple accept="image/*" style="display:none" onchange="handleDeliverImages(this)">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" onclick="document.getElementById('deliverAttachmentInput').click()">
              <div>📁 点击上传附件</div>
              <input type="file" id="deliverAttachmentInput" multiple style="display:none" onchange="handleDeliverAttachments(this)">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskDeliverModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitTaskDeliver(${pid},${tid}))">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

// ===== 自评分数输入校验：1-100，整数 =====
window.validateScoreInput = function(input) {
  let val = input.value.trim();
  if (val === '') { input.setCustomValidity(''); return; }
  const num = parseInt(val);
  if (isNaN(num) || num < 1 || num > 100) {
    input.setCustomValidity('请输入 1 ~ 100 之间的整数分数');
  } else {
    // 检查是否为整数（不允许小数）
    if (val.includes('.') || val.includes(',')) {
      input.setCustomValidity('不允许小数点，请输入整数');
    } else {
      input.setCustomValidity('');
    }
  }
  input.reportValidity();
};

async function submitTaskDeliver(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskDeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { alert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  data.currentUserId = currentUserId;
  data.referenceImagesJson = JSON.stringify(_deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })));
  data.attachmentsJson = JSON.stringify(_deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/deliver`, data);
    closeM('taskDeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

async function taskRedeliver(pid, tid) {
  if (!tryOpenModal('taskRedeliverModal')) return;
  try {
    const detail = await apiGet(`/projects/${pid}`);
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    _deliverImages = [];
    _deliverAttachments = [];

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskRedeliverModal';
    modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('taskRedeliverModal')">✕</button>
      <div class="modal modal-lg">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📤 重新交付：${task.name}</div></div></div>
        <div class="modal-body">
          ${task.reviewComments ? `<div class="review-box rejected" style="margin-bottom:16px;"><strong>驳回意见：</strong>${task.reviewComments}</div>` : ''}
          <form id="taskRedeliverForm">
            <input type="hidden" name="actualDate">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 交付成果描述</label><textarea class="form-textarea" name="deliverables" required style="min-height:100px;"></textarea></div>
            <div class="form-group"><label class="form-label"><span class="required">*</span> 自评分数</label>
              <div style="max-width:200px;">
                <input type="number" class="form-input" name="selfScore" required placeholder="1-100" min="1" max="100" step="1" style="text-align:center;font-size:18px;" oninput="validateScoreInput(this)">
                <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">总分100分，填写1-100的整数</div>
              </div>
            </div>
          </form>
          <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">🖼️ 交付参考图</div>
            <div class="upload-area" onclick="document.getElementById('deliverImageInput').click()">
              <div>📁 点击上传图片</div>
              <input type="file" id="deliverImageInput" multiple accept="image/*" style="display:none" onchange="handleDeliverImages(this)">
            </div>
            <div class="file-list" id="deliverImageList"></div>
          </div>
          <div style="margin-top:16px;padding-top:16px;border-top:1px solid var(--gray-200);">
            <div class="form-label" style="margin-bottom:8px;">📎 交付附件</div>
            <div class="upload-area" onclick="document.getElementById('deliverAttachmentInput').click()">
              <div>📁 点击上传附件</div>
              <input type="file" id="deliverAttachmentInput" multiple style="display:none" onchange="handleDeliverAttachments(this)">
            </div>
            <div class="file-list" id="deliverAttachmentList"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskRedeliverModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitTaskRedeliver(${pid},${tid}))">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
    doneOpenModal('taskRedeliverModal');
  } catch (e) {
    doneOpenModal('taskRedeliverModal');
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskRedeliver(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskRedeliverForm'));
  const data = Object.fromEntries(fd.entries());
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  const selfScore = parseInt(data.selfScore);
  if (isNaN(selfScore) || selfScore < 1 || selfScore > 100) { alert('请输入有效的自评分（1-100分）'); return; }
  data.selfScore = selfScore;
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  data.currentUserId = currentUserId;
  data.referenceImagesJson = JSON.stringify(_deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })));
  data.attachmentsJson = JSON.stringify(_deliverAttachments);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/redeliver`, data);
    closeM('taskRedeliverModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

// ==================== 验收 ====================
function taskApprove(pid, tid, projectType) {
  if (!tryOpenModal('taskApproveModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;

    const isChannel = projectType === 'channel_custom';
    const isSalesConfirm = currentRole === 'sales';
    const isAdminConfirm = currentRole === 'admin' && !isChannel;
    const needsScore = currentRole === 'planner' || isSalesConfirm || isAdminConfirm;
    const title = isSalesConfirm
      ? '✅ 销售确认评分通过'
      : (isAdminConfirm ? '✅ 管理确认评分通过' : (isChannel ? '👍 企划确认评分通过' : '✅ 验收评分通过'));

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskApproveModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">${title}：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:12px;">${isSalesConfirm ? '销售确认该子任务通过并评分？' : (isAdminConfirm ? '管理确认该子任务通过并评分？' : (isChannel ? '企划确认该子任务通过并评分？之后需销售再次确认评分。' : '企划确认该子任务验收通过并评分？之后需管理再次确认。'))}</p>
          ${needsScore ? `
          <div class="form-group">
              <label class="form-label"><span class="required">*</span> 综合评分</label>
              <input type="number" class="form-input" id="approveScore" min="1" max="100" step="1" placeholder="1-100" required style="max-width:200px;text-align:center;">
              <div style="font-size:11px;color:var(--gray-400);margin-top:4px;">总分100分，填写1-100的整数</div>
            </div>` : ''}
          <div class="form-group"><label class="form-label">验收意见（可选）</label><textarea class="form-textarea" id="approveComments" placeholder="输入验收意见..."></textarea></div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskApproveModal')">取消</button><button class="btn btn-success" onclick="submitGuard(this,()=>submitTaskApprove(${pid},${tid},'${projectType}'))">${needsScore ? '确认通过并评分' : '确认通过'}</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitTaskApprove(pid, tid) {
  const scoreVal = parseInt(document.getElementById('approveScore')?.value);
  const hasScoreFields = document.getElementById('approveScore') !== null;
  if (hasScoreFields) {
    if (isNaN(scoreVal) || scoreVal < 1 || scoreVal > 100) { alert('请输入有效的评分（1-100）'); return; }
  }
  const comments = document.getElementById('approveComments')?.value || '';

  const data = {
    comments: comments,
    score: hasScoreFields ? scoreVal : null,
    currentUser: getCurrentUserName(),
    currentRole: currentRole,
  };

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/approve`, data);
    closeM('taskApproveModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + (e.message || '未知错误'));
  }
}


function taskReject(pid, tid) {
  if (document.getElementById('taskRejectModal')) return;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'taskRejectModal';
  modal.innerHTML = `
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">↩️ 驳回修改</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 修改意见</label><textarea class="form-textarea" id="rejectComments" required placeholder="请详细说明修改意见..." style="min-height:100px;"></textarea></div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskRejectModal')">取消</button><button class="btn btn-danger" onclick="submitGuard(this,()=>submitTaskReject(${pid},${tid}))">确认驳回</button></div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitTaskReject(pid, tid) {
  const comments = document.getElementById('rejectComments')?.value || '';
  if (!comments) { alert('请填写修改意见'); return; }
  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/reject`, {
      comments,
      currentUser: getCurrentUserName(),
      currentRole: currentRole,
    });
    closeM('taskRejectModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// ==================== 评分 ====================
function openScoring(pid, tid) {
  if (!tryOpenModal('scoringModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task || !task.scoringRecords) return;
    const myRecord = task.scoringRecords.find(sr => sr.role === currentRole);
    if (!myRecord) { alert('您无需对此任务评分'); return; }

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'scoringModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><button class="modal-close" onclick="closeM('scoringModal')">✕</button><div class="modal-header-left"><div class="modal-title">⭐ 评分：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:8px;color:var(--gray-500);">评分人：<strong>${roleLabel(currentRole)}</strong>（${getCurrentUserName()}）</p>
          <p style="margin-bottom:16px;color:var(--gray-500);">请对 <strong>${task.name}</strong> 进行评分（1-100分）</p>
          <div>
            <div class="form-group"><label class="form-label">⭐ 综合评分</label><input type="number" class="form-input" id="scoreValue" min="1" max="100" step="1" placeholder="1-100" value="${myRecord.score ?? ''}" style="font-size:24px;text-align:center;max-width:200px;margin:0 auto;"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('scoringModal')">取消</button><button class="btn btn-primary" onclick="submitGuard(this,()=>submitScoring(${pid},${tid}))">提交评分</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitScoring(pid, tid) {
  const score = parseInt(document.getElementById('scoreValue').value);
  if (isNaN(score) || score < 1 || score > 100) { alert('请输入有效的评分（1-100分）'); return; }

  const data = {
    role: currentRole,
    score,
    currentUser: getCurrentUserName(),
    currentRole,
  };

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/score`, data);
    closeM('scoringModal');
    await refreshAfterMutation(pid);
  } catch (e) {
    alert('评分提交失败: ' + e.message);
  }
}

// ==================== 分享链接 ====================

async function shareProject(projectId) {
  document.getElementById('shareModal')?.remove();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'shareModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:520px;">
      <div class="modal-header">
        <div class="modal-title">🔗 分享此项目</div>
      </div>
      <div class="modal-body">
        <div id="shareForm">
          <div class="form-group">
            <label class="form-label">过期时间</label>
            <select class="form-select" id="shareExpires">
              <option value="3600">1 小时后</option>
              <option value="86400">24 小时后</option>
              <option value="604800" selected>7 天后</option>
              <option value="2592000">30 天后</option>
              <option value="">永不过期</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">访问密码（可选）</label>
            <input type="text" class="form-input" id="sharePassword" placeholder="留空则无需密码" style="text-align:center;">
          </div>
          <div id="shareCreateArea">
            <button class="btn btn-primary btn-lg" onclick="doCreateShareLink('project', ${projectId})" style="width:100%;justify-content:center;">生成分享链接</button>
          </div>
          <div id="shareResultArea" style="display:none;">
            <div class="form-group">
              <label class="form-label">分享链接</label>
              <div style="display:flex;gap:8px;">
                <input type="text" class="form-input" id="shareUrl" readonly style="text-align:center;flex:1;background:#f9fafb;">
                <button class="btn btn-primary" onclick="copyShareUrl()">复制</button>
              </div>
            </div>
            <div id="shareStatus" style="font-size:13px;color:var(--gray-500);text-align:center;margin-top:8px;"></div>
          </div>
        </div>
        <div id="shareLoading" style="display:none;text-align:center;padding:40px;color:var(--gray-400);">生成中...</div>
        <div id="shareError" style="color:var(--danger);font-size:13px;text-align:center;margin-top:12px;display:none;"></div>
      </div>
      <div class="modal-footer" style="justify-content:center;">
        <button class="btn btn-outline" onclick="closeM('shareModal')">关闭</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function doCreateShareLink(targetType, targetId) {
  const expiresEl = document.getElementById('shareExpires');
  const passwordEl = document.getElementById('sharePassword');
  const errEl = document.getElementById('shareError');
  const loadingEl = document.getElementById('shareLoading');
  const formEl = document.getElementById('shareForm');
  const createArea = document.getElementById('shareCreateArea');
  const resultArea = document.getElementById('shareResultArea');
  const urlInput = document.getElementById('shareUrl');
  const statusEl = document.getElementById('shareStatus');

  errEl.style.display = 'none';
  loadingEl.style.display = '';
  createArea.style.display = 'none';
  resultArea.style.display = 'none';

  try {
    const expiresIn = expiresEl.value ? parseInt(expiresEl.value) : null;
    const password = passwordEl.value.trim() || null;
    const result = await apiPost('/share', { targetType, targetId, expiresIn, password });
    const fullUrl = window.location.origin + result.url;
    urlInput.value = fullUrl;
    resultArea.style.display = '';
    try {
      await navigator.clipboard.writeText(fullUrl);
      statusEl.innerHTML = '✅ 已复制到剪贴板';
    } catch(e) {
      statusEl.innerHTML = '';
    }
  } catch(e) {
    errEl.textContent = e.message || '生成失败';
    errEl.style.display = '';
    createArea.style.display = '';
  } finally {
    loadingEl.style.display = 'none';
  }
}

function copyShareUrl() {
  const input = document.getElementById('shareUrl');
  input.select();
  document.execCommand('copy');
  const status = document.getElementById('shareStatus');
  status.innerHTML = '✅ 已复制到剪贴板';
}

// ==================== 后台管理 ====================
let currentAdminTab = 'dashboard';

async function renderAdmin(main, role, uid) {
  main.innerHTML = `
    <div class="admin-page">
      <div class="admin-header">
        <h2>⚙️ 系统管理</h2>
        <p>管理系统配置、外观、用户权限等</p>
      </div>
      <div class="admin-tabs" id="adminTabs">
        <button class="admin-tab ${currentAdminTab === 'dashboard' ? 'active' : ''}" onclick="switchAdminTab('dashboard')">📊 概览</button>
        <button class="admin-tab ${currentAdminTab === 'config' ? 'active' : ''}" onclick="switchAdminTab('config')">🔧 系统配置</button>
        <button class="admin-tab ${currentAdminTab === 'appearance' ? 'active' : ''}" onclick="switchAdminTab('appearance')">🎨 外观</button>
        <button class="admin-tab ${currentAdminTab === 'users' ? 'active' : ''}" onclick="switchAdminTab('users')">👥 用户管理</button>
        <button class="admin-tab ${currentAdminTab === 'roles' ? 'active' : ''}" onclick="switchAdminTab('roles')">🔐 角色管理</button>
        <button class="admin-tab ${currentAdminTab === 'categories' ? 'active' : ''}" onclick="switchAdminTab('categories')">📂 产品类目</button>
        <button class="admin-tab ${currentAdminTab === 'compliance' ? 'active' : ''}" onclick="switchAdminTab('compliance')">⚖️ 合规处罚</button>
        <button class="admin-tab ${currentAdminTab === 'priceRanges' ? 'active' : ''}" onclick="switchAdminTab('priceRanges')">💰 参考零售价</button>
        <button class="admin-tab ${currentAdminTab === 'org' ? 'active' : ''}" onclick="switchAdminTab('org')">🏢 组织架构</button>
        <button class="admin-tab ${currentAdminTab === 'scoring' ? 'active' : ''}" onclick="switchAdminTab('scoring')">⭐ 评分管理</button>
        <button class="admin-tab ${currentAdminTab === 'logs' ? 'active' : ''}" onclick="switchAdminTab('logs')">📜 日志</button>
        <button class="admin-tab ${currentAdminTab === 'shares' ? 'active' : ''}" onclick="switchAdminTab('shares')">🔗 分享管理</button>
        <button class="admin-tab ${currentAdminTab === 'filestorage' ? 'active' : ''}" onclick="switchAdminTab('filestorage')">📦 文件存储</button>
        <button class="admin-tab ${currentAdminTab === 'workload' ? 'active' : ''}" onclick="switchAdminTab('workload')">📊 工作量</button>
      </div>
      <div id="adminContent"></div>
    </div>`;
  await renderAdminContent();
}

async function switchAdminTab(tab) {
  currentAdminTab = tab;
  localStorage.setItem('design_pm_lastAdminTab', tab);
  const tabs = document.querySelectorAll('.admin-tab');
  tabs.forEach(t => t.classList.remove('active'));
  const activeTab = document.querySelector(`.admin-tab[onclick*="${tab}"]`);
  if (activeTab) activeTab.classList.add('active');
  await renderAdminContent();
}

async function renderAdminContent() {
  const container = document.getElementById('adminContent');
  if (!container) return;
  try {
    if (currentAdminTab === 'dashboard') {
      await renderAdminDashboard(container);
    } else if (currentAdminTab === 'config') {
      await renderAdminConfig(container);
    } else if (currentAdminTab === 'appearance') {
      await renderAdminAppearance(container);
    } else if (currentAdminTab === 'users') {
      await renderAdminUsers(container);
    } else if (currentAdminTab === 'roles') {
      await renderAdminRoles(container);
    } else if (currentAdminTab === 'categories') {
      await renderAdminCategories(container);
    } else if (currentAdminTab === 'compliance') {
      await renderAdminCompliance(container);
    } else if (currentAdminTab === 'priceRanges') {
      await renderAdminPriceRanges(container);
    } else if (currentAdminTab === 'org') {
      await renderAdminOrg(container);
    } else if (currentAdminTab === 'scoring') {
      await renderAdminScoring(container);
    } else if (currentAdminTab === 'logs') {
      await renderAdminLogs(container);
    } else if (currentAdminTab === 'shares') {
      await renderAdminShares(container);
    } else if (currentAdminTab === 'filestorage') {
      await renderAdminFileStorage(container);
    } else if (currentAdminTab === 'workload') {
      await renderAdminWorkload(container);
    }
  } catch (e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

// ===== Admin: 概览 =====
async function renderAdminDashboard(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  // 获取所有配置统计
  const [configs, users] = await Promise.all([
    apiGet('/admin/configs'),
    apiGet('/admin/users'),
  ]);

  const totalConfigs = Object.values(configs).reduce((sum, arr) => sum + arr.length, 0);
  const groupCount = Object.keys(configs).length;
  const totalUsers = users.length;

  const adminUserId = AUTH_USER.userId;
  const stats = [
    { icon: '🔧', label: '配置项总数', value: totalConfigs },
    { icon: '📂', label: '配置分组', value: groupCount },
    { icon: '👥', label: '用户总数', value: totalUsers },
    { icon: '👤', label: '当前管理员', value: AUTH_USER.name || '未知' },
  ];

  container.innerHTML = `
    <div class="admin-stats-grid">
      ${stats.map(s => `
        <div class="admin-stat-card">
          <div class="admin-stat-icon">${s.icon}</div>
          <div class="admin-stat-value">${s.value}</div>
          <div class="admin-stat-label">${s.label}</div>
        </div>
      `).join('')}
    </div>
    <div style="background:#fff;border-radius:12px;box-shadow:var(--shadow);padding:20px;">
      <h3 style="font-size:15px;font-weight:600;margin-bottom:16px;">📋 配置分组概览</h3>
      <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;">
        ${Object.entries(configs).map(([group, items]) => `
          <div style="padding:14px;background:var(--gray-50);border-radius:8px;border:1px solid var(--gray-200);">
            <div style="font-size:13px;font-weight:600;color:var(--gray-700);">${groupLabel(group)}</div>
            <div style="font-size:24px;font-weight:700;color:var(--primary);margin-top:4px;">${items.length}</div>
            <div style="font-size:11px;color:var(--gray-400);">个配置项</div>
          </div>
        `).join('')}
      </div>
    </div>`;
}

function groupLabel(group) {
  return { appearance: '🎨 外观设置', security: '🔒 安全设置', system: '💻 系统信息', feishu: '💬 飞书 SSO 登录', nas: '🗄️ NAS 归档', feishu_base: '📊 飞书多维表格' }[group] || group;
}

// ===== Admin: 系统配置 =====
async function renderAdminConfig(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  const configs = await apiGet('/admin/configs');

  let html = '';
  for (const [group, items] of Object.entries(configs)) {
    if (group === 'appearance') continue; // 外观有独立页面
    html += `
      <div class="config-card" data-group="${group}">
        <div class="config-card-header">
          <h3>${groupLabel(group)}</h3>
          <button class="btn btn-sm btn-primary" onclick="saveConfigGroup('${group}')">💾 保存</button>
        </div>
        <div class="config-card-body">
          <div class="config-grid">
            ${items.map(item => `
              <div class="config-item ${item.valueType === 'text' && (item.configValue || '').length > 60 ? 'full' : ''}">
                <label>${item.configKey.split('.').pop()}</label>
                <span class="config-desc">${escHtml(item.description || '')}</span>
                ${item.valueType === 'password'
                  ? `<input type="password" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}" autocomplete="off">`
                  : item.valueType === 'number'
                    ? `<input type="number" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}">`
                    : item.valueType === 'boolean'
                      ? `<select class="config-input" data-key="${item.configKey}">
                           <option value="true" ${item.configValue === 'true' ? 'selected' : ''}>开</option>
                           <option value="false" ${item.configValue === 'false' ? 'selected' : ''}>关</option>
                         </select>`
                      : `<input type="text" class="config-input" data-key="${item.configKey}" value="${escHtml(item.configValue || '')}" placeholder="${item.description}">`
                }
              </div>
            `).join('')}
          </div>
        </div>
      </div>`;
  }

  container.innerHTML = html;
}

async function saveConfigGroup(group) {
  const card = document.querySelector(`.config-card[data-group="${group}"]`);
  if (!card) return;
  const inputs = card.querySelectorAll('.config-input');
  const configs = {};
  inputs.forEach(inp => {
    configs[inp.dataset.key] = inp.value;
  });

  try {
    await apiPut('/admin/configs', { configs });
    showAdminToast('✅ 配置已保存', 'success');
  } catch (e) {
    showAdminToast('❌ 保存失败: ' + e.message, 'error');
  }
}

// ===== Admin: 外观设置 =====
async function renderAdminAppearance(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  const configs = await apiGet('/admin/configs');
  const appearanceItems = configs['appearance'] || [];

  const getVal = (key) => {
    const item = appearanceItems.find(i => i.configKey === key);
    return item ? (item.configValue || '') : '';
  };

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>🎨 外观设置</h3>
        <button class="btn btn-sm btn-primary" onclick="saveAppearanceConfig()">💾 保存</button>
      </div>
      <div class="config-card-body">
        <div class="config-grid">
          <div class="config-item full">
            <label>系统标题</label>
            <span class="config-desc">显示在浏览器标签和页面头部</span>
            <input type="text" class="config-input" data-key="app.title" value="${escHtml(getVal('app.title'))}" placeholder="系统标题">
          </div>
          <div class="config-item full">
            <label>系统副标题</label>
            <span class="config-desc">显示在页面头部和登录页</span>
            <input type="text" class="config-input" data-key="app.subtitle" value="${escHtml(getVal('app.subtitle'))}" placeholder="系统副标题">
          </div>
          <div class="config-item full">
            <label>Logo Emoji 备用</label>
            <span class="config-desc">未上传图片 Logo 时显示此 Emoji</span>
            <input type="text" class="config-input" data-key="app.logoEmoji" value="${escHtml(getVal('app.logoEmoji'))}" placeholder="🎨" style="font-size:24px;text-align:center;max-width:80px;">
          </div>
          <div class="config-item full">
            <label>Logo 图片</label>
            <span class="config-desc">上传 Logo 图片（PNG/JPG/SVG，推荐 120x120px）</span>
            <div class="admin-image-upload">
              ${getVal('app.logo') ? `<img src="${getVal('app.logo')}" class="admin-image-preview logo-preview" id="logoPreviewImg">` : `<div class="admin-image-preview logo-preview" id="logoPreviewPlaceholder" style="display:flex;align-items:center;justify-content:center;font-size:24px;background:var(--gray-100);">${getVal('app.logoEmoji') || '🎨'}</div>`}
              <div class="admin-image-upload-btn" onclick="document.getElementById('logoUploadInput').click()">
                📁 ${getVal('app.logo') ? '更换 Logo' : '上传 Logo'}
                <input type="file" id="logoUploadInput" accept="image/png,image/jpeg,image/gif,image/svg+xml,image/webp" style="display:none" onchange="uploadAdminImage(this, 'logo')">
              </div>
              ${getVal('app.logo') ? `<button class="btn btn-sm btn-outline" onclick="removeAdminImage('app.logo', 'logoPreviewImg')">🗑️ 移除</button>` : ''}
            </div>
          </div>
          <div class="config-item full">
            <label>登录页背景图片</label>
            <span class="config-desc">推荐 1920x1080px，上传后自动替换登录页背景</span>
            <div class="admin-image-upload">
              ${getVal('login.bg') ? `<img src="${getVal('login.bg')}" class="admin-image-preview" id="bgPreviewImg" style="width:120px;height:68px;object-fit:cover;">` : `<div class="admin-image-preview" id="bgPreviewPlaceholder" style="display:flex;align-items:center;justify-content:center;font-size:20px;background:var(--gray-100);width:120px;height:68px;">🌄</div>`}
              <div class="admin-image-upload-btn" onclick="document.getElementById('bgUploadInput').click()">
                📁 ${getVal('login.bg') ? '更换背景' : '上传背景'}
                <input type="file" id="bgUploadInput" accept="image/png,image/jpeg,image/gif,image/webp" style="display:none" onchange="uploadAdminImage(this, 'login-bg')">
              </div>
              ${getVal('login.bg') ? `<button class="btn btn-sm btn-outline" onclick="removeAdminImage('login.bg', 'bgPreviewImg')">🗑️ 移除</button>` : ''}
            </div>
          </div>
          <div class="config-item">
            <label>登录页背景色</label>
            <span class="config-desc">无背景图片时使用的颜色</span>
            <input type="text" class="config-input" data-key="login.bgColor" value="${escHtml(getVal('login.bgColor'))}" placeholder="#F3F4F6">
          </div>
        </div>
      </div>
    </div>
    <div class="config-card">
      <div class="config-card-header">
        <h3>🔒 安全设置</h3>
      </div>
      <div class="config-card-body">
        <p style="font-size:13px;color:var(--gray-500);">安全设置在「系统配置」页面中管理</p>
      </div>
    </div>`;
}

// 上传管理图片
async function uploadAdminImage(input, type) {
  if (!input.files || !input.files[0]) return;
  const file = input.files[0];
  const validTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/svg+xml', 'image/webp'];
  if (!validTypes.includes(file.type)) {
    showAdminToast('❌ 仅支持 PNG/JPG/GIF/SVG/WebP 格式', 'error');
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    showAdminToast('❌ 图片大小不能超过 5MB', 'error');
    return;
  }

  const fd = new FormData();
  fd.append('file', file);
  fd.append('type', type);
  const token = localStorage.getItem('design_pm_token');

  try {
    const r = await fetch('/api/admin/upload-image', {
      method: 'POST',
      headers: token ? { 'X-Auth-Token': token } : {},
      body: fd,
    });
    const result = await r.json();
    if (!r.ok) throw new Error(result.error || '上传失败');

    showAdminToast('✅ 上传成功，刷新页面即可看到效果', 'success');
    // 刷新外观页面
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ ' + e.message, 'error');
  }
}

// 移除管理图片
async function removeAdminImage(configKey, imgId) {
  if (!confirm('确定移除该图片？')) return;
  try {
    await apiPut('/admin/configs', { configs: { [configKey]: '' } });
    showAdminToast('✅ 已移除', 'success');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ ' + e.message, 'error');
  }
}

// 保存外观配置
async function saveAppearanceConfig() {
  const inputs = document.querySelectorAll('.config-input[data-key^="app."], .config-input[data-key^="login."]');
  const configs = {};
  inputs.forEach(inp => {
    configs[inp.dataset.key] = inp.value;
  });

  try {
    await apiPut('/admin/configs', { configs });
    showAdminToast('✅ 外观设置已保存，刷新页面生效', 'success');
  } catch (e) {
    showAdminToast('❌ 保存失败: ' + e.message, 'error');
  }
}

// ===== Admin: 用户管理 =====
async function renderAdminUsers(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  let users = [];
  try { users = await apiGet('/admin/users'); } catch(e) { /* ignore */ }

  const roleLabels = { sales: '销售', planner: '产品企划', designer: '设计师', supplychain: '供应链', admin: '管理员' };

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>👥 用户管理 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">共 ${users.length} 人</span></h3>
        <div style="display:flex;gap:8px;">
          <button class="btn btn-sm btn-outline" onclick="refreshUserList()">🔄 刷新</button>
        </div>
      </div>
      <div class="config-card-body">
        <div class="admin-user-filters">
          <input type="text" id="userSearchInput" placeholder="🔍 搜索用户ID/姓名..." oninput="filterAdminUsers()" style="flex:1;max-width:300px;">
          <select id="userRoleFilter" onchange="filterAdminUsers()">
            <option value="">全部角色</option>
            <option value="supplychain">供应链</option>
<option value="admin">管理员</option>
            <option value="sales">销售</option>
            <option value="planner">产品企划</option>
            <option value="designer">设计师</option>
            <option value="supplychain">供应链</option>
<option value="admin">管理员</option>
          </select>
          <select id="userStatusFilter" onchange="filterAdminUsers()">
            <option value="">全部状态</option>
            <option value="active">启用</option>
            <option value="disabled">停用</option>
          </select>
          <span class="admin-user-count" id="userCountDisplay">${users.length} 人</span>
        </div>
        <div class="table-wrap">
          <table class="admin-user-table">
            <thead>
              <tr>
                <th style="width:40px;">#</th>
                <th>用户ID</th>
                <th>姓名</th>
                <th>角色</th>
                <th>状态</th>
                <th>手机号</th>
                <th>邮箱</th>
                <th style="width:280px;">操作</th>
              </tr>
            </thead>
            <tbody id="adminUserTableBody">
              ${users.map((u, i) => `
                <tr data-user-id="${escHtml(u.userId)}" data-role="${u.role}" data-name="${escHtml(u.name)}" data-status="${u.status || 'active'}">
                  <td style="color:var(--gray-400);">${i + 1}</td>
                  <td><strong>${escHtml(u.userId)}</strong></td>
                  <td>${escHtml(u.name)}</td>
                  <td><span class="admin-user-role-badge role-${u.role}">${roleLabels[u.role] || u.role}</span></td>
                  <td><span class="admin-user-status-badge status-${u.status || 'active'}">${(u.status === 'disabled') ? '❌ 停用' : '✅ 启用'}</span></td>
                  <td>${escHtml(u.phone || '-')}</td>
                  <td>${escHtml(u.email || '-')}</td>
                  <td>
                    <div class="admin-user-actions">
                      <button class="btn-edit-user" onclick="openEditUserModal(${JSON.stringify(u).replace(/"/g, "'")})">✏️ 编辑</button>
                      <button class="btn-edit-role" onclick="openChangeRoleModal(${u.id}, '${u.role}', '${escHtml(u.name)}')">🔄 角色</button>
                      <button class="btn-reset-pwd" onclick="openResetPwdModal(${u.id}, '${escHtml(u.name)}')">🔑 密码</button>
                      ${u.userId !== AUTH_USER.userId
                        ? `<button class="btn-status" onclick="toggleUserStatus(${u.id}, '${escHtml(u.name)}', '${u.status || 'active'}')">${(u.status === 'disabled') ? '✅ 启用' : '⛔ 停用'}</button>`
                        : ''}
                      ${u.userId !== AUTH_USER.userId ? `<button class="btn-delete" onclick="confirmDeleteUser(${u.id}, '${escHtml(u.name)}')">🗑️ 删除</button>` : ''}
                    </div>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
        ${users.length === 0 ? '<div class="empty"><div class="empty-icon">📭</div><p>暂无用户数据</p></div>' : ''}
      </div>
    </div>`;
}

function filterAdminUsers() {
  const search = (document.getElementById('userSearchInput').value || '').toLowerCase();
  const roleFilter = document.getElementById('userRoleFilter').value;
  const statusFilter = document.getElementById('userStatusFilter').value;
  const rows = document.querySelectorAll('#adminUserTableBody tr');
  let visibleCount = 0;
  rows.forEach(row => {
    const userId = (row.dataset.userId || '').toLowerCase();
    const name = (row.dataset.name || '').toLowerCase();
    const role = row.dataset.role || '';
    const status = row.dataset.status || 'active';
    const matchSearch = !search || userId.includes(search) || name.includes(search);
    const matchRole = !roleFilter || role === roleFilter;
    const matchStatus = !statusFilter || status === statusFilter;
    const visible = matchSearch && matchRole && matchStatus;
    row.style.display = visible ? '' : 'none';
    if (visible) visibleCount++;
  });
  const countEl = document.getElementById('userCountDisplay');
  if (countEl) countEl.textContent = visibleCount + ' 人';
}

// ===== Admin: 编辑用户弹窗 =====
function openEditUserModal(userData) {
  if (isModalOpen()) return;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'editUserModal';
  modal.innerHTML = `
    <div class="modal modal-lg">
      <div class="modal-header"><button class="modal-close" onclick="closeM('editUserModal')">✕</button><div class="modal-header-left"><div class="modal-title">✏️ 编辑用户：${escHtml(userData.name || userData.userId)}</div></div></div>
      <div class="modal-body">
        <div id="editUserError" style="color:var(--danger);font-size:13px;display:none;margin-bottom:12px;text-align:center;padding:8px;background:var(--danger-light);border-radius:6px;"></div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 用户ID</label>
            <input type="text" class="form-input" id="editUserId" value="${escHtml(userData.userId)}" placeholder="登录用ID" required>
          </div>
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 姓名</label>
            <input type="text" class="form-input" id="editUserName" value="${escHtml(userData.name || '')}" placeholder="真实姓名" required>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">手机号</label>
            <input type="tel" class="form-input" id="editUserPhone" value="${escHtml(userData.phone || '')}" placeholder="手机号">
          </div>
          <div class="form-group">
            <label class="form-label">邮箱</label>
            <input type="email" class="form-input" id="editUserEmail" value="${escHtml(userData.email || '')}" placeholder="邮箱地址">
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">新密码</label>
            <input type="password" class="form-input" id="editUserPassword" placeholder="留空则不修改密码" autocomplete="new-password">
            <div class="form-hint">留空保持原密码，填入新密码则自动加密存储</div>
          </div>
          <div class="form-group">
            <label class="form-label">确认新密码</label>
            <input type="password" class="form-input" id="editUserPasswordConfirm" placeholder="再次输入新密码" autocomplete="new-password">
          </div>
        </div>
        <div style="background:var(--gray-50);padding:12px 16px;border-radius:8px;margin-top:8px;">
          <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
            <span style="font-size:13px;color:var(--gray-500);">当前角色：<span class="admin-user-role-badge role-${userData.role}">${({sales:'销售',planner:'产品企划',designer:'设计师',supplychain:'供应链',admin:'管理员'})[userData.role] || userData.role}</span></span>
            <span style="font-size:13px;color:var(--gray-500);">当前状态：<span class="admin-user-status-badge status-${userData.status || 'active'}">${(userData.status === 'disabled') ? '❌ 停用' : '✅ 启用'}</span></span>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('editUserModal')">取消</button>
        <button class="btn btn-primary" onclick="submitGuard(this,()=>submitEditUser(${userData.id}))">💾 保存修改</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitEditUser(userId) {
  const errEl = document.getElementById('editUserError');
  errEl.style.display = 'none';

  const newUserId = document.getElementById('editUserId').value.trim();
  const name = document.getElementById('editUserName').value.trim();
  const phone = document.getElementById('editUserPhone').value.trim();
  const email = document.getElementById('editUserEmail').value.trim();
  const password = document.getElementById('editUserPassword').value;
  const passwordConfirm = document.getElementById('editUserPasswordConfirm').value;

  // 校验
  if (!newUserId) { errEl.textContent = '用户ID不能为空'; errEl.style.display = ''; return; }
  if (!name) { errEl.textContent = '姓名不能为空'; errEl.style.display = ''; return; }

  if (password) {
    if (password.length < 6) { errEl.textContent = '密码至少6位'; errEl.style.display = ''; return; }
    if (password !== passwordConfirm) { errEl.textContent = '两次密码不一致'; errEl.style.display = ''; return; }
  }

  const body = { userId: newUserId, name, phone, email };
  if (password) body.password = password;

  try {
    await apiPut(`/admin/users/${userId}`, body);
    showAdminToast('✅ 用户资料已更新', 'success');
    closeM('editUserModal');
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message;
    errEl.style.display = '';
  }
}

// ===== Admin: 停用/启用账号 =====
async function toggleUserStatus(userId, userName, currentStatus) {
  const action = currentStatus === 'disabled' ? '启用' : '停用';
  if (!confirm(`⚠️ 确定要${action}用户「${userName}」吗？\n${action === '停用' ? '停用后该用户将无法登录系统。' : '启用后该用户可以正常登录。'}`)) return;

  try {
    const result = await apiPut(`/admin/users/${userId}/toggle-status`, {});
    showAdminToast(`✅ 用户「${userName}」已${result.status === 'active' ? '启用' : '停用'}`, 'success');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 操作失败: ' + e.message, 'error');
  }
}

function refreshUserList() {
  renderAdminContent();
}

function openChangeRoleModal(userId, currentRole, userName) {
  if (isModalOpen()) return;
  const roleOptions = { admin: '管理员', sales: '销售', planner: '产品企划', designer: '设计师', supplychain: '供应链' };
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'changeRoleModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><button class="modal-close" onclick="closeM('changeRoleModal')">✕</button><div class="modal-header-left"><div class="modal-title">✏️ 修改角色：${escHtml(userName)}</div></div></div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">当前角色</label>
          <div><span class="admin-user-role-badge role-${currentRole}">${roleOptions[currentRole] || currentRole}</span></div>
        </div>
        <div class="form-group">
          <label class="form-label">新角色</label>
          <select class="form-select" id="newRoleSelect">
            ${Object.entries(roleOptions).map(([k, v]) =>
              `<option value="${k}" ${k === currentRole ? 'selected' : ''}>${v}</option>`
            ).join('')}
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('changeRoleModal')">取消</button>
        <button class="btn btn-primary" onclick="submitGuard(this,()=>submitChangeRole(${userId}))">确认修改</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitChangeRole(userId) {
  const newRole = document.getElementById('newRoleSelect').value;
  try {
    await apiPut(`/admin/users/${userId}/role`, { role: newRole });
    showAdminToast('✅ 角色已更新', 'success');
    closeM('changeRoleModal');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 更新失败: ' + e.message, 'error');
  }
}

function openResetPwdModal(userId, userName) {
  if (isModalOpen()) return;
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'resetPwdModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><button class="modal-close" onclick="closeM('resetPwdModal')">✕</button><div class="modal-header-left"><div class="modal-title">🔑 重置密码：${escHtml(userName)}</div></div></div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">新密码</label>
          <input type="password" class="form-input" id="newPwdInput" placeholder="输入新密码（至少6位）" minlength="6" style="text-align:center;">
        </div>
        <div class="form-group">
          <label class="form-label">确认密码</label>
          <input type="password" class="form-input" id="confirmPwdInput" placeholder="再次输入新密码" style="text-align:center;">
        </div>
        <div id="pwdError" style="color:var(--danger);font-size:13px;display:none;text-align:center;"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('resetPwdModal')">取消</button>
        <button class="btn btn-warning" onclick="submitGuard(this,()=>submitResetPwd(${userId}))">确认重置</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitResetPwd(userId) {
  const pwd = document.getElementById('newPwdInput').value;
  const confirm = document.getElementById('confirmPwdInput').value;
  const errEl = document.getElementById('pwdError');
  if (!pwd || pwd.length < 6) {
    errEl.textContent = '密码至少6位';
    errEl.style.display = '';
    return;
  }
  if (pwd !== confirm) {
    errEl.textContent = '两次密码不一致';
    errEl.style.display = '';
    return;
  }
  errEl.style.display = 'none';
  try {
    await apiPut(`/admin/users/${userId}/reset-password`, { password: pwd });
    showAdminToast('✅ 密码已重置', 'success');
    closeM('resetPwdModal');
  } catch (e) {
    showAdminToast('❌ 重置失败: ' + e.message, 'error');
  }
}

function confirmDeleteUser(userId, userName) {
  if (!confirm(`⚠️ 确定要删除用户「${userName}」吗？\n此操作不可恢复！`)) return;
  submitDeleteUser(userId);
}

async function submitDeleteUser(userId) {
  try {
    await apiDelete(`/admin/users/${userId}`);
    showAdminToast('✅ 用户已删除', 'success');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 删除失败: ' + e.message, 'error');
  }
}

// 添加 DELETE 方法
async function apiDelete(url) {
  const r = await fetch(API + url, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
  if (!r.ok) throw new Error(`DELETE ${url} failed: ${r.status}`);
  return r.json();
}

// Toast 通知
function showAdminToast(message, type = 'success') {
  const existing = document.querySelector('.admin-toast');
  if (existing) existing.remove();
  const toast = document.createElement('div');
  toast.className = 'admin-toast ' + type;
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transition = 'opacity .3s';
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

// ===== Admin: 角色管理 =====
async function renderAdminRoles(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  let [roles, permDefs] = await Promise.all([
    apiGet('/admin/roles').catch(() => []),
    apiGet('/admin/permission-defs').catch(() => []),
  ]);

  // 按组归类权限
  const groups = {};
  permDefs.forEach(p => {
    if (!groups[p.group]) groups[p.group] = [];
    groups[p.group].push(p);
  });

  container.innerHTML = `
    <div class="config-card">
      <div class="config-card-header">
        <h3>🔐 角色管理 <span style="font-size:13px;color:var(--gray-400);font-weight:400;">共 ${roles.length} 个角色</span></h3>
        <button class="btn btn-sm btn-primary" onclick="openCreateRoleModal()">➕ 新建角色</button>
      </div>
      <div class="config-card-body">
        <div class="table-wrap">
          <table class="admin-user-table">
            <thead>
              <tr>
                <th style="width:40px;">#</th>
                <th>角色标识</th>
                <th>显示名称</th>
                <th>描述</th>
                <th>权限数</th>
                <th>类型</th>
                <th style="width:160px;">操作</th>
              </tr>
            </thead>
            <tbody>
              ${roles.map((r, i) => `
                <tr>
                  <td style="color:var(--gray-400);">${i + 1}</td>
                  <td><strong>${escHtml(r.name)}</strong></td>
                  <td>${escHtml(r.displayName)}</td>
                  <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escHtml(r.description || '-')}</td>
                  <td><span class="badge badge-progress">${r.permissions.length}</span></td>
                  <td>${r.isSystem ? '<span class="admin-user-role-badge role-admin">系统</span>' : '<span class="admin-user-role-badge role-sales">自定义</span>'}</td>
                  <td>
                    <div class="admin-user-actions">
                      <button class="btn-edit-user" onclick="openEditRoleModal(${r.id}, '${escHtml(r.name)}', '${escHtml(r.displayName)}', '${escHtml(r.description || '')}', ${JSON.stringify(r.permissions).replace(/"/g, "'")})">✏️ 编辑</button>
                      ${!r.isSystem ? `<button class="btn-delete" onclick="confirmDeleteRole(${r.id}, '${escHtml(r.displayName)}')">🗑️ 删除</button>` : ''}
                    </div>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
        ${roles.length === 0 ? '<div class="empty"><div class="empty-icon">📭</div><p>暂无角色数据</p></div>' : ''}
      </div>
    </div>`;
}

// ===== 新建角色 =====
function openCreateRoleModal() {
  loadPermDefsAndOpenModal(null);
}

function openEditRoleModal(id, name, displayName, description, permissions) {
  loadPermDefsAndOpenModal({ id, name, displayName, description, permissions });
}

async function loadPermDefsAndOpenModal(editData) {
  if (isModalOpen()) return;
  const permDefs = await apiGet('/admin/permission-defs').catch(() => []);
  const groups = {};
  permDefs.forEach(p => {
    if (!groups[p.group]) groups[p.group] = [];
    groups[p.group].push(p);
  });

  const isEdit = editData !== null && editData.id != null;
  const selectedPerms = isEdit ? (editData.permissions || []) : [];

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'roleFormModal';
  modal.innerHTML = `
    <div class="modal modal-lg">
      <div class="modal-header"><button class="modal-close" onclick="closeM('roleFormModal')">✕</button><div class="modal-header-left"><div class="modal-title">${isEdit ? '✏️ 编辑角色' : '➕ 新建角色'}</div></div></div>
      <div class="modal-body">
        <div id="roleFormError" style="color:var(--danger);font-size:13px;display:none;margin-bottom:12px;text-align:center;padding:8px;background:var(--danger-light);border-radius:6px;"></div>
        ${!isEdit ? `
        <div class="form-group">
          <label class="form-label"><span class="required">*</span> 角色标识</label>
          <input type="text" class="form-input" id="roleNameInput" placeholder="英文标识，如 senior_designer" value="">
          <div class="form-hint">唯一标识符，创建后不可修改。仅支持英文/数字/下划线</div>
        </div>` : `
        <div class="form-group">
          <label class="form-label">角色标识</label>
          <div style="padding:9px 12px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:13px;color:var(--gray-600);"><strong>${escHtml(editData.name)}</strong></div>
        </div>`}
        <div class="form-row">
          <div class="form-group">
            <label class="form-label"><span class="required">*</span> 显示名称</label>
            <input type="text" class="form-input" id="roleDisplayNameInput" placeholder="如 高级设计师" value="${isEdit ? escHtml(editData.displayName) : ''}">
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input type="text" class="form-input" id="roleDescInput" placeholder="角色说明" value="${isEdit ? escHtml(editData.description) : ''}">
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">权限分配</label>
        </div>
        ${Object.entries(groups).map(([group, perms]) => `
          <div style="margin-bottom:16px;">
            <div style="font-size:13px;font-weight:600;color:var(--gray-700);margin-bottom:8px;padding-bottom:4px;border-bottom:1px solid var(--gray-200);">${group}</div>
            <div class="checkbox-group">
              ${perms.map(p => `
                <label class="checkbox-item ${selectedPerms.includes(p.key) ? 'checked' : ''}">
                  <input type="checkbox" name="perm" value="${p.key}" ${selectedPerms.includes(p.key) ? 'checked' : ''} onchange="this.closest('.checkbox-item').classList.toggle('checked')">
                  <span>${p.label}</span>
                </label>
              `).join('')}
            </div>
          </div>
        `).join('')}
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('roleFormModal')">取消</button>
        <button class="btn btn-primary" onclick="${isEdit ? `submitEditRole(${editData.id})` : 'submitCreateRole()'}">${isEdit ? '💾 保存修改' : '✅ 创建角色'}</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitCreateRole() {
  const errEl = document.getElementById('roleFormError');
  errEl.style.display = 'none';

  const name = document.getElementById('roleNameInput').value.trim();
  const displayName = document.getElementById('roleDisplayNameInput').value.trim();
  const description = document.getElementById('roleDescInput').value.trim();

  if (!name || !/^[a-zA-Z0-9_]+$/.test(name)) {
    errEl.textContent = '角色标识仅支持英文/数字/下划线'; errEl.style.display = ''; return;
  }
  if (!displayName) { errEl.textContent = '显示名称不能为空'; errEl.style.display = ''; return; }

  const perms = Array.from(document.querySelectorAll('input[name="perm"]:checked')).map(el => el.value);

  try {
    await apiPost('/admin/roles', { name, displayName, description, permissions: perms });
    showAdminToast('✅ 角色已创建', 'success');
    closeM('roleFormModal');
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message; errEl.style.display = '';
  }
}

async function submitEditRole(roleId) {
  const errEl = document.getElementById('roleFormError');
  errEl.style.display = 'none';

  const displayName = document.getElementById('roleDisplayNameInput').value.trim();
  const description = document.getElementById('roleDescInput').value.trim();
  if (!displayName) { errEl.textContent = '显示名称不能为空'; errEl.style.display = ''; return; }

  const perms = Array.from(document.querySelectorAll('input[name="perm"]:checked')).map(el => el.value);

  try {
    await apiPut(`/admin/roles/${roleId}`, { displayName, description, permissions: perms });
    showAdminToast('✅ 角色已更新', 'success');
    closeM('roleFormModal');
    await renderAdminContent();
  } catch (e) {
    errEl.textContent = e.message; errEl.style.display = '';
  }
}

function confirmDeleteRole(roleId, displayName) {
  if (!confirm(`⚠️ 确定要删除角色「${displayName}」吗？\n删除后该角色下所有用户将失去对应权限。`)) return;
  submitDeleteRole(roleId);
}

async function submitDeleteRole(roleId) {
  try {
    await apiDelete(`/admin/roles/${roleId}`);
    showAdminToast('✅ 角色已删除', 'success');
    await renderAdminContent();
  } catch (e) {
    showAdminToast('❌ 删除失败: ' + e.message, 'error');
  }
}

// ==================== 管理员：产品类目管理 ====================
async function renderAdminCategories(container) {
  async function loadAndRender() {
    const cats = await apiGet('/categories/all');
    const nameList = cats.map(c => c.name).join(', ');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>📂 产品类目管理</h3>
          <button class="btn btn-primary btn-sm" onclick="addCategory()">➕ 新增类目</button>
        </div>
        <div class="config-card-body">
          <p style="font-size:13px;color:var(--gray-500);margin-bottom:16px;">当前类目（顺序按排序号）：${nameList}</p>
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${cats.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" onclick="editCategory(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" onclick="deleteCategory(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

// ==================== 新增类目弹窗 ====================
window.addCategory = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'categoryEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('categoryEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📂 新增产品类目</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 类目名称</label><input class="form-input" id="catName" placeholder="如：灯、音响..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="catOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('categoryEditModal')">取消</button>
        <button class="btn btn-primary" onclick="saveCategory(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.editCategory = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'categoryEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('categoryEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑产品类目</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 类目名称</label><input class="form-input" id="catName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="catOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="catActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('categoryEditModal')">取消</button>
        <button class="btn btn-primary" onclick="saveCategory(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.saveCategory = async function(id) {
  const name = document.getElementById('catName')?.value?.trim();
  if (!name) { alert('请输入类目名称'); return; }
  const sortOrder = parseInt(document.getElementById('catOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('catActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/categories/${id}`, body);
  } else {
    await apiPost('/categories', body);
  }
  closeM('categoryEditModal');
  // 刷新类目列表和主界面
  try { CATEGORIES = await apiGet('/categories'); } catch(e) {}
  switchAdminTab('categories');
};

window.deleteCategory = async function(id) {
  if (!confirm('确定删除该类目？已关联的项目不受影响。')) return;
  await apiDelete(`/categories/${id}`);
  try { CATEGORIES = await apiGet('/categories'); } catch(e) {}
  switchAdminTab('categories');
};

// ==================== 管理员：合规处罚管理 ====================
async function renderAdminCompliance(container) {
  async function loadAndRender() {
    const items = await apiGet('/compliance/all');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>⚖️ 合规处罚管理</h3>
          <button class="btn btn-primary btn-sm" onclick="addCompliance()">➕ 新增合规项</button>
        </div>
        <div class="config-card-body">
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${items.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" onclick="editCompliance(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" onclick="deleteCompliance(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

window.addCompliance = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'complianceEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('complianceEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">⚖️ 新增合规处罚项</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="compName" placeholder="如：蓝牙、无线发射..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="compOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('complianceEditModal')">取消</button>
        <button class="btn btn-primary" onclick="saveCompliance(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.editCompliance = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'complianceEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('complianceEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑合规处罚项</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="compName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="compOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="compActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('complianceEditModal')">取消</button>
        <button class="btn btn-primary" onclick="saveCompliance(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.saveCompliance = async function(id) {
  const name = document.getElementById('compName')?.value?.trim();
  if (!name) { alert('请输入名称'); return; }
  const sortOrder = parseInt(document.getElementById('compOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('compActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/compliance/${id}`, body);
  } else {
    await apiPost('/compliance', body);
  }
  closeM('complianceEditModal');
  try { COMPLIANCE_ITEMS = await apiGet('/compliance'); } catch(e) {}
  switchAdminTab('compliance');
};

window.deleteCompliance = async function(id) {
  if (!confirm('确定删除该合规项？')) return;
  await apiDelete(`/compliance/${id}`);
  try { COMPLIANCE_ITEMS = await apiGet('/compliance'); } catch(e) {}
  switchAdminTab('compliance');
};

// ==================== 管理员：参考零售价管理 ====================
async function renderAdminPriceRanges(container) {
  async function loadAndRender() {
    const items = await apiGet('/price-ranges/all');
    container.innerHTML = `
      <div class="config-card">
        <div class="config-card-header">
          <h3>💰 参考零售价管理</h3>
          <button class="btn btn-primary btn-sm" onclick="addPriceRange()">➕ 新增价格</button>
        </div>
        <div class="config-card-body">
          <div class="table-wrap"><table>
            <thead><tr><th>ID</th><th>名称</th><th>排序</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${items.map(c => `
              <tr>
                <td>${c.id}</td>
                <td><strong>${c.name}</strong></td>
                <td>${c.sortOrder}</td>
                <td><span class="badge ${c.active ? 'badge-completed' : 'badge-rejected'}">${c.active ? '启用' : '禁用'}</span></td>
                <td style="white-space:nowrap;">
                  <button class="btn btn-outline btn-sm" onclick="editPriceRange(${c.id}, '${escHtml(c.name)}', ${c.sortOrder}, ${c.active})">✏️ 编辑</button>
                  <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" onclick="deletePriceRange(${c.id})">🗑️ 删除</button>
                </td>
              </tr>`).join('')}
            </tbody>
          </table></div>
        </div>
      </div>`;
  }
  container.innerHTML = `<div class="loading">加载中</div>`;
  await loadAndRender();
}

window.addPriceRange = function() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'priceRangeEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('priceRangeEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">💰 新增参考零售价</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="prName" placeholder="如：100元以下、150元以下..."></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="prOrder" type="number" value="0" placeholder="数字越小越靠前"></div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('priceRangeEditModal')">取消</button>
        <button class="btn btn-primary" onclick="savePriceRange(null)">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.editPriceRange = function(id, name, order, active) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'priceRangeEditModal';
  overlay.innerHTML = `
    <button class="modal-close-float" onclick="closeM('priceRangeEditModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑参考零售价</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 名称</label><input class="form-input" id="prName" value="${name}"></div>
        <div class="form-group"><label class="form-label">排序号</label><input class="form-input" id="prOrder" type="number" value="${order}"></div>
        <div class="form-group"><label class="form-label">状态</label>
          <select class="form-select" id="prActive">
            <option value="true" ${active ? 'selected' : ''}>启用</option>
            <option value="false" ${!active ? 'selected' : ''}>禁用</option>
          </select>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('priceRangeEditModal')">取消</button>
        <button class="btn btn-primary" onclick="savePriceRange(${id})">保存</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
};

window.savePriceRange = async function(id) {
  const name = document.getElementById('prName')?.value?.trim();
  if (!name) { alert('请输入名称'); return; }
  const sortOrder = parseInt(document.getElementById('prOrder')?.value) || 0;
  const body = { name, sortOrder };
  if (id) {
    const active = document.getElementById('prActive')?.value === 'true';
    body.active = String(active);
    await apiPut(`/price-ranges/${id}`, body);
  } else {
    await apiPost('/price-ranges', body);
  }
  closeM('priceRangeEditModal');
  try { PRICE_RANGES = await apiGet('/price-ranges'); } catch(e) {}
  switchAdminTab('priceRanges');
};

window.deletePriceRange = async function(id) {
  if (!confirm('确定删除该价格？')) return;
  await apiDelete(`/price-ranges/${id}`);
  try { PRICE_RANGES = await apiGet('/price-ranges'); } catch(e) {}
  switchAdminTab('priceRanges');
};

/** 刷新组织架构及用户数据 */
async function refreshOrgData() {
  try {
    const [users, depts] = await Promise.all([
      apiGet('/users'),
      apiGet('/departments'),
    ]);
    USERS = users;
    DEPARTMENTS = depts;
  } catch(e) {}
}

  // ==================== 管理员：组织架构管理 ====================
async function renderAdminOrg(container) {
  const [depts, usersData] = await Promise.all([
    apiGet('/departments'),
    apiGet('/users'),
  ]);
  // 展平所有用户
  const allUsers = Object.values(usersData).flat();

  // 确保部门负责人也计入部门成员（即使 departmentId 未同步）
  for (const d of depts) {
    if (d.headUserId) {
      const headUser = allUsers.find(u => u.userId === d.headUserId);
      if (headUser && !headUser.departmentId) {
        headUser.departmentId = String(d.id);
      }
    }
  }

  container.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
      <h3 style="font-size:16px;margin:0;">🏢 组织架构</h3>
      <button class="btn btn-primary btn-sm" onclick="openCreateDeptModal()">➕ 新建部门</button>
    </div>
    ${depts.length === 0 ? `<div class="empty"><div class="empty-icon">🏢</div><p>暂无部门，点击上方按钮创建</p></div>` : `
    <div style="display:flex;flex-direction:column;gap:12px;">
      ${depts.map(d => {
        const headUser = allUsers.find(u => u.userId === d.headUserId);
        const members = allUsers.filter(u => u.departmentId === String(d.id));
        const roleLabel_ = {sales:'销售',planner:'产品企划',designer:'设计师',supplychain:'供应链'}[d.role] || d.role;
        return `<div class="card" style="padding:16px;">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
            <div>
              <strong style="font-size:15px;">${escHtml(d.name)}</strong>
              <span class="admin-user-role-badge role-${d.role}" style="margin-left:8px;font-size:11px;">${roleLabel_}</span>
              ${!d.active ? `<span style="color:var(--danger);font-size:12px;margin-left:8px;">⛔ 已停用</span>` : ''}
            </div>
            <div style="display:flex;gap:6px;">
              <button class="btn btn-outline btn-sm" onclick="editDept(${JSON.stringify(d).replace(/"/g,"'")})">✏️ 编辑</button>
              <button class="btn btn-outline btn-sm" style="color:var(--danger);border-color:var(--danger);" onclick="deleteDept(${d.id})">🗑️ 删除</button>
            </div>
          </div>
          <div style="font-size:13px;color:var(--gray-500);margin-bottom:8px;">
            ${headUser ? `👤 部门负责人：<strong>${escHtml(headUser.name)}</strong>` : `<span style="color:var(--warning);">⚠️ 未设置负责人</span>`}
            ｜ 👥 ${members.length} 人
          </div>
          ${members.length > 0 ? `<div style="display:flex;flex-wrap:wrap;gap:6px;">
            ${members.map(u => {
              const titleLevelLabel = u.titleLevel === '2' ? '（负责人）' : u.titleLevel === '1' ? '（组长）' : '';
              const isHeadUser = d.headUserId === u.userId;
              return `<span style="display:inline-flex;align-items:center;gap:4px;padding:4px 10px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:12px;">
                ${escHtml(u.name)}${titleLevelLabel}
                ${isHeadUser ? '<span style="color:var(--gray-300);font-size:11px;" title="部门负责人不可移出">🔒</span>' : `<span style="cursor:pointer;color:var(--gray-400);font-size:11px;" onclick="removeUserFromDept('${u.userId}')" title="移出部门">✕</span>`}
              </span>`;
            }).join('')}
          </div>` : '<div style="font-size:12px;color:var(--gray-400);">暂无成员</div>'}
        </div>`;
      }).join('')}
    </div>`}
    <div class="card" style="padding:16px;margin-top:16px;">
      <h4 style="margin:0 0 8px 0;font-size:14px;">未分配部门的用户</h4>
      ${allUsers.filter(u => !u.departmentId && u.role !== 'admin').map(u =>
        `<span style="display:inline-flex;align-items:center;gap:4px;padding:4px 10px;background:var(--gray-50);border:1px solid var(--gray-200);border-radius:6px;font-size:12px;margin:3px;">
          ${escHtml(u.name)}（${u.role}）
          <span style="cursor:pointer;color:var(--primary);font-size:11px;" onclick="openAssignUserDept('${u.userId}')" title="分配到部门">📂</span>
        </span>`
      ).join('') || '<span style="font-size:12px;color:var(--gray-400);">全部已分配</span>'}
    </div>
  `;
}

function escHtml(s) {
  if (!s) return '';
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

async function openCreateDeptModal() {
  if (isModalOpen()) return;
  const roles = ['sales','planner','designer','supplychain'];
  const allUsers = Object.values(await apiGet('/users')).flat();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'createDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('createDeptModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">➕ 新建部门</div></div></div>
      <div class="modal-body">
        <form id="createDeptForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 部门名称</label>
            <input type="text" class="form-input" name="name" placeholder="如：设计一部" required>
          </div>
          <div class="form-group"><label class="form-label"><span class="required">*</span> 关联角色</label>
            <select class="form-select" name="role" id="deptRoleSelect">
              ${roles.map(r => `<option value="${r}">${({sales:'销售',planner:'产品企划',designer:'设计师',supplychain:'供应链'})[r]}</option>`).join('')}
            </select>
          </div>
          <div class="form-group"><label class="form-label">部门负责人</label>
            <select class="form-select" name="headUserId" id="deptHeadSelect">
              <option value="">未设置</option>
            </select>
          </div>
        </form>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('createDeptModal')">取消</button>
        <button class="btn btn-primary" onclick="submitGuard(this,()=>submitCreateDept())">确认创建</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  // 根据选择角色动态更新负责人选项
  document.getElementById('deptRoleSelect').onchange = function() {
    const role = this.value;
    const sel = document.getElementById('deptHeadSelect');
    const filtered = allUsers.filter(u => u.role === role);
    sel.innerHTML = '<option value="">未设置</option>' +
      filtered.map(u => `<option value="${u.userId}">${u.name}</option>`).join('');
  };
  document.getElementById('deptRoleSelect').dispatchEvent(new Event('change'));
}

async function submitCreateDept() {
  const fd = new FormData(document.getElementById('createDeptForm'));
  const data = Object.fromEntries(fd.entries());
  if (!data.name) return alert('请填写部门名称');
  await apiPost('/departments', data);
  closeM('createDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function editDept(d) {
  if (isModalOpen()) return;
  const roles = ['sales','planner','designer','supplychain'];
  const allUsers = Object.values(await apiGet('/users')).flat();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'editDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('editDeptModal')">✕</button>
    <div class="modal">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">✏️ 编辑部门</div></div></div>
      <div class="modal-body">
        <form id="editDeptForm">
          <div class="form-group"><label class="form-label"><span class="required">*</span> 部门名称</label>
            <input type="text" class="form-input" name="name" value="${escHtml(d.name)}" required>
          </div>
          <div class="form-group"><label class="form-label">关联角色</label>
            <select class="form-select" name="role" id="editDeptRoleSelect">
              ${roles.map(r => `<option value="${r}" ${r === d.role ? 'selected' : ''}>${({sales:'销售',planner:'产品企划',designer:'设计师',supplychain:'供应链'})[r]}</option>`).join('')}
            </select>
          </div>
          <div class="form-group"><label class="form-label">部门负责人</label>
            <select class="form-select" name="headUserId" id="editDeptHeadSelect"></select>
          </div>
          <div class="form-group"><label class="form-label">状态</label>
            <select class="form-select" name="active">
              <option value="true" ${d.active ? 'selected' : ''}>启用</option>
              <option value="false" ${!d.active ? 'selected' : ''}>停用</option>
            </select>
          </div>
        </form>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('editDeptModal')">取消</button>
        <button class="btn btn-primary" onclick="submitGuard(this,()=>submitEditDept(${d.id}))">保存</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  const updateHeadSelect = () => {
    const role = document.getElementById('editDeptRoleSelect').value;
    const sel = document.getElementById('editDeptHeadSelect');
    sel.innerHTML = '<option value="">未设置</option>' +
      allUsers.filter(u => u.role === role).map(u =>
        `<option value="${u.userId}" ${u.userId === d.headUserId ? 'selected' : ''}>${u.name}</option>`
      ).join('');
  };
  document.getElementById('editDeptRoleSelect').onchange = updateHeadSelect;
  updateHeadSelect();
}

async function submitEditDept(id) {
  const fd = new FormData(document.getElementById('editDeptForm'));
  const data = Object.fromEntries(fd.entries());
  data.active = data.active === 'true';
  await apiPut(`/departments/${id}`, data);
  closeM('editDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function deleteDept(id) {
  if (!confirm('确定删除该部门？')) return;
  await apiDelete(`/departments/${id}`);
  await refreshOrgData();
  switchAdminTab('org');
}

async function openAssignUserDept(userId) {
  if (isModalOpen()) return;
  const [depts, usersData] = await Promise.all([
    apiGet('/departments'),
    apiGet('/users'),
  ]);
  const allUsers = Object.values(usersData).flat();
  const user = allUsers.find(u => u.userId === userId);
  if (!user) return;
  // 按角色过滤可选部门
  const roleDepts = depts.filter(d => d.role === user.role && d.active);
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'assignDeptModal';
  modal.innerHTML = `
    <button class="modal-close-float" onclick="closeM('assignDeptModal')">✕</button>
    <div class="modal" style="max-width:400px;">
      <div class="modal-header"><div class="modal-header-left"><div class="modal-title">📂 分配部门：${escHtml(user.name)}</div></div></div>
      <div class="modal-body">
        ${roleDepts.length === 0 ? `<p style="color:var(--gray-500)">没有可分配的 ${user.role} 部门，请先创建</p>` : `
        <div style="display:flex;flex-direction:column;gap:8px;">
          ${roleDepts.map(d => `
            <label style="display:flex;align-items:center;gap:8px;padding:10px 14px;border:1px solid var(--gray-200);border-radius:8px;cursor:pointer;">
              <input type="radio" name="deptId" value="${d.id}">
              <div><strong>${escHtml(d.name)}</strong><div style="font-size:12px;color:var(--gray-500);">负责人：${allUsers.find(u2 => u2.userId === d.headUserId)?.name || '未设置'}</div></div>
            </label>
          `).join('')}
        </div>`}
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('assignDeptModal')">取消</button>
        ${roleDepts.length > 0 ? `<button class="btn btn-primary" onclick="submitGuard(this,()=>submitAssignDept('${userId}'))">确认分配</button>` : ''}
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function submitAssignDept(userId) {
  const sel = document.querySelector('input[name="deptId"]:checked');
  if (!sel) return alert('请选择一个部门');
  await apiPut(`/users/org/${userId}`, { departmentId: parseInt(sel.value) });
  closeM('assignDeptModal');
  await refreshOrgData();
  switchAdminTab('org');
}

async function removeUserFromDept(userId) {
  // 检查是否为部门负责人，负责人不能移出
  const isHead = DEPARTMENTS.some(d => d.headUserId === userId);
  if (isHead) {
    alert('该用户是部门负责人，无法直接移出。请先更换部门负责人或删除部门。');
    return;
  }
  if (!confirm('确定将该用户移出部门？')) return;
  await apiPut(`/users/org/${userId}`, { departmentId: null });
  await refreshOrgData();
  switchAdminTab('org');
}

// ==================== 评分权重管理 ====================
async function renderAdminScoring(container) {
  container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</div>`;
  try {
    const data = await apiGet('/admin/scoring-weights');
    const types = data.types || [];
    container.innerHTML = `
      <div style="max-width:760px;margin:0 auto;">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
          <div>
            <h2 style="font-size:18px;margin:0 0 4px;">⭐ 评分权重管理</h2>
            <p style="font-size:13px;color:var(--gray-400);margin:0;">按项目类型分别设置各角色评分权重（百分比）</p>
          </div>
          <button class="btn btn-outline btn-sm" onclick="resetScoringWeights()" style="color:var(--gray-500);">↺ 重置默认</button>
        </div>
        ${types.map((t, ti) => `
          <div style="background:#fff;border:1px solid var(--gray-200);border-radius:10px;padding:20px;margin-bottom:16px;">
            <h3 style="font-size:14px;font-weight:500;margin:0 0 16px;">${t.label}</h3>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">
              ${t.weights.map(w => `
                <div style="background:var(--bg-secondary,#F9FAFB);border-radius:8px;padding:12px;">
                  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
                    <span style="font-size:13px;font-weight:500;">${w.label}</span>
                    <span style="font-size:20px;font-weight:600;" id="sd_${t.type}_${w.role}">${Math.round(w.weight)}</span>
                    <span style="font-size:12px;color:var(--gray-400);">%</span>
                  </div>
                  <div style="display:flex;align-items:center;gap:8px;">
                    <input type="range" min="0" max="100" step="1" value="${Math.round(w.weight)}" style="flex:1;" oninput="updateScoringPct('${t.type}','${w.role}',this.value)">
                    <input type="number" class="form-input" value="${Math.round(w.weight)}" min="0" max="100" step="1" style="width:55px;text-align:center;padding:4px 6px;" onchange="updateScoringPct('${t.type}','${w.role}',this.value)">
                  </div>
                </div>
              `).join('')}
            </div>
            <div style="margin-top:12px;display:flex;justify-content:space-between;align-items:center;font-size:12px;">
              <span style="color:var(--gray-400);">合计：<strong style="font-size:16px;" id="sum_${t.type}">${t.weights.reduce((s,w) => s + Math.round(w.weight), 0)}</strong>%</span>
            </div>
          </div>
        `).join('')}
        <div style="text-align:right;">
          <button class="btn btn-primary" onclick="saveScoringWeights()">💾 保存权重</button>
        </div>
        <div style="margin-top:16px;padding:12px;background:var(--warning-light,#FFFBEB);border-radius:8px;font-size:12px;color:#92400E;">
          💡 权重为百分比（0~100%），建议各项目类型的权重合计为 100%。综合分 = Σ(角色评分 × 权重%) ÷ Σ(权重%)。修改仅影响新建评分，历史评分不受影响。
        </div>
      </div>`;
    window._scoringWeights = {};
    types.forEach(t => {
      window._scoringWeights[t.type] = {};
      t.weights.forEach(w => { window._scoringWeights[t.type][w.role] = Math.round(w.weight); });
    });
  } catch (e) {
    container.innerHTML = `<div style="text-align:center;padding:40px;color:var(--danger);">加载失败: ${e.message}</div>`;
  }
}

function roleColor(role) {
  const colors = { planner: '#534AB7', sales: '#378ADD', designer: '#639922', admin: '#D85A30' };
  return colors[role] || '#888780';
}

window.updateScoringPct = function(type, role, val) {
  const num = Math.round(parseFloat(val || 0));
  const clamped = Math.max(0, Math.min(100, num));
  if (!window._scoringWeights) window._scoringWeights = {};
  if (!window._scoringWeights[type]) window._scoringWeights[type] = {};
  window._scoringWeights[type][role] = clamped;
  const display = document.getElementById('sd_' + type + '_' + role);
  if (display) display.textContent = clamped;
  const weights = window._scoringWeights[type] || {};
  const sum = Object.values(weights).reduce((a, b) => a + b, 0);
  const sumEl = document.getElementById('sum_' + type);
  if (sumEl) {
    sumEl.textContent = sum;
    sumEl.style.color = sum === 100 ? 'var(--success,#059669)' : sum > 100 ? 'var(--danger)' : 'var(--warning,#D97706)';
  }
};

window.saveScoringWeights = async function() {
  if (!window._scoringWeights) return;
  try {
    await apiPut('/admin/scoring-weights', window._scoringWeights);
    alert('评分权重已保存');
  } catch (e) {
    alert('保存失败: ' + e.message);
  }
};

window.resetScoringWeights = async function() {
  if (!confirm('确定重置所有权重为默认值？')) return;
  window.location.reload();
};

async function renderAdminLogs(container) {
  container.innerHTML = `
    <div class="filter-bar" style="margin-bottom:16px;">
      <input type="date" class="form-input" id="logStartDate" style="min-width:140px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="logEndDate" style="min-width:140px;" title="结束日期">
      <button class="btn btn-primary btn-sm" onclick="loadAdminLogs()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" onclick="document.getElementById('logStartDate').value='';document.getElementById('logEndDate').value='';loadAdminLogs()">重置</button>
    </div>
    <div id="logContainer"><div class="loading">加载中</div></div>
  `;
  await loadAdminLogs();
}

async function loadAdminLogs() {
  const container = document.getElementById('logContainer');
  if (!container) return;
  container.innerHTML = '<div class="loading">加载中</div>';
  try {
    const startDate = document.getElementById('logStartDate')?.value || '';
    const endDate = document.getElementById('logEndDate')?.value || '';
    let url = '/system/logs';
    const params = [];
    if (startDate) params.push('startDate=' + startDate);
    if (endDate) params.push('endDate=' + endDate);
    if (params.length) url += '?' + params.join('&');
    const logs = await apiGet(url);
    if (!logs.length) {
      container.innerHTML = '<div class="empty"><div class="empty-icon">📭</div><p>暂无日志记录</p></div>';
      return;
    }
    container.innerHTML = '<div style="font-size:13px;color:var(--gray-400);margin-bottom:8px;">共 ' + logs.length + ' 条记录</div>' +
      '<div class="card" style="padding:0;overflow-x:auto;"><table><thead><tr>' +
      '<th style="width:60px;">#</th><th style="width:150px;">时间</th><th style="width:60px;">角色</th>' +
      '<th style="width:80px;">操作人</th><th>操作内容</th><th style="width:80px;">关联项目</th></tr></thead><tbody>' +
      logs.map(l => {
        const rl = {sales:'销售',planner:'企划',designer:'设计师',supplychain:'供应链',admin:'管理员'};
        const rn = rl[l.role] || l.role;
        const pl = l.projectId ? '<a href="javascript:void(0)" onclick="openProjectDetail(' + l.projectId + ')" style="color:var(--primary);text-decoration:none;">#' + l.projectId + '</a>' : '-';
        return '<tr><td style="color:var(--gray-400);">' + l.id + '</td><td style="white-space:nowrap;font-size:12px;">' +
          l.time + '</td><td><span class="badge badge-progress" style="font-size:11px;">' + rn + '</span></td><td><strong>' +
          l.username + '</strong></td><td style="font-size:13px;">' + l.action + '</td><td>' + pl + '</td></tr>';
      }).join('') + '</tbody></table></div>';
  } catch (e) {
    container.innerHTML = '<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ' + e.message + '</p></div>';
  }
}

// ===== Admin: 分享管理 =====
async function renderAdminShares(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const shares = await apiGet('/share/admin/all');
    if (!shares || shares.length === 0) {
      container.innerHTML = `<div class="empty"><div class="empty-icon">🔗</div><p>暂无分享链接</p></div>`;
      return;
    }
    const statusLabel = s => ({ active: '有效', expired: '已过期', revoked: '已收回' }[s] || s);
    const statusCls = s => ({ active: 'badge-completed', expired: 'badge-pending', revoked: 'badge-rejected' }[s] || '');
    const typeLabel = t => ({ project: '项目', sub_task: '子任务' }[t] || t);

    container.innerHTML = `
      <div class="card">
        <div class="card-header" style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <h3 style="font-size:15px;font-weight:600;">🔗 全部分享链接</h3>
          <span style="font-size:12px;color:var(--gray-500);">共 ${shares.length} 条</span>
        </div>
        <div style="overflow-x:auto;">
          <table style="width:100%;border-collapse:collapse;font-size:13px;">
            <thead><tr>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">ID</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">类型</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">目标</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">创建人</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">创建时间</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">过期</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">状态</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">访问</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">操作</th>
            </tr></thead>
            <tbody>
              ${shares.map(s => {
                const isActive = s.status === 'active';
                return '<tr>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + s.id + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + typeLabel(s.targetType) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><a href="javascript:void(0)" onclick="openProjectDetail(' + s.targetId + ')" style="color:var(--primary);text-decoration:none;">#' + s.targetId + '</a></td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + (s.createdByName || s.createdBy || '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.createdAt ? s.createdAt.substring(0,16) : '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.expiresAt ? s.expiresAt.substring(0,10) : '永不过期') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><span class="badge ' + statusCls(s.status) + '">' + statusLabel(s.status) + '</span></td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (s.viewCount || 0) + ' 次</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);white-space:nowrap;">' +
                    (isActive
                      ? '<button class="btn btn-outline btn-sm" onclick="adminEditShare(' + s.id + ')" style="margin-right:4px;">编辑</button>' +
                        '<button class="btn btn-danger btn-sm" onclick="adminRevokeShare(' + s.id + ')">收回</button>'
                      : '<span style="font-size:12px;color:var(--gray-400);">-</span>') +
                  '</td></tr>';
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  } catch (e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

async function adminEditShare(id) {
  document.getElementById('shareEditModal')?.remove();
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'shareEditModal';
  modal.innerHTML = `
    <div class="modal" style="max-width:420px;">
      <div class="modal-header">
        <div class="modal-title">✏️ 编辑分享链接 #${id}</div>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label class="form-label">过期时间</label>
          <select class="form-select" id="editShareExpires">
            <option value="3600">1 小时后</option>
            <option value="86400">24 小时后</option>
            <option value="604800" selected>7 天后</option>
            <option value="2592000">30 天后</option>
            <option value="-1">永不过期</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">访问密码</label>
          <input type="text" class="form-input" id="editSharePassword" placeholder="留空不修改，输入新密码覆盖" style="text-align:center;">
          <div style="font-size:11px;color:var(--gray-400);text-align:center;margin-top:4px;">留空 = 不修改密码 / 清空输入框内容并保存 = 清除密码</div>
        </div>
        <div id="editShareError" style="color:var(--danger);font-size:13px;text-align:center;margin-top:12px;display:none;"></div>
      </div>
      <div class="modal-footer" style="justify-content:center;">
        <button class="btn btn-primary" onclick="doAdminUpdateShare(${id})">💾 保存</button>
        <button class="btn btn-outline" onclick="closeM('shareEditModal')">取消</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
}

async function doAdminUpdateShare(id) {
  const expiresEl = document.getElementById('editShareExpires');
  const passwordEl = document.getElementById('editSharePassword');
  const errEl = document.getElementById('editShareError');
  errEl.style.display = 'none';

  const expiresVal = expiresEl.value;
  const expiresIn = expiresVal === '-1' ? -1 : parseInt(expiresVal);
  // password：空字符串表示留空不传（不修改），有值表示修改
  const password = passwordEl.value;

  try {
    await apiPut('/share/admin/' + id, { expiresIn, password: password || null });
    showAdminToast('✅ 更新成功', 'success');
    closeM('shareEditModal');
    await renderAdminShares(document.getElementById('adminContent'));
  } catch(e) {
    errEl.textContent = e.message || '更新失败';
    errEl.style.display = '';
  }
}

async function adminRevokeShare(id) {
  if (!confirm('确定要收回此分享链接吗？收回后原链接将无法访问。')) return;
  try {
    const r = await apiPost('/share/admin/' + id + '/revoke', {});
    showAdminToast('✅ ' + (r.message || '已收回'), 'success');
    await renderAdminShares(document.getElementById('adminContent'));
  } catch(e) {
    alert('操作失败: ' + e.message);
  }
}

// ===== Admin: 文件存储 =====
async function renderAdminFileStorage(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const [stats, archived] = await Promise.all([
      apiGet('/admin/files/stats'),
      apiGet('/admin/files/archived'),
    ]);

    function fmt(s) {
      if (!s) return '0 B';
      if (s >= 1073741824) return (s / 1073741824).toFixed(1) + ' GB';
      if (s >= 1048576) return (s / 1048576).toFixed(1) + ' MB';
      if (s >= 1024) return (s / 1024).toFixed(0) + ' KB';
      return s + ' B';
    }

    const diskPercent = stats.diskTotalBytes ? ((stats.diskUsedBytes / stats.diskTotalBytes) * 100).toFixed(0) : 0;

    container.innerHTML = `
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:16px;">
        <div class="admin-stat-card">
          <div class="admin-stat-icon">💾</div>
          <div class="admin-stat-value">${fmt(stats.localSizeBytes)}</div>
          <div class="admin-stat-label">本地文件</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📦</div>
          <div class="admin-stat-value">${stats.archivedCount || 0} 个</div>
          <div class="admin-stat-label">已归档</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">🗄️</div>
          <div class="admin-stat-value">${fmt(stats.diskUsedBytes)} / ${fmt(stats.diskTotalBytes)}</div>
          <div class="admin-stat-label">磁盘使用</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">${stats.nasEnabled ? '✅' : '❌'}</div>
          <div class="admin-stat-value">${stats.nasHost || '未配置'}</div>
          <div class="admin-stat-label">NAS 状态</div>
        </div>
      </div>
      <div style="margin-bottom:16px;">
        <div style="background:var(--gray-200);border-radius:8px;height:12px;overflow:hidden;">
          <div style="background:${diskPercent > 80 ? 'var(--danger)' : diskPercent > 60 ? '#EF9F27' : '#639922'};width:${diskPercent}%;height:100%;border-radius:8px;transition:width 0.3s;"></div>
        </div>
        <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--gray-500);margin-top:4px;">
          <span>已用 ${diskPercent}%</span>
          <span>剩余 ${fmt(stats.diskFreeBytes)}</span>
        </div>
      </div>
      <div style="display:flex;gap:8px;margin-bottom:16px;">
        <button class="btn btn-primary" onclick="manualArchive()">📤 立即归档</button>
      </div>
      <div class="card">
        <div class="card-header" style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--gray-200);">
          <h3 style="font-size:15px;font-weight:600;">📦 已归档文件</h3>
          <span style="font-size:12px;color:var(--gray-500);">共 ${archived.length} 个</span>
        </div>
        <div style="overflow-x:auto;">
          <table style="width:100%;border-collapse:collapse;font-size:13px;">
            <thead><tr>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">原始文件名</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">原始大小</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">压缩后</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">归档时间</th>
              <th style="padding:10px 12px;text-align:left;border-bottom:1px solid var(--gray-200);color:var(--gray-500);font-weight:500;">操作</th>
            </tr></thead>
            <tbody>
              ${archived.length === 0 ? '<tr><td colspan="5" style="text-align:center;padding:24px;color:var(--gray-400);">暂无归档文件</td></tr>' :
                archived.map(f => '<tr>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + escHtml(f.originalName) + '">' + escHtml(f.originalName) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + fmt(f.fileSize) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);">' + fmt(f.archiveSize) + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);font-size:12px;">' + (f.archivedAt ? f.archivedAt.substring(0,16) : '-') + '</td>' +
                  '<td style="padding:8px 12px;border-bottom:1px solid var(--gray-100);"><button class="btn btn-outline btn-sm" onclick="restoreArchivedFile(' + f.id + ')">恢复本地</button></td>' +
                '</tr>').join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  } catch(e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

async function manualArchive() {
  if (!confirm('确定要手动归档过期文件吗？超过 90 天的文件将被压缩并推送到 NAS。')) return;
  try {
    const r = await apiPost('/admin/files/archive', {});
    showAdminToast('✅ 归档完成: 成功 ' + (r.success || 0) + ' 个, 失败 ' + (r.fail || 0) + ' 个', r.fail > 0 ? 'warning' : 'success');
    await renderAdminFileStorage(document.getElementById('adminContent'));
  } catch(e) {
    alert('归档失败: ' + e.message);
  }
}

async function restoreArchivedFile(fileId) {
  if (!confirm('确定要从 NAS 恢复此文件到本地吗？')) return;
  try {
    await apiPost('/admin/files/restore/' + fileId, {});
    showAdminToast('✅ 文件已恢复', 'success');
    await renderAdminFileStorage(document.getElementById('adminContent'));
  } catch(e) {
    alert('恢复失败: ' + e.message);
  }
}

// ==================== 文件下载选项 ====================

/** 显示文件下载选项面板（直接下载 / 复制链接） */
function showDownloadOptions(fileUrl, fileName, fileSize) {
  // 移除已有面板
  document.getElementById('downloadOptionPanel')?.remove();

  function fmtSize(bytes) {
    if (!bytes) return '';
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  const ext = fileName ? fileName.split('.').pop().toUpperCase() : '';
  const token = localStorage.getItem('design_pm_token') || '';
  const fullUrl = (fileUrl.startsWith('http') ? fileUrl : window.location.origin + fileUrl)
    + (fileUrl.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);

  const panel = document.createElement('div');
  panel.id = 'downloadOptionPanel';
  panel.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;display:flex;align-items:center;justify-content:center;';
  panel.innerHTML = `
    <div onclick="closeDownloadOptions()" style="position:absolute;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.3);"></div>
    <div style="position:relative;background:#fff;border-radius:16px;width:420px;max-width:90vw;box-shadow:0 8px 40px rgba(0,0,0,0.15);overflow:hidden;">
      <div style="padding:24px 24px 0;">
        <div style="display:flex;align-items:flex-start;gap:12px;margin-bottom:20px;">
          <div style="width:48px;height:48px;border-radius:12px;background:#E6F1FB;display:flex;align-items:center;justify-content:center;font-size:22px;flex-shrink:0;">📄</div>
          <div style="min-width:0;flex:1;">
            <p style="font-weight:600;font-size:14px;color:#1f2937;margin:0 0 4px 0;word-break:break-all;line-height:1.4;">${escHtml(fileName || '')}</p>
            <p style="font-size:12px;color:#6b7280;margin:0;">${fileSize ? fmtSize(fileSize) + ' · ' : ''}${ext}</p>
          </div>
          <button onclick="closeDownloadOptions()" style="background:none;border:none;cursor:pointer;font-size:18px;color:#9ca3af;padding:4px;line-height:1;">✕</button>
        </div>
      </div>
      <div style="padding:0 24px 24px;">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:16px;">
          <button onclick="doDirectDownload('${escHtml(fullUrl)}');closeDownloadOptions();"
            style="display:flex;align-items:center;justify-content:center;gap:6px;padding:12px;border-radius:10px;border:1px solid #e5e7eb;background:#fff;cursor:pointer;font-size:14px;color:#1f2937;transition:background 0.15s;"
            onmouseenter="this.style.background='#f9fafb'" onmouseleave="this.style.background='#fff'">
            <span style="font-size:18px;">⬇️</span> 直接下载
          </button>
          <button onclick="doCopyDownloadLink('${escHtml(fullUrl)}', this);"
            style="display:flex;align-items:center;justify-content:center;gap:6px;padding:12px;border-radius:10px;border:1px solid #e5e7eb;background:#fff;cursor:pointer;font-size:14px;color:#1f2937;transition:background 0.15s;"
            onmouseenter="this.style.background='#f9fafb'" onmouseleave="this.style.background='#fff'">
            <span style="font-size:18px;">🔗</span> <span id="copyBtnLabel">复制下载地址</span>
          </button>
        </div>
        <div style="padding:10px 14px;background:#f9fafb;border-radius:10px;font-size:12px;color:#9ca3af;display:flex;align-items:center;gap:8px;">
          <span style="flex-shrink:0;">🔗</span>
          <span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" title="${escHtml(fullUrl)}">${escHtml(fullUrl)}</span>
          <button onclick="copyUrlOnly('${escHtml(fullUrl)}', this)" style="background:none;border:none;cursor:pointer;font-size:12px;color:#3370FF;padding:2px 6px;border-radius:4px;flex-shrink:0;">复制</button>
        </div>
      </div>
    </div>`;
  document.body.appendChild(panel);
}

function closeDownloadOptions() {
  document.getElementById('downloadOptionPanel')?.remove();
}

/** 直接下载：添加 ?download=true 参数触发浏览器保存 */
function doDirectDownload(url) {
  const link = document.createElement('a');
  link.href = url + (url.includes('?') ? '&' : '?') + 'download=true';
  link.target = '_blank';
  link.rel = 'noopener';
  link.click();
}

/** 给 URL 追加 ?download=true 参数 */
function appendDownloadParam(url) {
  return url + (url.includes('?') ? '&' : '?') + 'download=true';
}

/** 复制下载链接（自动带上 ?download=true，粘贴到浏览器直接触发下载） */
async function doCopyDownloadLink(url, btn) {
  const dlUrl = appendDownloadParam(url);
  try {
    await navigator.clipboard.writeText(dlUrl);
    const label = btn.querySelector('#copyBtnLabel') || btn.querySelector('span:last-child');
    if (label) {
      const orig = label.textContent;
      label.textContent = '✅ 已复制';
      setTimeout(() => label.textContent = orig, 2000);
    }
  } catch(e) {
    // fallback
    const ta = document.createElement('textarea');
    ta.value = dlUrl;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    const label = btn.querySelector('#copyBtnLabel') || btn.querySelector('span:last-child');
    if (label) {
      const orig = label.textContent;
      label.textContent = '✅ 已复制';
      setTimeout(() => label.textContent = orig, 2000);
    }
  }
}

/** 复制带下载参数的链接（底部栏） */
async function copyUrlOnly(url, btn) {
  const dlUrl = appendDownloadParam(url);
  try {
    await navigator.clipboard.writeText(dlUrl);
    btn.textContent = '✅';
    setTimeout(() => btn.textContent = '复制', 2000);
  } catch(e) {
    const ta = document.createElement('textarea');
    ta.value = dlUrl;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    btn.textContent = '✅';
    setTimeout(() => btn.textContent = '复制', 2000);
  }
}

/** 生成文件操作按钮 HTML（用于嵌入到列表/卡片中） */
function renderFileActions(fileUrl, fileName, fileSize) {
  const fullUrl = fileUrl.startsWith('http') ? fileUrl : window.location.origin + fileUrl;
  return `<button class="btn btn-outline btn-sm" onclick="showDownloadOptions('${escHtml(fullUrl)}','${escHtml(fileName || '')}',${fileSize || 0})" title="下载选项">⬇️</button>`;
}

// ===== Admin: 工作量 =====
let currentWorkloadRange = 'month';

async function renderAdminWorkload(container) {
  container.innerHTML = `<div class="loading">加载中</div>`;
  try {
    const data = await apiGet('/admin/workload/timeline?range=' + currentWorkloadRange);
    const summary = data._summary || {};
    const rangeLabel = summary.rangeLabel || '本月';
    const rangeOptions = [
      { key: 'day', label: '今日', icon: '☀️' },
      { key: 'week', label: '本周', icon: '📅' },
      { key: 'month', label: '本月', icon: '📆' },
      { key: 'quarter', label: '本季度', icon: '🗓️' },
      { key: 'half-year', label: '本半年', icon: '📋' },
      { key: 'year', label: '本年度', icon: '📊' },
    ];

    container.innerHTML = `
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:14px;font-weight:500;color:#374151;">📊 工作量看板</span>
        <span style="font-size:12px;color:var(--gray-400);margin-right:4px;">时间范围:</span>
        ${rangeOptions.map(o => `
          <button onclick="switchWorkloadRange('${o.key}')"
            style="padding:5px 14px;border-radius:8px;border:${o.key === currentWorkloadRange ? '2px solid #3370FF' : '1px solid var(--gray-200)'};
            background:${o.key === currentWorkloadRange ? '#E6F1FB' : '#fff'};
            color:${o.key === currentWorkloadRange ? '#1E40AF' : '#374151'};
            font-size:13px;cursor:pointer;white-space:nowrap;">${o.icon} ${o.label}</button>
        `).join('')}
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:20px;">
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📁</div>
          <div class="admin-stat-value">${summary.totalProjectsCreated || 0}</div>
          <div class="admin-stat-label">新建项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">✅</div>
          <div class="admin-stat-value">${summary.totalProjectsCompleted || 0}</div>
          <div class="admin-stat-label">完成项目</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">📝</div>
          <div class="admin-stat-value">${summary.totalTasksAssigned || 0}</div>
          <div class="admin-stat-label">新分子任务</div>
        </div>
        <div class="admin-stat-card">
          <div class="admin-stat-icon">✅</div>
          <div class="admin-stat-value">${summary.totalTasksCompleted || 0}</div>
          <div class="admin-stat-label">完成任务</div>
        </div>
      </div>`;

    const roleOrder = ['sales', 'planner', 'designer', 'supplychain'];

    for (const role of roleOrder) {
      const r = data[role];
      if (!r || !r.users || r.users.length === 0) continue;

      let html = '<div class="card" style="margin-bottom:16px;">';
      html += `<div style="display:flex;justify-content:space-between;align-items:center;padding:14px 16px;border-bottom:1px solid var(--gray-200);">
        <div><span style="font-size:16px;margin-right:6px;">${r.icon || '👤'}</span><strong style="font-size:15px;">${r.label || role}</strong>
        <span style="font-size:12px;color:var(--gray-500);margin-left:8px;">${r.totalUsers} 人</span></div></div>`;

      // 表头
      const isWorker = (role === 'designer' || role === 'supplychain');
      html += `<div style="display:flex;padding:8px 16px;font-size:11px;color:var(--gray-500);border-bottom:1px solid var(--gray-100);">
        <div style="min-width:140px;">姓名</div>
        <div style="flex:1;display:flex;gap:16px;">
          <span style="width:60px;">${isWorker ? '新分配' : '新建'}</span>
          <span style="width:60px;">完成</span>
          <span style="width:60px;">完成率</span>
        </div>
      </div>`;

      for (const u of r.users) {
        const created = u.created || u.assigned || 0;
        const completed = u.completed || 0;
        const rate = created > 0 ? Math.round((completed / created) * 100) + '%' : '-';

        html += `<div style="display:flex;align-items:center;padding:10px 16px;border-bottom:1px solid var(--gray-100);">
          <div style="min-width:140px;flex-shrink:0;">
            <div style="font-size:13px;font-weight:500;color:#1f2937;">${escHtml(u.name)}</div>
            <div style="font-size:11px;color:var(--gray-400);">${escHtml(u.userId)}</div>
          </div>
          <div style="flex:1;display:flex;gap:16px;align-items:center;">
            <span style="width:60px;font-size:13px;font-weight:600;color:#374151;">${created}</span>
            <span style="width:60px;font-size:13px;font-weight:600;color:#065F46;">${completed}</span>
            <span style="width:60px;font-size:12px;color:${rate === '-' ? 'var(--gray-400)' : '#374151'};">${rate}</span>
          </div>
          <!-- 进度条 -->
          <div style="flex:1;max-width:120px;background:var(--gray-200);border-radius:6px;height:8px;overflow:hidden;">
            <div style="background:${created > 0 ? '#639922' : '#e5e7eb'};width:${created > 0 ? Math.min(100, (completed / created) * 100) : 0}%;height:100%;border-radius:6px;transition:width 0.3s;"></div>
          </div>
        </div>`;
      }
      html += '</div>';
      container.innerHTML += html;
    }
  } catch (e) {
    container.innerHTML = `<div class="empty"><div class="empty-icon">❌</div><p>加载失败: ${e.message}</p></div>`;
  }
}

function switchWorkloadRange(range) {
  currentWorkloadRange = range;
  const container = document.getElementById('adminContent');
  if (container) renderAdminWorkload(container);
}

// ==================== 启动 ====================
document.addEventListener('DOMContentLoaded', initApp);
