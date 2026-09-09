package com.emie.designpm.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 审计日志用的小工具：对象转 JSON、算出前后快照里变化的字段名。
 * 原先在 ProjectService / DefaultSubTaskCommandService 各有一份相同的私有方法。
 */
final class AuditJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditJson() {
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    static String changedFields(Map<String, Object> before, Map<String, Object> after) {
        return toJson(before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toList());
    }
}
