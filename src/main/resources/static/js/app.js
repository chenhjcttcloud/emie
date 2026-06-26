// ==================== 设计项目管理系统 - 应用逻辑 ====================

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
  if (!r.ok) throw new Error(`PUT ${url} failed: ${r.status}`);
  return r.json();
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
let currentRole = '';
let currentUserId = '';
let currentView = 'dashboard';
let currentFilter = 'all';
let USERS = {};
let APP_CACHE = { orders: [] };

async function initApp() {
  // 检查是否有保存的 token
  const token = localStorage.getItem('design_pm_token');
  if (token) {
    try {
      const r = await fetch('/api/auth/me', { headers: { 'X-Auth-Token': token } });
      if (r.ok) {
        AUTH_USER = await r.json();
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
  const email = document.getElementById('regEmail').value.trim();
  if (!/^[\w.-]+@[\w.-]+\.\w{2,}$/.test(email)) {
    alert('请输入正确的邮箱地址'); return;
  }
  const captchaKey = document.getElementById('captchaImg').dataset.key;
  const captchaCode = document.getElementById('regCaptcha').value.trim();
  if (!captchaCode) {
    alert('请先输入图形验证码'); return;
  }

  const btn = document.getElementById('emailBtn');
  btn.disabled = true;
  btn.textContent = '发送中...';

  fetch('/api/email-code/send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, captchaKey, captchaCode }),
  }).then(r => r.json()).then(d => {
    if (d.error) {
      alert(d.error);
      btn.disabled = false;
      btn.textContent = '获取验证码';
      refreshCaptcha();
    } else {
      let sec = 60;
      btn.textContent = sec + 's';
      const timer = setInterval(() => {
        sec--;
        if (sec <= 0) {
          clearInterval(timer);
          btn.disabled = false;
          btn.textContent = '重新获取';
        } else {
          btn.textContent = sec + 's';
        }
      }, 1000);
    }
  }).catch(() => {
    alert('网络错误');
    btn.disabled = false;
    btn.textContent = '获取验证码';
  });
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
    emailCode: document.getElementById('regEmailCode').value.trim(),
    password: document.getElementById('regPassword').value,
  };

  // ========== 前端校验 ==========
  if (!data.id || !data.name || !data.phone || !data.email || !data.emailCode || !data.password) {
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
  if (!/^\d{6}$/.test(data.emailCode)) {
    errEl.textContent = '邮箱验证码为6位数字'; errEl.style.display = ''; return;
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
      return;
    }
    // 注册成功，自动登录
    localStorage.setItem('design_pm_token', result.token);
    AUTH_USER = { userId: result.userId, name: result.name, role: result.role, title: result.title };
    showApp();
  } catch (e) {
    errEl.textContent = '网络错误，请重试';
    errEl.style.display = '';
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
          logoEl.innerHTML = `<img src="${cfg['app.logo']}" style="height:32px;width:auto;vertical-align:middle;margin-right:8px;" alt="logo"><span>${cfg['app.title'] || '设计项目管理'}</span>`;
        } else if (cfg['app.logoEmoji']) {
          logoEl.innerHTML = `${cfg['app.logoEmoji']} ${cfg['app.title'] || '设计项目管理'}<span>${cfg['app.subtitle'] || 'Design Project Management'}</span>`;
        } else {
          logoEl.innerHTML = `🎨 ${cfg['app.title'] || '设计项目管理'}<span>${cfg['app.subtitle'] || 'Design Project Management'}</span>`;
        }
      }
    }
  } catch(e) { /* ignore */ }

  document.getElementById('userDisplay').textContent = `${AUTH_USER.name}（${AUTH_USER.role === 'sales' ? '销售' : AUTH_USER.role === 'planner' ? '产品企划' : AUTH_USER.role === 'designer' ? '设计师' : AUTH_USER.role === 'superior' ? '上级' : '管理员'}）`;
  currentRole = AUTH_USER.role;
  currentUserId = AUTH_USER.userId;
  // 加载用户列表（用于下拉框）
  try { USERS = await apiGet('/users'); } catch(e) { USERS = {}; }
  renderSidebar();
  render();
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

function handleLogout() {
  const token = localStorage.getItem('design_pm_token');
  if (token) {
    fetch('/api/auth/logout', { method: 'POST', headers: { 'X-Auth-Token': token } }).catch(() => {});
  }
  localStorage.removeItem('design_pm_token');
  localStorage.removeItem('design_pm_create_draft');
  AUTH_USER = null;
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
  return { sales: '需求方/销售', planner: '产品企划', designer: '设计师', superior: '上级', admin: '管理员' }[r] || r;
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
    // 销售：工作台、渠道定制单、我的子任务、待评分
    navs.push({ view: 'dashboard', icon: '📊', label: '工作台', badge: '' });
    navs.push({ view: 'channel', icon: '📦', label: '渠道定制单', badge: 'badgeChannel' });
    navs.push({ view: 'tasks', icon: '📌', label: '我的子任务', badge: 'badgeMyTasks' });
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
    approved: { label: '已通过', cls: 'badge-completed', icon: '✅' },
    rejected: { label: '已驳回', cls: 'badge-rejected', icon: '↩️' },
  }[status] || { label: status, cls: '', icon: '❓' };
}

// ==================== 格式化 ====================
function formatDate(d) {
  if (!d) return '-';
  const m = d.match(/^\d{4}-\d{2}-\d{2}/);
  return m ? m[0] : d;
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
}

// 标记表单内容已修改（用于保存提示判断）
function formModified() { _formModified = true; }

