# EMIE 现有权限盘点与首版权限矩阵

> 盘点日期：2026-07-24  
> 状态：第一期实施输入，首版权限编码冻结前基线  
> 关联方案：[权限系统建设方案](permission-system-plan.md)

## 1. 当前权限机制结论

系统目前同时存在三套没有真正统一的权限机制：

1. `roles.permissions` 保存逗号分隔的权限字符串，管理后台可以配置；
2. `/api/auth/permissions` 可以返回当前角色的权限字符串；
3. 实际页面显示和接口授权仍主要依靠角色名称及人员归属硬编码。

因此当前“角色管理 → 权限分配”并不能可靠改变真实业务权限。它更接近权限配置原型，而不是安全边界。

主要代码位置：

| 机制 | 位置 | 当前作用 |
|---|---|---|
| 角色权限存储 | `Role.permissions` | 保存逗号分隔权限；实体注释误写成 JSON |
| 默认角色权限 | `AdminService.initDefaultRoles()` | 仅角色表为空时初始化一次 |
| 权限定义 | `AdminService.getPermissionDefs()` | 供角色管理弹窗展示 |
| 当前用户权限接口 | `GET /api/auth/permissions` | 返回角色权限，但前端未统一消费 |
| 登录认证 | `AuthFilter` | 校验 token、pending 状态以及 `/api/admin/**` 的 admin 角色 |
| 页面与按钮 | `core-shell.js` 等 | 大量按 `currentRole` 判断 |
| 项目数据范围 | `ProjectAccessService`、`ProjectAccessPolicy` | 根据角色、项目归属、部门关系计算 |
| 业务动作 | 各 Controller、`ProjectService` | 根据角色、负责人、项目类型和状态判断 |

## 2. 当前角色

| 标准角色 | 中文名称 | 当前主要职责 |
|---|---|---|
| `admin` | 管理员 | 全项目查看、系统管理、常规品二审、身份切换 |
| `sales` | 销售 | 渠道项目创建和归属、渠道二审、设计需求创建与评分 |
| `planner` | 产品企划 | 常规品创建、项目接单、子任务管理、首审和评分 |
| `promotion` | 产品推广 | 设计需求创建与评分、产品宣发子任务 |
| `designer` | 设计师 | 设计任务接单、交付、自评 |
| `supplychain` | 供应链 | 供应链任务接单、交付、自评 |
| `pending` | 待授权 | 只允许读取当前账号信息 |

兼容别名：

```text
Promotion / product_promotion / product-promotion → promotion
supply / supply_chain / supply-chain / 供应链 → supplychain
administrator / 管理员 → admin
销售 → sales
企划 / 产品企划 → planner
设计师 → designer
```

当前风险：

- 角色标准化逻辑分散在多个类中，覆盖范围不一致。
- `AdminService.BUSINESS_ROLES` 和部分用户编辑白名单尚未完整包含 `promotion`。
- 默认角色只在角色表为空时写入，新增系统角色不会自动补齐。
- 用户当前只有一个 `role` 字段，尚不支持多角色。

## 3. 页面与导航盘点

| 页面 | 当前显示规则 | 首版权限编码 |
|---|---|---|
| 工作台 | 所有已授权角色 | `page.dashboard.view` |
| 全部项目 | 所有已授权角色 | `page.projects.view` |
| 渠道定制单 | 所有已授权角色 | `page.projects.channel.view` |
| 公司常规品 | 所有已授权角色 | `page.projects.regular.view` |
| 设计/送审需求 | 所有已授权角色 | `page.design_requirements.view` |
| 我的子任务 | 所有已授权角色 | `page.subtasks.mine.view` |
| 其他子任务 | 管理员、企划、符合部门负责人条件的销售 | `page.subtasks.department.view` |
| 评分 | 所有已授权角色 | `page.scoring.view` |
| 系统管理 | 仅 `admin` | `page.admin.view` |

发现：

- 导航结构在 `core-shell.js` 中按角色重复拼装。
- `navigate(view)` 没有统一路由权限拦截。
- 用户可以手动恢复 `localStorage` 中的旧页面标识；目前只能依赖后端接口拒绝。
- 后续应使用权限定义生成导航，不再维护按角色的三套菜单。

## 4. 项目权限盘点

### 4.1 页面动作

| 动作 | 当前规则 | 首版编码 |
|---|---|---|
| 查看项目列表 | 按角色和部门范围查询 | `project.view` |
| 查看项目详情 | 管理员或项目可见参与者 | `project.detail.view` |
| 新建渠道定制单 | 销售 | `project.channel.create` |
| 新建公司常规品 | 产品企划 | `project.regular.create` |
| 新建设计/送审需求 | 销售、产品推广、产品企划 | `design_requirement.create` |
| 编辑渠道项目信息 | 该项目所属销售 | `project.channel.edit` |
| 编辑常规品信息 | 该项目所属企划 | `project.regular.edit` |
| 项目接单 | 项目所属企划及符合状态条件 | `project.accept` |
| 暂停项目 | 当前由企划、销售、管理员相关逻辑控制 | `project.pause` |
| 恢复项目 | 当前由企划、销售、管理员相关逻辑控制 | `project.resume` |
| 终止项目 | 按角色和项目状态判断 | `project.terminate` |
| 删除项目 | Controller/Service 中按角色和状态判断 | `project.delete` |
| 查看操作日志 | 项目详情可见范围内 | `project.audit.view` |
| 创建分享链接 | 项目可管理者 | `project.share.create` |
| 管理全部分享链接 | 管理员 | `admin.share.manage` |
| 历史项目导入 | 管理员路径 | `admin.project_import.execute` |

