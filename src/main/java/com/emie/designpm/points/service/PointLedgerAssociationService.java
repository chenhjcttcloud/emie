package com.emie.designpm.points.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PointLedgerAssociationService {
    private final NamedParameterJdbcTemplate jdbc;

    public PointLedgerAssociationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String LEDGER_ASSOCIATIONS =
            """
            SELECT l.id,p.id AS projectId,p.product_name AS projectName,
                CASE WHEN p.id IS NOT NULL THEN t.id ELSE NULL END AS subTaskId,t.name AS subTaskName,
                NULL AS requirementId,NULL AS requirementName
            FROM point_ledgers l
                LEFT JOIN sub_tasks t ON t.id=l.sub_task_id
                LEFT JOIN projects p ON p.id=t.project_id
            WHERE l.user_id=:userId AND l.id IN (:ids)
            """;

    private static final String ADJUSTMENT_ASSOCIATIONS =
            """
            SELECT l.id,p.id AS projectId,p.product_name AS projectName,
                CASE WHEN p.id IS NOT NULL THEN t.id ELSE NULL END AS subTaskId,t.name AS subTaskName,
                dr.id AS requirementId,dr.name AS requirementName
            FROM point_adjustment_ledgers l
                LEFT JOIN task_withdrawals w ON l.source_type='TASK_WITHDRAWAL'
                    AND w.id=l.source_id AND w.user_id=l.user_id
                LEFT JOIN point_appeals ap ON l.source_type='APPEAL'
                    AND ap.id=l.source_id AND ap.applicant_user_id=l.user_id
                LEFT JOIN point_ledgers original ON original.id=ap.point_ledger_id AND original.user_id=l.user_id
                LEFT JOIN sub_tasks t ON t.id=COALESCE(w.sub_task_id,original.sub_task_id)
                LEFT JOIN projects p ON p.id=CASE WHEN l.source_type='MATERIAL_MARKET' THEN l.source_id ELSE t.project_id END
                LEFT JOIN design_requirements dr ON l.source_type='DESIGN_REQUIREMENT' AND dr.id=l.source_id
            WHERE l.user_id=:userId AND l.id IN (:ids)
            """;

    public Map<String, Map<String, Object>> associations(
            String userId, Collection<Long> ledgerIds, Collection<Long> adjustmentIds) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("积分用户不能为空");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        load(result, "L-", LEDGER_ASSOCIATIONS, userId, ledgerIds);
        load(result, "A-", ADJUSTMENT_ASSOCIATIONS, userId, adjustmentIds);
        return result;
    }

    private void load(
            Map<String, Map<String, Object>> result,
            String prefix,
            String query,
            String userId,
            Collection<Long> requestedIds) {
        List<Long> ids =
                requestedIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        jdbc.query(query, Map.of("userId", userId, "ids", ids), rows -> {
            Map<String, Object> association = new LinkedHashMap<>();
            for (String field : List.of(
                    "projectId", "projectName", "subTaskId", "subTaskName", "requirementId", "requirementName")) {
                Object value = rows.getObject(field);
                if (value != null) association.put(field, value);
            }
            result.put(prefix + rows.getLong("id"), association);
        });
    }
}
