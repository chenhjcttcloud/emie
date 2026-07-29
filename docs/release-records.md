# EMIE 生产发布记录

本文件按时间倒序记录代码推送和生产部署。记录中不得填写服务器密码、数据库密码、令牌、私钥或完整环境变量内容。

## 状态定义

- `待发布`：改动已纳入发布范围，尚未推送；
- `检查完成`：只完成本地/远端/环境检查，未推送或未部署；
- `已推送`：目标提交已经推送，但尚未完成生产部署；
- `发布成功`：生产部署与业务验证均通过；
- `发布失败`：部署或验证失败，尚未恢复；
- `已回滚`：发布失败后已恢复至上一稳定版本并验证。

---

## 2026-07-29 WPS 快捷键复制与项目协作可见性发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `65d6de9dd3751838d50ddbe04f370189d6e79190` / `2835d08ab4749cc8f07a2ffb5d339a961dc23cdc` |
| Gitee 推送 | `project_manager_system` 已推送，本地与远端完整 SHA 一致 |
| 变更范围 | 放大图快捷键复制改为原生 PNG 图片剪贴板；项目参与者可在项目详情查看跨设计师、供应链、产品推广角色的完整子任务链，写权限不扩大。 |
| 本地验证 | Java 21 `clean package` 成功，108 项测试通过；全部前端 JavaScript 语法和差异检查通过；Firefox → WPS 连续复制两次均保持 `14.29 × 19.05 cm` 与锁定纵横比；测试环境加载 `bootstrap.js?v=233`。 |
| 数据库 | 无结构或数据迁移；发布前备份 `/home/emie/emie-deploy-backups/20260729_185154/designpm.sql.gz`，218674 字节且 `gzip -t` 通过。 |
| 应用产物 | 本地、上传产物、镜像与生产容器 JAR SHA-256 均为 `7e575e1fd3dfa6f30fa7132ca586817d32ed4527eef4e0afc6ad6495e3a3e48a`。 |
| 回滚 | 旧 JAR 位于 `/home/emie/emie-deploy-backups/20260729_185154/app.jar`，旧镜像标签为 `emie-app:backup-20260729_185154`；未执行回滚。 |
| 生产验证 | `release-sha.txt` 与功能提交一致；`emie-app:2835d08` running、重启次数 0；内网、公开配置和 `emie.emie.cn:9001` HTTP 200；生产加载 `bootstrap.js?v=233`、`project-detail.js?v=188`、`project-uploads.js?v=155`；受保护项目接口未登录返回预期 401。 |
| 发布观察 | 应用正常启动后，飞书主表镜像定时对账出现一次外部请求超时；该轮按既有保护机制停止全量重刷，未影响应用健康和本次功能，需继续观察后续对账。 |

## 2026-07-29 执行角色工作台与子任务驳回期限发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `3e94237` / `65d6de9dd3751838d50ddbe04f370189d6e79190` |
| Gitee 推送 | `project_manager_system` 已推送，功能提交的本地与远端完整 SHA 一致 |
| 变更范围 | 设计师、供应链和产品推广工作台按项目分类展示待接单、设计中、已驳回的关联子任务，并过滤已完成、待终止和已终止项目；驳回子任务必须设置新完成时间；修复图片连续拖入 PowerPoint 时比例不一致。 |
| 本地验证 | Java 21 `clean package` 成功，108 项测试通过；全量前端 JavaScript 语法、暂存差异与空白检查通过。 |
| 数据库 | 无结构迁移；发布前备份 `/home/emie/emie-deploy-backups/20260729_174843/designpm.sql.gz`，256625 字节且 `gzip -t` 通过。 |
| 应用产物 | 本地、上传产物与生产容器 JAR SHA-256 均为 `a7246d519556ec6e96da3fa4b68e4cb2621c4a8d7431ce326ea1226b43b531c7`。 |
| 回滚 | 旧 JAR 位于 `/home/emie/emie-deploy-backups/20260729_174843/app.jar`，旧镜像标签为 `emie-app:backup-20260729_174843`；未执行回滚。 |
| 生产验证 | `release-sha.txt` 与功能提交一致；`emie-app:65d6de9` running、重启次数 0；首页、公开配置、内网入口和 `emie.emie.cn:9001` HTTP 200，`product.emie.cn` 正常跳转登录后 HTTP 200；生产加载 `bootstrap.js?v=228`；启动后无新增严重日志。 |
| 已知问题 | 发布前日志存在一次通知幂等键长度超限，业务操作按降级策略继续；本次启动后未复现，后续单独治理。 |

## 2026-07-28 产品推广“我的子任务”修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产目标 / 生产提交 | 本地生产服务器 `192.168.200.51` / `4b150f50c053ff694bf8883e139034c493eb080a` |
| 变更范围 | 产品推广作为子任务负责人时进入“我的子任务”执行视图，不再错误进入项目列表；卡片显示产品推广角色标识。 |
| 本地验证 | Java 21 `clean package` 成功，106 项测试通过。 |
| 数据库 | 无迁移；发布前已完成数据库备份。 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `17a0e1b8e2ca1cf2471df8f515a7792b5d8f25fe61a1a6243cfea987c925053b`。 |
| 回滚包 | 镜像 `emie-app:backup-20260728_143650`。 |
| 生产验证 | `release-sha.txt` 与目标提交一致；容器 running、重启次数 0；外网 HTTPS HTTP 200；生产加载 `dashboard-lists.js?v=185` 和 `dashboard-designer.js?v=177`；启动日志无严重错误。 |
| 回滚 | 未执行。 |

## 2026-07-28 子任务通知与配置管理修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产目标 / 生产提交 | 本地生产服务器 `192.168.200.51` / `ec44b025cb1994c02f5246d6c20e875cf4f343dc` |
| Gitee 推送 | `project_manager_system` 已推送，功能提交的本地与远端完整 SHA 一致 |
| 变更范围 | 子任务创建先持久化获得真实 ID，再注册提交后通知；通知异常不再影响主业务。产品推广补齐子任务接单、交付、重新交付权限。系统管理将飞书跳转地址、数据库类型和连接地址以清晰中文配置项展示，跳转地址增加服务端 URL 校验。 |
| 本地验证 | Java 21 `clean package` 成功，106 项测试通过；前端模块语法检查通过。 |
| 数据库 | 发布前备份 `/home/emie/emie-deploy-backups/20260728_105426/designpm.sql.gz`，173420 字节，`gzip -t` 通过；执行可重复权限迁移后，产品推广具备 3 项子任务执行权限。 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `1c02cfc053f000ce359240a27aa91e06462dda63ee93f9e5a992566928cef059`。 |
| 回滚包 | 镜像 `emie-app:backup-20260728_105426`。 |
| 生产验证 | `release-sha.txt` 与目标提交一致；容器 `emie-app:ec44b02` running、重启次数 0；本机公开配置和外网 HTTPS 入口 HTTP 200；生产加载 `admin-shell.js?v=151`；飞书跳转地址为 `https://emie.emie.cn:9001`，数据库展示配置为 MySQL 8.4.10 与 `127.0.0.1:3306/designpm`。 |
| 回滚 | 未执行。 |

## 2026-07-27 权限治理与子任务版本化交付发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产目标 | 本地生产服务器 `192.168.200.51` |
| 生产提交 | `20c4399ae6e9c16333f5efd157bdc88defe01d00` |
| Gitee 推送 | `project_manager_system` 已推送，本地与远端完整 SHA 一致 |
| 变更范围 | 完成权限治理基础设施与管理页面；补齐角色、通知和评分逻辑；新增子任务详情、驳回/修改记录、版本化交付与修正交付；上传白名单新增 `.step` |
| 本地验证 | Java 21 `clean package` 成功，106 项测试通过 |
| 数据库 | 发布前备份 `/home/emie/emie-deploy-backups/20260727_163016/designpm.sql.gz`，169978 字节，`gzip -t` 通过；执行权限基础和子任务交付版本两条幂等迁移，最终为 71 项权限、34 条交付版本 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `d31715821aef84cb3ec3cdb88fa2a16eba07fce200fe63696260ed7e6044911d` |
| 回滚包 | 镜像 `emie-app:backup-20260727_163016`，镜像归档 `/home/emie/emie-deploy-backups/20260727_163016/emie-app-image.tar.gz` |
| 生产验证 | `release-sha.txt` 与目标提交一致；`emie-app:20c4399` running、重启次数 0；首页和公开配置 HTTP 200，未登录受保护项目接口返回预期 401；外网 HTTPS 入口 HTTP 200；生产加载 `bootstrap.js?v=227`；启动日志无严重错误 |
| 云端校正 | 发布目标识别偏差期间未替换云端 JAR；云端数据库已由发布前备份恢复，并清除仅本轮误加且备份中不存在的 6 张新表。云端仍运行原提交 `66346838f8183c5f5a276d3b2fbd6955e396c375`，公开配置 HTTP 200 |
| 回滚 | 本地生产未执行；云端误操作已完整校正 |

## 2026-07-24 产品推广切换角色徽章修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `0abd63a16ed4fa37a2ca6f1e516d9cba3a75eda7` / `66346838f8183c5f5a276d3b2fbd6955e396c375` |
| Gitee 推送 | `project_manager_system` 已推送，功能提交的本地与远端完整 SHA 一致 |
| 变更范围 | 补充管理员身份切换器中产品推广角色的紫色徽章样式，并将 CSS 缓存版本递增至 `app.css?v=29` |
| 本地验证 | Java 21 `clean package` 成功，85 项测试通过；`git diff --check` 通过 |
| 数据库 | 无结构或数据变更；复用同日同轮已验证备份 `/root/emie/db-backup-before-d2b6782-20260724_153416.sql.gz` |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `a2dcfa578a865436c828ff491e299ce3e363a9b32cac20ae412938fe82a7ade1` |
| 回滚包 | `/root/emie/app.jar.bak.pre-6634683-20260724_161457` |
| 生产验证 | `release-sha.txt` 与目标提交一致；首页、公开配置和预览健康接口 HTTP 200；受保护项目接口未登录返回预期 401；生产加载 `app.css?v=29` 且包含 `.identity-user-role.r-promotion`；应用和预览容器 running、重启次数均为 0；启动日志无应用严重错误 |
| 回滚 | 未执行 |

