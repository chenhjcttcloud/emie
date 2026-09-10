# ADR-0004 架构护栏（ArchUnit + 类体积上限 + CI 容器冒烟）

状态：已采纳

## 背景

`docs/architecture-stability-roadmap.md` 的原则是对的，但**没有任何机制拦得住
违反它的代码**——CI 原来只跑 `mvn clean package`。每条原则都靠人记住 = 会腐坏。

## 决定

把规则写成会失败的测试，放进 CI：

### `ArchitectureRulesTest`（ArchUnit）

- controller / service / repository 必须在 `<domain>.{controller|service|repository}`，
  扁平顶层包禁止再出现。
- 不向上依赖：service 不依赖 controller；repository 不依赖 service/controller；
  entity 不依赖 controller。
- controller 不得直连 repository —— 17 个历史违规冻结在 `CONTROLLERS_ALLOWED_TO_TOUCH_REPOSITORIES`
  白名单，规则只禁新增。**修一个删一个，清零后把规则收紧成硬禁止。**
- 跨业务域循环依赖：`feature_domains_are_free_of_cycles` 目前 `@Disabled`
  （100+ 处既有债，需要真正解耦），解耦后删 `@Disabled`。

### `ClassSizeCeilingTest`

- 默认单类 ≤ 600 行。
- 现存 6 个超标类（FeishuBaseService 1844、DefaultSubTaskCommandService 1695、
  ProjectController 1377、ProjectService 1125、AdminService 950、SyncWorker 675）
  冻结在 `FROZEN_CEILINGS`，各自以当前行数为上限——**只能变小不能变大**。
  拆到 600 以下后从清单删除。

### CI 容器冒烟（`scripts/container-smoke.sh`）

`java21-ci.yml` 新增 `container-smoke` job：`docker compose up` → 等健康 →
查静态资源 → 查启动错误日志。以前这步只在本地手动跑（AGENTS.md 要求），
现在每个 PR 都过。

## 后果

- 新 PR 违反结构 / 分层 / 体积约束，合并前就红。
- 白名单和 `FROZEN_CEILINGS` 是"债务台账"，会随重构逐条清空。
- god class 拆分（Stage 5）有了 600 行硬上限，会被持续推着做。