### 4.2 当前数据范围

| 角色 | 当前项目范围 |
|---|---|
| 管理员 | 全部项目 |
| 销售 | 本人所属销售项目；部门负责人可扩展到部门成员 |
| 产品企划 | 本人负责、待企划接单及部门关联项目 |
| 设计师 | 本人作为负责人参与的子任务所属项目 |
| 供应链 | 本人作为负责人参与的子任务所属项目 |
| 产品推广 | 设计需求及本人负责子任务的范围逻辑尚未完全统一 |

现有范围实现集中在 `ProjectAccessService`、`ProjectSearchRepositoryImpl` 和 `ProjectAccessPolicy`，这是后续 Scope 引擎的主要迁移来源。

## 5. 子任务与流程权限盘点

| 动作 | 当前规则摘要 | 首版编码 |
|---|---|---|
| 查看本人子任务 | 本人负责人 | `subtask.mine.view` |
| 查看部门子任务 | 管理员、企划或符合部门负责人规则 | `subtask.department.view` |
| 新建子任务 | 项目所属企划，管理员已明确禁止 | `subtask.create` |
| 编辑子任务 | 项目/任务负责人和业务状态共同判断 | `subtask.edit` |
| 删除子任务 | 项目所属企划或现有特殊规则 | `subtask.delete` |
| 接单 | 当前任务负责人 | `subtask.accept` |
| 首次交付 | 当前任务负责人 | `subtask.deliver` |
| 再次交付 | 当前任务负责人且状态允许 | `subtask.redeliver` |
| 首审通过 | 产品企划 | `subtask.review.first.approve` |
| 首审驳回 | 产品企划 | `subtask.review.first.reject` |
| 渠道二审通过 | 项目所属销售 | `subtask.review.channel.approve` |
| 渠道二审驳回 | 项目所属销售 | `subtask.review.channel.reject` |
| 常规品二审通过 | 管理员 | `subtask.review.regular.approve` |
| 常规品二审驳回 | 管理员 | `subtask.review.regular.reject` |
| 查看驳回记录 | 可查看该子任务者 | `subtask.rejection_history.view` |
| 推进项目阶段 | 项目所属企划及阶段规则 | `project.workflow.advance` |
| 审核项目阶段 | 渠道销售或常规品管理员 | `project.workflow.review` |

业务状态仍必须作为权限之外的第二道条件。例如拥有 `subtask.deliver` 也只能交付本人负责、且处于可交付状态的任务。

## 6. 设计需求与评分权限盘点

| 动作 | 当前规则 | 首版编码 |
|---|---|---|
| 查看设计需求列表 | 管理员全量，其他角色按用户关系 | `design_requirement.view` |
| 查看设计需求详情 | 管理员、创建人、设计师、企划、评分人 | `design_requirement.detail.view` |
| 创建需求 | 销售、产品推广、产品企划 | `design_requirement.create` |
| 提交设计成果 | 指定设计师 | `design_requirement.deliver` |
| 设计师自评 | 指定设计师且处于自评阶段 | `design_requirement.score.self` |
| 复评 | 后端生成的指定评分人 | `design_requirement.score.review` |
| 查看评分中心 | 所有业务角色 | `scoring.view` |
| 提交项目子任务评分 | 匹配本轮评分记录者 | `scoring.submit` |
| 查看最终评分 | 当前随项目可见范围 | `scoring.result.view` |
| 修改评分权重 | 管理员 | `admin.scoring_weight.manage` |

评分权限的最终判断必须继续保留“当前用户是否是本轮评分记录指定人”，角色权限不能代替评分记录归属。

## 7. 系统管理权限盘点

当前 `AuthFilter` 对除公开配置外的全部 `/api/admin/**` 直接要求角色为 `admin`。这会阻止未来把用户管理、角色管理、通知管理分别授权给不同角色。

