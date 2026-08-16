# EMIE 工作接续记录

> 用途：跨设备、跨网络环境继续开发时，先阅读本文档，再查看 `git status` 和最近提交。

## 当前状态

- 当前分支：`project_manager_system`
- 当前生产版本：`1.4.1`
- 当前已发布提交：`fd35ceb`
- 生产发布状态：已完成（2026-08-16，含 V38/V39/V40 迁移；备份目录 emie-deploy-backups/20260816_195910）
- 测试环境状态：已通过 `./scripts/test-update.sh`
- 最近完整测试：210 个测试全部通过

## 最近完成

- 缺陷审计与修复（2026-08-16）：四领域并行审计（安全/并发事务/业务逻辑/前端）+ 4 名工程师并行修复 + 3 路复审 + P3 加固批次 + 测试缺口补齐。
- P0：积分异议重复入账（APPROVED 终审拦截 + V38 处理中唯一索引 + V40 APPROVED 唯一索引 + 并发兜底转中文业务异常）。
- P1×12：分享页反射 XSS（esc 转义 + CSP + share.css/share.js 外置）；前端存储型 XSS ×5（评分中心/管理后台/新建弹窗/操作日志/登录页品牌）；admin 子目录下载授权旁路；SyncWorker 事务边界；deleteSubTask 无锁删除；评分中心终验不发质量加分；月度归档 shortage 保护失效；异常分类缺失。
- 关键 P2×19：project_code 唯一约束（V39）+ 顺延重试；状态流转/流程审核/轮次计数行锁；退单行锁；通知 CAS 认领；归档临时文件 + ATOMIC_MOVE + 互斥；需求评分锁内重算；deleteProject 孤儿清理；质量阈值加权平均统一；前端转义补齐（escHtml(escJsString) 组合、renderId 竞态）。
- P3 加固：全站 CSP（script-src-attr 'none'，/share 页除外）；匿名 admin logo 闭环（AuthFilter 精确前缀 + 文件名正则白名单）；分享密码复杂度（≥6 位非纯数字）+ 前端校验与清除语义对齐；文件名校验收紧（拒绝引号/尖括号）；慢日志去 query；sanitizeText 事件属性白名单；输入清洗补漏（IP 选项/部门/PO 项目）；登出清理定时器/SSE；日期本地化（renderDatePicker/分享过期时间）；design_pm_user 无效缓存移除；内联事件迁移 CSS。
- 测试补齐：deleteSubTask 锁守卫、SyncWorker 逐条事务、通知 CAS、需求评分锁内重算、归档幂等/互斥/isRegularFile 守卫（新增 3 测试类 + 扩展 2 类，共 +31 用例）。
- 新迁移：V38（积分异议处理中唯一）、V39（项目编号唯一）、V40（积分异议 APPROVED 唯一）。
- 产品确认：REJECTED 复议保留；历史 QUALITY 不补发；加权阈值接受；V40 历史展示变化接受。

## 开始工作前

```bash
git status -sb
git pull --ff-only
sed -n '1,240p' WORKLOG.md
```

如果工作区存在未提交改动，先确认来源，不要直接覆盖或重置。

## 修改完成后

1. 更新本文档的“当前状态”“最近完成”或“下一步”。
2. 按项目要求运行相关测试。
3. 代码或静态资源修改后运行：

```bash
./scripts/test-update.sh
```

4. 记录测试结果、部署状态和遗留风险。
5. 未经明确授权，不自动提交、推送或发布生产。

## 下一步候选

- 补充分享链接有效期的服务层测试，覆盖缺失、超过 60 天和自定义时间。
- 评估将前端 API 的 `localStorage` token 逐步迁移为纯 Cookie 认证。
- 持续扫描业务流程错误页和未使用接口。
- 【产品确认】积分异议 REJECTED 复议规则是否保留；V40 对历史多笔 APPROVED 改为 REJECTED 的展示变化是否可接受。
- 【产品确认】质量阈值改加权平均后，角色权重不均衡任务的质量加分判定变化；历史经评分中心完成的任务缺 QUALITY 流水是否补发。
- 【后续批次 P3】匿名 logo 功能闭环（AuthFilter 放行或独立公开资产端点）；分享页个别插值（statusLabel/viewCount 等）转义补齐；regular 类型幽灵 plannerId 校验；主应用 CSP（script-src-attr 'none'）；服务端文件名校验限制引号/尖括号；event-runtime 黑名单改进；登出清理定时器/SSE；日期 UTC 偏差；慢日志 query 脱敏；IpOptionController/PoPointsService/DepartmentController 输入清洗；测试缺口补齐（deleteSubTask 锁、SyncWorker 条目事务、通知 CAS、需求评分锁、归档幂等）。

## 重要环境约定

- 生产发布使用：`./scripts/release-production.sh`
- 生产配置文件不纳入 Git：`.server.production.local.env`
- 不执行 `docker compose down -v`，避免删除持久化数据。
- 通过 VPN 访问项目时，优先使用 Git 同步；不要同时让两台设备修改同一份未提交文件。
