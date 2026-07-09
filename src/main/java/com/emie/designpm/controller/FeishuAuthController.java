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

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    private final SystemConfigRepository configRepository;
    private final UserRepository userRepository;

    public FeishuAuthController(SystemConfigRepository configRepository,
                                UserRepository userRepository) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
    }

    /** 获取飞书 App ID（供前端跳转用） */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getFeishuConfig() {
        String appId = configRepository.findByConfigKey("feishu.appId")
                .map(SystemConfig::getConfigValue).orElse("");
        String enabled = configRepository.findByConfigKey("feishu.enabled")
                .map(SystemConfig::getConfigValue).orElse("false");
        return ResponseEntity.ok(Map.of("appId", appId, "enabled", enabled));
    }

    /** 飞书 OAuth 登录回调（使用官方 SDK） */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam("code") String code) {
        try {
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
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?sso_error=" + e.getMessage()))
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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
                .logReqAtDebug(true)
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
        String userName = userInfo.getName();
        String email = userInfo.getEmail();
        String mobile = userInfo.getMobile();

        // 查找或创建用户
        User user = userRepository.findByFeishuOpenId(openId).orElse(null);
        if (user == null && email != null && !email.isBlank()) {
            user = userRepository.findByEmail(email).orElse(null);
        }

        if (user == null) {
            String userId = "feishu_" + openId.substring(0, Math.min(8, openId.length()));
            user = User.builder()
                    .userId(userId)
                    .name(userName != null ? userName : "飞书用户")
                    .role("sales")
                    .feishuOpenId(openId)
                    .email(email)
                    .phone(mobile)
                    .status("active")
                    .build();
            userRepository.save(user);
        } else if (user.getFeishuOpenId() == null) {
            user.setFeishuOpenId(openId);
            userRepository.save(user);
        }

        // 保存 userAccessToken 供飞书 Base 同步使用
        SystemConfig tokenConfig = configRepository.findByConfigKey("feishu.userAccessToken")
                .orElse(SystemConfig.builder().configKey("feishu.userAccessToken").configGroup("feishu").build());
        tokenConfig.setConfigValue(userAccessToken);
        configRepository.save(tokenConfig);

        return user;
    }
}