// 安全创建模态框（防止重复点击出现多个）
function openModal(id) {
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
async function render() {
  const main = document.getElementById('mainContent');
  showLoading(main);

  try {
    const role = currentRole;
    const uid = getCurrentUserId();

    // 预加载项目列表用于徽章更新
    let orders = [];
    try { orders = await apiGet(`/projects?role=${role}&userId=${uid}`); } catch(e) {}

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
      for (const order of orders) {
        const detail = await apiGet(`/projects/${order.id}`);
        if (!detail.tasks) continue;
        for (const t of detail.tasks) {
      // 待评分
      if (t.status === 'approved' && t.scoringRecords) {
        if (role === 'admin') {
          // 管理员：统计所有未评分的记录
          const hasPending = t.scoringRecords.some(sr => sr.aesthetics === null);
          if (hasPending) pendingScoreCount++;
        } else {
          const needMe = detail.type === 'channel_custom'
            ? (role === 'sales' || role === 'planner')
            : (role === 'planner');
          if (needMe) {
            const myRecord = t.scoringRecords.find(sr => sr.role === role);
            if (myRecord && myRecord.aesthetics === null) pendingScoreCount++;
          }
        }
      }
      // 渠道定制单：企划已评分但销售尚未确认评分 → 销售待评分
      if (detail.type === 'channel_custom' && t.status === 'planner_approved') {
        if (role === 'admin' || role === 'sales') {
          pendingScoreCount++;
        }
      }
        // 我的子任务（销售：渠道定制单中待处理的子任务）
        if (role === 'sales' && detail.type === 'channel_custom' && ['pending','accepted','rejected','delivered'].includes(t.status)) {
          myTaskCount++;
        }
        // 我的子任务（设计师）
        if (role === 'designer' && t.designerId === uid && ['pending','accepted','rejected'].includes(t.status)) {
          myTaskCount++;
        }
      }
    // 我的子任务（企划/上级/管理员：进行中的项目数）
  }
  if (role !== 'sales' && role !== 'designer') {
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

    if (role === 'designer') {
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
          if (myRecord && myRecord.aesthetics === null) pendingScoreCount++;
        }
      }
    }
    const elScoring = document.getElementById('badgeScoring');
    if (elScoring) elScoring.textContent = pendingScoreCount;

    let myTasks = 0;
    if (currentRole === 'designer') {
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
    } else {
      // 企划/上级/管理员: 显示进行中的项目数
      myTasks = orders.filter(o =>
        o.status === 'in_progress' || o.status === 'planner_accepted'
      ).length;
    }
    document.getElementById('badgeMyTasks').textContent = myTasks;
  } catch (e) {
    console.error('徽章更新失败:', e);
  }
}

