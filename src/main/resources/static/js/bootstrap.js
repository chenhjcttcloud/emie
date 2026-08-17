import './core-runtime.js?v=159';
import './event-runtime.js?v=155';
import './core-auth.js?v=319';
import './core-identity.js?v=283';
import './core-shell.js?v=270';
import './core-ui.js?v=346';
import './core.js?v=147';
import './dashboard-projects.js?v=264';
import './dashboard-home.js?v=257';
import './dashboard-lists.js?v=289';
import './dashboard-scoring.js?v=225';
import './dashboard-points.js?v=112';
import './dashboard-designer.js?v=358';
import './dashboard.js?v=219';
import './project-uploads.js?v=298';
import './project-form.js?v=338';
import './project-detail.js?v=373';
import './project-tasks.js?v=400';
import './project-sharing.js?v=152';
import './projects.js?v=148';
import './admin-shell.js?v=257';
import './admin-users.js?v=190';
import './admin-roles.js?v=162';
import './admin-catalog.js?v=212';
import './admin-org.js?v=227';
import './admin-scoring.js?v=356';
import './admin-audit.js?v=296';
import './admin-storage.js?v=210';
import './admin-workload.js?v=201';
import './admin.js?v=147';
import './files.js?v=156';

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
