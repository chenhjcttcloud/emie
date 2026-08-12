import './core-runtime.js?v=159';
import './event-runtime.js?v=155';
import './core-auth.js?v=234';
import './core-identity.js?v=151';
import './core-shell.js?v=254';
import './core-ui.js?v=245';
import './core.js?v=147';
import './dashboard-projects.js?v=233';
import './dashboard-home.js?v=209';
import './dashboard-lists.js?v=225';
import './dashboard-scoring.js?v=177';
import './dashboard-points.js?v=2';
import './dashboard-designer.js?v=267';
import './dashboard.js?v=219';
import './project-uploads.js?v=230';
import './project-form.js?v=260';
import './project-detail.js?v=301';
import './project-tasks.js?v=288';
import './project-sharing.js?v=147';
import './projects.js?v=148';
import './admin-shell.js?v=211';
import './admin-users.js?v=175';
import './admin-roles.js?v=151';
import './admin-catalog.js?v=147';
import './admin-org.js?v=165';
import './admin-scoring.js?v=204';
import './admin-audit.js?v=184';
import './admin-storage.js?v=157';
import './admin-workload.js?v=148';
import './admin.js?v=147';
import './files.js?v=147';

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
