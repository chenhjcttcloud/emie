# 设计项目管理系统 - EMIE Design PM

基于 Java + Spring Boot 的设计项目管理系统，涵盖渠道定制项目和常规品设计项目的全流程管理。

## 技术栈

- **后端**: Java 17 + Spring Boot 3.2 + Spring Data JPA
- **数据库**: H2（开发）/ MySQL（生产）
- **前端**: Vanilla JS + REST API
- **构建**: Maven
- **认证**: Token 认证（SHA-256 密码）

## 项目结构

```
emie/
├── pom.xml                          # Maven 依赖配置
├── mvnw                             # Maven Wrapper
├── dev.sh                           # 开发启动快捷入口
├── .gitignore
├── scripts/                         # 开发/运维脚本
│   └── dev.sh                       # 开发启动脚本（核心）
├── db/                              # 数据库脚本
│   └── init.sql                     # MySQL 初始化脚本
├── docs/                            # 文档（可选）
├── src/
│   ├── main/
│   │   ├── java/com/emie/designpm/
│   │   │   ├── DesignPmApplication.java    # 入口
│   │   │   ├── config/
│   │   │   │   ├── WebConfig.java          # CORS 配置
│   │   │   │   └── AuthFilter.java         # 认证过滤器
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java     # 登录/登出 API
│   │   │   │   ├── ProjectController.java  # 项目 CRUD API
│   │   │   │   ├── UserController.java     # 用户 API
│   │   │   │   ├── DashboardController.java# 数据统计 API
│   │   │   │   └── ...                     # 管理后台、文件等
│   │   │   ├── entity/                     # 7 个数据实体
│   │   │   ├── repository/                 # JPA 数据访问
│   │   │   ├── service/                    # 业务逻辑
│   │   │   ├── dto/                        # 数据传输对象
│   │   │   └── util/                       # 工具类
│   │   └── resources/
│   │       ├── application.yml             # 多 profile 配置
│   │       └── static/
│   │           ├── index.html              # 前端入口
│   │           ├── css/app.css             # 样式
│   │           └── js/app.js               # 前端逻辑
│   └── test/
├── data/                                   # 运行时数据（已 gitignore）
├── uploads/                                # 上传文件（已 gitignore）
└── target/                                 # 编译产物（已 gitignore）
```

## 快速启动

### 前置条件
- JDK 17+
- Maven 3.6+

### 开发模式（H2 数据库）

```bash
# 使用开发脚本（推荐）
./dev.sh start

# 或直接 Maven 启动
mvn spring-boot:run

# 打开浏览器
open http://localhost:8080
```

> `./dev.sh` 会在首次自动编译打包，支持 `start|stop|restart|log` 四个命令。  
> 详细说明见 `scripts/dev.sh`。

### 生产模式（MySQL）

```bash
# 1. 先在 MySQL 中执行 db/init.sql
# 2. 用 prod profile 启动
mvn spring-boot:run -Dspring.profiles.active=prod
```

### H2 控制台（开发调试）
```
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./data/designpm
用户名: sa
密码: （空）
```

## 登录账号

默认密码 = 用户ID（如 `sales_sun` 密码为 `sales_sun`）

| 用户 | ID | 角色 |
|------|----|------|
| 孙瑞婷 | sales_sun | 销售 |
| 蔡小露 | sales_cai | 销售 |
| 郑诗绚 | planner_zheng | 产品企划 |
| 吴思欣 | planner_wu | 产品企划 |
| 陈月珍 | designer_cheny | 设计师 |
| joy | superior_chen | 上级 |
| 刘海娇 | admin_liu | 管理员 |

## API 接口

| 方法 | 路径 | 说明 | 需认证 |
|------|------|------|--------|
| POST | /api/auth/login | 登录 | ❌ |
| POST | /api/auth/logout | 退出 | ✅ |
| GET | /api/auth/me | 当前用户 | ✅ |
| GET | /api/users | 获取所有用户 | ✅ |
| GET | /api/projects | 获取项目列表 | ✅ |
| GET | /api/projects/{id} | 获取项目详情 | ✅ |
| POST | /api/projects | 新建项目 | ✅ |
| POST | /api/projects/{id}/accept | 企划接单 | ✅ |
| POST | /api/projects/{id}/tasks | 添加子任务 | ✅ |
| PUT | /api/projects/{pid}/tasks/{tid} | 编辑子任务 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/accept | 设计师接单 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/deliver | 设计师交付 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/redeliver | 重新交付 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/approve | 验收通过 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/reject | 驳回 | ✅ |
| POST | /api/projects/{pid}/tasks/{tid}/score | 提交评分 | ✅ |
| GET | /api/projects/designer-status | 设计师状态 | ✅ |
| GET | /api/dashboard/stats | 工作台统计 | ✅ |

## 功能说明

### 角色与权限
- **销售**: 新建渠道定制项目，查看/评分自己的项目
- **产品企划**: 新建常规品项目，接单渠道定制，管理子任务，验收/评分
- **设计师**: 查看子任务，接单/交付/重新交付
- **上级**: 查看所有项目
- **管理员**: 查看所有项目

### 项目类型
1. **渠道定制单**: 销售发起 → 企划接单 → 添加子任务 → 设计师执行 → 验收 → 销售+企划评分
2. **常规品设计项目**: 企划直接创建 → 添加子任务 → 设计师执行 → 验收 → 企划评分

### 子任务工作流
`待接单 → 设计中 → 待验收 → 已通过 / 已驳回 → 评分完成`
