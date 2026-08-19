// EMIE 前端核心：API、认证、全局状态、导航与通用 UI 工具
const EMIE = window.EMIE = window.EMIE || {};
// 所有上传入口共用的前端白名单；后端 SecurityUtil 是最终安全校验。
EMIE.fileAccept = Object.freeze({
  reference: 'image/*',
  attachment: '.ai,.step,.stp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.zip,.rar,.7z,image/*'
});
EMIE.installUploadHints = EMIE.installUploadHints || function installUploadHints(root = document) {
  root.querySelectorAll?.('.upload-area').forEach(area => {
    if (area.querySelector('.upload-hint')) return;
    const input = area.querySelector('input[type="file"]');
    if (!input) return;
    const hint = document.createElement('div');
    hint.className = 'upload-hint';
    hint.textContent = input.accept === EMIE.fileAccept.reference
      ? '支持 JPG、PNG、GIF、BMP、WebP；最多 6 张，单个文件不超过 200MB'
      : '支持 AI、STEP、STP、PDF、Office、TXT、CSV、ZIP、RAR、7Z 及图片；单个文件不超过 200MB';
    area.appendChild(hint);
  });
};
if (!EMIE._uploadHintObserver) {
  EMIE._uploadHintObserver = new MutationObserver(() => EMIE.installUploadHints());
  EMIE._uploadHintObserver.observe(document.documentElement, { childList: true, subtree: true });
}
EMIE.modules = EMIE.modules || {};
EMIE.actions = EMIE.actions || Object.create(null);
EMIE.registerActions = EMIE.registerActions || function registerActions(actions) {
  Object.entries(actions).forEach(([actionName, handler]) => {
    if (typeof handler === 'function' && !EMIE.actions[actionName]) {
      EMIE.actions[actionName] = handler;
    }
  });
};
EMIE.registerModule = EMIE.registerModule || function registerModule(name, api) {
  if (EMIE.modules[name]) throw new Error('前端模块重复注册: ' + name);
  EMIE.modules[name] = Object.freeze(api);
  EMIE.registerActions(api);
};

EMIE.state = Object.assign({
  authUser: null,
  originalUser: null,
  currentRole: '',
  currentUserId: '',
  currentView: 'dashboard',
  renderId: 0,
  taskBucket: localStorage.getItem('design_pm_taskBucket') || 'all',
  users: {},
  categories: [],
  complianceItems: [],
  priceRanges: [],
  ipOptions: [],
  departments: [],
  permissions: null,
  permissionScopes: {},
  permissionVersion: null,
  permissionMode: 'unavailable',
  cache: { orders: [] },
  showAppInProgress: false,
  idleMonitorInited: false,
}, EMIE.state || {});

EMIE.dashboardState = Object.assign({
  workloadRange: 'day',
  scoringCache: [],
  designerTaskCache: [],
  taskProjectsCache: [],
}, EMIE.dashboardState || {});
EMIE.projectState = Object.assign({
  createRefImages: [],
  createAttachments: [],
  formModified: false,
  deliverImages: [],
  deliverAttachments: [],
  uploadingCount: 0,
  subTaskRefImages: [],
  subTaskAttachments: [],
  editTaskRefImages: [],
  editTaskAttachments: [],
  editProjectRefImages: [],
  editProjectAttachments: [],
  rejectionImages: [],
  rejectionAttachments: [],
  createProjectType: 'channel_custom',
}, EMIE.projectState || {});
EMIE.adminState = Object.assign({
  currentTab: 'dashboard',
  workloadRange: 'day',
  scoringWeights: null,
}, EMIE.adminState || {});
EMIE.fileState = Object.assign({ previewSequence: 0, currentPreview: null }, EMIE.fileState || {});

const handleLogout = (...args) => EMIE.actions.handleLogout(...args);
const formModified = (...args) => EMIE.actions.formModified(...args);

// ==================== 产品管理系统 - 应用逻辑 ====================

const API = '/api';
const API_TIMEOUT_MS = 20000;

function hasPermission(permission) {
  // 兼容接入期间，能力接口临时失败时保持旧页面可用；后端仍执行原有角色与归属校验。
  if (!Array.isArray(EMIE.state.permissions)) return true;
  return EMIE.state.permissions.includes(permission);
}

function hasDataScope(permission, scope) {
  const scopes = EMIE.state.permissionScopes?.[permission];
  return Array.isArray(scopes) && scopes.includes(scope);
}

function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), API_TIMEOUT_MS);
  return fetch(url, { ...options, signal: controller.signal })
    .catch(e => {
      if (e?.name === 'AbortError') throw new Error('请求超时，请检查网络后重试');
      throw e;
    })
    .finally(() => clearTimeout(timer));
}

// ==================== API 层（带 Token） ====================
function authHeaders() {
  const t = localStorage.getItem('design_pm_token');
  return t ? { 'Content-Type': 'application/json', 'X-Auth-Token': t } : { 'Content-Type': 'application/json' };
}

