import './core-runtime.js?v=282';
import './event-runtime.js?v=269';
import './core-auth.js?v=382';
import './core-identity.js?v=489';
import './core-shell.js?v=459';
import './core-ui.js?v=493';
import './core.js?v=147';
import './dashboard-projects.js?v=435';
import './dashboard-home.js?v=577';
import './dashboard-lists.js?v=376';
import './dashboard-scoring.js?v=342';
import './dashboard-points.js?v=330';
import './dashboard-designer.js?v=550';
import './dashboard.js?v=219';
import './material-market.js?v=123';
import './image-library.js?v=22';
import './project-uploads.js?v=470';
import './project-form.js?v=417';
import './project-detail.js?v=591';
import './project-tasks.js?v=643';
import './project-sharing.js?v=264';
import './projects.js?v=148';
import './admin-shell.js?v=353';
import './admin-users.js?v=241';
import './admin-roles.js?v=229';
import './admin-catalog.js?v=276';
import './admin-org.js?v=292';
import './admin-scoring.js?v=565';
import './admin-audit.js?v=357';
import './admin-storage.js?v=321';
import './admin-workload.js?v=430';
import './admin.js?v=147';
import './files.js?v=278';

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
