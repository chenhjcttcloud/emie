import './core-runtime.js?v=128';
import './event-runtime.js?v=128';
import './core-auth.js?v=128';
import './core-identity.js?v=128';
import './core-shell.js?v=128';
import './core-ui.js?v=128';
import './core.js?v=128';
import './dashboard-projects.js?v=128';
import './dashboard-home.js?v=128';
import './dashboard-lists.js?v=128';
import './dashboard-scoring.js?v=128';
import './dashboard-designer.js?v=128';
import './dashboard.js?v=128';
import './project-uploads.js?v=128';
import './project-form.js?v=128';
import './project-detail.js?v=128';
import './project-tasks.js?v=128';
import './project-sharing.js?v=128';
import './projects.js?v=128';
import './admin-shell.js?v=128';
import './admin-users.js?v=128';
import './admin-roles.js?v=128';
import './admin-catalog.js?v=128';
import './admin-org.js?v=128';
import './admin-scoring.js?v=128';
import './admin-audit.js?v=128';
import './admin-storage.js?v=128';
import './admin-workload.js?v=128';
import './admin.js?v=128';
import './files.js?v=128';

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
