import './core-runtime.js?v=134';
import './event-runtime.js?v=134';
import './core-auth.js?v=134';
import './core-identity.js?v=134';
import './core-shell.js?v=134';
import './core-ui.js?v=134';
import './core.js?v=134';
import './dashboard-projects.js?v=134';
import './dashboard-home.js?v=134';
import './dashboard-lists.js?v=134';
import './dashboard-scoring.js?v=134';
import './dashboard-designer.js?v=134';
import './dashboard.js?v=134';
import './project-uploads.js?v=134';
import './project-form.js?v=134';
import './project-detail.js?v=134';
import './project-tasks.js?v=134';
import './project-sharing.js?v=134';
import './projects.js?v=134';
import './admin-shell.js?v=134';
import './admin-users.js?v=134';
import './admin-roles.js?v=134';
import './admin-catalog.js?v=134';
import './admin-org.js?v=134';
import './admin-scoring.js?v=134';
import './admin-audit.js?v=134';
import './admin-storage.js?v=134';
import './admin-workload.js?v=134';
import './admin.js?v=134';
import './files.js?v=134';

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
