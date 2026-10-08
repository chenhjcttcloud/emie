package com.emie.designpm.points.service;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PointAuditService {
    private final NamedParameterJdbcTemplate jdbc;

    public PointAuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // MySQL/MariaDB conditional COLLATE reconciles legacy/new name columns; H2 ignores it.
    private static final String LOGS =
            """
        SELECT CONCAT('L-',l.id) AS id,l.user_id AS userId,u.name AS userName,
        CASE WHEN l.rule_code LIKE '%:CHANGE:%' THEN 'TASK_REVISION' WHEN l.rule_code LIKE '%:BASE' THEN 'TASK_BASE' ELSE 'TASK_QUALITY' END AS source,
        l.sub_task_id AS businessId,'sub_task' AS businessType,t.name AS businessName,t.project_id AS projectId,
        l.rule_code AS ruleCode,l.rule_description AS ruleDescription,l.points,l.reason,l.created_by AS createdBy,a.name AS createdByName,
        l.created_at AS createdAt,l.submitted_at AS submittedAt,l.confirmed_at AS confirmedAt,v.version_no AS deliveryVersionNo,
        CASE WHEN l.delivery_version_id IS NULL THEN 'historical' ELSE 'tracked' END AS tracking
        FROM point_ledgers l LEFT JOIN users u ON u.user_id=l.user_id LEFT JOIN users a ON a.user_id=l.created_by
        LEFT JOIN sub_tasks t ON t.id=l.sub_task_id LEFT JOIN sub_task_delivery_versions v ON v.id=l.delivery_version_id
        UNION ALL
        SELECT CONCAT('A-',l.id),l.user_id,u.name,l.source_type,COALESCE(bt.id,l.source_id),
        CASE WHEN l.source_type='DESIGN_REQUIREMENT' THEN 'design_requirement' ELSE LOWER(l.source_type) END,
        COALESCE(bt.name,dr.name,mp.product_name,pp.name) /*! COLLATE utf8mb4_unicode_ci */,COALESCE(bt.project_id,mp.id),l.rule_code,l.rule_description,l.points,l.reason,l.created_by,a.name,l.created_at,l.submitted_at,NULL,NULL,'historical'
        FROM point_adjustment_ledgers l
            LEFT JOIN task_withdrawals w ON l.source_type='TASK_WITHDRAWAL' AND w.id=l.source_id
            LEFT JOIN point_appeals ap ON l.source_type='APPEAL' AND ap.id=l.source_id
            LEFT JOIN point_ledgers original ON original.id=ap.point_ledger_id
            LEFT JOIN sub_tasks bt ON bt.id=COALESCE(w.sub_task_id,original.sub_task_id)
            LEFT JOIN design_requirements dr ON l.source_type='DESIGN_REQUIREMENT' AND dr.id=l.source_id
            LEFT JOIN projects mp ON l.source_type='MATERIAL_MARKET' AND mp.id=l.source_id
            LEFT JOIN po_monthly_progress progress ON l.source_type='PO_PROGRESS' AND progress.id=l.source_id
            LEFT JOIN po_point_projects pp ON pp.id=progress.po_project_id
            LEFT JOIN users u ON u.user_id=l.user_id LEFT JOIN users a ON a.user_id=l.created_by
        """;
    private static final String VERSIONS =
            """
        SELECT v.id AS businessVersionId,v.expected_points AS expectedPoints,v.point_rule_code AS ruleCode,
        v.submitted_at AS submittedAt,v.confirmed_at AS confirmedAt,v.confirmed_by AS confirmedBy,v.version_no AS versionNo,
        t.id AS businessId,'sub_task' AS businessType,t.name AS businessName,t.project_id AS projectId,
        v.submitted_by_id AS userId,u.name AS userName,
        CASE WHEN COUNT(l.id)>0 AND (v.confirmed_at IS NULL OR MIN(l.created_at)<v.confirmed_at) THEN 'EARLY_LEDGER'
            WHEN SUM(CASE WHEN l.id IS NOT NULL AND l.user_id<>v.submitted_by_id THEN 1 ELSE 0 END)>0 THEN 'RECIPIENT_MISMATCH'
            WHEN SUM(CASE WHEN l.id IS NOT NULL AND l.rule_code<>CONCAT(v.point_rule_code,CASE WHEN v.point_request_id IS NULL THEN ':BASE' ELSE CONCAT(':CHANGE:',v.point_request_id) END) THEN 1 ELSE 0 END)>0 THEN 'RULE_MISMATCH'
        WHEN v.confirmed_at IS NOT NULL AND v.expected_points>0 AND COUNT(l.id)=0 THEN 'MISSING_LEDGER'
        WHEN v.expected_points=0 AND COUNT(l.id)>0 THEN 'UNEXPECTED_LEDGER'
        WHEN v.confirmed_at IS NOT NULL AND ABS(COALESCE(SUM(l.points),0)-v.expected_points)>0.005 THEN 'AMOUNT_MISMATCH'
        ELSE NULL END AS issue
        FROM sub_task_delivery_versions v JOIN sub_tasks t ON t.id=v.sub_task_id
        LEFT JOIN users u ON u.user_id=v.submitted_by_id LEFT JOIN point_ledgers l ON l.delivery_version_id=v.id
        WHERE v.expected_points IS NOT NULL
        GROUP BY v.id,v.expected_points,v.point_rule_code,v.submitted_at,v.confirmed_at,v.confirmed_by,v.version_no,
        t.id,t.name,t.project_id,v.submitted_by_id,v.point_request_id,u.name
        """;

    public Map<String, Object> logs(Map<String, String> filters, int page, int size) {
        var params = new HashMap<String, Object>();
        String where = filters(filters, params, "createdAt");
        String source = filters.get("source");
        if (source != null && !source.isBlank()) {
            where += " AND source=:source";
            params.put("source", source);
        }
        String keyword = filters.get("keyword");
        if (keyword != null && !keyword.isBlank()) {
            where +=
                    " AND (ruleCode LIKE :keyword OR ruleDescription LIKE :keyword OR reason LIKE :keyword OR businessName LIKE :keyword)";
            params.put("keyword", "%" + keyword + "%");
        }
        if ("positive".equals(filters.get("sign"))) where += " AND points>0";
        if ("negative".equals(filters.get("sign"))) where += " AND points<0";
        String table = "(" + LOGS + ") x";
        var result = page(table, where, params, page, size, "createdAt DESC,id DESC");
        result.put(
                "summary",
                jdbc.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN points>0 THEN points ELSE 0 END),0) AS positive,COALESCE(SUM(CASE WHEN points<0 THEN points ELSE 0 END),0) AS negative,COALESCE(SUM(points),0) AS net FROM "
                                + table + where,
                        params));
        return result;
    }

    public Map<String, Object> audit(Map<String, String> filters, int page, int size) {
        var params = new HashMap<String, Object>();
        String where = filters(filters, params, "submittedAt");
        var result = page(
                "(" + VERSIONS + ") x",
                where + " AND issue IS NOT NULL",
                params,
                page,
                size,
                "submittedAt DESC,businessVersionId DESC");
        String historical =
                "(SELECT v.submitted_at AS submittedAt,v.submitted_by_id AS userId FROM sub_task_delivery_versions v JOIN sub_tasks t ON t.id=v.sub_task_id WHERE v.expected_points IS NULL) x";
        result.put(
                "historicalCount",
                jdbc.queryForObject("SELECT COUNT(*) FROM " + historical + where, params, Long.class));
        return result;
    }

    private String filters(Map<String, String> f, Map<String, Object> p, String time) {
        String where = " WHERE 1=1";
        if (f.get("userId") != null && !f.get("userId").isBlank()) {
            where += " AND userId=:userId";
            p.put("userId", f.get("userId"));
        }
        if (f.get("from") != null && !f.get("from").isBlank()) {
            where += " AND " + time + ">=:from";
            p.put("from", LocalDate.parse(f.get("from")).atStartOfDay());
        }
        if (f.get("to") != null && !f.get("to").isBlank()) {
            where += " AND " + time + "<:to";
            p.put("to", LocalDate.parse(f.get("to")).plusDays(1).atStartOfDay());
        }
        return where;
    }

    private Map<String, Object> page(
            String table, String where, Map<String, Object> params, int page, int size, String order) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + where, params, Long.class);
        params.put("limit", safeSize);
        params.put("offset", (long) safePage * safeSize);
        var result = new LinkedHashMap<String, Object>();
        var items = jdbc.queryForList(
                "SELECT * FROM " + table + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", params);
        items.forEach(item -> item.replaceAll((key, value) -> value instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime().toString()
                : value));
        result.put("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", (total + safeSize - 1) / safeSize);
        return result;
    }
}
