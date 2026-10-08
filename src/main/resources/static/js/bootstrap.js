import './core-runtime.js?v=300';
import './event-runtime.js?v=269';
import './core-auth.js?v=398';
import './core-identity.js?v=496';
import './core-shell.js?v=500';
import './core-ui.js?v=521';
import './core.js?v=147';
import './dashboard-projects.js?v=473';
import './dashboard-home.js?v=623';
import './dashboard-lists.js?v=426';
import './dashboard-scoring.js?v=345';
import './dashboard-points.js?v=375';
import './dashboard-designer.js?v=593';
import './dashboard.js?v=219';
import './material-market.js?v=124';
import './image-library.js?v=106';
import './project-uploads.js?v=480';
import './project-form.js?v=459';
import './project-detail.js?v=653';
import './project-tasks.js?v=689';
import './project-sharing.js?v=264';
import './projects.js?v=148';
import './admin-shell.js?v=418';
import './admin-users.js?v=279';
import './admin-roles.js?v=229';
import './admin-catalog.js?v=276';
import './admin-org.js?v=292';
import './admin-scoring.js?v=626';
import './admin-audit.js?v=384';
import './admin-point-logs.js?v=13';
import './admin-storage.js?v=321';
import './admin-workload.js?v=526';
import './admin.js?v=147';
import './files.js?v=282';

const EMIE = window.EMIE;
const requiredEmieModules = [
  'coreRuntime', 'eventRuntime', 'coreAuth', 'coreIdentity', 'coreShell', 'coreUi', 'core',
  'dashboardProjects', 'dashboardHome', 'dashboardLists', 'dashboardScoring', 'dashboardPoints', 'dashboardDesigner', 'dashboard',
  'projectUploads', 'projectForm', 'projectDetail', 'projectTasks', 'projectSharing', 'projects', 'materialMarket', 'imageLibrary',
  'adminShell', 'adminUsers', 'adminRoles', 'adminCatalog', 'adminOrg', 'adminScoring', 'adminAudit',
  'adminStorage', 'adminWorkload', 'admin', 'files',
];
const missingEmieModules = requiredEmieModules.filter(name => !EMIE.modules[name]);
if (missingEmieModules.length) {
  throw new Error('前端模块加载不完整: ' + missingEmieModules.join(', '));
}

document.addEventListener('DOMContentLoaded', EMIE.modules.coreAuth.initApp);
