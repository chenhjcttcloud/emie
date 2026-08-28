package com.emie.designpm.service;

import com.emie.designpm.entity.User;
import com.emie.designpm.util.SecurityUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.YearMonth;
import java.util.*;

/** 子任务创建/编辑共用的输入规则。 */
@Component
public class SubTaskInputPolicy {
    private final UserService users;
    private final ObjectMapper json;
    public SubTaskInputPolicy(UserService users) { this.users = users; this.json = new ObjectMapper(); }
    public String skillTags(Object raw) {
        if (raw == null || raw instanceof String s && s.isBlank()) return null;
        Collection<?> values;
        try { values = raw instanceof Collection<?> c ? c : json.readValue(String.valueOf(raw), new TypeReference<List<Object>>() {}); }
        catch (Exception e) { throw new RuntimeException("能力标签格式无效"); }
        List<String> out = values.stream().filter(Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(s -> !s.isBlank()).map(s -> SecurityUtil.sanitizeText(s, 40)).filter(Objects::nonNull).distinct().limit(20).toList();
        try { return out.isEmpty() ? null : json.writeValueAsString(out); } catch (Exception e) { throw new RuntimeException("能力标签保存失败"); }
    }
    public String milestoneMonth(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String value = String.valueOf(raw).trim();
        try { YearMonth.parse(value); return value; } catch (Exception e) { throw new RuntimeException("里程碑月份格式应为YYYY-MM"); }
    }
    public String collaboratorAllocations(Object raw, String primary) {
        if (raw == null || String.valueOf(raw).isBlank() || "[]".equals(String.valueOf(raw).trim())) return null;
        List<Map<String,Object>> rows;
        try { rows = raw instanceof Collection<?> c ? json.convertValue(c, new TypeReference<>() {}) : json.readValue(String.valueOf(raw), new TypeReference<>() {}); }
        catch (Exception e) { throw new RuntimeException("合作成员比例格式无效"); }
        Set<String> seen = new LinkedHashSet<>(); int total = 0; List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            String id = SecurityUtil.sanitizeText(String.valueOf(row.getOrDefault("userId", "")), 100); int ratio;
            try { ratio = Integer.parseInt(String.valueOf(row.get("ratio"))); } catch (Exception e) { throw new RuntimeException("合作比例必须是整数百分比"); }
            if (id == null || id.isBlank() || id.equals(primary) || !seen.add(id)) throw new RuntimeException("合作成员不能重复或与主负责人相同");
            if (ratio <= 0 || ratio >= 100) throw new RuntimeException("单个合作比例必须在1%到99%之间");
            User user = users.getUserByUserId(id);
            if (user == null || !"designer".equals(user.getRole())) throw new RuntimeException("合作成员必须是有效设计师");
            total += ratio; out.add(Map.of("userId", id, "name", user.getName(), "ratio", ratio));
        }
        if (total >= 100) throw new RuntimeException("合作成员比例合计必须小于100%，剩余比例归主负责人");
        try { return json.writeValueAsString(out); } catch (Exception e) { throw new RuntimeException("合作比例保存失败"); }
    }
}