// ==================== 工作台 ====================
async function renderDashboard(main, role, uid) {
  const [stats, orders] = await Promise.all([
    apiGet(`/dashboard/stats?role=${role}&userId=${uid}`),
    apiGet(`/projects?role=${role}&userId=${uid}`),
  ]);

  const channel = orders.filter(o => o.type === 'channel_custom');
  const regular = orders.filter(o => o.type === 'regular');

  let designerHtml = '';
  if (currentRole === 'planner') {
    designerHtml = await renderDesignerPanel();
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
    ${designerHtml}
    ${orders.length === 0 ? `<div class="empty"><div class="empty-icon">📭</div><p>暂无您负责的项目</p></div>` : ''}
    ${renderProjectSummary(channel, '📦 渠道定制单')}
    ${currentRole !== 'sales' ? renderProjectSummary(regular, '🏭 公司常规品') : ''}
  `;
}

async function renderDesignerPanel() {
  try {
    const status = await apiGet('/projects/designer-status');
    const designers = Object.values(status);
    const busy = designers.filter(d => d.busy);
    const idle = designers.filter(d => !d.busy);

    return `<div class="designer-status-panel">
      <div class="card-header">
        <div class="card-title">👥 设计师状态看板</div>
        <div style="display:flex;gap:12px;font-size:13px;">
          <span><span class="badge badge-busy" style="margin-right:4px;">🟡</span>忙碌 ${busy.length}人</span>
          <span><span class="badge badge-idle" style="margin-right:4px;">🟢</span>空闲 ${idle.length}人</span>
        </div>
      </div>
      <div class="designer-grid">
        ${designers.map(d => `
        <div class="designer-card ${d.busy ? 'busy' : 'idle'}">
          <div class="designer-avatar">${d.name.charAt(0)}</div>
          <div class="designer-info">
            <div class="designer-name">${d.name}</div>
            <div class="designer-title">${d.title}</div>
            <div class="designer-tasks">
              ${d.busy ? `进行中：${d.activeTasks.length}个子任务` : `🟢 空闲`}
            </div>
            ${d.activeTasks.length > 0 ? `<div style="margin-top:4px;display:flex;flex-wrap:wrap;gap:4px;">
              ${d.activeTasks.map(t => `<span style="font-size:10px;background:#fff;padding:1px 6px;border-radius:4px;cursor:pointer;" onclick="openProjectDetail('${t.projectId}')" title="${t.name}">${t.name.substring(0, 8)}...</span>`).join('')}
            </div>` : ''}
          </div>
        </div>`).join('')}
      </div>
    </div>`;
  } catch (e) {
    return '';
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
        <thead><tr><th>项目编号</th><th>需求方</th><th>产品企划</th><th>子任务数</th><th>进度</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>${display.map(o => {
          const st = getProjectStatusInfo(o.status);
          return `<tr onclick="openProjectDetail(${o.id})" style="cursor:pointer;">
            <td><strong>#${o.id}</strong></td>
            <td>${o.salesName || '-'}</td>
            <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
            <td>${o.taskCount}（完成${o.approvedTaskCount}）</td>
            <td><div class="progress-bar" style="width:80px;"><div class="progress-fill" style="width:${o.progressPercent}%;"></div></div></td>
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
  // 设计师查看渠道/常规品时只显示已参与的项目（已接单，排除待认领）
  const participating = role === 'designer' ? '&participating=true' : '';
  let orders = await apiGet(`/projects?role=${role}&userId=${uid}${participating}`);
  let title = '全部项目';
  if (type === 'channel_custom') { orders = orders.filter(o => o.type === 'channel_custom'); title = '📦 渠道定制单'; }
  else if (type === 'regular') { orders = orders.filter(o => o.type === 'regular'); title = '🏭 公司常规品'; }
  else title = '📋 全部项目';

  APP_CACHE.orders = orders;

  main.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;">
      <h2 style="font-size:22px;">${title} <span style="font-size:14px;color:var(--gray-400);font-weight:400;">(${orders.length})</span></h2>
      <div style="display:flex;gap:8px;">
        ${currentRole === 'sales' ? `<button class="btn btn-primary" onclick="openCreateProject('channel_custom')">➕ 新建渠道定制项目</button>` : ''}
        ${currentRole === 'planner' && type === 'regular' ? `<button class="btn btn-primary" onclick="openCreateProject('regular')">➕ 新建常规品设计项目</button>` : ''}
      </div>
    </div>
    <div class="filter-bar">
      <select class="form-select" onchange="filterProjectList()" style="min-width:120px;" id="projectStatusFilter">
        <option value="all">全部状态</option>
        <option value="in_progress">进行中</option>
        <option value="completed">已完成</option>
        <option value="completed_pending_score">待评分</option>
        <option value="pending_planner">待企划接单</option>
      </select>
      <input class="form-input" placeholder="🔍 搜索编号/描述..." oninput="filterProjectList()" style="min-width:200px;" id="searchInput">
      <input type="date" class="form-input" id="filterDateStart" style="min-width:140px;" title="开始日期">
      <span style="color:var(--gray-400);font-size:13px;">~</span>
      <input type="date" class="form-input" id="filterDateEnd" style="min-width:140px;" title="结束日期">
      <button class="btn btn-primary btn-sm" onclick="filterProjectList()">🔍 查询</button>
      <button class="btn btn-outline btn-sm" onclick="resetProjectFilters()">↺ 重置</button>
    </div>
    <div class="card" id="projectListContainer">${renderProjectTable(orders)}</div>
  `;
}

function renderProjectTable(orders) {
  if (!orders.length) return `<div class="empty"><div class="empty-icon">📭</div><p>暂无项目</p></div>`;
  return `<div class="table-wrap"><table>
    <thead><tr><th>项目编号</th><th>类型</th><th>需求方</th><th>产品企划</th><th>子任务</th><th>要求时间</th><th>状态</th><th>操作</th></tr></thead>
    <tbody>${orders.map(o => {
      const st = getProjectStatusInfo(o.status);
      return `<tr onclick="openProjectDetail(${o.id})" style="cursor:pointer;">
        <td><strong>#${o.id}</strong></td>
        <td>${o.type === 'channel_custom' ? '📦 渠道定制' : '🏭 常规品'}</td>
        <td>${o.salesName || '-'}</td>
        <td>${o.plannerName || '<span style="color:var(--gray-400);">未指定</span>'}</td>
        <td>${o.approvedTaskCount}/${o.taskCount}</td>
        <td>${formatDate(o.deadline)}</td>
        <td><span class="badge ${st.cls}">${st.label}</span></td>
        <td style="white-space:nowrap;">
          <button class="btn btn-outline btn-sm" onclick="event.stopPropagation();openProjectDetail(${o.id})" style="color:#D97706;border-color:#FCD34D;">查看</button>
          ${o.status === 'pending_planner' && currentRole === 'planner' ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();plannerAcceptFromList(${o.id})" style="color:var(--success);border-color:var(--success);">接单</button>` : ''}
          ${['planner_accepted','in_progress','paused'].includes(o.status) ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();${o.status === 'paused' ? `resumeProject(${o.id})` : `pauseProject(${o.id})`}" style="font-size:11px;color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};border-color:${o.status === 'paused' ? 'var(--success)' : 'var(--primary)'};">${o.status === 'paused' ? '继续' : '暂停'}</button>` : ''}
          ${['pending_planner','planner_accepted','in_progress','paused'].includes(o.status) ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();terminateProject(${o.id})" style="font-size:11px;color:var(--danger);border-color:var(--danger);">终止</button>` : ''}
          ${o.status === 'pending_terminate' ? `<button class="btn btn-outline btn-sm" onclick="event.stopPropagation();terminateProject(${o.id})" style="font-size:11px;color:var(--danger);border-color:var(--danger);">确认终止</button><button class="btn btn-outline btn-sm" onclick="event.stopPropagation();cancelTerminate(${o.id})" style="font-size:11px;color:var(--gray-600);border-color:var(--gray-300);">取消终止</button>` : ''}
        </td>
      </tr>`;
    }).join('')}</tbody>
  </table></div>`;
}

function filterProjectList() {
  let filtered = [...APP_CACHE.orders];

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
  if (role === 'designer') {
    // 设计师: 展示分配给自己的子任务卡片
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
  const orders = await apiGet(`/projects?role=${role}&userId=${uid}`);
  let pendingTasks = [];

  for (const order of orders) {
    const detail = await apiGet(`/projects/${order.id}`);
    if (!detail.tasks) continue;
    const isChannel = detail.type === 'channel_custom';

    for (const t of detail.tasks) {
      // 待评分展示：approved 未完成评分的任务 + 渠道定制单 planner_approved 等待销售确认的任务
      const isPendingScore = (t.status === 'approved' && t.scoringRecords)
        || (isChannel && t.status === 'planner_approved');

      if (!isPendingScore) continue;

      if (role === 'admin') {
        // 管理员：查看所有未完成评分记录（包含待销售确认的渠道任务）
        const anyPending = t.scoringRecords
          ? t.scoringRecords.some(sr => sr.aesthetics === null)
          : true;
        if (t.status === 'planner_approved' || anyPending) {
          pendingTasks.push({
            ...t,
            projectId: detail.id,
            projectType: detail.type,
            projectName: detail.productRequirements,
            isPending: true,
            isAdminView: true,
            myRecord: null,
          });
        }
      } else if (role === 'sales' && isChannel && t.status === 'planner_approved') {
        // 销售：渠道定制单中企划已评分、待销售确认评分的任务
        pendingTasks.push({
          ...t,
          projectId: detail.id,
          projectType: detail.type,
          projectName: detail.productRequirements,
          isPending: true,
          isDesignerView: true,
          myRecord: null,
        });
      } else if (role === 'designer' && t.status === 'approved' && t.scoringRecords) {
        // 设计师：只查看分配给自己的子任务的评分
        if (t.designerId !== uid) continue;
        const hasPending = t.scoringRecords.some(sr => sr.aesthetics === null);
        pendingTasks.push({
          ...t,
          projectId: detail.id,
          projectType: detail.type,
          projectName: detail.productRequirements,
          isPending: hasPending,
          myRecord: t.scoringRecords.find(sr => sr.role === 'designer') || null,
          isDesignerView: true,
        });
      } else if (t.status === 'approved' && t.scoringRecords) {
        const hasPending = t.scoringRecords.some(sr => sr.aesthetics === null);
        pendingTasks.push({
          ...t,
          projectId: detail.id,
          projectType: detail.type,
          projectName: detail.productRequirements,
          isPending: hasPending,
          myRecord: t.scoringRecords.find(sr => sr.role === 'designer') || null,
          isDesignerView: true,
        });
      } else {
        // 当前角色是否有评分权限
        const needScoringRole = isChannel
          ? ['sales', 'planner']
          : ['planner'];
        if (!needScoringRole.includes(role)) continue;

        // 检查当前角色是否有评分权限且尚未评分
        const myRecord = t.scoringRecords.find(sr => sr.role === role);
        if (!myRecord) continue;
        const isPending = myRecord.aesthetics === null;

        pendingTasks.push({
          ...t,
          projectId: detail.id,
          projectType: detail.type,
          projectName: detail.productRequirements,
          isPending,
          myRecord,
        });
      }
    }
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
              const scored = r.aesthetics !== null;
              return `<span style="padding:4px 10px;border-radius:6px;font-size:12px;background:${scored ? 'var(--success-light)' : 'var(--warning-light)'};color:${scored ? 'var(--success)' : 'var(--warning)'};">
                ${roleLabel(r.role)}: ${scored ? `✅ ${r.aesthetics}/${r.innovation}` : '⏳ 待评分'}
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
  const orders = await apiGet(`/projects?role=designer&userId=${uid}`);
  let myTasks = [];
  const myId = uid;

  for (const order of orders) {
    const detail = await apiGet(`/projects/${order.id}`);
    for (const t of detail.tasks) {
      const isMine = t.designerId === myId;
      const isUnassigned = !t.designerId || t.designerId === '';
      if (isMine) {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30) });
      } else if (isUnassigned && t.status === 'pending') {
        myTasks.push({ ...t, projectId: detail.id, projectType: detail.type, projectName: (detail.productRequirements || '').substring(0, 30), _unassigned: true });
      }
      if (t.status === 'approved' && t.scoringRecords) {
        const needScore = t.scoringRecords.some(sr => sr.aesthetics === null && sr.role === 'designer');
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
        const needScore = t.scoringRecords && t.scoringRecords.some(sr => sr.aesthetics === null && sr.role === 'designer');
        return `<div class="subtask-card" style="${t._unassigned ? 'border-left:3px solid var(--warning);' : ''}">
          <div class="subtask-header">
            <div class="subtask-name">${t._unassigned ? '📋' : tsi.icon} ${t.name}</div>
            <span class="badge ${t._unassigned ? 'badge-pending' : tsi.cls}">${t._unassigned ? '待接单' : tsi.label}</span>
          </div>
          <div style="font-size:12px;color:var(--gray-400);margin-bottom:6px;">📁 项目 #${t.projectId}：${t.projectName}</div>
          <div class="subtask-meta">
            <div class="subtask-meta-item">👤 设计师：<strong>${t.designerName || '<span style="color:var(--warning);">待认领</span>'}</strong></div>
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

function renderScoringMini(task) {
  if (!task.scoringRecords || !task.scoringRecords.length) return '';
  const records = task.scoringRecords;
  const allScored = records.filter(r => r.aesthetics !== null).length;

  let ta = 0, ti = 0, tw = 0;
  records.forEach(r => {
    if (r.aesthetics !== null && r.innovation !== null) {
      ta += r.aesthetics * r.weight;
      ti += r.innovation * r.weight;
      tw += r.weight;
    }
  });
  const overall = tw > 0 ? ((ta + ti) / (tw * 2)).toFixed(1) : null;

  return `<div style="margin-top:10px;padding:12px;background:var(--primary-light);border-radius:8px;">
    <div style="font-size:12px;font-weight:600;color:var(--primary);margin-bottom:6px;">⭐ 评分 (${allScored}/${records.length}人)</div>
    <div style="display:flex;gap:12px;flex-wrap:wrap;font-size:11px;">
      ${records.map(r => `<span style="background:#fff;padding:2px 8px;border-radius:4px;">${roleLabel(r.role)}: ${r.aesthetics !== null ? `✅ ${r.aesthetics}/${r.innovation}` : '<span style="color:var(--gray-400);">⏳ 待评</span>'}</span>`).join('')}
    </div>
    ${overall ? `<div style="margin-top:8px;text-align:center;"><span style="font-size:12px;color:var(--gray-500);">综合：</span><span style="font-size:24px;font-weight:700;color:var(--primary);">${overall}</span></div>` : ''}
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

  if (isImage) {
    c.innerHTML = `<div class="image-preview">${list.map((img, i) =>
      `<div style="position:relative;display:inline-block;">
        <img src="${img.url}" alt="${img.name}" class="img-clickable" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
        <button style="position:absolute;top:-6px;right:-6px;width:20px;height:20px;border-radius:50%;border:none;background:var(--danger);color:#fff;font-size:12px;cursor:pointer;display:flex;align-items:center;justify-content:center;" onclick="removeFileItem('${suffix}',${i},${isImage})">✕</button>
      </div>`).join('')}</div>`;
  } else {
    c.innerHTML = list.map((f, i) =>
      `<div class="file-item"><span>📎 ${f.name}</span><span style="font-size:11px;color:var(--gray-400);">${fmtSize(f.size)}</span><button class="remove-file" onclick="removeFileItem('${suffix}',${i},${isImage})">✕</button></div>`
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
    isImage ? (suffix === 'Create' ? '参考图片' : '交付参考图') : '附件');
}

function handleCreateRefImages(input) { handleFileUpload(input, _createRefImages, 9, '参考图片', true); }
function handleCreateAttachments(input) { handleFileUpload(input, _createAttachments, 5, '附件', false); }
function handleDeliverImages(input) { handleFileUpload(input, _deliverImages, 9, '交付参考图', true); }
function handleDeliverAttachments(input) { handleFileUpload(input, _deliverAttachments, 5, '交付附件', false); }

function openCreateProject(type) {
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

  const plannerOpts = `<option value="">未指定</option>` + USERS.planner.map(u =>
    `<option value="${u.userId}" ${defaultPlanner === u.userId ? 'selected' : ''}>${u.name} (${u.title})</option>`
  ).join('');
  const salesOpts = `<option value="">未指定</option>` + USERS.sales.map(u =>
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
        <button class="btn btn-primary" onclick="submitCreateProject('${type}')">创建项目</button>
      </div>
    </div>`;
  document.body.appendChild(modal);
  if (_createRefImages.length) renderFileList(_createRefImages, '参考图片');
  if (_createAttachments.length) renderFileList(_createAttachments, '附件');
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
  if (!data.salesId) data.salesId = '';

  try {
    await apiPost('/projects', data);
    // 创建成功，清除草稿
    sessionStorage.removeItem('design_pm_create_draft');
    closeM('createProjectModal', true); // force=true 跳过保存提示
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
  if (document.getElementById('projectDetailModal')) return;
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
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

function renderProjectDetailContent(detail) {
  const isChannel = detail.type === 'channel_custom';
  // 进度：需要考虑评分完成才算真正完成
  const totalTasks = detail.tasks.length;
  const doneTasks = detail.tasks.filter(t => {
    if (t.status !== 'approved') return false;
    // 已验收的子任务需要所有评分角色都评完才算完成
    if (t.scoringRecords && t.scoringRecords.length > 0) {
      return t.scoringRecords.every(r => r.aesthetics !== null && r.innovation !== null);
    }
    // 无评分记录（常规品只有企划评分的场景，评分可能还没创建）
    return false;
  }).length;
  const pct = totalTasks ? Math.round(doneTasks / totalTasks * 100) : 0;

  return `
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
        ${((currentRole === 'planner') || (currentRole === 'sales' && (detail.type === 'channel_custom'))) && (detail.status === 'planner_accepted' || detail.status === 'in_progress' || detail.status === 'completed' || detail.status === 'completed_pending_score') ? `<button class="btn btn-primary btn-sm" style="margin-left:auto;" onclick="addSubTask(${detail.id})">➕ 添加子任务</button>` : ''}
      </div>
      ${detail.tasks.length === 0 ? `<div class="empty" style="padding:30px;"><div class="empty-icon">📭</div><p>暂无子任务，产品企划可在此添加</p></div>` : ''}
      ${detail.tasks.map((t, i) => renderSubTaskCard(detail, t, i)).join('')}
    </div>

    ${renderProjectScoringSummary(detail)}

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
  const roleName = l.role === 'sales' ? '销售' : l.role === 'planner' ? '产品企划' : l.role === 'designer' ? '设计师' : l.role === 'superior' ? '上级' : l.role === 'admin' ? '管理员' : l.role;
  const isScore = l.action && l.action.includes('评分');
  // 评分日志特殊格式
  if (isScore) {
    // 提取角色名: "子任务评分：xxx（planner已评）"
    const match = l.action.match(/（(.+?)已评/);
    const scoreRole = match ? match[1] : l.role;
    const scoreRoleName = scoreRole === 'sales' ? '销售' : scoreRole === 'planner' ? '产品企划' : scoreRole === 'designer' ? '设计师' : scoreRole;
    return `（${scoreRoleName}：${l.user} 已评）`;
  }
  return `（${roleName}：${l.user}）`;
}

function renderSubTaskCard(detail, task, idx) {
  const tsi = getTaskStatusInfo(task.status);
  const isPlanner = currentRole === 'planner';
  const myTask = currentRole === 'designer' && task.designerId === getCurrentUserId();
  const needScore = task.scoringRecords && task.scoringRecords.some(sr => sr.aesthetics === null && sr.role === currentRole);

  return `<div class="subtask-card">
    <div class="subtask-header">
      <div class="subtask-name"><span class="subtask-number">${idx + 1}</span> ${task.name}</div>
      <span class="badge ${tsi.cls}">${tsi.label}</span>
    </div>
    <div class="subtask-meta">
      <div class="subtask-meta-item">👤 设计师：<strong>${task.designerName || '待分配'}</strong></div>
      <div class="subtask-meta-item">📅 计划完成：<strong>${formatDate(task.plannedDate)}</strong></div>
      ${task.actualDate ? `<div class="subtask-meta-item">✅ 实际完成：<strong>${formatDate(task.actualDate)}</strong></div>` : ''}
    </div>
    ${task.details ? `<div style="font-size:13px;color:var(--gray-600);margin-top:6px;">📝 ${task.details}</div>` : ''}
    ${task.referenceImagesJson ? renderSubTaskImages(task.referenceImagesJson) : ''}
    ${task.attachmentsJson ? renderTaskAttachments(task.attachmentsJson) : ''}

    ${task.status === 'delivered' || task.status === 'planner_approved' || task.status === 'approved' || task.status === 'rejected' ? `
    <div class="subtask-deliver">
      ${task.deliverables ? `<div class="detail-item"><div class="detail-label">交付成果</div><div class="detail-value">${task.deliverables}</div></div>` : ''}
    </div>` : ''}

    ${task.reviewComments ? `<div class="review-box ${task.status === 'rejected' ? 'rejected' : 'approved'}"><strong>${task.status === 'rejected' ? '驳回意见' : '验收意见'}：</strong>${task.reviewComments}</div>` : ''}

    ${task.scoringRecords && (task.status === 'approved' || task.status === 'planner_approved') ? renderScoringMini(task) : ''}

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
      ${myTask && task.status === 'pending' ? `<button class="btn btn-primary btn-sm" onclick="taskAccept(${detail.id},${task.id})">✅ 接单</button>` : ''}
      ${myTask && task.status === 'accepted' ? `<button class="btn btn-primary btn-sm" onclick="taskDeliver(${detail.id},${task.id})">📤 交付成果</button>` : ''}
      ${myTask && task.status === 'rejected' ? `<button class="btn btn-warning btn-sm" onclick="taskRedeliver(${detail.id},${task.id})">📤 重新交付</button>` : ''}
      ${(isPlanner || (currentRole === 'sales' && detail.type === 'channel_custom')) && (task.status === 'pending' || task.status === 'accepted') ? `
        <button class="btn btn-outline btn-sm" onclick="editTask(${detail.id},${task.id})">✏️ 编辑</button>
        <button class="btn btn-outline btn-sm" onclick="deleteTask(${detail.id},${task.id})" style="color:var(--danger);border-color:var(--danger);">🗑️ 删除</button>
      ` : ''}
      ${needScore ? `<button class="btn btn-warning btn-sm" onclick="openScoring(${detail.id},${task.id})">⭐ 评分</button>` : ''}
    </div>
  </div>`;
}

function renderProjectActions(detail) {
  let actions = '';
  const canManageProject = currentRole === 'planner' || currentRole === 'sales' || currentRole === 'superior' || currentRole === 'admin';

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
  actions += `<button class="btn btn-outline" onclick="closeM('projectDetailModal')">关闭</button>`;
  return actions;
}

async function terminateProject(pid) {
  if (!confirm('确定要终止该项目吗？终止后项目将无法恢复。')) return;
  try {
    await apiPost(`/projects/${pid}/terminate`, { currentUser: getCurrentUserName(), currentRole: currentRole });
    closeM('projectDetailModal');
    render();
  } catch(e) { alert('操作失败: ' + e.message); }
}

async function cancelTerminate(pid) {
  if (!confirm('确定要取消终止吗？')) return;
  try {
    await apiPost(`/projects/${pid}/cancel-terminate`, { currentUser: getCurrentUserName(), currentRole: currentRole });
    closeM('projectDetailModal');
    render();
  } catch(e) { alert('操作失败: ' + e.message); }
}

async function pauseProject(pid) {
  if (!confirm('确定要暂停该项目吗？暂停期间无法进行任何操作。')) return;
  try {
    await apiPost(`/projects/${pid}/pause`, { currentUser: getCurrentUserName(), currentRole: currentRole });
    closeM('projectDetailModal');
    render();
  } catch(e) { alert('操作失败: ' + e.message); }
}

async function resumeProject(pid) {
  try {
    await apiPost(`/projects/${pid}/resume`, { currentUser: getCurrentUserName(), currentRole: currentRole });
    closeM('projectDetailModal');
    render();
  } catch(e) { alert('操作失败: ' + e.message); }
}

// 渲染项目参考图片
function renderProjectReferenceImages(detail) {
  if (!detail.referenceImagesJson) return '';
  let imgs;
  try { imgs = JSON.parse(detail.referenceImagesJson); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  return `<div style="margin-top:8px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${img.url}" alt="${img.name || ''}" title="${img.name || ''}" class="img-clickable" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
          <a href="${img.url}" download="${img.name || 'image.png'}" title="下载 ${img.name || ''}" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;" onclick="event.stopPropagation();">⬇</a>
      </div>`).join('')}
    </div></div>`;
}

/* 子任务参考图 */
function renderSubTaskImages(jsonStr) {
  if (!jsonStr) return '';
  let imgs;
  try { imgs = JSON.parse(jsonStr); } catch(e) { return ''; }
  if (!imgs || !imgs.length) return '';
  return `<div style="margin-top:8px;padding-left:4px;"><div class="detail-label">🖼️ 参考图片</div>
    <div class="image-preview" style="margin-top:4px;">
      ${imgs.map(img => `<div style="position:relative;display:inline-block;">
          <img src="${img.url}" alt="${img.name || ''}" class="img-clickable" style="width:60px;height:60px;object-fit:cover;border-radius:4px;border:1px solid var(--gray-200);cursor:pointer;">
          <a href="${img.url}" download="${img.name || 'image.png'}" title="下载 ${img.name || ''}" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;" onclick="event.stopPropagation();">⬇</a>
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
      <a href="${a.url}" download="${a.name}" style="text-decoration:none;padding:2px 8px;border-radius:4px;background:var(--primary-light);color:var(--primary);font-size:12px;white-space:nowrap;">⬇ 下载</a>
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
  // 图片预览
  if (images.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">🖼️ 交付图片</div>
      <div class="image-preview" style="margin-top:4px;">
        ${images.map(img => `<div style="position:relative;display:inline-block;">
            <img src="${img.url}" alt="${img.name || ''}" class="img-clickable" style="width:80px;height:80px;object-fit:cover;border-radius:6px;border:1px solid var(--gray-200);cursor:pointer;">
            <a href="${img.url}" download="${img.name || 'image.png'}" style="position:absolute;bottom:2px;right:2px;width:22px;height:22px;border-radius:4px;background:rgba(0,0,0,.5);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;text-decoration:none;" onclick="event.stopPropagation();">⬇</a>
        </div>`).join('')}
      </div></div>`;
  }
  // 附件下载
  if (files.length) {
    html += `<div style="margin-top:8px;"><div class="detail-label">📎 交付附件</div>
      ${files.map(a => `<div class="attachment-item" style="margin-top:4px;display:flex;align-items:center;gap:8px;">
        <span>📎</span><span class="attachment-name" style="flex:1;">${a.name}</span>
        ${a.size ? `<span class="attachment-size">${fmtSize(a.size)}</span>` : ''}
        <a href="${a.url}" download="${a.name}" style="text-decoration:none;padding:2px 8px;border-radius:4px;background:var(--primary-light);color:var(--primary);font-size:12px;white-space:nowrap;">⬇ 下载</a>
      </div>`).join('')}
      </div>`;
  }
  return html;
}

function renderProjectScoringSummary(detail) {
  const approvedTasks = detail.tasks.filter(t => t.status === 'approved' && t.scoringRecords && t.scoringRecords.length > 0);
  if (approvedTasks.length === 0) return '';
  const allScoredTasks = approvedTasks.filter(t => t.scoringRecords.every(sr => sr.aesthetics !== null));

  let html = `<div class="detail-section"><div class="detail-section-title">⭐ 项目评分汇总 <span style="font-size:12px;color:var(--gray-400);font-weight:400;">${allScoredTasks.length}/${approvedTasks.length} 已完成</span></div>`;
  approvedTasks.forEach((task, i) => {
    const records = task.scoringRecords;
    let ta = 0, ti = 0, tw = 0;
    records.forEach(r => {
      if (r.aesthetics !== null && r.innovation !== null) {
        ta += r.aesthetics * r.weight;
        ti += r.innovation * r.weight;
        tw += r.weight;
      }
    });
    const final = tw > 0 ? ((ta + ti) / (tw * 2)).toFixed(1) : null;
    html += `<div style="display:flex;align-items:center;gap:12px;padding:8px 12px;background:var(--gray-50);border-radius:6px;margin-bottom:6px;font-size:13px;">
      <span style="font-weight:600;">#${i + 1} ${task.name}</span>
      <span style="flex:1;"></span>
      ${final ? `<span style="font-size:16px;font-weight:700;color:var(--primary);">${final}</span>` : `<span style="color:var(--gray-400);">评分中…</span>`}
    </div>`;
  });
  html += `</div>`;
  return html;
}

// ==================== 企划接单 ====================
async function plannerAcceptProject(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: currentRole, userId: getCurrentUserId() });
    closeM('projectDetailModal');
    render();
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// 从列表直接接单（不需要打开弹窗）
async function plannerAcceptFromList(pid) {
  try {
    await apiPost(`/projects/${pid}/accept`, { currentUser: getCurrentUserName(), currentRole: currentRole, userId: getCurrentUserId() });
    render();
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
  const designerOpts = `<option value="">请选择设计师</option><option value="">未指定</option>` + USERS.designer.map(u =>
    `<option value="${u.userId}">${u.name} (${u.title})</option>`).join('');

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
          <div class="form-group"><label class="form-label"><span class="required">*</span> 指派设计师</label>
            <select class="form-select" name="designerId" required onchange="this.closest('.form-group')?.querySelector('.field-error')?.remove();this.style.borderColor=''">${designerOpts}</select>
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
      <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('addSubTaskModal')">取消</button><button class="btn btn-primary" onclick="submitAddSubTask('${pid}')">确认添加</button></div>
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

  // 设计师：未指定可以提交，但"请选择设计师"不允许
  const designerSel = document.querySelector('#addSubTaskForm [name="designerId"]');
  if (designerSel && designerSel.selectedIndex === 0) {
    showError('designerId', '请选择设计师或未指定');
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
    // 重新加载详情
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
  } catch (e) {
    alert('添加失败: ' + (e.message || '未知错误'));
  }
}

function editTask(pid, tid) {
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;
    // 加载现有图片和附件
    _editTaskRefImages = [];
    _editTaskAttachments = [];
    if (task.referenceImagesJson) {
      try { _editTaskRefImages = JSON.parse(task.referenceImagesJson); } catch(e) {}
    }
    if (task.attachmentsJson) {
      try { _editTaskAttachments = JSON.parse(task.attachmentsJson); } catch(e) {}
    }

    const designerOpts = `<option value="">请选择设计师</option><option value="">未指定</option>` + USERS.designer.map(u =>
      `<option value="${u.userId}" ${(task.designerId === u.userId) ? 'selected' : ''}>${u.name} (${u.title})</option>`).join('');

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
            <div class="form-group"><label class="form-label"><span class="required">*</span> 指派设计师</label>
              <select class="form-select" name="designerId">${designerOpts}</select>
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
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('editTaskModal')">取消</button><button class="btn btn-primary" onclick="submitEditTask('${pid}','${tid}')">保存修改</button></div>
      </div>`;
    document.body.appendChild(modal);
    // 渲染现有文件
    if (_editTaskRefImages.length) renderFileList(_editTaskRefImages, '编辑参考图片');
    if (_editTaskAttachments.length) renderFileList(_editTaskAttachments, '编辑附件');
  });
}

let _editTaskRefImages = [];
let _editTaskAttachments = [];
function handleEditRefImages(input) { handleFileUpload(input, _editTaskRefImages, 9, '编辑参考图片', true); }
function handleEditAttachments(input) { handleFileUpload(input, _editTaskAttachments, 9, '编辑附件', false); }

async function submitEditTask(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('editTaskForm'));
  const data = Object.fromEntries(fd.entries());
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  // 始终提交当前图片和附件列表（包含已有的和新上传的）
  data.referenceImagesJson = JSON.stringify(_editTaskRefImages.map(img => ({name: img.name, url: img.url, size: img.size, storedName: img.storedName})));
  data.attachmentsJson = JSON.stringify(_editTaskAttachments.map(a => ({name: a.name, url: a.url, size: a.size, storedName: a.storedName})));

  try {
    await apiPut(`/projects/${pid}/tasks/${tid}`, data);
    closeM('editTaskModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
  } catch (e) {
    alert('编辑失败: ' + e.message);
  }
}

async function deleteTask(pid, tid) {
  if (!confirm('确定要删除这个子任务吗？此操作不可恢复。')) return;
  try {
    await apiDelete(`/projects/${pid}/tasks/${tid}`);
    closeM('editTaskModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
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
          <p style="margin-bottom:12px;color:var(--gray-500);">设计师：<strong>${task.designerName || '未指定'}</strong></p>
          <form id="taskAcceptForm">
            <div class="form-group"><label class="form-label"><span class="required">*</span> 计划完成时间</label>${renderDatePicker('plannedDate', {required:true, value: task.plannedDate || ''})}</div>
          </form>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskAcceptModal')">取消</button><button class="btn btn-primary" onclick="submitTaskAccept(${pid},${tid})">确认接单</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskAccept(pid, tid) {
  const fd = new FormData(document.getElementById('taskAcceptForm'));
  const plannedDate = fd.get('plannedDate');
  if (!plannedDate) { alert('请选择计划完成时间'); return; }

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/accept`, {
      plannedDate,
      currentUser: getCurrentUserName(),
      currentRole: currentRole,
      designerUserId: getCurrentUserId(),
    });
    closeM('taskAcceptModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
    render(); // 刷新列表状态
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

async function taskDeliver(pid, tid) {
  if (document.getElementById('taskDeliverModal')) return;
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
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskDeliverModal')">取消</button><button class="btn btn-primary" onclick="submitTaskDeliver(${pid},${tid})">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskDeliver(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskDeliverForm'));
  const data = Object.fromEntries(fd.entries());
  // 自动填写实际完成时间为当前时间
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  // 组装上传文件（改用url引用）
  data.attachmentsJson = JSON.stringify([..._deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })), ..._deliverAttachments]);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/deliver`, data);
    closeM('taskDeliverModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
    // 也刷新页面（确保导航栏和任务列表状态同步）
    render();
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

async function taskRedeliver(pid, tid) {
  if (document.getElementById('taskRedeliverModal')) return;
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
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskRedeliverModal')">取消</button><button class="btn btn-primary" onclick="submitTaskRedeliver(${pid},${tid})">确认交付</button></div>
      </div>`;
    document.body.appendChild(modal);
  } catch (e) {
    alert('加载失败: ' + e.message);
  }
}

async function submitTaskRedeliver(pid, tid) {
  if (_uploadingCount > 0) { alert('文件正在上传中，请等待上传完成'); return; }
  const fd = new FormData(document.getElementById('taskRedeliverForm'));
  const data = Object.fromEntries(fd.entries());
  // 自动填写实际完成时间为当前时间
  data.actualDate = new Date().toISOString().split('T')[0];
  if (!data.deliverables) { alert('请填写交付成果描述'); return; }
  data.currentUser = getCurrentUserName();
  data.currentRole = currentRole;
  data.attachmentsJson = JSON.stringify([..._deliverImages.map(i => ({ name: i.name, url: i.url, size: i.size, storedName: i.storedName })), ..._deliverAttachments]);

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/redeliver`, data);
    closeM('taskRedeliverModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
    render();
  } catch (e) {
    alert('交付失败: ' + e.message);
  }
}

// ==================== 验收 ====================
function taskApprove(pid, tid, projectType) {
  if (document.getElementById('taskApproveModal')) return;
  apiGet(`/projects/${pid}`).then(detail => {
    const task = detail.tasks.find(t => t.id === tid);
    if (!task) return;

    const isChannel = projectType === 'channel_custom';
    const isSalesConfirm = currentRole === 'sales';
    const title = isSalesConfirm ? '✅ 销售确认评分通过' : (isChannel ? '👍 企划确认评分通过' : '✅ 验收通过');

    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.id = 'taskApproveModal';
    modal.innerHTML = `
      <div class="modal">
        <div class="modal-header"><div class="modal-header-left"><div class="modal-title">${title}：${task.name}</div></div></div>
        <div class="modal-body">
          <p style="margin-bottom:12px;">${isSalesConfirm ? '销售确认该子任务通过并评分？' : (isChannel ? '企划确认该子任务通过并评分？之后需销售再次确认评分。' : '确认该子任务验收通过？')}</p>
          ${isChannel ? `
          <div class="form-row">
            <div class="form-group">
              <label class="form-label"><span class="required">*</span> 审美评分</label>
              <input type="number" class="form-input" id="approveAesthetics" min="1" max="10" step="0.1" placeholder="1-10" required>
            </div>
            <div class="form-group">
              <label class="form-label"><span class="required">*</span> 创新评分</label>
              <input type="number" class="form-input" id="approveInnovation" min="1" max="10" step="0.1" placeholder="1-10" required>
            </div>
          </div>` : ''}
          <div class="form-group"><label class="form-label">验收意见（可选）</label><textarea class="form-textarea" id="approveComments" placeholder="输入验收意见..."></textarea></div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskApproveModal')">取消</button><button class="btn btn-success" onclick="submitTaskApprove(${pid},${tid},'${projectType}')">${isChannel ? '确认通过并评分' : '确认通过'}</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitTaskApprove(pid, tid, projectType) {
  const comments = document.getElementById('approveComments')?.value || '验收通过';

  const data = {
    comments,
    currentUser: getCurrentUserName(),
    currentRole: currentRole,
    projectType: projectType || 'regular',
  };

  // 渠道定制单：审批时同时提交评分
  if (projectType === 'channel_custom') {
    const aesthetics = parseFloat(document.getElementById('approveAesthetics').value);
    const innovation = parseFloat(document.getElementById('approveInnovation').value);
    if (isNaN(aesthetics) || aesthetics < 1 || aesthetics > 10) { alert('请输入有效的审美评分（1-10）'); return; }
    if (isNaN(innovation) || innovation < 1 || innovation > 10) { alert('请输入有效的创新评分（1-10）'); return; }
    data.aesthetics = aesthetics;
    data.innovation = innovation;
  }

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/approve`, data);
    closeM('taskApproveModal');
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
    // 刷新导航栏徽章
    updateBadges(currentRole, getCurrentUserId());
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

function taskReject(pid, tid) {
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'taskRejectModal';
  modal.innerHTML = `
    <div class="modal">
      <div class="modal-header"><button class="modal-close" onclick="closeM('taskRejectModal')">✕</button><div class="modal-header-left"><div class="modal-title">↩️ 驳回修改</div></div></div>
      <div class="modal-body">
        <div class="form-group"><label class="form-label"><span class="required">*</span> 修改意见</label><textarea class="form-textarea" id="rejectComments" required placeholder="请详细说明修改意见..." style="min-height:100px;"></textarea></div>
      </div>
      <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('taskRejectModal')">取消</button><button class="btn btn-danger" onclick="submitTaskReject(${pid},${tid})">确认驳回</button></div>
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
    document.getElementById('projectDetailModal')?.remove();
    openProjectDetail(Number(pid));
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
}

// ==================== 评分 ====================
function openScoring(pid, tid) {
  if (document.getElementById('scoringModal')) return;
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
          <p style="margin-bottom:16px;color:var(--gray-500);">请对 <strong>${task.name}</strong> 的审美和创新打分（1-10分）</p>
          <div style="display:flex;gap:24px;">
            <div class="form-group" style="flex:1;"><label class="form-label">🎨 审美评分</label><input type="number" class="form-input" id="scoreAesthetics" min="1" max="10" step="0.5" placeholder="1-10" style="font-size:24px;text-align:center;"></div>
            <div class="form-group" style="flex:1;"><label class="form-label">💡 创新评分</label><input type="number" class="form-input" id="scoreInnovation" min="1" max="10" step="0.5" placeholder="1-10" style="font-size:24px;text-align:center;"></div>
          </div>
        </div>
        <div class="modal-footer"><button class="btn btn-outline" onclick="closeM('scoringModal')">取消</button><button class="btn btn-primary" onclick="submitScoring(${pid},${tid})">提交评分</button></div>
      </div>`;
    document.body.appendChild(modal);
  });
}

async function submitScoring(pid, tid) {
  const aesthetics = parseFloat(document.getElementById('scoreAesthetics').value);
  const innovation = parseFloat(document.getElementById('scoreInnovation').value);
  if (isNaN(aesthetics) || aesthetics < 1 || aesthetics > 10) { alert('请输入有效的审美评分（1-10）'); return; }
  if (isNaN(innovation) || innovation < 1 || innovation > 10) { alert('请输入有效的创新评分（1-10）'); return; }

  const data = {
    role: currentRole,
    aesthetics,
    innovation,
    currentUser: getCurrentUserName(),
    currentRole,
  };

  try {
    await apiPost(`/projects/${pid}/tasks/${tid}/score`, data);
    closeM('scoringModal');
    document.getElementById('projectDetailModal')?.remove();
    // 如果在待评分页面，刷新列表；否则刷新项目详情
    if (currentView === 'scoring') {
      render();
    } else {
      openProjectDetail(Number(pid));
    }
  } catch (e) {
    alert('评分提交失败: ' + e.message);
  }
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
        <button class="admin-tab ${currentAdminTab === 'logs' ? 'active' : ''}" onclick="switchAdminTab('logs')">📜 日志</button>
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
    } else if (currentAdminTab === 'logs') {
      await renderAdminLogs(container);
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
  return { smtp: '📧 SMTP 邮件', appearance: '🎨 外观设置', security: '🔒 安全设置', system: '💻 系统信息' }[group] || group;
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

  const roleLabels = { sales: '销售', planner: '产品企划', designer: '设计师', superior: '上级', admin: '管理员' };

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
            <option value="admin">管理员</option>
            <option value="sales">销售</option>
            <option value="planner">产品企划</option>
            <option value="designer">设计师</option>
            <option value="superior">上级</option>
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
            <span style="font-size:13px;color:var(--gray-500);">当前角色：<span class="admin-user-role-badge role-${userData.role}">${({sales:'销售',planner:'产品企划',designer:'设计师',superior:'上级',admin:'管理员'})[userData.role] || userData.role}</span></span>
            <span style="font-size:13px;color:var(--gray-500);">当前状态：<span class="admin-user-status-badge status-${userData.status || 'active'}">${(userData.status === 'disabled') ? '❌ 停用' : '✅ 启用'}</span></span>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" onclick="closeM('editUserModal')">取消</button>
        <button class="btn btn-primary" onclick="submitEditUser(${userData.id})">💾 保存修改</button>
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
  const roleOptions = { admin: '管理员', sales: '销售', planner: '产品企划', designer: '设计师', superior: '上级' };
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
        <button class="btn btn-primary" onclick="submitChangeRole(${userId})">确认修改</button>
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
        <button class="btn btn-warning" onclick="submitResetPwd(${userId})">确认重置</button>
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

// ==================== 管理员：系统日志 ====================
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
        const rl = {sales:'销售',planner:'企划',designer:'设计师',superior:'上级',admin:'管理员'};
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

// ==================== 启动 ====================
document.addEventListener('DOMContentLoaded', initApp);
