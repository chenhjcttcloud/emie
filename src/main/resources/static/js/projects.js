const EMIE = window.EMIE;

// EMIE 项目域门面：聚合项目子模块对外提供的兼容接口
EMIE.registerModule('projects', {
  openCreateProject: EMIE.modules.projectForm.openCreateProject,
  openProjectDetail: EMIE.modules.projectDetail.openProjectDetail,
  taskAccept: EMIE.modules.projectTasks.taskAccept,
  taskDeliver: EMIE.modules.projectTasks.taskDeliver,
  taskApprove: EMIE.modules.projectTasks.taskApprove,
  taskReject: EMIE.modules.projectTasks.taskReject,
  openScoring: EMIE.modules.projectTasks.openScoring,
  shareProject: EMIE.modules.projectSharing.shareProject,
});
