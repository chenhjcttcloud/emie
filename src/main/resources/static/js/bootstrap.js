import './core-runtime.js?v=328';
import './event-runtime.js?v=269';
import './core-auth.js?v=426';
import './core-identity.js?v=498';
import './core-shell.js?v=519';
import './core-ui.js?v=523';
import './core.js?v=147';
import './dashboard-projects.js?v=511';
import './dashboard-home.js?v=623';
import './dashboard-lists.js?v=429';
import './dashboard-scoring.js?v=345';
import './dashboard-points.js?v=383';
import './dashboard-designer.js?v=610';
import './dashboard.js?v=219';
import './material-market.js?v=162';
import './image-library.js?v=117';
import './project-uploads.js?v=499';
import './project-form.js?v=459';
import './project-detail.js?v=673';
import './project-tasks.js?v=697';
import './project-sharing.js?v=264';
import './projects.js?v=148';
// 管理模块只在进入对应页面时下载；保留原有版本号作为缓存边界。
const adminModuleDefinitions = [
  ['./admin-shell.js?v=454', 'adminShell'],
  ['./admin-users.js?v=279', 'adminUsers'],
  ['./admin-roles.js?v=229', 'adminRoles'],
  ['./admin-catalog.js?v=276', 'adminCatalog'],
  ['./admin-org.js?v=292', 'adminOrg'],
  ['./designer-evaluation.js?v=19', 'designerEvaluation'],
  ['./admin-scoring.js?v=663', 'adminScoring'],
  ['./admin-audit.js?v=384', 'adminAudit'],
  ['./admin-point-logs.js?v=17', 'adminPointLogs'],
  ['./admin-storage.js?v=321', 'adminStorage'],
  ['./admin-workload.js?v=526', 'adminWorkload'],
  ['./admin.js?v=147', 'admin'],
];
let adminModulesPromise;
const moduleLoadAttempts = new Map();

async function loadAdminModule([url, name]) {
  if (window.EMIE.modules[name]) return;
  const attempt = moduleLoadAttempts.get(url) || 0;
  moduleLoadAttempts.set(url, attempt + 1);
  // 失败 URL 可能被浏览器缓存；仅重试失败模块，已注册模块不会再次执行。
  await import(attempt ? `${url}&retry=${attempt}` : url);
  if (!window.EMIE.modules[name]) throw new Error('前端模块加载不完整: ' + name);
}

async function loadViewModules(view) {
  if (!['admin', 'logs', 'workload', 'designer-performance'].includes(view)) return;
  if (!adminModulesPromise) {
    adminModulesPromise = (async () => {
      const results = await Promise.allSettled(adminModuleDefinitions.slice(0, -1).map(loadAdminModule));
      const failure = results.find(result => result.status === 'rejected');
      if (failure) throw failure.reason;
      await loadAdminModule(adminModuleDefinitions.at(-1));
    })().catch(error => {
      adminModulesPromise = null;
      throw error;
    });
  }
  await adminModulesPromise;
}

import './files.js?v=302';
import './business-assistant-position.js?v=3';
import './business-assistant.js?v=19';

const EMIE = window.EMIE;
EMIE.loadViewModules = loadViewModules;
const requiredEmieModules = [
  'coreRuntime', 'eventRuntime', 'coreAuth', 'coreIdentity', 'coreShell', 'coreUi', 'core',
  'dashboardProjects', 'dashboardHome', 'dashboardLists', 'dashboardScoring', 'dashboardPoints', 'dashboardDesigner', 'dashboard',
  'projectUploads', 'projectForm', 'projectDetail', 'projectTasks', 'projectSharing', 'projects', 'materialMarket', 'imageLibrary',
  'files', 'businessAssistantPosition', 'businessAssistant',
];
const missingEmieModules = requiredEmieModules.filter(name => !EMIE.modules[name]);
if (missingEmieModules.length) {
  throw new Error('前端模块加载不完整: ' + missingEmieModules.join(', '));
}

document.addEventListener('DOMContentLoaded', EMIE.modules.coreAuth.initApp);
