import './core-runtime.js?v=137';
import './event-runtime.js?v=137';
import './core-auth.js?v=137';
import './core-identity.js?v=137';
import './core-shell.js?v=137';
import './core-ui.js?v=137';
import './core.js?v=137';
import './dashboard-projects.js?v=137';
import './dashboard-home.js?v=137';
import './dashboard-lists.js?v=137';
import './dashboard-scoring.js?v=137';
import './dashboard-designer.js?v=137';
import './dashboard.js?v=137';
import './project-uploads.js?v=137';
import './project-form.js?v=137';
import './project-detail.js?v=137';
import './project-tasks.js?v=137';
import './project-sharing.js?v=137';
import './projects.js?v=137';
import './admin-shell.js?v=137';
import './admin-users.js?v=137';
import './admin-roles.js?v=137';
import './admin-catalog.js?v=137';
import './admin-org.js?v=137';
import './admin-scoring.js?v=137';
import './admin-audit.js?v=137';
import './admin-storage.js?v=137';
import './admin-workload.js?v=137';
import './admin.js?v=137';
import './files.js?v=137';

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