## 2026-07-24 设计需求“草稿”状态文案修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `d2b67823aff0323140fb6f2289128ba36f8abe3c` / `0abd63a16ed4fa37a2ca6f1e516d9cba3a75eda7` |
| Gitee 推送 | `project_manager_system` 已推送，功能提交的本地与远端完整 SHA 一致 |
| 变更范围 | 项目列表优先采用接口返回的业务状态文案，修复设计需求内部状态 `draft` 错误显示为“草稿”，正确展示“待设计交付” |
| 本地验证 | Java 21 `clean package` 成功，85 项测试通过；全量前端 JavaScript 语法和差异检查通过 |
| 数据库 | 无结构或数据变更；复用同轮发布已验证备份 `/root/emie/db-backup-before-d2b6782-20260724_153416.sql.gz` |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `acd22851d2fdff6660fac9aad7634f011e59082f7a9ae58f7ee89a03eb540b21` |
| 回滚包 | `/root/emie/app.jar.bak.pre-0abd63a-20260724_155428` |
| 生产验证 | `release-sha.txt` 与目标提交一致；首页 HTTP 200；生产加载 `bootstrap.js?v=216`、`dashboard-projects.js?v=159`；应用和预览容器 running、重启次数均为 0；近期严重日志 0 |
| 回滚 | 未执行 |

## 2026-07-24 设计/送审需求交付与评分发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `a38d9ff72e8a89b007195a5dff2d4674fc503518` / `d2b67823aff0323140fb6f2289128ba36f8abe3c` |
| Gitee 推送 | `project_manager_system` 已推送，功能提交的本地与远端完整 SHA 一致 |
| 变更范围 | 修复产品推广角色显示及设计需求创建入口；设计需求增加真实设计师/企划校验、成果图片与附件交付、设计师自评和按创建人动态双人复评；评分任务接入评分中心 |
| 本地验证 | Java 21 `clean package` 成功，85 项测试通过；全量前端 JavaScript 语法检查、`git diff --check` 和本地页面加载检查通过 |
| 数据库备份 | `/root/emie/db-backup-before-d2b6782-20260724_153416.sql.gz`，156425 字节，SHA-256 `7183fe163a7a469be94945b10822c599a275942c4b192537ec03a16c56afb241`，`gzip -t` 及关键表检查通过 |
| 数据库迁移 | `2026-07-24-add-design-requirement-scoring.sql` 已执行并登记；7 个新字段、`design_requirement_scores` 表、3 个索引和外键均验证存在；迁移前历史设计需求为 0 条 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `ef5e1bd000429e59a20eb5914ed76acebce64b8c3309491cbf2ea820e442265a` |
| 回滚包 | `/root/emie/app.jar.bak.pre-d2b6782-20260724_153506` |
| 生产验证 | `release-sha.txt` 与目标提交一致；应用和预览容器 running；首页、公开配置 HTTP 200；未登录设计需求列表和评分接口均为 401；生产加载 `bootstrap.js?v=215`、`dashboard-lists.js?v=182`、`dashboard-scoring.js?v=161`、`project-form.js?v=181`；启动日志无严重错误 |
| 备份异常 | 首次备份尝试因临时 MySQL 容器网络和表空间权限提示未作为恢复点；改用宿主网络及 `--no-tablespaces` 后生成并验证上述有效备份，迁移前未改动数据库 |
| 外部动作 | 发布过程未触发系统通知或飞书群发接口 |
| 回滚 | 未执行 |

## 2026-07-23 子任务驳回快照与列表整行交互发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `7106c73a02fc51a720d0ccaf8ab1bb21ba3ac9e5` / `a38d9ff72e8a89b007195a5dff2d4674fc503518` |
| Gitee 推送 | `project_manager_system` 已推送，本地与远端目标提交一致 |
| 变更范围 | 子任务卡片逐行展示每次驳回及不可覆盖的当轮提交快照；渠道定制单、公司常规品和设计/送审需求支持整行打开详情；设计需求新增独立详情接口和弹窗 |
| 本地验证 | Java 21 `clean package` 成功，82 项测试通过；全量前端 JavaScript 语法检查和 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-a38d9ff-20260723_180350.sql.gz`，151399 字节，`gzip -t` 通过 |
| 数据库迁移 | 无表结构变更，不需迁移 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `3f50f4fc8a59747a64995b90091ae0eea01a551cf5ebfc27a975100e247cbc91` |
| 回滚包 | `/root/emie/app.jar.bak.pre-a38d9ff-20260723_180417` |
| 生产验证 | `release-sha.txt` 与目标提交一致；应用和预览容器 running、重启次数均为 0；首页、公开配置和预览健康接口 HTTP 200；未登录项目及设计需求详情接口均为 401；生产加载 `bootstrap.js?v=213`、`dashboard-projects.js?v=158`、`dashboard-lists.js?v=180`、`project-detail.js?v=182`；启动日志无严重错误 |
| 回滚 | 未执行 |

## 2026-07-23 六阶段子任务进度与动态角色发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `961cd1bd8f0939e89f00b5483e281831badca1f6` / `7106c73a02fc51a720d0ccaf8ab1bb21ba3ac9e5` |
| Gitee 推送 | `project_manager_system` 已推送，本地、远端目标提交一致 |
| 变更范围 | 全员通知后台任务启动修复；管理员子任务创建权限；动态管理员身份切换；子任务六阶段聚合进度、3D送审、产品推广负责人；项目总进度与大货完成联动 |
| 本地验证 | Java 21 `clean package` 成功，81 项测试通过；全量前端 JavaScript 语法检查、`git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-7106c73-20260723_174342.sql.gz`，151016 字节，`gzip -t` 通过；前两次不满足标准的产物已分别标记为 `.invalid`、`.partial`，不作为恢复点 |
| 数据库迁移 | `2026-07-23-add-project-workflow.sql` 已执行；`projects` 两个流程字段、`sub_tasks.workflow_stage` 和 `project_workflow_attempts` 表均存在，历史项目及子任务阶段空值为 0 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `a71cd1c9527718fc1ca0f2616af42f33bd4c843fe42b3a5d17192f79571af261` |
| 回滚包 | `/root/emie/app.jar.bak.pre-7106c73-20260723_174623` |
| 生产验证 | `release-sha.txt` 与目标提交一致；应用和预览容器 running、重启次数均为 0；首页、公开配置和预览健康接口 HTTP 200；未登录项目接口 401；生产加载 `bootstrap.js?v=211`、`project-form.js?v=180`、`project-detail.js?v=181`、`project-tasks.js?v=180`；启动日志无严重错误 |
| 外部动作 | 发布过程未触发全员通知发送接口，未产生飞书群发 |
| 回滚 | 未执行 |

## 2026-07-23 子任务、评分中心、设计需求与系统更新通知发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产标记 / 生产提交 | `efed402`（旧标记与实际 JAR 时间不一致） / `961cd1bd8f0939e89f00b5483e281831badca1f6` |
| Gitee 推送 | `project_manager_system` 已推送，本地与远端目标提交一致 |
| 变更范围 | 子任务展示所属项目及添加权限修复；评分中心产品企划筛选、驳回排除和待评分排序；独立设计/送审需求迁移与测试；系统更新飞书全员通知入口；本地启动及 Maven Wrapper 修复 |
| 本地验证 | Java 21 精确提交干净构建成功，75 项测试通过；全量前端 JavaScript 语法检查和 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-961cd1b-20260723_115649.sql.gz`，非空且 `gzip -t` 通过 |
| 数据库迁移 | `2026-07-23-add-design-requirements.sql` 已执行；`design_requirements` 表存在且 18 个字段完整 |
| 应用产物 | JAR SHA-256 `8623d545818398c481c23afa60e49dc811caf9b1735949bee872904cad2d9abc`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-961cd1b-20260723_115649`，基于发布前实际 JAR 创建 |
| 构建异常 | 首次 BuildKit 因生产目录缺少 `.dockerignore`、发送持久化上下文而无进展；旧应用持续运行。终止后增加忽略规则，并短暂停止应用释放内存，传统构建器成功重建；未删除上传、日志或备份 |
| 生产验证 | `release-sha.txt` 与目标提交一致；应用和预览容器 running；公开配置和预览健康接口 HTTP 200；未登录项目接口 401；加载 `bootstrap.js?v=199`、`admin-shell.js?v=149`；启动日志正常 |
| 外部动作 | 发布过程未调用系统更新通知发送接口，未产生全员飞书通知 |
| 回滚 | 未执行 |

## 2026-07-21 AI 文件选择器兼容修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产提交 | `efed402` |
| 变更范围 | 图片/参考图上传入口的浏览器文件选择器新增 `.ai` 扩展名；同步递增前端资源版本，避免旧缓存继续拦截 |
| 数据库备份 | `/root/emie/db-backup-before-e9936d3-20260721_171639.sql.gz`，发布前已创建 |
| 应用产物 | JAR SHA-256 `74f3c4924e6a5c34122c0623680bc63228d2e3ba8b0230b9056c2a8d69afedbe` |
| 回滚包 | `/root/emie/app.jar.bak.pre-e14d215-20260721_171639` |
| 生产验证 | `release-sha.txt=e14d21577dbd8ab92536ad79ba2f6f3a00e87058`；应用容器 running；公开配置 HTTP 200 |
| 回滚 | 未执行 |

## 2026-07-21 AI 文件上传兼容修复发布

## 2026-07-21 AI 文件上传兼容修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产提交 | `209c158` |
| 变更范围 | `.ai` 文件同时允许出现在附件和参考图片上传入口；仍不生成缩略图、不在线预览，仅保存和下载 |
| 数据库备份 | `/root/emie/db-backup-before-87a8a2e-20260721_170013.sql.gz`，`gzip -t` 通过 |
| 应用产物 | JAR SHA-256 `87a8a2ebe8e49f0f998ac2d0a9a59665f171b08d5b7b823c3d8e9fc5f787da82` |
| 生产验证 | `release-sha.txt=209c158`；应用容器 running；公开配置 HTTP 200 |
| 回滚 | 未执行 |

## 2026-07-21 项目筛选、子任务权限与 AI 附件支持发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `6ca3a45` / `5f7232c` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 全部项目负责人两级筛选；企划统一项目视角；子任务负责人范围与其他子任务权限调整；支持销售接收企划派发的子任务；`.ai` 附件上传；缩略图并发与 JVM 内存优化 |
| 数据库备份 | `/root/emie/db-backup-before-442854b-20260721_163504.sql.gz`，`gzip -t` 通过 |
| 应用产物 | JAR SHA-256 `442854bc2df199d0b407a0ff20f002f795bc1eb995b6f286ff71aaf735fce359` |
| 回滚包 | `/root/emie/app.jar.bak.pre-442854b-20260721_163504` |
| 生产验证 | `release-sha.txt=5f7232c`；应用与预览容器 running；公开配置 HTTP 200；Java 21 启动完成；JVM 使用 `-Xmx320m` |
| 回滚 | 未执行 |

## 2026-07-20 系统时区统一发布

## 2026-07-20 系统时区统一发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `2f21b58` / `6ca3a45` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 本地启动脚本、生产 Compose 和 JVM 统一使用 `Asia/Shanghai`（UTC+8） |
| 本地验证 | Java 21 测试、`git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-6ca3a45-20260720_195430.sql.gz`，`gzip -t` 通过；SHA-256 `d75650ca7b0ecf28554f9af4a0dc93d104af332e27c4743af162214390804870` |
| 应用产物 | JAR SHA-256 `b2f9de664deac10569cf10bc865ef0e3e8cb977f00b8d5cf28373db0ca5d5389`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-6ca3a45-20260720_195430`，旧 JAR SHA-256 `be389f12714df203629d9081f6a929a84a52e48ff515f207a123df13cd055024` |
| 生产配置备份 | `/root/emie/docker-compose.yml.bak.pre-timezone-20260720_195744` |
| 生产验证 | `release-sha.txt=6ca3a45`；主机和应用容器均为 `CST`；Java 日志显示 `+08:00`；公开配置 HTTP 200；两个容器 running |
| 回滚 | 未执行 |

