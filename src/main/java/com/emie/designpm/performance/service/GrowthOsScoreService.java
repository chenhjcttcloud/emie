package com.emie.designpm.performance.service;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Reads external summaries without opening a write transaction or changing local evaluations. */
@Service
public class GrowthOsScoreService {
    public record Score(
            BigDecimal score,
            String status,
            boolean isFinal,
            BigDecimal previousScore,
            String source,
            String updatedAt,
            String revision) {}

    public record UserScores(
            String userId,
            String feishuUserId,
            String name,
            String matchStatus,
            Score weeklyQuality,
            Score collaboration360) {}

    public record MonthScores(
            String month, boolean configured, String status, String message, List<UserScores> users) {}

    private static final Set<String> SCORE_STATUSES = Set.of(
            "published",
            "not_published",
            "pending_ai",
            "no_reports",
            "rescoring",
            "rescore_failed",
            "identity_mismatch",
            "invalid_result");
    private static final Set<String> SOURCES = Set.of("legacy", "automatic_report", "weighted_30_70");
    private final UserRepository users;
    private final GrowthOsScoreClient client;

    public GrowthOsScoreService(UserRepository users, GrowthOsScoreClient client) {
        this.users = users;
        this.client = client;
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public MonthScores month(String month) {
        MonthlyDesignerEvaluationService.validatePeriod(month, true);
        List<User> selected = users.findByRole("designer").stream()
                .filter(user -> user.getStatus() == null
                        || user.getStatus().isBlank()
                        || "active".equalsIgnoreCase(user.getStatus()))
                .toList();
        return read(month, selected);
    }

    public UserScores forUser(String month, String localUserId) {
        MonthlyDesignerEvaluationService.validatePeriod(month, true);
        if (localUserId == null || localUserId.isBlank()) throw new IllegalArgumentException("设计师不存在");
        User user = users.findByUserId(localUserId).orElseThrow(() -> new IllegalArgumentException("设计师不存在"));
        if (!"designer".equals(user.getRole())) throw new IllegalArgumentException("仅可查询设计师评分");
        // Historical reads remain available for an inactive designer.
        return read(month, List.of(user)).users().getFirst();
    }

    private MonthScores read(String month, List<User> selected) {
        if (!client.isConfigured())
            return new MonthScores(
                    month,
                    false,
                    "not_configured",
                    "成长周报评分接口尚未配置，请联系管理员",
                    selected.stream()
                            .map(user -> unavailable(user, "not_configured"))
                            .toList());
        Map<String, Integer> occurrences = new HashMap<>();
        for (User user : selected) {
            String id = validFeishuId(user.getFeishuUserId());
            if (id != null) occurrences.merge(id, 1, Integer::sum);
        }
        Map<String, User> eligible = new LinkedHashMap<>();
        Map<String, UserScores> local = new HashMap<>();
        for (User user : selected) {
            String id = validFeishuId(user.getFeishuUserId());
            if (id == null) local.put(user.getUserId(), unavailable(user, "missing_feishu_user_id"));
            else if (occurrences.get(id) > 1)
                local.put(user.getUserId(), unavailable(user, "duplicate_feishu_user_id"));
            else if (!validName(user.getName())) local.put(user.getUserId(), unavailable(user, "name_mismatch"));
            else eligible.put(id, user);
        }
        List<User> pending = new ArrayList<>(eligible.values());
        try {
            for (int offset = 0; offset < pending.size(); offset += 100) {
                List<User> batch = pending.subList(offset, Math.min(offset + 100, pending.size()));
                List<GrowthOsScoreClient.Identity> identities = batch.stream()
                        .map(user -> new GrowthOsScoreClient.Identity(
                                validFeishuId(user.getFeishuUserId()), normalizeName(user.getName())))
                        .toList();
                JsonNode response = client.fetch(month, identities);
                Map<String, User> requested = new HashMap<>();
                batch.forEach(user -> requested.put(validFeishuId(user.getFeishuUserId()), user));
                Map<String, UserScores> returned = validateRows(response, month, requested);
                returned.forEach((id, scores) -> local.put(requested.get(id).getUserId(), scores));
            }
        } catch (GrowthOsScoreClient.Unavailable e) {
            eligible.values().forEach(user -> local.put(user.getUserId(), unavailable(user, "unavailable")));
            return new MonthScores(month, true, "unavailable", e.getMessage(), ordered(selected, local));
        } catch (RuntimeException ignored) {
            eligible.values().forEach(user -> local.put(user.getUserId(), unavailable(user, "unavailable")));
            return new MonthScores(month, true, "unavailable", "成长周报返回的评分数据格式不正确", ordered(selected, local));
        }
        return new MonthScores(month, true, "ok", "已读取成长周报月度评分", ordered(selected, local));
    }

    private List<UserScores> ordered(List<User> selected, Map<String, UserScores> scores) {
        return selected.stream().map(user -> scores.get(user.getUserId())).toList();
    }

    private Map<String, UserScores> validateRows(JsonNode response, String month, Map<String, User> requested) {
        if (response == null
                || !response.path("ok").isBoolean()
                || !response.path("ok").booleanValue()
                || !"1".equals(text(response.get("apiVersion")))
                || !month.equals(text(response.get("month")))
                || !response.path("data").isArray()) throw malformed();
        Map<String, UserScores> rows = new HashMap<>();
        for (JsonNode row : response.path("data")) {
            String id = text(row.path("input").get("feishuUserId"));
            User user = requested.get(id);
            if (user == null || rows.containsKey(id)) throw malformed();
            if (!normalizeName(user.getName()).equals(text(row.path("input").get("name")))) throw malformed();
            String match = text(row.get("matchStatus"));
            if (!Set.of("matched", "not_found", "name_mismatch").contains(match == null ? "" : match))
                throw malformed();
            Score quality;
            Score collaboration;
            if ("matched".equals(match)) {
                if (!id.equals(text(row.path("employee").get("feishuUserId")))
                        || !normalizeName(user.getName())
                                .equals(normalizeName(text(row.path("employee").get("name"))))) throw malformed();
                quality = score(row.get("weeklyQuality"), true);
                collaboration = score(row.get("collaboration360"), false);
            } else {
                quality = empty("identity_mismatch");
                collaboration = empty("identity_mismatch");
            }
            rows.put(id, new UserScores(user.getUserId(), id, user.getName(), match, quality, collaboration));
        }
        if (!rows.keySet().equals(requested.keySet())) throw malformed();
        return rows;
    }

    private Score score(JsonNode node, boolean quality) {
        if (node == null || !node.isObject()) throw malformed();
        String status = text(node.get("status"));
        if (!SCORE_STATUSES.contains(status == null ? "" : status)
                || !node.path("isFinal").isBoolean()) throw malformed();
        boolean finalized = node.path("isFinal").booleanValue();
        BigDecimal value = number(node.get("score"));
        BigDecimal previous = number(node.get("previousScore"));
        if ((finalized && (!"published".equals(status) || value == null))
                || (!finalized && "published".equals(status))
                || (!finalized && value != null)) throw malformed();
        if (previous != null
                && (!quality || !Set.of("rescoring", "rescore_failed").contains(status))) throw malformed();
        String source = optionalText(node.get("source"));
        if (source != null && !SOURCES.contains(source)) throw malformed();
        String updatedAt = optionalText(node.get("updatedAt"));
        if (updatedAt != null) {
            try {
                Instant.parse(updatedAt);
            } catch (RuntimeException ignored) {
                throw malformed();
            }
        }
        String revision = optionalText(node.get("revision"));
        if (revision != null && !revision.matches("[a-f0-9]{64}")) throw malformed();
        return new Score(value, status, finalized, previous, source, updatedAt, revision);
    }

    private BigDecimal number(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw malformed();
        BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (RuntimeException ignored) {
            throw malformed();
        }
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw malformed();
        return value;
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw malformed();
        return node.textValue();
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }

    private static boolean validName(String value) {
        String name = normalizeName(value);
        return !name.isBlank() && name.length() <= 200 && name.codePoints().noneMatch(GrowthOsScoreService::control);
    }

    private static String validFeishuId(String value) {
        if (value == null) return null;
        String id = value.strip();
        return id.isEmpty()
                        || id.length() > 200
                        || id.toLowerCase(java.util.Locale.ROOT).startsWith("ou_")
                        || id.codePoints().anyMatch(code -> Character.isWhitespace(code) || control(code))
                ? null
                : id;
    }

    private static boolean control(int code) {
        return Character.isISOControl(code) || Character.getType(code) == Character.FORMAT;
    }

    private UserScores unavailable(User user, String status) {
        return new UserScores(
                user.getUserId(), user.getFeishuUserId(), user.getName(), status, empty(status), empty(status));
    }

    private Score empty(String status) {
        return new Score(null, status, false, null, null, null, null);
    }

    private GrowthOsScoreClient.Unavailable malformed() {
        return new GrowthOsScoreClient.Unavailable("成长周报返回的评分数据格式不正确");
    }
}