// 合并同一时刻发出的重复 GET 请求，避免多个组件同时初始化时重复访问接口。
const pendingApiGets = new Map();
const cachedApiGets = new Map();
const API_CACHE_TTL_MS = 10000;
const CACHEABLE_GET = /^\/(users|roles|departments|categories|ip-options|admin\/configs|dashboard\/role-status|projects\/role-status)(\/|\?|$)/;

async function apiGet(url) {
  const cacheable = CACHEABLE_GET.test(url);
  if (cacheable) {
    const cached = cachedApiGets.get(url);
    if (cached && cached.expiresAt > Date.now()) return cached.value;
    if (cached) cachedApiGets.delete(url);
  }
  if (pendingApiGets.has(url)) return pendingApiGets.get(url);
  const request = (async () => {
    const r = await fetchWithTimeout(API + url, { headers: authHeaders(), cache: 'no-store' });
    if (r.status === 401) { handleLogout(); throw new Error('登录已过期'); }
    if (!r.ok) throw new Error(`GET ${url} failed: ${r.status}`);
    const value = await r.json();
    if (cacheable) cachedApiGets.set(url, { value, expiresAt: Date.now() + API_CACHE_TTL_MS });
    return value;
  })();
  pendingApiGets.set(url, request);
  try {
    return await request;
  } finally {
    pendingApiGets.delete(url);
  }
}

async function apiPost(url, data) {
  cachedApiGets.clear();
  const r = await fetchWithTimeout(API + url, {
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

function invalidateApiCache() { cachedApiGets.clear(); }

async function optimizeUploadFile(file) {
  // 仅压缩大尺寸 JPEG/WebP，保留 PNG 透明通道和办公附件原文件。
  if (file.size <= 3 * 1024 * 1024 || !/^image\/(jpeg|webp)$/i.test(file.type)) return file;
  try {
    const bitmap = await createImageBitmap(file);
    const maxSide = 2400;
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size <= 8 * 1024 * 1024) return file;
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, file.type, 0.84));
    bitmap.close();
    return blob && blob.size < file.size ? new File([blob], file.name, { type: file.type, lastModified: file.lastModified }) : file;
  } catch (e) {
    return file;
  }
}

// 文件上传（XMLHttpRequest 流式上传，支持进度条）
async function uploadFile(file, onProgress) {
  // 客户端前置检查：限制 200MB
  const MAX_BYTES = 200 * 1024 * 1024;
  if (file.size > MAX_BYTES) {
    return Promise.reject(new Error('文件大小超过限制（最大 200MB），当前文件 ' + (file.size / 1024 / 1024).toFixed(1) + 'MB'));
  }
  file = await optimizeUploadFile(file);
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
  cachedApiGets.clear();
  const r = await fetchWithTimeout(API + url, {
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
function showActionError(message) {
  const old = document.getElementById('actionErrorToast');
  if (old) old.remove();
  const toast = document.createElement('div');
  toast.id = 'actionErrorToast';
  toast.textContent = message || '操作失败，请稍后重试';
  toast.style.cssText = 'position:fixed;right:20px;bottom:24px;z-index:10000;max-width:360px;padding:12px 16px;border-radius:8px;background:#b42318;color:#fff;box-shadow:0 4px 16px rgba(0,0,0,.18);font-size:13px;';
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

async function submitGuard(btn, handler) {
  if (!btn || btn.disabled) return;
  btn.disabled = true;
  btn.setAttribute('aria-busy', 'true');
  btn.setAttribute('aria-disabled', 'true');
  const orig = btn.textContent;
  btn.textContent = '⏳...';
  btn.style.opacity = '0.5';
  try {
    await handler();
  } catch (e) {
    console.error(e);
    showActionError(e?.message);
  } finally {
    btn.disabled = false;
    btn.removeAttribute('aria-busy');
    btn.removeAttribute('aria-disabled');
    btn.textContent = orig;
    btn.style.opacity = '';
  }
}

async function apiDelete(url) {
  cachedApiGets.clear();
  const r = await fetchWithTimeout(API + url, {
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


EMIE.registerActions({
  hasPermission,
  hasDataScope,
  fetchWithTimeout,
  authHeaders,
  apiGet,
  apiPost,
  optimizeUploadFile,
  uploadFile,
  apiPut,
  tryOpenModal,
  doneOpenModal,
  isModalOpen,
  showActionError,
  submitGuard,
  apiDelete,
  swrFetch,
  clearSWRCache,
});

EMIE.registerModule('coreRuntime', {
  apiGet,
  apiPost,
  apiPut,
  apiDelete,
  uploadFile,
  tryOpenModal,
  doneOpenModal,
  isModalOpen,
  showActionError,
  submitGuard,
  swrFetch,
  clearSWRCache,
});