## 2026-07-20 飞书同步游标异常修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `899d58b` / `2f21b58` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 修复飞书同步游标缺失/异常时使用 MySQL 不支持极小时间导致定时对账失败 |
| 本地验证 | Java 21 测试、`git diff --check` 通过；生产构建使用精确提交 `2f21b58` |
| 数据库备份 | `/root/emie/db-backup-before-2f21b58-20260720_193842.sql.gz`，`gzip -t` 通过；SHA-256 `2aa3cb8690ab02d7ebd6df3fb21f12c93bfa5a8b39d89ff382310564e0422b65` |
| 数据库迁移 | 无 |
| 应用产物 | JAR SHA-256 `be389f12714df203629d9081f6a929a84a52e48ff515f207a123df13cd055024`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-2f21b58-20260720_193842`，旧 JAR SHA-256 `b30450adf6000d9d6cddd739a4e1916ad99dfae6d1c2c8b48fbeb8d4ce80927f` |
| 生产验证 | `release-sha.txt=2f21b58`；应用和预览容器 running；公开配置 HTTP 200；应用启动后未再出现 `Incorrect DATETIME` |
| 同步状态 | 历史失败数仍为 3 条，属于既有记录；发布后未新增同类失败日志，待下一轮同步继续观察 |
| 回滚 | 未执行 |

## 2026-07-20 上传提示优化发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `31f4140` / `899d58b` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 图片和附件上传区域统一提示支持拖拽上传和点击选择 |
| 本地验证 | Java 21 测试、全量前端 JS 语法检查、`git diff --check` 通过 |
| 数据库迁移 | 无 |
| 应用产物 | JAR SHA-256 `b30450adf6000d9d6cddd739a4e1916ad99dfae6d1c2c8b48fbeb8d4ce80927f`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-899d58b-20260720_175210`，旧 JAR SHA-256 `acec0608dc817c48260a5e5865a8d5770a97aff34f2b4677bb115acd0ba1e2d3` |
| 生产验证 | `release-sha.txt=899d58b`；两个容器 running；公开配置 HTTP 200；生产加载 `bootstrap.js?v=174`、上传模块 `v174` |
| 日志 | 发布后启动日志无 `ERROR` 或异常，启动耗时约 33 秒 |
| 回滚 | 未执行 |

## 2026-07-20 项目编号与子任务分页发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `dec6113` / `31f4140` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 项目编号 `EMIEyyyyMM####`、编号搜索、待评分统计口径修复、子任务管理服务端分页、任务筛选简化、图片缩略图加载优化 |
| 本地验证 | Java 21 测试、全量前端 JS 语法检查、`git diff --check` 通过；生产构建使用精确提交 `31f4140` |
| 数据库备份 | `/root/emie/db-backup-before-31f4140-20260720_154128.sql.gz`，`gzip -t` 通过；SHA-256 `324ef1dc11f17d20d27cd51858e5f9973544bdc402861f41f1b5b47d2dff689b` |
| 数据库迁移 | `2026-07-20-add-project-code.sql` 已执行；生产 34 个项目均已具备项目编号 |
| 应用产物 | JAR SHA-256 `acec0608dc817c48260a5e5865a8d5770a97aff34f2b4677bb115acd0ba1e2d3`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-31f4140-20260720_154128`，旧 JAR SHA-256 `7ca4f375450f7be0c73863a1861b8d577ef65ca7daf66f845cd0e27722a52f74` |
| 生产验证 | `release-sha.txt=31f4140`；`emie-app`、`emie-preview-converter` 均 running；公开配置 HTTP 200；Java 21、Tomcat 8080 正常启动 |
| 日志 | 发布后启动日志无 `ERROR` 或异常，启动耗时约 33 秒 |
| 回滚 | 未执行 |

## 2026-07-20 公司常规品二次评分通知发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `850874141c72894fa4acb0de3299f5c5f554f201` / `dec6113` |
| Gitee 推送 | `project_manager_system` 已推送，目标提交已确认 |
| 变更范围 | 产品企划完成公司常规品评分后，通知全部有效管理员；逐人保留站内、飞书投递和审计记录，并防重复通知 |
| 本地验证 | Java 21 `./mvnw test -q`、`git diff --check` 通过；生产构建使用精确提交 `dec6113` |
| 数据库备份 | `/root/emie/db-backup-before-dec6113-20260720_143023.sql.gz`，`gzip -t` 通过；SHA-256 `2b386ec9b39811171f47901e3ed190b4b5945bb6ee29121c1e2e6fab4ea5b4e6` |
| 数据库迁移 | 无，本次仅应用逻辑变更 |
| 应用产物 | JAR SHA-256 `7ca4f375450f7be0c73863a1861b8d577ef65ca7daf66f845cd0e27722a52f74`，上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-dec6113-20260720_143023`，旧 JAR SHA-256 `47b20327a4772468ed183a8cb6dd6dc6b3c847a2ef3d66a73d9aede9eddc5ed7` |
| 生产验证 | `release-sha.txt=dec6113`；`emie-app`、`emie-preview-converter` 均 running；公开配置 HTTP 200；应用使用 Java 21 启动且 Tomcat 8080 正常监听 |
| 日志 | 发布后启动日志无 `ERROR` 或异常，启动耗时约 33 秒 |
| 回滚 | 未执行 |

## 2026-07-20 子任务视图、角色管理与剪贴板图片上传发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 生产提交 | `9e6f6262d3e6cabfbadf9ceb06edbade829eb186` / `dfd84d9c2bcf936ae468bfaecefab3bb00dcb55b` |
| Gitee 推送 | `project_manager_system` 已推送，远端 SHA 与目标提交一致 |
| 变更范围 | 我的子任务独立查询、部门负责人和管理员候选、用户角色显示兼容、工作台子任务统计、上传框剪贴板图片粘贴 |
| 本地验证 | Java 21 `clean package` 成功，65/65 测试通过；全量前端 JS 语法检查和 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-373ed7e-20260720_115935.sql.gz`，55034 字节，`gzip -t` 通过 |
| 数据库迁移 | `2026-07-18-add-my-subtask-publisher.sql` 已执行；`sub_tasks` 的 `publisher_id`、`publisher_name`、`publisher_role` 三列已验证 |
| 应用产物 | JAR SHA-256：`dded226676ef5658b6a40690b84d23dfd0d2060d40f40b0daf9b92202d7f9840`；生产上传前后核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-dfd84d9-20260720_120153` |
| 生产验证 | `release-sha.txt`、应用公开配置 HTTP 200、`bootstrap.js?v=165`、剪贴板上传模块 HTTP 200；`emie-app` 与 `emie-preview-converter` 均 running |
| 日志 | 发布后应用日志无新增 `ERROR` |
| 待人工验收 | 登录后检查我的子任务、组织架构管理员候选、用户角色徽章，以及飞书图片复制后粘贴上传 |

## 2026-07-20 产品企划子任务只读页面修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产提交 | `2657128eb05ffa62b69259e96a87a867ab503a23` |
| Gitee 推送 | `project_manager_system` 已推送，远端 SHA 与提交一致 |
| 变更范围 | 修复产品企划打开全部子任务/待处理子任务时 `readonly is not defined` 的前端运行时错误 |
| 本地验证 | Java 21 clean package、65/65 测试、全量 JS 语法检查、`git diff --check` 通过 |
| 数据库迁移 | 无 |
| 应用产物 | JAR SHA-256：`41cd0727a83c52b9e022a79dc88371487161adb28848d10378a7ca9a3a1168ae`；生产核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-2657128-20260720_133934` |
| 生产验证 | `release-sha.txt` 为目标提交，公开配置 HTTP 200，首页加载 `bootstrap.js?v=166`，`emie-app` 和 `emie-preview-converter` 均 running，发布后日志无新增 ERROR |

## 2026-07-20 其他子任务 403 体验修复发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产提交 | `66090a483c44ded4f98d496e55d32eea444e465d` |
| Gitee 推送 | `project_manager_system` 已推送 |
| 变更范围 | 普通成员打开“其他子任务”无部门可见范围时返回空列表，不再出现 403；部门负责人和管理员权限不变 |
| 数据库迁移 | 无 |
| 应用产物 | JAR SHA-256：`6a1f9dcd1c97afc6cfaee9a0e061b63d9f41330b9c9955f90473fd81f39ea8b0`；生产核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-66090a4-20260720_135308` |
| 生产验证 | `release-sha.txt` 为目标提交，公开配置 HTTP 200，首页加载 `bootstrap.js?v=167`，两个容器 running，发布后日志无新增 ERROR |

## 2026-07-20 普通成员隐藏其他子任务入口发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 生产提交 | `850874141c72894fa4acb0de3299f5c5f554f201` |
| Gitee 推送 | `project_manager_system` 已推送 |
| 变更范围 | 普通销售/产品企划隐藏“其他子任务”，管理员及匹配部门负责人保留入口；后端权限不变 |
| 数据库迁移 | 无 |
| 应用产物 | JAR SHA-256：`47b20327a4772468ed183a8cb6dd6dc6b3c847a2ef3d66a73d9aede9eddc5ed7`；生产核对一致 |
| 回滚包 | `/root/emie/app.jar.bak.pre-8508741-20260720_140430` |
| 生产验证 | `release-sha.txt` 为目标提交，公开配置 HTTP 200，首页加载 `bootstrap.js?v=168`，两个容器 running，发布后日志无新增 ERROR |

---

