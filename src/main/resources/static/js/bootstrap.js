import './core-runtime.js?v=261';
import './event-runtime.js?v=155';
import './core-auth.js?v=319';
import './core-identity.js?v=336';
import './core-shell.js?v=324';
import './core-ui.js?v=385';
import './core.js?v=147';
import './dashboard-projects.js?v=318';
import './dashboard-home.js?v=482';
import './dashboard-lists.js?v=289';
import './dashboard-scoring.js?v=225';
import './dashboard-points.js?v=222';
import './dashboard-designer.js?v=465';
import './dashboard.js?v=219';
import './material-market.js?v=23';
import './project-uploads.js?v=342';
import './project-form.js?v=341';
import './project-detail.js?v=476';
import './project-tasks.js?v=540';
import './project-sharing.js?v=152';
import './projects.js?v=148';
import './admin-shell.js?v=288';
import './admin-users.js?v=190';
import './admin-roles.js?v=167';
import './admin-catalog.js?v=212';
import './admin-org.js?v=232';
import './admin-scoring.js?v=480';
import './admin-audit.js?v=296';
import './admin-storage.js?v=210';
import './admin-workload.js?v=316';
import './admin.js?v=147';
import './files.js?v=161';

const EMIE = window.EMIE;
const requiredEmieModules = [
  'coreRuntime', 'eventRuntime', 'coreAuth', 'coreIdentity', 'coreShell', 'coreUi', 'core',
  'dashboardProjects', 'dashboardHome', 'dashboardLists', 'dashboardScoring', 'dashboardPoints', 'dashboardDesigner', 'dashboard',
  'projectUploads', 'projectForm', 'projectDetail', 'projectTasks', 'projectSharing', 'projects', 'materialMarket',
  'adminShell', 'adminUsers', 'adminRoles', 'adminCatalog', 'adminOrg', 'adminScoring', 'adminAudit',
  'adminStorage', 'adminWorkload', 'admin', 'files',
];
const missingEmieModules = requiredEmieModules.filter(name => !EMIE.modules[name]);
if (missingEmieModules.length) {
  throw new Error('前端模块加载不完整: ' + missingEmieModules.join(', '));
}

document.addEventListener('DOMContentLoaded', EMIE.modules.coreAuth.initApp);
