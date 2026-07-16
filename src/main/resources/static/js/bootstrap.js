import './core-runtime.js?v=139';
import './event-runtime.js?v=139';
import './core-auth.js?v=139';
import './core-identity.js?v=139';
import './core-shell.js?v=139';
import './core-ui.js?v=139';
import './core.js?v=139';
import './dashboard-projects.js?v=139';
import './dashboard-home.js?v=139';
import './dashboard-lists.js?v=139';
import './dashboard-scoring.js?v=139';
import './dashboard-designer.js?v=139';
import './dashboard.js?v=139';
import './project-uploads.js?v=139';
import './project-form.js?v=139';
import './project-detail.js?v=139';
import './project-tasks.js?v=139';
import './project-sharing.js?v=139';
import './projects.js?v=139';
import './admin-shell.js?v=139';
import './admin-users.js?v=139';
import './admin-roles.js?v=139';
import './admin-catalog.js?v=139';
import './admin-org.js?v=139';
import './admin-scoring.js?v=139';
import './admin-audit.js?v=139';
import './admin-storage.js?v=139';
import './admin-workload.js?v=139';
import './admin.js?v=139';
import './files.js?v=139';

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