## 2026-07-17 工作台、通知、同步与日期控件优化发布

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功（首次尝试因构造器注入失败回滚，修复后重新发布） |
| 旧生产提交 / 首次提交 / 最终生产提交 | `62502d764dbe77fc9f5f8493480688d9cf56cead` / `2f5d0b53dcdc052ed3693a879da7ae566d549b2a` / `0ece99758b54f0d1ceaf786a405e45bbdae94713` |
| Gitee 推送 | `project_manager_system` 已核对与最终本地 SHA 一致 |
| 变更范围 | 工作台 ID+数据库聚合、前端请求缓存、通知失败/死信重试、JVM/连接池/同步队列告警、同步增量游标、查询索引、Safari/多浏览器日期控件 |
| 本地验证 | Java 21 `clean package` 成功，65/65 测试通过；全量前端 JS 语法检查和 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-236de30_20260717_123040.sql.gz`，47931 字节，`gzip -t` 通过 |
| 数据库迁移 | `2026-07-17-add-notification-retry-payload.sql`、`2026-07-17-add-query-hotpath-indexes.sql`、`2026-07-17-add-scoring-badge-index.sql`、`2026-07-17-add-sync-change-cursors.sql` 已执行并验证；同步字段迁移按 MySQL 兼容写法重新执行 |
| 首次发布异常 | `2f5d0b5` 启动时 Spring 无法在两个 `SyncWorker` 构造器中选择构造器，应用未监听 8080；未产生数据写入影响 |
| 回滚 | 恢复 `/root/emie/app.jar.bak.pre-62502d7-20260717_001440`，生产 SHA 恢复为 `62502d...`；应用 HTTP 200、公开配置 HTTP 200、预览 HTTP 200 |
| 最终产物核验 | 最终 JAR SHA-256 `6aa62496fe5b223bd35822e654bf26c4c9acd823db3d8e80abe73a2aa327fbc3`，上传前后核对一致 |
| 最终生产验证 | `emie-app` 与 `emie-preview-converter` 持续运行；应用首页 HTTP 200、公开配置 HTTP 200、预览健康 HTTP 200；最终日志无启动异常；生产 `release-sha.txt` 为 `0ece997...` |
| 待人工验收 | 使用实际管理员、产品企划和设计师账号检查工作台、通知失败管理、项目日期控件及飞书同步状态 |

## 2026-07-17 生产备份清理

| 项目 | 记录 |
|---|---|
| 状态 | 已完成 |
| 清理范围 | `/root/emie` 下历史数据库、应用 JAR、同步队列、Docker/配置备份；未触碰当前 `app.jar`、上传文件、日志和 Docker volume |
| 清理结果 | 删除 112 个历史备份文件，备份占用由约 5.6GB 降至约 187MB |
| 保留文件 | 数据库最近两份有效备份、应用最近两份回滚包、同步队列最近两份、Dockerfile 一份 |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 仍正常运行；清理后未重启服务，生产代码 SHA 仍为 `0ece997...` |

## 2026-07-17 渠道定制项目批量导入

| 项目 | 记录 |
|---|---|
| 状态 | 已完成 |
| 数据来源 | 用户确认的 `EMIE-项目批量导入模板.xlsx`，仅处理「渠道定制单」工作表，忽略「公司常规品」 |
| 预校验 | 3 条全部通过；销售、企划、类目、价格区间、IP、二级 IP、市场和日期均符合当前生产配置 |
| 导入结果 | 新增 3 条渠道定制项目，项目 ID `32`、`33`、`34`；日期分别为 `2026-10-20`、`2026-08-30`、`2026-10-30`；价格区间均为「100元以下」；IP 为「三丽鸥」，二级 IP 为 `HelloKitty` |
| 导入方式 | 生产应用受控一次性 Excel 导入命令，未修改应用代码；公司常规品未写入 |
| 生产验证 | 应用首页 HTTP 200；项目记录已从生产数据库核对；相关导入操作日志同步队列状态为 done |

---

## 2026-07-17 左侧导航统计统一修复

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 新生产提交 | `bc396bf1f1a96815f43e4803502a575b52ac99ab` / `62502d764dbe77fc9f5f8493480688d9cf56cead` |
| 变更范围 | 收敛项目、类型、我的子任务和待评分徽章为唯一后端统计口径；移除页面缓存和历史工作台 N+1 统计对导航数据的覆盖 |
| 本地验证 | Java 21 `clean package` 成功，65/65 测试通过；全部前端 JS 语法检查与 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-62502d7-20260717_001426.sql.gz`，38077 字节，`gzip -t` 通过 |
| 数据库迁移 | 无。本次仅修改统计逻辑与前端资源 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-62502d7-20260717_001440` |
| 产物核验 | 本地上传与生产 `app.jar` SHA-256 均为 `f7350004d59c1c3cc4fc570bd1e3353533e3e15a4e6665dc195045d591a2d4e5` |
| 生产验证 | `emie-app` 已重建并持续运行；`emie-preview-converter` 持续运行；公开配置与预览健康检查 HTTP 200；未登录徽章接口返回预期 401；首页确认加载 `bootstrap.js?v=146`；本次启动日志无 `ERROR`、`Exception` 或 `Failed` |
| 待人工验收 | 使用实际账号切换项目、子任务、评分页面并完成一次项目/任务操作，确认各导航数字稳定且与页面列表一致 |

---

## 2026-07-17 项目服务端筛选与 CRUD 规范

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 新生产提交 | `b6406fc7563849f4e9eb76e650f50ddf52b4187d` / `bc396bf1f1a96815f43e4803502a575b52ac99ab` |
| 远端核对 | Gitee `emie/project_manager_system` 与本地新提交一致 |
| 变更范围 | 项目状态、类目、市场、关键词、截止日期筛选下推到服务端分页；新增统一分页/参数错误 DTO、动态 Criteria 查询片段、CRUD 接口规范文档和组合索引迁移 |
| 本地验证 | Java 21 `clean package` 成功，64/64 测试通过；全部前端 JS 语法检查与 `git diff --check` 通过 |
| 数据库备份 | `/root/emie/db-backup-before-bc396bf-20260717_000222.sql.gz`，37510 字节，`gzip -t` 通过 |
| 数据库迁移 | `2026-07-16-add-project-search-indexes.sql` 已执行；三个 `type/status/created_at` 相关项目索引均由 `information_schema.statistics` 核验存在 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-bc396bf-20260717_000334` |
| 产物核验 | 本地上传与生产 `app.jar` SHA-256 均为 `1a9272fd49613a9cdf7fd4bbc95b4f10f94554f2d3b3de981a7630e473d830f4` |
| 生产验证 | `emie-app` 已重建并持续运行；`emie-preview-converter` 持续运行；公开配置与预览健康检查 HTTP 200；未登录分页接口返回预期 401；首页确认加载 `bootstrap.js?v=145`；本次启动日志无 `ERROR`、`Exception` 或 `Failed` |
| 待人工验收 | 使用实际账号登录后验证项目列表的关键词、条件筛选与分页结果；自动化发布不使用或伪造用户登录会话 |

---

## 2026-07-16 Excel 项目批量导入与 Java 21 统一入口

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 旧生产提交 / 新生产提交 | `097c473edc1e1697048e6509718ddeaf04a24fe5` / `8db9d0d2de1cf39b54972b776091323420bb08e2` |
| 变更范围 | 新增管理员 Excel 项目预校验与原子导入、受控命令行导入入口；开发构建与启动统一强制 Java 21；本地守护服务修复 |
| 本地验证 | Java 21 完整构建：61 项测试通过；本地应用 Java 21 健康检查通过 |
| 数据库备份 | `/root/emie/db-backup-before-excel-import-20260716_172340-valid.sql.gz`，28443 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-8db9d0d-20260716_172417` |
| 数据导入 | 导入前项目 3 条；按完整预校验后原子写入 27 条（渠道定制单 16 条、公司常规品 11 条）。统一映射为一级 IP“环球”、二级 IP“小黄人”；数值价格映射为现有价格区间；未批量发送通知 |
| 生产验证 | 项目总数 30 条，其中 27 条 IP 映射为 `环球 / 小黄人`；同步队列无待处理项；`emie-app` 与 `emie-preview-converter` 正常；公开配置 HTTP 200；应用日志无新增严重错误 |

---

## 2026-07-16 飞书通知文案编辑器

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 / 应用提交 | `project_manager_system` / `097c473edc1e1697048e6509718ddeaf04a24fe5` |
| 变更范围 | 飞书通知模板覆盖 15 个业务场景并支持实时配置；系统管理新增低门槛“通知文案”编辑器，按中文场景展示标题、正文与一键变量插入；前端资源 `v139` |
| 数据库结构变更 | 无；应用启动自动补齐 `notification_templates` 配置组 |
| 本地验证 | Java 21 `./mvnw clean package`：61 项测试通过；全部前端 JS 语法检查通过；模板配置覆盖测试通过；本地服务健康 |
| 数据库备份 | `/root/emie/db-backup-before-097c473-20260716_162817.sql.gz`，25916 字节，`gzip -t` 通过 |
| 应用回滚包 | 已生成并保留 `app.jar.bak.pre-097c473...` 回滚包 |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 正常；生产运行 SHA 为 `097c473edc1e1697048e6509718ddeaf04a24fe5`；公开配置 HTTP 200；首页加载 `bootstrap.js?v=139`；30 项通知模板标题/正文配置已初始化；应用日志无严重错误 |

---

## 2026-07-16 IP 二级选项

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 / 应用提交 | `project_manager_system` / `a87a67df8abe9372a216be236e282fe62d3e242c` |
| 变更范围 | 一级 IP 支持维护二级项与单选/多选规则；两类新建项目页按一级 IP 动态展示标签式二级选择；项目保存并展示实际二级 IP；前端资源 `v137` |
| 数据库迁移 | `db/migrations/2026-07-16-add-ip-secondary-options.sql` 已执行，新增 `ip_options.sub_options_json`、`ip_options.sub_option_selection_mode`、`projects.ip_sub_options` 三列并核验 |
| 本地验证 | Java 21 `./mvnw clean package`：60 项测试通过；全部前端 JS 语法检查通过；本地测试库字段与 `8080` 健康检查通过 |
| 数据库备份 | `/root/emie/db-backup-before-a87a67d-20260716_160418.sql.gz`，25787 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-a87a67df8abe9372a216be236e282fe62d3e242c-20260716_160538` |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 正常；生产运行 SHA 为 `a87a67df8abe9372a216be236e282fe62d3e242c`；公开配置 HTTP 200；首页加载 `bootstrap.js?v=137`；应用启动日志无严重错误 |

---