| 管理能力 | 首版编码 | 风险等级 |
|---|---|---|
| 进入系统管理 | `page.admin.view` | 重要 |
| 查看系统配置 | `admin.config.view` | 重要 |
| 修改系统配置 | `admin.config.edit` | 高危 |
| 管理用户 | `admin.user.manage` | 高危 |
| 分配用户角色 | `admin.user.role.assign` | 高危 |
| 管理角色 | `admin.role.manage` | 高危 |
| 配置权限 | `admin.permission.manage` | 高危 |
| 管理组织架构 | `admin.department.manage` | 高危 |
| 身份切换 | `admin.identity.switch` | 高危 |
| 发送通知测试 | `admin.notification.test` | 重要 |
| 发送全员通知 | `admin.notification.broadcast` | 高危 |
| 查看、重试失败通知 | `admin.notification.failure.manage` | 重要 |
| 管理基础字典 | `admin.catalog.manage` | 重要 |
| 查看工作量 | `admin.workload.view` | 普通 |
| 查看系统指标和日志 | `admin.system_monitor.view` | 重要 |
| 管理文件归档 | `admin.file_archive.manage` | 高危 |
| 执行飞书全量同步 | `admin.feishu_sync.execute` | 高危 |
| 清空测试业务数据 | `admin.data.clear` | 极高危 |
| 管理全部分享链接 | `admin.share.manage` | 高危 |

## 8. 文件权限盘点

| 动作 | 当前情况 | 首版编码 |
|---|---|---|
| 上传业务附件 | 已登录用户，业务归属校验分散 | `file.upload` |
| 下载业务附件 | 根据文件关联对象做访问判断 | `file.download` |
| 生成/读取预览 | 根据文件访问判断 | `file.preview` |
| 上传系统图片 | `/api/admin/upload-image`，管理员路径保护 | `admin.config.asset.upload` |
| 文件归档和恢复 | 管理员路径保护 | `admin.file_archive.manage` |

重点安全项：

- `/api/files/download/admin/**` 当前在认证过滤器白名单中，用于登录页图片访问；后续应只允许明确标记为公共系统资产的文件，不能把整个 `admin` 子目录视为公开。
- 文件访问必须继承关联项目、子任务或设计需求的数据范围。

## 9. API 保护现状

### 已具备

- `/api/**` 默认要求 token。
- `pending` 用户除 `/api/auth/me` 外统一返回 403。
- `/api/admin/**` 当前统一要求 `admin` 角色。
- 多数项目、任务和文件接口有业务归属校验。

### 缺口

- `roles.permissions` 没有成为统一后端授权依据。
- Controller 和 Service 中存在大量重复的角色字符串判断。
- 同一角色别名在不同路径的标准化结果可能不同。
- 部分基础数据的 `/all` 接口只依赖登录，没有独立管理权限。
- 前端菜单、按钮与后端判断没有统一来源。
- 403 响应缺少“缺少哪个权限、数据范围还是业务状态不允许”的结构化解释。
- 没有权限版本、用户例外权限、临时授权和权限审计表。

## 10. 首版权限编码冻结规则

从第一期开始，新增统一权限使用点号格式；旧的冒号权限只作为兼容别名：

```text
dashboard:view  → page.dashboard.view
project:view    → project.view
project:create  → project.create.legacy
project:edit    → project.edit.legacy
task:view       → subtask.view
task:assign     → subtask.create
task:execute    → subtask.execute.legacy
task:approve    → subtask.review.approve.legacy
task:reject     → subtask.review.reject.legacy
scoring:view    → scoring.view
scoring:submit  → scoring.submit
admin:config    → admin.config.edit
admin:users     → admin.user.manage
admin:roles     → admin.role.manage
file:upload     → file.upload
```

带 `.legacy` 的权限不得直接作为新配置项展示，只用于迁移期间保持行为一致，后续拆分为具体权限。

## 11. 第一批接入范围

第一批只接入低风险、容易验证的能力：

1. `page.dashboard.view`
2. `page.projects.view`
3. `page.projects.channel.view`
4. `page.projects.regular.view`
5. `page.design_requirements.view`
6. `page.subtasks.mine.view`
7. `page.subtasks.department.view`
8. `page.scoring.view`
9. `page.admin.view`
10. `project.channel.create`
11. `project.regular.create`
12. `design_requirement.create`

第一批只接管页面和新建入口；项目详情、写接口和数据范围在完成并行比对后接管。

## 12. 兼容上线策略

1. 新权限服务先读取角色配置，缺失时使用“当前角色行为模板”补齐。
2. 初期同时计算旧判断与新权限结果，记录差异但仍按旧规则执行。
3. 管理后台显示新权限定义，但不自动清空现有权限。
4. 完成全角色矩阵测试后，先接管页面入口。
5. 再按资源域逐步接管后端接口。
6. 全部接管后移除冒号权限兼容及散落角色判断。

## 13. 第一阶段验收矩阵

| 角色 | 工作台 | 项目页 | 渠道新建 | 常规品新建 | 设计需求新建 | 系统管理 |
|---|---:|---:|---:|---:|---:|---:|
| 管理员 | 是 | 是 | 否 | 否 | 否 | 是 |
| 销售 | 是 | 是 | 是 | 否 | 是 | 否 |
| 产品企划 | 是 | 是 | 否 | 是 | 是 | 否 |
| 产品推广 | 是 | 是 | 否 | 否 | 是 | 否 |
| 设计师 | 是 | 是 | 否 | 否 | 否 | 否 |
| 供应链 | 是 | 是 | 否 | 否 | 否 | 否 |
| 待授权 | 否 | 否 | 否 | 否 | 否 | 否 |

这张矩阵只描述第一批页面和新建入口，不代表完整业务权限。
