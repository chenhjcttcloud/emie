package com.emie.designpm.service;

import com.emie.designpm.entity.SystemConfig;
import com.emie.designpm.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/** 飞书项目群能力：创建群、增量拉人、解散群。 */
@Service
public class FeishuChatService {
    private static final String API = "https://open.feishu.cn/open-apis";
    private final SystemConfigRepository configs;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String cachedToken;
    private long tokenExpiresAt;

    public FeishuChatService(SystemConfigRepository configs) { this.configs = configs; }

    public boolean enabled() { return !cfg("feishu.appId").isBlank() && !cfg("feishu.appSecret").isBlank(); }

    public String createChat(String name, Collection<String> openIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("chat_mode", "group");
        body.put("chat_type", "private");
        body.put("user_id_list", openIds == null ? List.of() : openIds.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList());
        JsonNode root = request("POST", "/im/v1/chats", body);
        return required(root.path("data").path("chat_id"), "创建项目群未返回 chat_id");
    }

    public void addMembers(String chatId, Collection<String> openIds) throws Exception {
        List<String> members = openIds == null ? List.of() : openIds.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList();
        if (chatId == null || chatId.isBlank() || members.isEmpty()) return;
        request("POST", "/im/v1/chats/" + enc(chatId) + "/members?member_id_type=open_id", Map.of("id_list", members));
    }

    public void dissolve(String chatId) throws Exception {
        if (chatId == null || chatId.isBlank()) throw new IllegalArgumentException("项目群不存在");
        request("DELETE", "/im/v1/chats/" + enc(chatId), null);
    }

    private JsonNode request(String method, String path, Object body) throws Exception {
        String token = token();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json; charset=utf-8");
        if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body == null ? Map.of() : body)));
        else b.DELETE();
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = json.readTree(response.body());
        if (response.statusCode() / 100 != 2 || root.path("code").asInt(0) != 0) throw new IllegalStateException("飞书群操作失败：" + root.path("msg").asText("HTTP " + response.statusCode()));
        return root;
    }

    private synchronized String token() throws Exception {
        if (cachedToken != null && tokenExpiresAt > System.currentTimeMillis() + 60_000) return cachedToken;
        String body = json.writeValueAsString(Map.of("app_id", cfg("feishu.appId"), "app_secret", cfg("feishu.appSecret")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/auth/v3/tenant_access_token/internal")).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode root = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        if (root.path("code").asInt(0) != 0) throw new IllegalStateException("获取飞书访问令牌失败：" + root.path("msg").asText());
        cachedToken = root.path("tenant_access_token").asText(); tokenExpiresAt = System.currentTimeMillis() + root.path("expire").asLong(7200) * 1000; return cachedToken;
    }

    private String cfg(String key) { return configs.findByConfigKey(key).map(SystemConfig::getConfigValue).orElse(""); }
    private static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static String required(JsonNode node, String message) { if (node == null || node.isMissingNode() || node.asText().isBlank()) throw new IllegalStateException(message); return node.asText(); }
}