## 2026-07-16 工作流通知与飞书跳转修复

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 / 提交 | `project_manager_system` / `ccd4b943acb1d120e6c21dad111bac8df0dd9a86` |
| 变更范围 | 修复子任务派发通知聚合 ID 为空、通知独立事务与失败隔离；新增飞书卡片绝对公网 URL 配置与项目深链打开；修复新建子任务后详情即时刷新；前端资源更新至 `v136` |
| 数据库结构变更 | 无；启动时自动补齐通知中心配置 `notification.publicBaseUrl` |
| 本地验证 | Java 21 `./mvnw clean package`：60 项通过；全部前端 JS 语法检查通过；`dev` 服务 `8080` 健康检查通过；实际新建子任务的站内和飞书投递均为 `delivered` |
| 远端核对 | Gitee `emie/project_manager_system` SHA 与本地提交一致 |
| 数据库备份 | `/root/emie/db-backup-before-c6f3be3-20260716_153951.sql.gz`，25410 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-c6f3be3fef51ed3ac32123a26f8970807190c166-20260716_154028` |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 均正常运行；生产运行 SHA 为 `c6f3be3fef51ed3ac32123a26f8970807190c166`；公开配置 HTTP 200；首页加载 `bootstrap.js?v=136`；应用启动日志与后续日志无严重错误 |
| 配置提醒 | `notification.publicBaseUrl` 已由应用自动初始化，但当前为空；需在系统管理的通知中心填入可由飞书访问的公网 HTTPS 地址后，新发送的“查看并处理”按钮才会跳转 |

---

## 2026-07-16 核心业务通知接入

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 新提交 / 生产运行提交 | `3948c7dfec0fc888e20e36bf4249f6b8e3d9b2cf` |
| 变更范围 | 项目指派、子任务派发、交付、再次交付和驳回自动创建站内通知、飞书投递与审计 |
| 数据库结构变更 | 无 |
| 本地验证 | 60 项测试通过 |
| 数据库备份 | `/root/emie/db-backup-before-3948c7d-20260716_142835.sql.gz`，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-3948c7d-20260716_142835` |
| 生产验证 | 应用与预览容器正常，公开配置 HTTP 200，应用完成启动且无新增严重错误 |

---

## 2026-07-16 通知模板工厂

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `e907f4c` |
| 新提交 / 远端核对 / 生产运行提交 | `233a40bf740feacc1af9b11ffdcb42bafc287c18` |
| 变更范围 | 新增统一通知模板工厂，覆盖项目、子任务、审核、催办、临期/逾期和系统告警的站内与飞书 Card JSON 2.0 格式；新增模板回归测试 |
| 数据库结构变更 | 无 |
| 本地验证 | Java 21 `./mvnw clean package` 成功，60 项测试通过 |
| 数据库备份 | `/root/emie/db-backup-before-233a40b-20260716_135645.sql.gz`，23763 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-233a40b-20260716_135645` |
| 新生产 JAR | SHA-256 `ba6297559a72fd6fe02876ac09abd46eb5b9c6735758e71ed72d6d97eec858de`，上传前后核对一致 |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 正常；公开配置 HTTP 200；应用日志完成启动且无新增严重错误 |

---

## 2026-07-16 飞书通知测试卡片热修复

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `5d00f65` |
| 新提交 / 远端核对 / 生产运行提交 | `e907f4cf4ae3c6004ffe3dcd6de23073b4fd27c7` |
| 操作时间 | `2026-07-16 13:45 +0800` 至 `2026-07-16 13:46 +0800` |
| 变更范围 | 修复飞书 Card JSON 2.0 测试消息的 `body.elements` 结构，新增卡片结构回归测试 |
| 数据库结构变更 | 无 |
| 本地验证 | Java 21 `./mvnw clean package` 成功，58 项测试通过 |
| 数据库备份 | `/root/emie/db-backup-before-e907f4c-20260716_134523.sql.gz`，23325 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-e907f4c-20260716_134523` |
| 新生产 JAR | SHA-256 `d0804db0e90233a94213b2e5e4f79d8ee9174f47acf1f509e0a5f75728db3596`，上传前后核对一致 |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 正常；公开配置 HTTP 200；首页加载 `bootstrap.js?v=133`；应用日志完成启动且无新增严重错误 |

---

## 2026-07-16 通知渠道真实测试

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `2419054` |
| 新提交 / 远端核对 / 生产运行提交 | `5d00f65cca1f0c00cf62a8f5974607ba7743ba37` |
| 操作时间 | `2026-07-16 11:56 +0800` 至 `2026-07-16 11:58 +0800` |
| 变更范围 | 通知中心新增“保存并发送测试”；站内通知真实落库，飞书开启且账号已绑定 Open ID 时发送交互式卡片，并记录投递审计；前端资源 `v133` |
| 数据库结构变更 | 无 |
| 本地验证 | Java 21 `./mvnw clean package` 成功，57 项测试通过；全部前端模块语法检查通过 |
| 数据库备份 | `/root/emie/db-backup-before-5d00f65-20260716_115630.sql.gz`，22307 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-5d00f65-20260716_115630` |
| 新生产 JAR | SHA-256 `d279047b0789c953527ed663e469ad87a77b85b7406fa44ba85bf6b5e98905a7`，上传前后核对一致 |
| 生产验证 | `emie-app` 正常启动且无新增严重错误；`emie-preview-converter` 持续正常；公开配置 HTTP 200；首页加载 `bootstrap.js?v=133` |

说明：测试按钮不会伪造飞书成功。飞书渠道关闭时仅创建站内测试通知；当前账号未绑定 Open ID 时明确返回未绑定；飞书 API 失败会记录失败原因，便于管理员修正配置后重试。

---

## 2026-07-16 通知中心第一阶段基础

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 当前生产提交 | `2419054` |
| 变更范围 | 通知 Outbox、站内通知、渠道投递、追加式审计实体与迁移；系统设置通知中心配置；前端资源 `v132` |
| 数据库结构变更 | 新增 `notification_events`、`notifications`、`notification_deliveries`、`notification_audit_logs` 四张表 |
| 本地验证 | Java 21 完整构建 57 项测试通过；所有前端模块语法检查通过；本地 `dev` 服务 HTTP 200 |
| 发布状态 | 数据库备份已通过 `gzip -t`；四张通知表迁移成功，8 项通知配置自动初始化；应用/预览容器重启次数 0，首页和公开配置接口 HTTP 200，生产 JAR SHA 与本地一致 |

---

## 2026-07-16 Java 17 升级 Java 21

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 当前生产提交 | `b090c68774adce61e1665f3430ad2f39895cfac3`（生产仍为 Java 17） |
| 本地改动 | Maven、Dockerfile、README、开发/发布规范和 Java 基线文档升级至 Java 21 |
| 数据库结构变更 | 无 |
| 发布状态 | 提交 `af7dd39` 已推送；本地与 Docker Java 21 验证通过。服务器独立 Dockerfile 已切换为 Java 21，生产重建并验证成功 |

- 当前 Spring Boot 3.2.5 应用代码未发现 Java 21 阻塞点；JDK 21 编译、55 项测试、class 版本 65、本地 `dev` 启动和 Docker 镜像验证均通过。
- 生产最终部署：服务器 `/root/emie` 的独立 Dockerfile 已更新为 `eclipse-temurin:21-jre`；上传 JAR SHA-256 为 `5f7594fd4d9df395d8c6047ba9b1a63eeb24c23d07a14d242cd4433761d50729`，`release-sha.txt` 为 `af7dd39`。
- 生产验证：应用日志确认 Java 21，容器重启次数 0，数据库连接成功，首页 HTTP 200，公开配置接口 HTTP 200；发布前 Java 17 JAR 与 Dockerfile 已保留备份。

### 2026-07-16 负载控制优化

- 提交 `9ea41e3` 已推送并部署生产；Java 21 虚拟线程保持可配置，生产默认数据库连接池收紧为 10/2，Tomcat 限制为 100 线程、200 连接、50 排队请求。
- 本地 `./mvnw clean package` 55 项测试通过；生产 JAR SHA-256 为 `66854725649c4e594fa0d863850355128f7ad6f870ee42f6aa6d9fd325379440`。
- 生产容器重启次数 0，首页和公开配置接口 HTTP 200，预览转换容器正常；飞书同步和文件预览的既有限流策略未改变。

### 2026-07-16 JVM 内存收紧

- 生产 JVM 堆调整为 `-Xmx192m -Xms64m`；发布前已在本地 Java 21 实例完成启动、Tomcat、JPA 与数据库连接验证。
- 初次将 Metaspace 收紧至 96MiB 后，生产出现 `OutOfMemoryError: Metaspace` 并导致项目查看请求 500；已恢复为 `-XX:MaxMetaspaceSize=128m` 并重启，容器重启次数 0，首页和公开配置接口 HTTP 200。
- 全部项目、渠道/常规品、我的子任务、待评分等“查看项目”入口共用 `GET /api/projects/{id}`，本次异常为全局运行时内存不足，不是单独页面逻辑错误。

### 2026-07-16 项目详情图片闪烁修复

- 提交 `d48b7b8` 已推送并部署生产：移除详情缩略图 `content-visibility:auto`，避免滚动弹窗回到图片区域时浏览器重新绘制造成闪烁；保留固定缩略图尺寸及 lazy loading。
- 静态资源版本为 `app.css?v=19`；生产容器重启次数 0，首页和公开配置接口 HTTP 200。
- 生产切换前保持 Java 17 回滚包和数据库备份流程不变。

---

## 2026-07-15 五个业务列表页搜索与条件筛选

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `5a2a49b9d2177b1a46a84cf832668d73279cb820` |
| 应用发布提交 | `b090c68774adce61e1665f3430ad2f39895cfac3` |
| 远端核对 | `b090c68774adce61e1665f3430ad2f39895cfac3` |
| 生产运行提交 | `b090c68774adce61e1665f3430ad2f39895cfac3` |
| 操作时间 | `2026-07-15 22:24:13 +0800` 至 `2026-07-15 22:28:12 +0800` |
| 本地改动 | `dashboard-lists.js`、`dashboard-designer.js`、`dashboard-scoring.js` 及前端缓存版本 `v131` |
| 功能范围 | 五个页面统一支持关键词搜索与条件筛选，项目页同时修复类型切换覆盖全量缓存的问题 |
| 数据库结构变更 | 无 |
| 本地验证 | 精确提交干净工作树执行 JavaScript 语法检查；`./mvnw clean package` 成功，55 项测试全部通过 |
| 新生产 JAR | SHA-256 为 `62bafd1ed8562ef7b91cb0f737eeac3560ccb3ae46e940a6dcefffbe170e46cd` |
| 数据库备份 | `/root/emie/db-backup-before-b090c68-20260715_222429.sql.gz`，19466 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-b090c68-20260715_222645`，旧 JAR SHA-256 为 `8c6d30fc3d3fa4f73e991da6eb3c35305e54963006853755b442c6a072a412f9` |
| 生产验证 | `emie-app` 与 `emie-preview-converter` 正常，重启次数均为 0；首页、公开配置、预览健康接口 HTTP 200；未登录项目接口 401；`bootstrap.js?v=131` 和三个筛选模块静态资源均验证通过 |

