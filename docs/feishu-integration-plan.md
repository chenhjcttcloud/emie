# EMIE 设计项目管理系统 · 飞书集成方案

> **版本**: v1.0  
> **日期**: 2026-06-27  
> **作者**: Claw 🦀  
> **状态**: 方案设计稿 · 待评审

---

## 目录

1. [方案总览](#1-方案总览)
2. [飞书应用配置](#2-飞书应用配置)
3. [Part A：飞书 SSO 登录](#3-part-a飞书-sso-登录)
4. [Part B：飞书内嵌应用](#4-part-b飞书内嵌应用)
5. [后端改造详述](#5-后端改造详述)
6. [前端改造详述](#6-前端改造详述)
7. [数据库变更](#7-数据库变更)
8. [安全考量](#8-安全考量)
9. [部署与配置](#9-部署与配置)
10. [实施路线图](#10-实施路线图)

---

## 1. 方案总览

### 1.1 目标

将 EMIE 设计项目管理系统接入飞书生态，实现：

- **飞书 SSO 登录**：用户通过飞书扫码/授权一键登录系统，无需记忆账号密码
- **飞书内嵌应用**：在飞书工作台内直接打开系统，免登录无缝访问
- **用户自动匹配**：通过飞书用户邮箱自动匹配本地用户，无需额外绑定流程

### 1.2 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                     用户访问入口                          │
├──────────────┬──────────────────────┬───────────────────┤
│  浏览器 Web  │  飞书移动端内嵌      │  飞书桌面端内嵌    │
│  (PC/Mobile) │  (飞书工作台)        │  (飞书工作台)      │
└──────┬───────┴──────────┬───────────┴────────┬──────────┘
       │                  │                    │
       │  OAuth SSO       │  App Ticket        │  App Ticket
       ▼                  ▼                    ▼
┌──────────────────────────────────────────────────────────┐
│                   反向代理 / Nginx                         │
├──────────────────────────────────────────────────────────┤
│           EMIE Design PM (Spring Boot 3.2.5)             │
├──────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ AuthFilter   │  │ FeishuAuth   │  │ 现有 Controllers│  │
│  │ (白名单+Token)│  │ Controller   │  │ (Project/User…) │  │
│  └─────────────┘  └──────┬───────┘  └────────────────┘  │
│                          │                               │
│                   ┌──────▼───────┐                      │
│                   │ FeishuApi     │                      │
│                   │ Client        │                      │
│                   └──────┬───────┘                      │
│                          │                               │
│  ┌───────────────────────▼────────────────────────────┐  │
│  │  Feishu Open Platform API (open.feishu.cn)          │  │
│  │  • 获取 tenant_access_token                         │  │
│  │  • 获取 user_access_token & 用户信息                 │  │
│  │  • 验证 app_ticket (内嵌应用)                        │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  DB (MySQL / H2)                                   │  │
│  │  users 表新增: feishu_open_id, feishu_union_id     │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 1.3 两种场景的认证流程对比

| 场景 | 认证方式 | 用户操作步骤 |
|------|---------|------------|
| **浏览器访问** | OAuth 2.0 Authorization Code | 打开系统 → 点击"飞书登录" → 扫码 → 自动登录 |
| **飞书内嵌访问** | App Ticket + 免登 Code | 飞书工作台 → 点击应用图标 → 自动登录 |

---

## 2. 飞书应用配置

### 2.1 创建应用

前往 [飞书开发者后台](https://open.feishu.cn/app) → 创建**企业自建应用**：

| 配置项 | 值 |
|--------|-----|
| 应用名称 | EMIE 设计项目管理 |
| 应用描述 | 亿觅 IP 设计项目管理系统 |

### 2.2 配置安全项

| 配置项 | 值（示例） |
|--------|-----------|
| **App ID** | `cli_xxxxxxxxxxxxxxx` |
| **App Secret** | （保密，配置到服务端环境变量） |

### 2.3 配置重定向 URI（SSO 登录用）

```
https://<你的域名>/api/auth/feishu/callback
```

### 2.4 配置 H5 网页 URL（内嵌应用用）

```
https://<你的域名>/
```

### 2.5 申请权限

| 权限 | 权限代码 | 用途 |
|------|---------|------|
| 获取用户基本信息 | `contact:user.base:readonly` | 获取用户姓名、邮箱、头像 |
| 获取用户邮箱信息 | `contact:user.email:readonly` | 匹配系统中注册邮箱 |
| 以应用身份读取用户信息 | `contact:user.base:readonly` | 后台通过 open_id 查用户详情 |

### 2.6 发布应用

飞书应用需**发布**后才能生效。开发测试阶段可使用**测试版本**或**沙箱环境**。

---

## 3. Part A：飞书 SSO 登录

### 3.1 流程总图

```
浏览器                                后端(EMIE)                          飞书平台
 │                                      │                                  │
 │  1. 点击「飞书登录」                    │                                  │
 │ ──────────────────────────────────►   │                                  │
 │                                      │                                  │
 │  2. 302 重定向到飞书授权页              │                                  │
 │ ◄──────────────────────────────────   │                                  │
 │                                      │                                  │
 │  3. 浏览器跳转飞书授权页                │                                  │
 │ ───────────────────────────────────────────────────────────────────►   │
 │                                      │                                  │
 │  4. 用户确认授权                       │                                  │
 │ ◄────────────────────────────────────────────────────────────────────   │
 │                                      │                                  │
 │  5. 飞书回调带 code & state            │                                  │
 │ ──────────────────────────────────►   │                                  │
 │                                      │                                  │
 │  6. 用 code 换 user_access_token      │                                  │
 │                                      ├────────────────────────────────►  │
 │                                      │                                  │
 │  7. 返回 token + user_info           │                                  │
 │                                      │◄────────────────────────────────  │
 │                                      │                                  │
 │  8. 查本地用户（按邮箱匹配）             │                                  │
 │     没找到 → 拒绝（未授权用户）           │                                  │
 │     找到 → 记录 feishu_open_id         │                                  │
 │     生成系统 Token                     │                                  │
 │                                      │                                  │
 │  9. 302 跳回前端首页 + Token           │                                  │
 │ ◄──────────────────────────────────   │                                  │
 │                                      │                                  │
 │  10. 前端存储 Token → 进入主应用        │                                  │
 │ ◄──────────────────────────────────   │                                  │
```

### 3.2 API 端点

#### `GET /api/auth/feishu/login` —— 发起飞书登录

**说明**：302 重定向用户到飞书授权页。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `redirect` | string | 否 | 登录成功后跳转的前端页面路径（如 `/orders`），默认 `/` |

响应：302 重定向到：

```
https://open.feishu.cn/open-apis/authen/v1/index
  ?app_id={appId}
  &redirect_uri={redirectUri}
  &state={randomState}
```

> **state 说明**：生成随机字符串，存 session 中，回调时校验防 CSRF。

---

#### `GET /api/auth/feishu/callback` —— 飞书回调处理

**说明**：飞书授权完成后回调此地址，处理 code 换取 token 并登录。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `code` | string | 是 | 飞书返回的授权临时凭证 |
| `state` | string | 是 | 发起登录时生成的随机 state，用于 CSRF 防护 |

处理步骤：

1. 校验 `state` 与 session 中保存的一致（防 CSRF）
2. 调用飞书 API 换取 `tenant_access_token`
3. 用 `tenant_access_token` 换取 `user_access_token`
4. 用 `user_access_token` 获取用户信息（name, email, avatar_url, open_id, union_id）
5. 在 `users` 表中按邮箱查找匹配用户：
   - **匹配成功**：更新 `feishu_open_id` 和 `feishu_union_id`，生成系统 Token
   - **匹配失败**：返回错误页面"你不在系统授权列表中，请联系管理员"
6. 302 重定向到前端首页，URL 携带 Token 参数

响应（成功时 302）：

```
https://<域名>/?token=xxx&userId=xxx&name=xxx&role=xxx
```

---

### 3.3 飞书 API 调用链

#### Step 1：获取 tenant_access_token

```
POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal
Content-Type: application/json

{
  "app_id": "cli_xxxxxxxxxx",
  "app_secret": "xxxxxxxxxxxxxxxxxxxx"
}

→ 响应:
{
  "code": 0,
  "msg": "ok",
  "tenant_access_token": "t-xxxxxxxxxxxxx",
  "expire": 7200
}
```

#### Step 2：获取 user_access_token

```
POST https://open.feishu.cn/open-apis/authen/v1/access_token
Authorization: Bearer {tenant_access_token}
Content-Type: application/json

{
  "grant_type": "authorization_code",
  "code": "{code从回调参数取}"
}

→ 响应:
{
  "code": 0,
  "msg": "ok",
  "data": {
    "access_token": "u-xxxxxxxxxxxx",
    "token_type": "Bearer",
    "expires_in": 7140,
    "name": "张三",
    "en_name": "San Zhang",
    "avatar_url": "https://sf3-cn.feishucdn.com/xxx",
    "avatar_thumb": "https://sf3-cn.feishucdn.com/xxx",
    "avatar_middle": "https://sf3-cn.feishucdn.com/xxx",
    "avatar_big": "https://sf3-cn.feishucdn.com/xxx",
    "open_id": "ou_xxxxxxxxxxxxxx",
    "union_id": "on_xxxxxxxxxxxxxx",
    "email": "zhangsan@emie.com",
    "user_id": "xxxxx",
    "mobile": "13800138000",
    "tenant_key": "xxxxx"
  }
}
```

> **注意**：`user_access_token` 的 `expires_in` 约 2 小时，只需在回调时使用一次，无需持久化。

---

## 4. Part B：飞书内嵌应用

### 4.1 飞书小程序/网页应用

在飞书工作台内嵌一个 **H5 网页应用**（不是小程序），即飞书内打开一个 Webview 指向你的系统页面。

飞书内嵌应用最大的好处是：**飞书会自动注入免登凭证**，不需要用户手动扫码。

### 4.2 免登流程

```
飞书客户端                              后端(EMIE)                    飞书平台
 │                                        │                            │
 │  1. 用户在飞书工作台点击应用图标           │                            │
 │  2. 飞书打开 webview → 系统首页           │                            │
 │  3. URL 自动带 code 参数                 │                            │
 │  ───────────────────────────────────►   │                            │
 │                                        │                            │
 │  4. 前端检测到 code 参数                  │                            │
 │  ───────────────────────────────────►   │                            │
 │     POST /api/auth/feishu/app-login     │                            │
 │     { code: "xxxx" }                    │                            │
 │                                        │                            │
 │  5. 用 code + app_access_token         │                            │
 │     换取用户身份                        │                            │
 │                                        ├───────────────────────────► │
 │  6. 返回用户信息 + 系统 Token            │                            │
 │                                        │◄─────────────────────────── │
 │  ◄───────────────────────────────────  │                            │
 │                                        │                            │
 │  7. 存储 Token → 正常使用系统            │                            │
```

### 4.3 免登 API

#### 获取 app_access_token（与 SSO 的 tenant_access_token 不同）

```
POST https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal
Content-Type: application/json

{
  "app_id": "cli_xxxxxxxxxx",
  "app_secret": "xxxxxxxxxxxxxxxxxxxx"
}

→ 响应:
{
  "code": 0,
  "msg": "ok",
  "app_access_token": "a-xxxxxxxxxxxxxxxx",
  "expire": 7200
}
```

> ⚠️ **关键区分**：
> - `tenant_access_token`：代表**某个租户**的应用身份，用于 SSO OAuth 流程
> - `app_access_token`：代表**应用本身**的身份，用于验证飞书内嵌免登 code

#### 验证免登 code

```
POST https://open.feishu.cn/open-apis/authen/v1/oidc/access_token
Authorization: Bearer {app_access_token}
Content-Type: application/json

{
  "grant_type": "authorization_code",
  "code": "{从URL获取的code}"
}

→ 响应:
{
  "code": 0,
  "data": {
    "access_token": "u-xxxxxxxxxx",
    "token_type": "Bearer",
    "expires_in": 7140,
    "open_id": "ou_xxxxxxxxxxx",
    "union_id": "on_xxxxxxxxxxx"
  }
}
```

#### 获取用户信息

```
GET https://open.feishu.cn/open-apis/contact/v3/users/{open_id}
Authorization: Bearer {app_access_token}

或使用 user_access_token:
GET https://open.feishu.cn/open-apis/authen/v1/user_info
Authorization: Bearer {user_access_token}
```

### 4.4 内嵌应用适配

内嵌在飞书 Webview 中时，需注意：

| 项目 | 说明 |
|------|------|
| **URL 中自动带 code** | 飞书会自动在 URL 末尾追加 `?code=xxx`，前端需捕获 |
| **取消导航栏** | 在飞书开发者后台配置"应用主页"时勾选"隐藏导航栏" |
| **适配飞书主题色** | 可通过 `window.__feishu_theme` 或 CSS 变量判断亮/暗主题 |
| **页面宽度** | 飞书内嵌 Webview 宽度约 375pt（移动端），需要响应式适配（已有） |
| **操作栏高度** | 飞书顶部有操作栏，页面内滚动需预留空间 |

---

## 5. 后端改造详述

### 5.1 新增：`FeishuAuthConfig.java`

```java
package com.emie.designpm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "feishu.auth")
public class FeishuAuthConfig {
    /** 飞书应用 App ID */
    private String appId;
    /** 飞书应用 App Secret */
    private String appSecret;
    /** OAuth 回调地址（需在飞书后台配置一致） */
    private String redirectUri;

    // getters & setters
}
```

### 5.2 新增：`FeishuApiClient.java`

职责：封装所有飞书 OpenAPI 调用。

**核心方法：**

```java
public class FeishuApiClient {
    // 获取 tenant_access_token（SSO 用）
    public String getTenantAccessToken();

    // 获取 app_access_token（内嵌应用用）
    public String getAppAccessToken();

    // 获取 user_access_token + 用户信息
    public FeishuUserInfo getUserInfoByCode(String code);

    // 验证内嵌应用的免登 code
    public FeishuUserInfo verifyAppCode(String code);

    // 获取用户详情（通过 open_id）
    public FeishuUserDetail getUserDetail(String openId);
}
```

**FeishuUserInfo DTO：**

```java
@Data
public class FeishuUserInfo {
    private String openId;
    private String unionId;
    private String name;
    private String enName;
    private String email;
    private String mobile;
    private String avatarUrl;
    private String avatarThumb;
    private String avatarMiddle;
    private String avatarBig;
}
```

### 5.3 新增：`FeishuAuthController.java`

```java
@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    // 1. 发起飞书 SSO 登录
    @GetMapping("/login")
    public void feishuLogin(HttpServletRequest request, HttpServletResponse response,
                            @RequestParam(defaultValue = "/") String redirect) { ... }

    // 2. 飞书 SSO 回调
    @GetMapping("/callback")
    public void feishuCallback(@RequestParam String code,
                               @RequestParam String state,
                               HttpSession session,
                               HttpServletResponse response) { ... }

    // 3. 飞书内嵌应用免登
    @PostMapping("/app-login")
    public ResponseEntity<Map<String, Object>> appLogin(@RequestBody Map<String, String> body) { ... }
}
```

### 5.4 修改：`AuthFilter.java`

在白名单中增加飞书相关路径：

```java
// 新增的白名单项
path.equals("/api/auth/feishu/login") ||
path.equals("/api/auth/feishu/callback") ||
path.equals("/api/auth/feishu/app-login") ||
```

### 5.5 修改：`User.java`

新增字段：

```java
/** 飞书 open_id（唯一标识） */
@Column(unique = true)
private String feishuOpenId;

/** 飞书 union_id（跨应用统一标识） */
private String feishuUnionId;
```

### 5.6 修改：`UserRepository.java`

新增查询方法：

```java
Optional<User> findByFeishuOpenId(String feishuOpenId);
Optional<User> findByEmail(String email);
```

### 5.7 Token 系统兼容

现有系统 Token 机制**完全不变**，飞书登录成功后：

```
飞书登录 → 匹配本地用户 → 调用 AuthController 的 Token 生成逻辑
         → 存入 TOKENS Map
         → 响应中返回 token
```

前端拿到 Token 后走完全相同的 `localStorage` + `X-Auth-Token` 流程。

---

## 6. 前端改造详述

### 6.1 登录页新增"飞书登录"按钮

在 `index.html` 的登录卡片中，密码登录框下方增加：

```html
<!-- 在登录按钮下方添加 -->
<div class="feishu-login-divider">
  <span>or</span>
</div>
<button type="button" class="btn btn-feishu" onclick="handleFeishuLogin()">
  <svg>飞书图标</svg>
  飞书登录
</button>
```

### 6.2 样式新增（`app.css`）

```css
.feishu-login-divider {
  display: flex;
  align-items: center;
  margin: 16px 0;
  color: var(--gray-400);
  font-size: 12px;
  gap: 12px;
}
.feishu-login-divider::before,
.feishu-login-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--gray-300);
}
.btn-feishu {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--gray-300);
  border-radius: 8px;
  background: #fff;
  color: #333;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}
.btn-feishu:hover {
  background: #f5f5f5;
  border-color: #3370ff;
}
```

### 6.3 `app.js` 新增逻辑

#### 初始化时检测：

- **URL 参数检测**：检测 URL 中是否有 `token` 参数（SSO 回调带过来的）
- **飞书环境检测**：检测 `code` 参数（飞书内嵌应用注入的）

```javascript
async function initApp() {
  // ★ 新增：检测飞书 SSO 回调（URL 中带 token）
  const urlParams = new URLSearchParams(window.location.search);
  const ssoToken = urlParams.get('token');
  if (ssoToken) {
    localStorage.setItem('design_pm_token', ssoToken);
    // 清除 URL 参数
    window.history.replaceState({}, '', window.location.pathname);
    // 继续走正常的 Token 校验流程
  }

  // ★ 新增：检测飞书内嵌免登（URL 中带 code）
  const feishuCode = urlParams.get('code');
  if (feishuCode && !ssoToken) {
    try {
      const r = await fetch('/api/auth/feishu/app-login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: feishuCode }),
      });
      if (r.ok) {
        const data = await r.json();
        localStorage.setItem('design_pm_token', data.token);
        window.history.replaceState({}, '', window.location.pathname);
      }
    } catch(e) {
      console.warn('飞书免登失败', e);
    }
  }

  // 原有的 Token 校验逻辑...
  const token = localStorage.getItem('design_pm_token');
  // ... (保持不变)
}
```

#### 飞书登录按钮 handler：

```javascript
function handleFeishuLogin() {
  // 跳转到后端飞书登录端点
  const currentPath = encodeURIComponent(window.location.pathname + window.location.search);
  window.location.href = `/api/auth/feishu/login?redirect=${currentPath}`;
}
```

---

## 7. 数据库变更

### 7.1 表结构变更

```sql
ALTER TABLE users
  ADD COLUMN feishu_open_id  VARCHAR(128) NULL UNIQUE COMMENT '飞书用户 open_id',
  ADD COLUMN feishu_union_id VARCHAR(128) NULL COMMENT '飞书用户 union_id';

CREATE INDEX idx_users_feishu_open_id ON users(feishu_open_id);
```

### 7.2 JPA 实体变更

`User.java` 中新增字段后，JPA 的 `ddl-auto: update` 会自动创建列，无需手动执行 SQL。

### 7.3 数据迁移说明

存量用户的数据迁移策略：

1. **纯自愿**：不用批量迁移，用户第一次飞书登录时自动绑定 open_id
2. **手动导入**：如果飞书邮箱和系统邮箱一致，可以写一个 SQL 批量关联：
   ```sql
   UPDATE users u
   JOIN feishu_user_import f ON u.email = f.email
   SET u.feishu_open_id = f.open_id, u.feishu_union_id = f.union_id;
   ```

---

## 8. 安全考量

### 8.1 OAuth State 防 CSRF

```java
// 生成随机 state
String state = UUID.randomUUID().toString();
request.getSession().setAttribute("feishu_oauth_state", state);

// 回调时校验
String savedState = (String) session.getAttribute("feishu_oauth_state");
if (!savedState.equals(state)) {
    throw new SecurityException("State 不匹配，疑似 CSRF 攻击");
}
```

### 8.2 Token 存储

| 凭据 | 存储位置 | 安全措施 |
|------|---------|---------|
| `App Secret` | 环境变量 / 密钥管理服务 | ❌ 不写进代码仓库 |
| `tenant_access_token` | 内存缓存（2 小时过期） | 不落盘 |
| `app_access_token` | 内存缓存（2 小时过期） | 不落盘 |
| 系统 Token | 内存 `ConcurrentHashMap` | 已有机制，不变 |

### 8.3 scope 最小化原则

仅申请 `contact:user.base:readonly` 和 `contact:user.email:readonly` 两个权限，不申请额外写权限。

### 8.4 用户授权验证

飞书登录成功 ≠ 允许访问系统。必须通过邮箱在白名单中匹配。不为系统外的飞书用户创建账号。

### 8.5 日志审计

所有飞书登录操作记录到 `activity_logs` 表：

```java
activityLogRepository.save(new ActivityLog(
    "飞书登录：" + feishuUser.getName() + "（" + feishuUser.getEmail() + "）",
    user.getName(), user.getRole()
));
```

---

## 9. 部署与配置

### 9.1 环境变量配置

```yaml
# application.yml 新增
feishu:
  auth:
    app-id: ${FEISHU_APP_ID:}
    app-secret: ${FEISHU_APP_SECRET:}
    redirect-uri: ${FEISHU_REDIRECT_URI:http://localhost:8080/api/auth/feishu/callback}
```

生产环境通过环境变量注入：

```bash
export FEISHU_APP_ID=cli_xxxxxxxxxxxxxx
export FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxx
export FEISHU_REDIRECT_URI=https://pm.emie.com/api/auth/feishu/callback
```

### 9.2 Nginx 配置备忘

如果前端通过 Nginx 反向代理，需确保：

```nginx
# OAuth 回调路径代理
location /api/auth/feishu/ {
    proxy_pass http://localhost:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

# session 支持（state 校验需要）
proxy_set_header Cookie $http_cookie;
proxy_pass_header Set-Cookie;
```

### 9.3 飞书内嵌应用配置

在飞书开发者后台 → 应用功能 → **网页**：

| 配置项 | 值 |
|--------|-----|
| 应用主页 | `https://pm.emie.com/` |
| 是否隐藏导航栏 | 是（建议） |
| 桌面端默认打开方式 | 浏览器 / 内嵌 |
| 移动端默认打开方式 | 内嵌 |

---

## 10. 实施路线图

### Phase 1：飞书 SSO 登录（核心，需飞书管理员配合）

| # | 任务 | 负责人 | 预估 |
|---|------|--------|------|
| 1.1 | 飞书开发者后台创建应用 + 申请权限 | 飞书管理员 | 30min |
| 1.2 | 后端：新建 `FeishuAuthConfig` 配置类 | 开发 | 10min |
| 1.3 | 后端：新建 `FeishuApiClient` API 调用层 | 开发 | 1h |
| 1.4 | 后端：新建 `FeishuAuthController`（login + callback） | 开发 | 1h |
| 1.5 | 后端：修改 `User.java` + `UserRepository.java` | 开发 | 15min |
| 1.6 | 后端：修改 `AuthFilter.java` 白名单 | 开发 | 5min |
| 1.7 | 后端：修改 `application.yml` 添加飞书配置 | 开发 | 5min |
| 1.8 | 前端：`index.html` 添加飞书登录按钮 | 开发 | 15min |
| 1.9 | 前端：`app.js` 添加飞书登录 + 回调处理 | 开发 | 30min |
| 1.10 | 前端：`app.css` 添加飞书按钮样式 | 开发 | 10min |
| **1.11** | **联调测试**：本地启动 → 飞书回调测试 | 开发 | 1h |

### Phase 2：飞书内嵌应用

| # | 任务 | 负责人 | 预估 |
|---|------|--------|------|
| 2.1 | 飞书后台配置 H5 网页应用主页 | 飞书管理员 | 15min |
| 2.2 | 后端：`FeishuAuthController` 新增 `/app-login` 端点 | 开发 | 1h |
| 2.3 | 前端：`app.js` 初始化时检测飞书注入 code 并调免登 | 开发 | 30min |
| 2.4 | **测试**：飞书内打开应用 → 自动登录 | 开发 | 30min |

### Phase 3：上线前检查

| # | 任务 | 说明 |
|---|------|------|
| 3.1 | 环境变量注入生产 key | 不写死到配置文件中 |
| 3.2 | 生产域名 HTTPS 配置 | 回调地址必须 HTTPS |
| 3.3 | 飞书后台提交应用发布 | 测试通过后正式发布 |
| 3.4 | 通知用户飞书登录上线 | 邮件/飞书群公告 |

### 预计总工时

**开发**：约 4-5 小时（不含飞书管理员配置时间）  
**测试**：约 2 小时（含 SSO + 内嵌两种场景）

---

## 附录 A：飞书 OpenAPI 参考

| API | 端点 | 用途 |
|-----|------|------|
| 获取 tenant_access_token | `POST /open-apis/auth/v3/tenant_access_token/internal` | SSO OAuth 流程 |
| 获取 app_access_token | `POST /open-apis/auth/v3/app_access_token/internal` | 内嵌免登流程 |
| 获取 user_access_token | `POST /open-apis/authen/v1/access_token` | 用 code 换用户身份 |
| 验证 OIDC code | `POST /open-apis/authen/v1/oidc/access_token` | 内嵌免登验证 |
| 获取用户信息 | `GET /open-apis/authen/v1/user_info` | 获取已认证用户详情 |
| 获取用户详情 | `GET /open-apis/contact/v3/users/{open_id}` | 通过 open_id 查询 |

## 附录 B：异常场景处理

| 场景 | 处理方式 |
|------|---------|
| 飞书回调 code 过期 | 返回错误页，提示用户重新发起登录 |
| 邮箱未匹配到本地用户 | 返回"你不在系统授权列表中，请联系管理员" |
| 飞书 API 调用失败（网络/限频） | 后端重试 1 次，失败后返回友好错误页 |
| 用户已在系统内绑定不同飞书账号 | 以最新一次飞书登录为准，更新 feishu_open_id |
| 飞书内嵌 code 无效 | 清除 URL 中的 code，显示登录页让用户手动登录 |
| 飞书平台临时故障 | 退回到原有密码登录方式，不影响存量用户 |
