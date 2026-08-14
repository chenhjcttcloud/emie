import './core-runtime.js?v=159';
import './event-runtime.js?v=155';
import './core-auth.js?v=308';
import './core-identity.js?v=274';
import './core-shell.js?v=270';
import './core-ui.js?v=344';
import './core.js?v=147';
import './dashboard-projects.js?v=254';
import './dashboard-home.js?v=247';
import './dashboard-lists.js?v=278';
import './dashboard-scoring.js?v=213';
import './dashboard-points.js?v=107';
import './dashboard-designer.js?v=358';
import './dashboard.js?v=219';
import './project-uploads.js?v=287';
import './project-form.js?v=326';
import './project-detail.js?v=358';
import './project-tasks.js?v=387';
import './project-sharing.js?v=150';
import './projects.js?v=148';
import './admin-shell.js?v=247';
import './admin-users.js?v=178';
import './admin-roles.js?v=151';
import './admin-catalog.js?v=200';
import './admin-org.js?v=218';
import './admin-scoring.js?v=340';
import './admin-audit.js?v=294';
import './admin-storage.js?v=210';
import './admin-workload.js?v=191';
import './admin.js?v=147';
import './files.js?v=151';

const EMIE = window.EMIE;
const requiredEmieModules = [
  'coreRuntime', 'eventRuntime', 'coreAuth', 'coreIdentity', 'coreShell', 'coreUi', 'core',
  'dashboardProjects', 'dashboardHome', 'dashboardLists', 'dashboardScoring', 'dashboardPoints', 'dashboardDesigner', 'dashboard',
  'projectUploads', 'projectForm', 'projectDetail', 'projectTasks', 'projectSharing', 'projects',
  'adminShell', 'adminUsers', 'adminRoles', 'adminCatalog', 'adminOrg', 'adminScoring', 'adminAudit',
  'adminStorage', 'adminWorkload', 'admin', 'files',
];
const missingEmieModules = requiredEmieModules.filter(name => !EMIE.modules[name]);
if (missingEmieModules.length) {
  throw new Error('前端模块加载不完整: ' + missingEmieModules.join(', '));
}

document.addEventListener('DOMContentLoaded', EMIE.modules.coreAuth.initApp);