- 全部项目、渠道定制单、公司常规品支持状态、类目、市场、关键词和日期范围筛选。
- 我的子任务支持任务归属、任务状态、项目类型、关键词和计划完成日期筛选。
- 待评分支持评分状态、项目类型、审核阶段、关键词和计划完成日期筛选。
- 本地 JavaScript 语法检查、`git diff --check` 通过；`./mvnw clean package` 成功，55 项测试全部通过；本地首页 HTTP 200 且引用 `bootstrap.js?v=131`。
- 首次数据库备份因缺少 `PROCESS` 权限产生的 `tablespaces` 文件已改名为 `.invalid`，未作为回滚依据；随后使用 `--no-tablespaces` 生成有效备份。

---

## 2026-07-15 飞书主表严格镜像与备份永久保留

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `4c70d1752dbc80b78f6729e8480c8bf628968528` |
| 应用发布提交 | `5a2a49b9d2177b1a46a84cf832668d73279cb820` |
| 远端核对 | `5a2a49b9d2177b1a46a84cf832668d73279cb820` |
| 生产运行提交 | `5a2a49b9d2177b1a46a84cf832668d73279cb820` |
| 操作时间 | `2026-07-15 20:23:12 +0800` 至 `2026-07-15 20:55:47 +0800` |
| 本地验证 | `git diff --check` 通过；`./mvnw clean package` 成功，54 项测试全部通过；本地 `8080` HTTP 200 |
| 新生产 JAR | SHA-256 为 `8c6d30fc3d3fa4f73e991da6eb3c35305e54963006853755b442c6a072a412f9` |
| 数据库结构变更 | 无 |
| 数据库备份 | `/root/emie/db-backup-before-5a2a49b-20260715_202312.sql.gz`，19639 字节，`gzip -t` 通过 |
| 同步队列备份 | `/root/emie/sync-queue-before-mirror-marking-20260715_203825.sql.gz`，7170 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-5a2a49b-20260715_202447`，79173308 字节 |
| 飞书结构变更 | 8 张表字段补齐成功；主表新增文本字段“同步来源”，备份表新增文本字段“同步来源”“备份状态”和日期字段“源数据删除时间” |
| 生产验证 | 应用与预览容器正常、重启次数 0；应用和预览健康接口 HTTP 200；8 张表字段类型校验通过；161 条当前业务同步任务完成；无新增应用错误或同步失败 |

- 非 `_backup` 主表按数据库当前业务 ID 集合清理孤儿记录；仅删除“同步来源=系统”且对应系统备份已存在的记录。
- `_backup` 表按业务 ID 幂等更新且永不删除，源数据删除后改为“源数据已删除”并记录删除时间；同一业务 ID 恢复时重新标记为“有效”。
- 每小时空闲对账和管理员手动全量重刷都会先执行镜像清理，清理或读取失败时不会继续批量入队。
- 发布时先补齐字段，再执行一次全量同步写入来源标记；本次已完成 161 条当前业务记录的首次标记，主表与备份表记录均核验为“同步来源=系统”。
- 队列最终为 `done=471`、`fail=8`、无 `pending/processing`；8 条 `fail` 为本次发布前保留的历史审计记录，本次没有新增失败。
- 飞书 API 只读核验：项目主备各 `2/2`、子任务主备各 `2/2`、评分主备各 `2/2`、操作日志主备各 `155/155`，8 张表均已完成来源标记。

---

## 2026-07-15 飞书两级审核与子任务双评分同步

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `d888b9b244dd8c03a1c6c51475a8311fac35d7df` |
| 应用发布提交 | `4c70d1752dbc80b78f6729e8480c8bf628968528` |
| 远端核对 | `4c70d1752dbc80b78f6729e8480c8bf628968528` |
| 生产运行提交 | `4c70d1752dbc80b78f6729e8480c8bf628968528` |
| 操作时间 | `2026-07-15 18:05:24 +0800` 至 `2026-07-15 18:27:41 +0800` |
| 本地验证 | 精确提交的干净工作树执行 `./mvnw clean package` 成功，48 项测试全部通过 |
| 新生产 JAR | SHA-256 为 `df2b2d71155d4053b2211a0d2bdf105937eb173c7ed5ed61921a1567623d29f6` |
| 数据库备份 | `/root/emie/db-backup-before-review-workflow-20260715_180524.sql.gz`，17601 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-4c70d17-20260715_180524`，79161908 字节，哈希与旧应用一致 |
| 同步队列备份 | `/root/emie/sync-queue-before-review-resync-20260715_181110.sql.gz`，5444 字节，`gzip -t` 通过 |
| 数据库迁移 | `2026-07-15-add-scoring-review-workflow.sql` 执行成功；5 个字段完整，历史已交付子任务补齐 2 条审核记录，无重复角色记录 |
| 飞书结构变更 | 项目主备各新增 3 个字段；子任务主备各新增 8 个并复用原“审核得分”；评分主备各新增 7 个字段，类型全部校验通过 |
| 生产验证 | 应用与预览容器正常、重启次数 0；内外网入口和预览健康接口 HTTP 200；无新增应用错误或同步失败 |

- 子任务表不再只保留一个审核分数，改为分别同步“一审得分”和“二审得分”，原“审核得分”作为两级评分加权汇总。
- 渠道定制审核链为产品企划一审、销售二审；公司常规品为产品企划一审、管理员二审。二审在一审通过前显示“等待一审”，不会提前进入待审核列表。
- 审核通过、驳回和重新交付均更新当前审核记录；操作日志继续保留历史过程。
- 全量同步共 161 条，最终队列 `done=471`、`fail=8`、无 `pending/processing`；8 条失败仍是本次发布前的历史审计记录。
- 飞书 API 只读核验：项目主备 `2/2`、子任务主备 `2/2`、评分主备 `2/2`、操作日志主备 `155/155`；企划一审“待审核”、管理员二审“等待一审”及项目审核流程均正确。

---

## 2026-07-15 飞书八表映射切换与全量同步

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14` |
| 首次应用提交 | `d9e4ef04524cad1f2e2f4112bf4199b2262f673a` |
| 最终应用提交 | `d888b9b244dd8c03a1c6c51475a8311fac35d7df` |
| 远端核对 | `d888b9b244dd8c03a1c6c51475a8311fac35d7df` |
| 生产运行提交 | `d888b9b244dd8c03a1c6c51475a8311fac35d7df` |
| 操作时间 | `2026-07-15 16:20:56 +0800` 至 `2026-07-15 16:58:47 +0800` |
| 本地验证 | 两次 `./mvnw clean package` 均成功，41 项测试全部通过；最终本地服务 HTTP 200 |
| 最终生产 JAR | SHA-256 为 `f311f107499a39b5ac3198c156d0f29a15d29f56c11bda35758a5c7137b7fc83` |
| 数据库备份 | `/root/emie/db-backup-before-feishu-eight-table-20260715_162056.sql.gz`，17602 字节，`gzip -t` 通过 |
| 飞书结构快照 | `/root/emie/feishu-schema-before-eight-table-20260715_162304.json`，权限设为仅管理员可读写 |
| 配置备份 | `/root/emie/feishu-config-before-eight-table-20260715_162736.tsv`，敏感值仅保存在生产服务器 |
| 同步队列备份 | `/root/emie/sync-queue-before-eight-table-resync-20260715_162923.sql.gz`，5474 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-d9e4ef0-20260715_162736`；热修复前包 `/root/emie/app.jar.bak.pre-d888b9b-20260715_163438` |
| 数据库结构变更 | 无；仅事务更新飞书 Base 与 8 张表的配置映射 |
| 生产验证 | 应用与预览容器正常、重启次数 0；首页、公共配置、预览健康接口 HTTP 200；159 条全量任务完成且无新增失败 |

- 目标 Base 的 8 张表均完成名称、权限和字段结构校验；补齐项目截止日期、备份子任务所属项目与创建时间、主备日志角色与所属项目字段。
- 同步服务支持文本、单选、日期、自动时间和双向关联字段；操作日志改为主表、备份表双写。
- 首次生产验证发现双向关联字段要求记录 ID 字符串数组，2 条子任务首次调用返回参数错误；应用随即停止，修正并发布热修复，原队列保留后成功重试，无数据丢失。
- 最终飞书 API 逐项核对：项目主备各 `2/2`、子任务主备各 `2/2`、评分主备各 `0/0`、操作日志主备各 `155/155`；业务 ID 集合与数据库一致，子任务项目关联逐条正确。
- 队列最终为 `done=310`、`fail=8`、无 `pending/processing`；8 条失败均为本次切换前的历史审计记录。本轮热修复后无新增重试或失败。
- 热修复后无应用级 `ERROR`；日志中仅有一条外部客户端将 TLS 流量发到 HTTP 端口造成的 Tomcat 非法方法名告警，与本次功能无关。

## 2026-07-15 飞书备份表生产修复与全量同步

| 项目 | 记录 |
|---|---|
| 状态 | 生产操作与验证完成；仓库记录随本提交推送 |
| 目标分支 | `project_manager_system` |
| 应用发布提交 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14`（本次未替换应用产物） |
| 生产运行提交 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14` |
| 操作时间 | `2026-07-15 14:34:00 +0800` 至 `2026-07-15 14:57:00 +0800` |
| 数据库结构变更 | 无 |
| 备份表配置备份 | `/root/emie/db-backup-before-feishu-backup-config-20260715_143402.sql.gz`，`gzip -t` 通过 |
| 同步队列备份 | `/root/emie/sync-queue-before-full-resync-20260715_144310.sql.gz`，`gzip -t` 通过 |
| 生产验证 | 应用容器持续运行、重启次数 0；153 条本轮同步全部完成、无新增失败；飞书备份记录逐项复核通过 |

- 将项目、子任务、评分备份映射至当前 Base 内已有表，并创建、映射操作日志备份表；四张表字段已校验。
- 本轮按全量重刷规则复用 4 条历史失败任务并新增 149 条操作日志任务，共完成 153 条；队列最终无 `pending/processing`。保留 8 条修复前的历史失败记录用于审计。
- 飞书 API 复核：项目备份 `2/2`、子任务备份 `2/2`、评分备份 `0/0`、操作日志备份 `149/149`。

---

