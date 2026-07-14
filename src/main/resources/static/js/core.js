const EMIE = window.EMIE;

// EMIE 核心域门面：聚合运行时、认证、身份、导航和 UI 子模块
EMIE.registerModule('core', {
  apiGet: EMIE.modules.coreRuntime.apiGet,
  apiPost: EMIE.modules.coreRuntime.apiPost,
  apiPut: EMIE.modules.coreRuntime.apiPut,
  apiDelete: EMIE.modules.coreRuntime.apiDelete,
  uploadFile: EMIE.modules.coreRuntime.uploadFile,
  initApp: EMIE.modules.coreAuth.initApp,
  handleFeishuLogin: EMIE.modules.coreAuth.handleFeishuLogin,
  handleLogout: EMIE.modules.coreAuth.handleLogout,
  navigate: EMIE.modules.coreShell.navigate,
  escHtml: EMIE.modules.coreUi.escHtml,
  displayText: EMIE.modules.coreUi.displayText,
  closeM: EMIE.modules.coreUi.closeM,
  openModal: EMIE.modules.coreUi.openModal,
});
