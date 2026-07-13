package com.emie.designpm.controller;

import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.authen.v1.model.*;
import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthController.class);

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
        String appId = configRepository.findByConfigKey("feishu.appId")
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
            Object savedState = request.getSession(false) != null
                    ? request.getSession(false).getAttribute("feishu_oauth_state") : null;
            request.getSession(false).removeAttribute("feishu_oauth_state");
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

            // 生成系统 token
            String token = AuthController.generateToken(user.getUserId(), user.getRole(), user.getName());

            // 重定向到前端，带上 token
            String redirectUrl = "/?sso_token=" + token
                    + "&userId=" + user.getUserId()
                    + "&userName=" + java.net.URLEncoder.encode(user.getName(), "UTF-8")
                    + "&role=" + user.getRole();

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
                    "role", user.getRole()
            );

            return ResponseEntity.ok(Map.of("token", token, "user", userData));

        } catch (Exception e) {
            log.error("飞书内嵌免登处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "飞书登录失败，请稍后重试"));
        }
    }

    /** 提取公共方法：用飞书 code 换取用户身份并登录/注册 */
    private User processFeishuCode(String code) throws Exception {
        String appId = configRepository.findByConfigKey("feishu.appId")
                .map(SystemConfig::getConfigValue).orElse("");
        String appSecret = configRepository.findByConfigKey("feishu.appSecret")
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

        GetUserInfoRespBody userInfo = userInfoResp.getData();
        String openId = userInfo.getOpenId();
        String email = userInfo.getEmail();

        // 查找或创建用户
        User user = userRepository.findByFeishuOpenId(openId).orElse(null);
        if (user == null && email != null && !email.isBlank()) {
            user = userRepository.findByEmail(email).orElse(null);
        }

        if (user == null) {
            // 飞书身份必须先由管理员创建/绑定，禁止通过 SSO 自动获得业务账号和角色。
            return null;
        } else if (user.getFeishuOpenId() == null) {
            user.setFeishuOpenId(openId);
            userRepository.save(user);
        }

        return user;
    }
}