## 2026-07-15 组织架构支持自定义角色

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `739b17de166c2ffeddf6319073970b8f8fb90471` |
| 应用发布提交 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14` |
| 远端核对 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14` |
| 生产运行提交 | `38bdfc3dca4ab6d03c3fc182f1fd5999ad8dcb14` |
| 操作时间 | `2026-07-15 13:58:00 +0800` 至 `2026-07-15 14:03:24 +0800` |
| 本地验证 | JavaScript 语法检查；`./mvnw clean package`，36 项测试通过；精确提交干净工作树再次构建通过；本地 `v130` 首页与组织架构模块 HTTP 200 |
| 新生产产物 | JAR SHA-256 为 `8afcb9ef55f4f6dbdece8de79b8232a01465194dbadfc5ba1f8f3c4fe27b452c` |
| 数据库备份 | `/root/emie/db-backup-before-38bdfc3-20260715_135938.sql.gz`，15134 字节，`gzip -t` 通过 |
| 数据库迁移 | 无实体或表结构变更 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-38bdfc3-20260715_135938` |
| 生产验证 | 两个容器正常，应用重启次数 0；首页、公共配置和预览健康接口 HTTP 200；`v130` 与组织架构动态角色逻辑均已生效；无新增 `ERROR` |

- `/api/users` 保留标准角色空分组，同时动态加入所有非待分配自定义角色用户；
- 组织架构的角色名称、新建部门和编辑部门关联角色统一读取角色管理数据；
- 新增自定义角色用户分组与组织架构动态角色回归测试，ES Module 缓存版本更新至 `v130`。

---

## 2026-07-15 产品推广角色键大小写兼容

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `5d9f6c712b40bc71de6e0eb862c2c7e72d53f85b` |
| 应用发布提交 | `739b17de166c2ffeddf6319073970b8f8fb90471` |
| 远端核对 | `739b17de166c2ffeddf6319073970b8f8fb90471` |
| 生产运行提交 | `739b17de166c2ffeddf6319073970b8f8fb90471` |
| 操作时间 | `2026-07-15 12:34:00 +0800` 至 `2026-07-15 12:38:53 +0800` |
| 本地验证 | JavaScript 语法检查；`./mvnw clean package`，34 项测试通过；精确提交干净工作树再次构建通过；本地 `v129` 首页与角色模块 HTTP 200 |
| 新生产产物 | JAR SHA-256 为 `5e2e91d4f31560e2cb45a1044178fe143de61726475cbb45892466a2678a8a16` |
| 数据库备份 | `/root/emie/db-backup-before-739b17d-20260715_123622.sql.gz`，15131 字节，`gzip -t` 通过 |
| 数据库迁移 | 无实体或表结构变更 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-739b17d-20260715_123622` |
| 生产验证 | 两个容器正常，应用重启次数 0；首页、公共配置和预览健康接口 HTTP 200；`v129`、角色类名规范化逻辑与产品推广 CSS 均已生效；无新增 `ERROR` |

- 生产角色键实际为大写开头的 `Promotion`，此前页面生成 `role-Promotion`，无法命中小写 CSS 选择器；
- 用户列表、编辑用户和修改角色弹窗统一将徽章 CSS 类名规范为小写；
- 新增角色键大小写回归测试，并将 ES Module 缓存版本更新至 `v129`。

备份说明：首次工具容器未使用主机网络，无法访问容器视角下的 `127.0.0.1` 数据库；该次在应用替换前即停止，未生成有效备份，空临时文件已清理。改用主机网络后备份成功并通过校验。

---

## 2026-07-15 产品推广徽章背景增强

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `241196433a62520eca41295a112ef852c758dbf2` |
| 应用发布提交 | `5d9f6c712b40bc71de6e0eb862c2c7e72d53f85b` |
| 远端核对 | `5d9f6c712b40bc71de6e0eb862c2c7e72d53f85b` |
| 生产运行提交 | `5d9f6c712b40bc71de6e0eb862c2c7e72d53f85b` |
| 操作时间 | `2026-07-15 12:23:00 +0800` 至 `2026-07-15 12:24:00 +0800` |
| 本地验证 | `./mvnw clean package`，33 项测试通过；本地 HTTP 200 |
| 数据库备份 | `/root/emie/db-backup-before-5d9f6c7-20260715_122337.sql.gz`，15343 字节，`gzip -t` 通过 |
| 数据库迁移 | 无实体或表结构变更 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-5d9f6c7-20260715_122337` |
| 生产验证 | 两个容器正常、重启次数 0、健康接口 HTTP 200、日志无 ERROR、CSS 入口为 `v18` |

- 产品推广徽章增加紫色渐变背景、边框和轻微阴影；
- CSS 缓存版本由 `v17` 提升为 `v18`，确保浏览器加载新样式。

---

## 2026-07-15 产品推广角色徽章与动态角色显示

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 旧生产提交 | `1b45f73c4610a12d5725f4c945d06b41415805c3` |
| 应用发布提交 | `241196433a62520eca41295a112ef852c758dbf2` |
| 远端核对 | `241196433a62520eca41295a112ef852c758dbf2` |
| 生产运行提交 | `241196433a62520eca41295a112ef852c758dbf2` |
| 操作时间 | `2026-07-15 12:16:00 +0800` 至 `2026-07-15 12:18:00 +0800` |
| 本地验证 | JavaScript 语法检查；`./mvnw clean package`，33 项测试通过；本地 HTTP 200 |
| 数据库备份 | `/root/emie/db-backup-after-2411964-20260715_121741.sql.gz`，15126 字节，`gzip -t` 通过 |
| 数据库迁移 | 无实体或表结构变更 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-2411964-20260715_121629` |
| 生产验证 | 两个容器正常、重启次数 0、公共配置接口 HTTP 200、应用日志无 ERROR、前端入口为 `v128` |

- 用户管理列表、角色筛选和当前角色徽章统一读取角色表中的显示名；
- 为 `promotion` 增加紫色头像与角色徽章样式；
- 全部 ES Module 缓存版本更新至 `v128`；
- 修正 `dev` profile YAML 层级，恢复专用测试 MySQL。

备份说明：生产主机未安装 `mysqldump`，首次命令生成的空压缩文件已改名为 `.invalid`，未计作有效备份；随后使用现有 MySQL Docker 镜像连接同一数据库完成有效补备份。本次发布不含数据库结构或数据迁移。

---

## 2026-07-15 自定义角色分配与操作日志备份同步

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 应用发布提交 | `1b45f73c4610a12d5725f4c945d06b41415805c3` |
| 生产运行提交 | `1b45f73c4610a12d5725f4c945d06b41415805c3` |
| 本地验证 | `./mvnw clean package`，33 项测试通过 |
| 数据库迁移 | 无实体或表结构变更 |
| 生产验证 | 两个容器运行正常；公共配置接口 HTTP 200；前端入口为 `v127` |

- 用户管理角色下拉改为动态读取角色表，后端按角色是否存在校验；
- 新增第四张操作日志备份表配置、预检、全量入队与周期对账同步；
- 修正前端缓存版本测试并重新构建生产 JAR。

---

## 2026-07-14 权限加固、飞书同步与前端模块化

