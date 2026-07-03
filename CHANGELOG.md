# 更新日志

## v2.5 (2026-07-02)

### ✨ 产品类目独立表

- **实体 + Repository**：新建 `ProductCategory` 实体（id/name/sortOrder/active）及 `ProductCategoryRepository`
- **Project 关联**：`Project.productCategory` 从 String 改为 `@ManyToOne` 关联 `ProductCategory` 实体
- **数据种子**：启动时自动插入 5 个默认类目（灯/音响/相机/个护/其他）
- **管理 API**：新增 `CategoryController`，提供类目 CRUD（`GET /api/categories`、`POST`、`PUT`、`DELETE`）
- **管理后台 UI**：新增「产品类目」tab，支持在线增删改类目，修改后自动刷新前端下拉选项
- **前端自适应**：新建项目时类目选项从 API 动态加载，不再硬编码

## v2.4 (2026-07-02)

### ✨ 新功能：渠道定制项目表单增强

- **产品企划必选**：新建渠道定制项目时，产品企划下拉框移除"未指定"选项，必须选择一名产品企划
- **新增产品类目选项卡**：在产品企划下方增加产品类目选择，选项包括灯、音响、相机、个护、其他；选择"其他"时弹出文字说明输入框且必填
- **新增目标市场选项卡**：在产品类目下方增加目标市场选择，支持国内、海外多选，至少选择一个
- **后端支持**：Project 实体新增 `productCategory`、`productCategoryNote`、`targetMarket` 字段；DTO、Controller、Service 全面对接数据存储与返回
- **列表展示**：渠道定制单列表和工作台概览增加产品类目、目标市场两列
- **详情展示**：项目详情页显示产品类目（含其他说明）和目标市场
- **草稿支持**：产品类目和目标市场选择支持草稿保存与恢复

## v2.3 (2026-06-26)

### 📱 移动端适配优化

- 工作台统计卡片改用 CSS 类（`sales-stats` / `four-col-stats`）替代内联样式，方便响应式重置
- 统计卡片最小宽度 200px → 180px，适配更小屏幕
- 移动端按钮统一缩小（`font-size:12px`），增加 `white-space:nowrap` 防止换行
- 筛选栏弹性布局：表单项 `flex: 1 1 auto`，支持换行自适应
- 表格移除固定 `min-width:500px`，改为内容自适应
- 页面标题 + 操作按钮区域移动端自动换行
- 480px 超小屏额外收紧内边距、字号、表格单元格间距
- 移动端布局高度新增 `-webkit-fill-available` 兼容 iOS Safari
- 新增 `.ft-bot` 样式用于卡片底部链接

### 🐛 iOS Safari 兼容

- 登录/注册表单 input 使用 `font-size:16px` 防止 iOS Safari 自动缩放
- 登录成功 + 页面刷新时重置 viewport meta（`maximum-scale=5.0`），解决输入框放大后不恢复的问题
- 切换页面后自动滚动到顶部（延迟确保渲染完成）

### 🔄 浏览状态保持

- 页面刷新后自动恢复上次浏览的页面（localStorage 持久化 `design_pm_lastView`）
- 管理后台恢复上次打开的 tab（`design_pm_lastAdminTab`）
- 关闭空闲倒计时 HTML 元素（功能正常但显示存在体验问题，后续优化）

## v2.2 (2026-06-26)

### 📱 移动端适配（基础框架）

- 新增汉堡菜单按钮，768px 以下自动显示
- 侧栏改为全屏覆盖面板 + 半透明遮罩，点击遮罩或导航项自动关闭
- Header 小屏精简布局，隐藏副标题
- 响应式断点 768px / 480px，覆盖表格/模态框/筛选栏/统计卡片/子任务卡片/详情页/表单等所有主要组件
- 管理员标签页支持水平滚动

## v2.1 (2026-06-26)

### ✨ 新功能

#### 系统操作日志
- 新增 `ActivityLogRepository` 数据访问层，支持日志持久化查询
- 登录/登出、项目查询等操作自动记录日志
- 管理后台新增「日志」查看 tab，支持按日期范围筛选
- 新增 `LogArchiveService` 归档服务：每月 1 日凌晨自动将上月日志压缩归档到 `logs/archive/`，查询时自动合并数据库与归档文件
- 管理员可通过 `POST /api/system/archive` 手动触发归档

#### 静止页面超时退出
- 60 分钟无操作自动退出登录，确保系统安全
- 最后 1 分钟在页面顶部显示倒计时提示
- 超时后弹出模态框提醒，需重新登录
- 支持键盘、鼠标、触摸、滚动等多种活动检测

### 🐛 Bug 修复

- 清理 `@Repository` 多余注解 — Spring Data JPA 自动实现，无需显式声明
- 移除 `DashboardController`、`ProjectController` 中未使用的依赖注入
- 移除 `ProjectService`、`UserService`、`ProjectSummaryDTO` 中未使用的 import
- 废弃导入清理，减少编译警告

### ⚙️ 工程优化

- 新增 `SchedulingConfig` 启用 Spring 定时任务支持
- 日志归档目录路径可配置（`app.log-archive.dir`）
- 添加 `logs/` 运行时目录到 `.gitignore`
