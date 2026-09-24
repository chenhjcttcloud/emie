package com.emie.designpm.admin.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作量统计嘅查询层：净係负责由数据库攞数同转型，唔含任何口径判断。
 * 口径喺 {@link WorkloadStatusBoundary}，归户喺 {@link AdminWorkloadTaskLedger}，
 * 编排喺 {@link AdminWorkloadService}。
 */
final class AdminWorkloadQueries {

    private AdminWorkloadQueries() {}

    /** 逐件子任务（期末之前建立嘅）拉返嚟，交畀台账归户。 */
    @SuppressWarnings("unchecked")
    static List<AdminWorkloadTaskLedger.TaskRow> loadTaskRows(EntityManager em, LocalDateTime endExclusive) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT s.designer_id, s.publisher_id, s.status, s.created_at, s.completed_at, p.type "
                                + "FROM sub_tasks s LEFT JOIN projects p ON p.id = s.project_id WHERE s.created_at < ?1")
                .setParameter(1, endExclusive)
                .getResultList();
        List<AdminWorkloadTaskLedger.TaskRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new AdminWorkloadTaskLedger.TaskRow(
                    row[0] == null ? null : String.valueOf(row[0]),
                    row[1] == null ? null : String.valueOf(row[1]),
                    row[2] == null ? null : String.valueOf(row[2]),
                    toLocalDateTime(row[3]),
                    toLocalDateTime(row[4]),
                    row[5] == null ? null : String.valueOf(row[5])));
        }
        return result;
    }

    static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.time.Instant instant)
            return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        return null;
    }

    /** 只带期末一个参数嘅「按状态分组」查询。 */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Long>> workloadCountsAtEnd(
            EntityManager em, String sql, LocalDateTime endExclusive) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : (List<Object[]>)
                em.createNativeQuery(sql).setParameter(1, endExclusive).getResultList()) {
            if (row[0] == null || row[1] == null) continue;
            result.computeIfAbsent(String.valueOf(row[0]), ignored -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), numberOrZero(row[2]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Long> workloadCountByUser(
            EntityManager em, String sql, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery(sql)
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getResultList()) {
            if (row[0] != null) result.put(String.valueOf(row[0]), numberOrZero(row[1]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Long> workloadCountByUserAtEnd(EntityManager em, String sql, LocalDateTime endExclusive) {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>)
                em.createNativeQuery(sql).setParameter(1, endExclusive).getResultList()) {
            if (row[0] != null) result.put(String.valueOf(row[0]), numberOrZero(row[1]));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, long[]> workloadTimelineCounts(
            EntityManager em, String sql, LocalDateTime cutoff, LocalDateTime endExclusive) {
        Map<String, long[]> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery(sql)
                .setParameter(1, cutoff)
                .setParameter(2, endExclusive)
                .getResultList()) {
            if (row[0] == null) continue;
            long[] counts = new long[Math.max(2, row.length - 1)];
            for (int i = 1; i < row.length; i++) counts[i - 1] = numberOrZero(row[i]);
            result.put(String.valueOf(row[0]), counts);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Long>> workloadCounts(EntityManager em, String sql) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) continue;
            result.computeIfAbsent(String.valueOf(row[0]), ignored -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        return result;
    }

    static long numberOrZero(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