| 项目 | 记录 |
|---|---|
| 状态 | 发布成功 |
| 目标分支 | `project_manager_system` |
| 基线提交 | `5a67d7dbe567104e3a84ec21a243a2407793ad9d` |
| 应用发布提交 | `7943e6b02ce8cea630a5b49551735dee516f4159` |
| 远端核对 | `7943e6b02ce8cea630a5b49551735dee516f4159` |
| 生产运行提交 | `7943e6b02ce8cea630a5b49551735dee516f4159`（由 `/root/emie/release-sha.txt` 记录） |
| 操作时间 | `2026-07-14 17:55:00 +0800` 至 `2026-07-14 18:11:00 +0800` |
| 旧生产版本 | 产物目录无 Git SHA；旧 JAR SHA-256 为 `183c2f50e3636c12670c2f4d486482c2d83790112c31914f83c4c8e604606c50` |
| 新生产产物 | JAR SHA-256 为 `eb9b120508b6f3b8ce98fc9521d6da7be1fd55f5af2cfad9bd2cef3a11dab307` |
| 数据库备份 | `/root/emie/db-backup-before-7943e6b-20260714_180313.sql.gz`，已验证非空且 `gzip -t` 通过 |
| 数据库迁移 | 无实体或表结构变更，本次不执行迁移 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-7943e6b.20260714_180405` |
| 代码推送 | 成功 |
| 生产部署 | 成功 |

本次纳入范围：

- 收紧项目、附件和角色相关的可见范围，并保留部门负责人查看部门成员项目与忙碌状态的能力；
- 移除测试数据库连接信息的代码内默认值，改为本机 `.env` 注入；
- 补充前端输出转义、管理员字段校验与上传类型限制；
- 将原单体 `app.js` 按业务域拆分，并将跨模块状态收敛到 `window.EMIE` 分域容器；
- 将 `core.js`、`dashboard.js`、`projects.js` 和 `admin.js` 拆分为职责子模块和轻量门面，所有业务文件控制在约 700 行以内；
- 页面改为单一 `bootstrap.js` ES Module 启动入口，按序导入并校验 30 个可注册模块；
- 移除 297 个 HTML 内联事件属性和散落的全局业务函数，改为 `EMIE.actions` 与 `data-emie-on*` 声明式事件；
- 飞书首次登录的新成员自动创建为待授权账号，只能查看授权状态；管理员分配正式角色后自动启用；
- 统一在后端阻断待授权账号访问业务接口，并彻底删除旧的自助注册接口、页面、前端逻辑及验证码端点；
- 飞书项目、子任务和评分记录支持可选备份表双写，并新增数据库到飞书的周期性全量对账；
- 全量对账默认每小时且仅在增量队列空闲时执行，避免持续全量写入和新任务饥饿；
- 前端静态资源缓存版本更新至 `v126`。
- 新增 `docs/development-handoff.md`，固化每次开发进度、验证结果和运行状态的交接记录。

本地验证：

- [x] `git diff --check`
- [x] 全部 JavaScript 文件通过 ES Module 语法检查
- [x] 前端模块状态与公共接口合约检查
- [x] `./mvnw clean package`（31 个测试通过）
- [x] 从已推送的精确提交创建干净工作树并再次构建，31 个测试通过
- [x] 服务使用 `dev` profile 在 `8080` 端口启动
- [x] `v126` ES Module 启动入口及 30 个导入模块均返回 HTTP 200
- [x] 飞书登录配置已启用且应用 ID 存在
- [x] 未登录访问项目接口返回 HTTP 401
- [x] 生产数据库备份已校验；首次备份命令因 tablespace 权限警告未采信，使用 `--no-tablespaces` 重新生成有效备份
- [x] 新旧 JAR SHA-256、应用发布提交和远端提交均已核对
- [x] `emie-app` 与 `emie-preview-converter` 持续运行且重启次数为 0
- [x] 生产首页、公开配置、飞书配置、预览健康检查及外部入口均返回 HTTP 200
- [x] 生产 `v126` 启动入口及 30 个导入模块均返回 HTTP 200
- [x] 旧注册页面与前端逻辑不存在，未认证访问旧注册地址由统一鉴权返回 HTTP 401
- [x] 首轮飞书全量对账按 5 分钟初始延迟执行，处理 4 条任务且无同步失败或应用错误
- [x] 3 个飞书备份表配置项已创建；当前值为空，备份表双写需管理员填写对应 Table ID 后启用
- [ ] 桌面浏览器可视化交互验证（当前浏览器运行时初始化冲突，已以脚本运行时合约检查和 HTTP 冒烟验证替代）
- [x] 代码提交、推送与生产部署

### 发布后备份表配置验证

| 项目 | 结果 |
|---|---|
| 操作时间 | `2026-07-14 18:21:37 +0800` 至 `2026-07-14 18:28:31 +0800` |
| 队列备份 | `/root/emie/sync-queue-before-full-resync-20260714_182137.sql.gz`，已验证非空且 `gzip -t` 通过 |
| 生产数据量 | 2 个项目、2 个子任务、0 条评分记录 |
| 入队结果 | 项目 2 条、子任务 2 条、评分 0 条 |
| 最终结果 | 4 条任务均在 3 次重试后转为 `fail`，共记录 12 次 `TableIdNotFound` |
| 应用状态 | `emie-app` 持续运行，重启次数为 0，无应用级 `ERROR` |
| 业务影响 | 未修改项目、子任务或评分业务数据；主表写入发生在备份表写入之前，失败点为备份表创建记录 |

结论：三个配置值均通过格式检查，为无首尾空格的 16 位 `tbl...` Table ID，但飞书在当前 `feishu.base.appToken` 下无法找到这些表。备份表必须位于主表所使用的同一个 Base 中，并且当前企业自建应用必须拥有该 Base 的访问权限。修正 Table ID 或权限后，再重新触发全量同步；修正前不要重复入队。

---

## 2026-07-14 仓库整理与 IP 排序修复

| 项目 | 记录 |
|---|---|
| 状态 | 已推送 |
| 目标分支 | `project_manager_system` |
| 基线提交 | `517a0d765a8f763f759f10d422e302eb1b66faeb` |
| 变更提交 | `85f833675bdf0821802bc11bd591dd5be6b0e620` |
| 远端核对 | `85f833675bdf0821802bc11bd591dd5be6b0e620` |
| 推送时间 | `2026-07-14 11:53:08 +0800` |
| 代码推送 | 成功 |
| 生产部署 | 未执行 |

本次纳入范围：

- `README.md`：新增项目入口、快速启动、标准目录结构和文档导航；
- `CONTRIBUTING.md`、`SECURITY.md`：新增协作流程与安全处理说明；
- `RTK.md`：新增项目级代码修改、推送、生产部署、数据库迁移和留痕规范；
- `.editorconfig`、`.gitattributes`：统一编码、缩进、换行符和二进制文件处理；
- `.gitignore`、`.dockerignore`：排除本地工具输出、运行数据、备份和敏感环境文件；
- `src/main/resources/static/index.html`：前端静态资源版本由 `v117` 更新至 `v118`；
- `src/main/resources/static/js/app.js`：新增 IP 配置时，默认排序号使用现有最大排序号加一；
- `CHANGELOG.md`：记录本次用户可见变化和发布流程文档；
- `scripts/project-import/`：归档项目导入模板生成工具并移除本机硬编码输出路径；
- `docs/README.md`：新增统一文档索引；
- `docs/development.md`、`docs/repository-structure.md`：新增开发指南和仓库目录规范；
- `docs/templates/project-import/`：归档项目导入模板、预览和检查结果；
- `docs/deployment.md`：关联发布操作与发布记录文档；
- `docs/release-runbook.md`：新增标准推送、部署、验证及回滚流程；
- `docs/release-records.md`：新增实际发布记录和模板。

验证状态：

- [x] JavaScript 语法检查
- [x] Maven 全量构建与测试（11 个测试通过）
- [x] 提交内容和敏感信息检查
- [x] 标准目录、Markdown 链接和忽略规则检查
- [x] 项目导入模板移动前后内容哈希核对
- [x] 推送后远端 SHA 核对
- [ ] 生产部署及业务验证

---

## 2026-07-14 接管前检查

| 项目 | 记录 |
|---|---|
| 状态 | 检查完成 |
| 业务分支 | `project_manager_system` |
| 本地提交 | `517a0d765a8f763f759f10d422e302eb1b66faeb` |
| 远端提交 | `517a0d765a8f763f759f10d422e302eb1b66faeb` |
| 构建与测试 | `./mvnw clean package` 成功；11 个测试通过 |
| 生产连接 | SSH 22 端口可达；未登录服务器 |
| 代码推送 | 未执行 |
| 生产部署 | 未执行 |
| 数据库变更 | 未执行 |
| 回滚 | 不适用 |

检查发现：

- `AGENTS.md` 引用的 `RTK.md` 不存在；
- 当前有未跟踪的 `.sheet-work/` 和 `outputs/` 产物，需防止误提交；
- 生产使用 `ddl-auto=validate`，数据库迁移必须先于应用启动；
- 当前项目 IP 迁移脚本不适合直接重复执行；
- 测试数据库密码存在代码内默认值，需要移除并轮换；
- 远端默认分支为 `master`，实际业务分支为 `project_manager_system`；
- 尚无自动数据库备份、部署验证和回滚脚本。

---

## 2026-07-16 18:25 - 项目编辑与分页性能优化

| 项目 | 记录 |
|---|---|
| 状态 | 已推送、已部署；浏览器登录后的交互验收待用户确认 |
| 业务分支 | `project_manager_system` |
| 旧提交 SHA | `8db9d0d2de1cf39b54972b776091323420bb08e2` |
| 新提交 SHA | `b6406fc7563849f4e9eb76e650f50ddf52b4187d` |
| 远端核对 SHA | `b6406fc7563849f4e9eb76e650f50ddf52b4187d` |
| 生产运行 SHA | `b6406fc7563849f4e9eb76e650f50ddf52b4187d` |
| 停机情况 | 应用容器重建期间短暂不可用，预览服务未中断 |

变更内容：

- 已创建项目仅允许所属销售或所属产品企划编辑，完整记录项目编辑审计快照。
- 全部项目、渠道定制单、公司常规品默认按 15 条服务端分页；补充查询动画、总页数及指定页跳转。
- 消除分页评分汇总 N+1 查询，并新增项目/子任务列表索引。

数据库：

| 项目 | 记录 |
|---|---|
| 是否涉及迁移 | 是，仅新增索引 |
| 有效备份 | `/root/emie/db-backup-before-b6406fc-20260716_182824-valid.sql.gz`，34KB，`gzip -t` 通过 |
| 迁移脚本 | `2026-07-16-add-project-list-page-indexes.sql` |
| 迁移结果 | 4 个目标索引均已创建并由 `information_schema.statistics` 验证 |
| 数据验证 | 未修改业务数据 |

部署与验证：

- 本地 Java 21 `clean package` 通过，64 项测试通过；前端 JS 语法检查通过。
- 已核对本地与 Gitee 业务分支提交一致；本地 JAR SHA-256 与服务器上传包一致。
- 已备份旧应用包：`/root/emie/app.jar.bak.pre-b6406fc-20260716_182957`。
- `emie-app` 与 `emie-preview-converter` 均正常运行；公开配置和预览健康接口均返回 HTTP 200；应用容器 Java 21.0.11。
- 应用日志未发现本次启动后的 `ERROR`、`Exception` 或 `Failed` 记录。

待用户验收：

- 使用实际账号登录生产系统，打开全部项目、渠道定制单、公司常规品，确认 15 条分页、跳页、查询动画和项目编辑权限。

---

## 发布记录模板

复制本节并填写，标题使用 `YYYY-MM-DD HH:mm - 发布主题`。

### YYYY-MM-DD HH:mm - 发布主题

| 项目 | 记录 |
|---|---|
| 状态 | 已推送 / 发布成功 / 发布失败 / 已回滚 |
| 操作人 |  |
| 业务分支 | `project_manager_system` |
| 旧提交 SHA |  |
| 新提交 SHA |  |
| 远端核对 SHA |  |
| 生产运行 SHA |  |
| 开始时间 |  |
| 完成时间 |  |
| 影响范围 |  |
| 停机情况 | 无 / 有，持续时间 |

变更内容：

- （填写）

发布前检查：

- [ ] 工作区和暂存内容已人工核对
- [ ] 未包含密码、令牌和本地生成文件
- [ ] `git diff --check` 通过
- [ ] `./mvnw clean package` 通过
- [ ] 远端分支状态已同步
- [ ] 旧提交 SHA 已记录
- [ ] 服务器磁盘、内存和容器状态正常

数据库：

| 项目 | 记录 |
|---|---|
| 是否涉及迁移 | 否 / 是 |
| 备份文件 |  |
| 备份验证 |  |
| 迁移脚本 |  |
| 迁移结果 |  |
| 数据验证 |  |

部署与验证：

- [ ] 推送后本地与远端 SHA 一致
- [ ] 生产运行 SHA 与计划发布 SHA 一致
- [ ] `emie-app` 正常运行
- [ ] `emie-preview-converter` 正常运行
- [ ] 应用日志无新增严重错误
- [ ] 健康检查接口通过
- [ ] 登录和核心业务流程通过
- [ ] 本次变更专项验证通过

异常、处理与回滚：

- 异常：无 / （填写）
- 处理：
- 回滚提交：
- 数据库恢复：不需要 / （填写）
- 回滚后验证：

最终结论：

- （填写）
# 2026-07-18 高优先级查询与告警优化发布

| 项目 | 结果 |
|---|---|
| 代码提交 | `9e6f6262d3e6cabfbadf9ceb06edbade829eb186` |
| 推送 | 已推送 `emie/project_manager_system`，远端 SHA 与本地一致 |
| 变更范围 | 工作台日志瘦身、时间线聚合、通知重试批量查询、运行时告警状态、迁移版本基础表 |
| 本地验证 | `./mvnw clean package`、`git diff --check`、全部前端 JS 语法检查通过 |
| 数据库备份 | `/root/emie/db-backup-before-9e6f626-20260718_172338.sql.gz`，52392 字节，`gzip -t` 通过 |
| 应用回滚包 | `/root/emie/app.jar.bak.pre-9e6f626-20260718_172338` |
| 数据库迁移 | `2026-07-18-add-runtime-alerts.sql`、`2026-07-18-add-schema-migrations.sql`，两张表创建并核验 |
| 应用产物 | 本地与生产 JAR SHA-256 均为 `bb97d9624e9572c2f83c57c207a969d998ea5a818c2a723fe8328bbdce87b733` |
| 生产运行提交 | `/root/emie/release-sha.txt` 为 `9e6f6262d3e6cabfbadf9ceb06edbade829eb186` |
| 容器验证 | `emie-app`、`emie-preview-converter` 均正常运行；应用启动完成约 32 秒 |
| 接口验证 | 公开配置 HTTP 200，预览健康 HTTP 200；启动日志无应用异常 |
| 回滚 | 未执行 |
