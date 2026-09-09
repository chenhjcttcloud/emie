# ADR-0001 按业务域组织包（package-by-feature）

状态：已采纳（2026-09，refactor/package-restructure）

## 背景

原结构是按技术分层的扁平包：`com.emie.designpm.controller`（29 个类）、
`com.emie.designpm.service`（54 个）、`com.emie.designpm.repository`（54 个）。
包内只按字母序排列，无法从结构上表达"这块代码属于哪个业务"，跨业务的
改动散落在三个大包里，边界只靠命名约定和人记忆维持。

## 决定

按业务域组织：`com.emie.designpm.<domain>.{controller,service,repository}`。
域包括 admin / auth / compliance / dashboard / designrequirement / feishu /
file / imagelibrary / materialmarket / notification / performance / points /
project / reference / scoring / sharing / sync / system。

`entity` / `dto` / `util` / `config` 暂留在顶层（跨域共享，后续再议）。
`background.repository` 保留独立（见 ADR-0003）。

## 理由

- 一个业务改动集中在一个包，边界可见。
- 配合 ArchUnit（见 `ArchitectureRulesTest`）可以机械地锁死结构，
  不再依赖 review 纪律。
- 单体应用内先治理边界，不提前拆微服务（沿用架构稳定性路线图的总原则）。

## 后果

- 跨域调用变得显式（一眼能看到 `project.service` import 了 `points.repository`），
  这也暴露出跨域循环依赖这个既有债（ArchUnit `feature_domains_are_free_of_cycles`
  目前 `@Disabled`，是待还的债）。
- `com.emie.designpm.controller` / `.service` / `.repository` 三个扁平包已删除，
  ArchUnit 规则禁止它们再出现。

## 不做什么

- 不给每个 service 提取 interface（见 ADR-0002）。
- 不动 `@RequestMapping` 路径——迁移期间 API 契约零改动。
