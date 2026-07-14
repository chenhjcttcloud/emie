const EMIE = window.EMIE;

// EMIE 管理域门面：聚合管理后台各职责子模块
EMIE.registerModule('admin', {
  renderAdmin: EMIE.modules.adminShell.renderAdmin,
  switchAdminTab: EMIE.modules.adminShell.switchAdminTab,
  renderAdminContent: EMIE.modules.adminShell.renderAdminContent,
});
