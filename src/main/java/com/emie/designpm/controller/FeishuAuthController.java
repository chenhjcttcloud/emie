package com.emie.designpm.controller;

import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.authen.v1.model.*;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.emie.designpm.util.SecurityUtil;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthController.class);
    private static final ConcurrentMap<String, PendingLogin> PENDING_LOGINS = new ConcurrentHashMap<>();

    private final SystemConfigRepository configRepository;
    private final UserRepository userRepository;

    public FeishuAuthController(SystemConfigRepository configRepository,
                                UserRepository userRepository) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
    }

    /** 获取飞书 App ID（供前端跳转用） */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getFeishuConfig(HttpServletRequest request) {
        String appId = configRepository.findByConfigKey("feishu.ssoAppId")
                .map(SystemConfig::getConfigValue).orElse("");
        String enabled = configRepository.findByConfigKey("feishu.enabled")
                .map(SystemConfig::getConfigValue).orElse("false");
        String state = UUID.randomUUID().toString();
        request.getSession(true).setAttribute("feishu_oauth_state", state);
        return ResponseEntity.ok(Map.of("appId", appId, "enabled", enabled, "state", state));
    }

    /** 飞书 OAuth 登录回调（使用官方 SDK） */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam("code") String code,
                                      @RequestParam("state") String state,
                                      HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            Object savedState = session != null ? session.getAttribute("feishu_oauth_state") : null;
            if (session != null) session.removeAttribute("feishu_oauth_state");
            if (savedState == null || !savedState.toString().equals(state)) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/?sso_error=飞书登录请求已失效，请重新发起登录"))
                        .build();
            }
            User user = processFeishuCode(code);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/?sso_error=飞书登录失败"))
                        .build();
            }

            // 通过一次性票据交付 token，避免 token 出现在浏览器地址栏、历史记录和 Referer 中。
            String token = AuthController.generateToken(user.getUserId(), user.getRole(), user.getName());
            String ticket = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            PENDING_LOGINS.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
            PENDING_LOGINS.put(ticket, new PendingLogin(token, user.getUserId(), user.getName(), user.getRole(),
                    now + 60_000));

            String redirectUrl = "/?sso_ticket=" + ticket;

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirectUrl))
                    .build();

        } catch (Exception e) {
            log.error("飞书 OAuth 回调处理失败", e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?sso_error=飞书登录失败，请稍后重试"))
                    .build();
        }
    }

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body) {
        String ticket = body.get("ticket");
        PendingLogin pending = ticket == null ? null : PENDING_LOGINS.remove(ticket);
        if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "登录票据已失效"));
        }
        Map<String, Object> user = Map.of("userId", pending.userId, "userName", pending.userName, "role", pending.role);
        return ResponseEntity.ok(Map.of("token", pending.token, "user", user));
    }

    private record PendingLogin(String token, String userId, String userName, String role, long expiresAt) {}

    /** 飞书客户端内静默登录（JSAPI tt.login() 获取 code） */
    @PostMapping("/auto-login")
    public ResponseEntity<Map<String, Object>> autoLogin(@RequestBody Map<String, String> body) {
        try {
            String code = body.get("code");
            if (code == null || code.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 code"));
            }

            // 复用回调逻辑获取用户
            User user = processFeishuCode(code);
            if (user == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "飞书登录失败"));
            }

            String token = AuthController.generateToken(user.getUserId(), user.getRole(), user.getName());
            Map<String, Object> userData = Map.of(
                    "userId", user.getUserId(),
                    "userName", user.getName(),
                    "role", user.getRole(),
                    "status", user.getStatus() != null ? user.getStatus() : "active"
            );

            return ResponseEntity.ok(Map.of("token", token, "user", userData));

        } catch (Exception e) {
            log.error("飞书内嵌免登处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "飞书登录失败，请稍后重试"));
        }
    }

    /** 提取公共方法：用飞书 code 换取用户身份并登录/注册 */
    private User processFeishuCode(String code) throws Exception {
        String appId = configRepository.findByConfigKey("feishu.ssoAppId")
                .map(SystemConfig::getConfigValue).orElse("");
        String appSecret = configRepository.findByConfigKey("feishu.ssoAppSecret")
                .map(SystemConfig::getConfigValue).orElse("");

        if (appId.isEmpty() || appSecret.isEmpty()) return null;

        Client client = Client.newBuilder(appId, appSecret)
                .logReqAtDebug(false)
                .requestTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // 用 code 换取 userAccessToken
        CreateOidcAccessTokenReq tokenReq = CreateOidcAccessTokenReq.newBuilder()
                .createOidcAccessTokenReqBody(CreateOidcAccessTokenReqBody.newBuilder()
                        .grantType("authorization_code")
                        .code(code)
                        .build())
                .build();

        CreateOidcAccessTokenResp tokenResp = client.authen().oidcAccessToken().create(tokenReq);
        if (!tokenResp.success()) return null;

        String userAccessToken = tokenResp.getData().getAccessToken();

        // 获取用户信息
        GetUserInfoResp userInfoResp = client.authen().userInfo().get(
                RequestOptions.newBuilder().userAccessToken(userAccessToken).build());
        if (!userInfoResp.success()) return null;

        return resolveFeishuUser(userInfoResp.getData());
    }

    /**
     * 将已经通过飞书认证的身份绑定到系统账号。新成员只创建待授权账号，
     * 不自动授予任何业务角色；管理员分配角色后才会获得业务访问权限。
     */
    User resolveFeishuUser(GetUserInfoRespBody userInfo) {
        if (userInfo == null || userInfo.getOpenId() == null || userInfo.getOpenId().isBlank()) {
            return null;
        }

        String openId = userInfo.getOpenId().trim();
        String email = firstNonBlank(userInfo.getEnterpriseEmail(), userInfo.getEmail());
        User user = userRepository.findByFeishuOpenId(openId).orElse(null);
        if (user == null && email != null) {
            user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        }

        if (user == null) {
            user = User.builder()
                    .userId(buildPendingUserId(openId))
                    .name(SecurityUtil.sanitizeText(firstNonBlank(userInfo.getName(), "飞书用户"), 20))
                    .role("pending")
                    .title("待分配角色")
                    .email(email)
                    .status("pending")
                    .feishuOpenId(openId)
                    .build();
            user = userRepository.save(user);
            log.info("已创建飞书待授权账号: userId={}", user.getUserId());
        } else if (user.getFeishuOpenId() == null || user.getFeishuOpenId().isBlank()) {
            user.setFeishuOpenId(openId);
            user = userRepository.save(user);
        }

        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            return null;
        }
        return user;
    }

    private String buildPendingUserId(String openId) {
        String base = "feishu_" + AuthController.sha256(openId).substring(0, 16);
        if (userRepository.findByUserId(base).isEmpty()) return base;
        return "feishu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
