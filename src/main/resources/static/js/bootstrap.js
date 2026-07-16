import './core-runtime.js?v=133';
import './event-runtime.js?v=133';
import './core-auth.js?v=133';
import './core-identity.js?v=133';
import './core-shell.js?v=133';
import './core-ui.js?v=133';
import './core.js?v=133';
import './dashboard-projects.js?v=133';
import './dashboard-home.js?v=133';
import './dashboard-lists.js?v=133';
import './dashboard-scoring.js?v=133';
import './dashboard-designer.js?v=133';
import './dashboard.js?v=133';
import './project-uploads.js?v=133';
import './project-form.js?v=133';
import './project-detail.js?v=133';
import './project-tasks.js?v=133';
import './project-sharing.js?v=133';
import './projects.js?v=133';
import './admin-shell.js?v=133';
import './admin-users.js?v=133';
import './admin-roles.js?v=133';
import './admin-catalog.js?v=133';
import './admin-org.js?v=133';
import './admin-scoring.js?v=133';
import './admin-audit.js?v=133';
import './admin-storage.js?v=133';
import './admin-workload.js?v=133';
import './admin.js?v=133';
import './files.js?v=133';

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
