import './core-runtime.js?v=126';
import './event-runtime.js?v=126';
import './core-auth.js?v=126';
import './core-identity.js?v=126';
import './core-shell.js?v=126';
import './core-ui.js?v=126';
import './core.js?v=126';
import './dashboard-projects.js?v=126';
import './dashboard-home.js?v=126';
import './dashboard-lists.js?v=126';
import './dashboard-scoring.js?v=126';
import './dashboard-designer.js?v=126';
import './dashboard.js?v=126';
import './project-uploads.js?v=126';
import './project-form.js?v=126';
import './project-detail.js?v=126';
import './project-tasks.js?v=126';
import './project-sharing.js?v=126';
import './projects.js?v=126';
import './admin-shell.js?v=126';
import './admin-users.js?v=126';
import './admin-roles.js?v=126';
import './admin-catalog.js?v=126';
import './admin-org.js?v=126';
import './admin-scoring.js?v=126';
import './admin-audit.js?v=126';
import './admin-storage.js?v=126';
import './admin-workload.js?v=126';
import './admin.js?v=126';
import './files.js?v=126';

const EMIE = window.EMIE;
const requiredEmieModules = [
  'coreRuntime', 'eventRuntime', 'coreAuth', 'coreIdentity', 'coreShell', 'coreUi', 'core',
  'dashboardProjects', 'dashboardHome', 'dashboardLists', 'dashboardScoring', 'dashboardDesigner', 'dashboard',
  'projectUploads', 'projectForm', 'projectDetail', 'projectTasks', 'projectSharing', 'projects',
  'adminShell', 'adminUsers', 'adminRoles', 'adminCatalog', 'adminOrg', 'adminScoring', 'adminAudit',
  'adminStorage', 'adminWorkload', 'admin', 'files',
];
const missingEmieModules = requiredEmieModules.filter(name => !EMIE.modules[name]);
if (missingEmieModules.length) {
  throw new Error('前端模块加载不完整: ' + missingEmieModules.join(', '));
}

document.addEventListener('DOMContentLoaded', EMIE.modules.coreAuth.initApp);
