const EMIE = window.EMIE;

// EMIE 工作台域门面：聚合项目刷新、工作台、列表和评分子模块
EMIE.registerModule('dashboard', {
  render: EMIE.modules.dashboardProjects.render,
  renderDashboard: EMIE.modules.dashboardHome.renderDashboard,
  renderOrderList: EMIE.modules.dashboardLists.renderOrderList,
  renderMyTasks: EMIE.modules.dashboardLists.renderMyTasks,
  renderTaskMarket: EMIE.modules.dashboardDesigner.renderTaskMarket,
  renderScoringView: EMIE.modules.dashboardScoring.renderScoringView,
  renderPointsView: EMIE.modules.dashboardPoints.renderPointsView,
});
