import './core-runtime.js?v=152';
import './event-runtime.js?v=147';
import './core-auth.js?v=147';
import './core-identity.js?v=147';
import './core-shell.js?v=168';
import './core-ui.js?v=147';
import './core.js?v=147';
import './dashboard-projects.js?v=155';
import './dashboard-home.js?v=158';
import './dashboard-lists.js?v=173';
import './dashboard-scoring.js?v=157';
import './dashboard-designer.js?v=172';
import './dashboard.js?v=147';
import './project-uploads.js?v=148';
import './project-form.js?v=147';
import './project-detail.js?v=169';
import './project-tasks.js?v=147';
import './project-sharing.js?v=147';
import './projects.js?v=147';
import './admin-shell.js?v=147';
import './admin-users.js?v=165';
import './admin-roles.js?v=147';
import './admin-catalog.js?v=147';
import './admin-org.js?v=165';
import './admin-scoring.js?v=147';
import './admin-audit.js?v=147';
import './admin-storage.js?v=147';
import './admin-workload.js?v=147';
import './admin.js?v=147';
import './files.js?v=147';

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
