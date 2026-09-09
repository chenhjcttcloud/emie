# ADR-0003 双数据源与 Repository 扫描方式

状态：已采纳

## 背景

后台连接池隔离（`app.db.pool.isolation.enabled`，见 `docs/database-pool-isolation.md`）
开启后，通知 / 同步队列相关的少数 Repository 走独立的 `backgroundEntityManager`
和 `backgroundTransactionManager`。这些 Repository 放在
`com.emie.designpm.background.repository`，并且**和主库 Repository 存在 6 组同名类**
（`UserRepository`、`Notification*Repository`、`SyncQueueRepository`）。

包结构按域拆分后（ADR-0001），主库 Repository 从单一的
`com.emie.designpm.repository` 分散到 `com.emie.designpm.<domain>.repository`。

## 决定

- `background.repository` **保持独立包名不变**，绝不与主库 Repository 并入同一 Java 包
  （同包同名类会直接编译冲突）。
- `PrimaryJpaConfig`：`@EnableJpaRepositories` 从
  `basePackages = "com.emie.designpm.repository"` 改为
  `basePackages = "com.emie.designpm"` + `excludeFilters` 正则排除
  `com\.emie\.designpm\.background\.repository\..*`。
  这样以后新增业务域不用再回来改 `basePackages`。
- `BackgroundJpaConfig` 不变：仍显式 `basePackages = "com.emie.designpm.background.repository"`，
  带 `nameGenerator = BackgroundRepositoryBeanNameGenerator`（bean 名加 `background` 前缀避免冲突）。
- 主库业务消费方一律 import `<domain>.repository`；只有 `BackgroundNotificationRetryService`
  和 background 配置用 `background.repository`。
  （例外：`NotificationRetryService` 也用了 `background.repository` +
  `backgroundTransactionManager`，属既有设计。）

## 验证

测试容器 `APP_DB_POOL_ISOLATION_ENABLED=true`，所以 `test-update.sh` /
`scripts/container-smoke.sh` 会真正加载 `BackgroundJpaConfig`、绑定两套
EntityManager——配置错了容器直接起不来。

## 后果

`@EntityListeners(com.emie.designpm.sync.service.XxxSyncListener.class)` 这类
写死的全限定类名，迁移 sync 监听器时必须同步改（`entity/Project.java`、
`SubTask.java`、`ScoringRecord.java`）——注解值是 `Class` 字面量，漏改会编译失败，
不会静默。
