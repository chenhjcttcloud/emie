# ADR-0002 不给每个 service 提取接口

状态：已采纳（2026-09）

## 背景

有人提出"service 没有实现类分离，应该给每个 service 提 interface + Impl"。
项目当前 ~50 个 service，只有 3 个有接口：`ProjectLifecycleCommandService`、
`SubTaskCommandService`、`NotificationRetryOperations`。

## 决定

保持"默认具体类，按需抽接口"。**不**做全量接口化。

## 理由

- 测试不需要：Mockito 可直接 mock 具体类；现有测试大量 `new XxxService(...)`
  + `mock(XxxRepository.class)`，全仓 0 个 `@MockBean`。
- Spring AOP（`@Transactional`/`@Async`/`@EventListener`）走 CGLIB 代理，不需要接口。
- 全量接口化的成本是实打实的：文件数翻倍、每次改动改两处、Java 单继承下
  接口一旦发布演进就是破坏性变更、导航和搜索成本增加。
- 现有 3 个接口各有用意（面向 controller 的写操作契约、可替换的重试抽象），
  是"需要时才抽"的健康范本，不是"service 都该有接口"。

## 何时才提取接口

满足任一即可考虑：

1. 存在或确定会有 ≥2 个实现（如 primary vs background 双实现）。
2. 需要显式依赖倒置来消除跨包/跨域循环依赖。
3. 作为拆分 god class 的第一刀，给不同调用方不同窄契约（接口隔离）。
4. 需要隔离第三方系统 / 数据库 / 消息队列等基础设施。

## 后果

真正对抗腐坏的是：包结构（ADR-0001）+ ArchUnit 护栏 + 拆 god class +
下沉重复代码，不是接口数量。
