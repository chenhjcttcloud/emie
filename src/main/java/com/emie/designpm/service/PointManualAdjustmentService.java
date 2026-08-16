package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 管理员主动调账（积分纠错）：无需用户发起异议，管理员直接补分/扣分。
 *
 * <p>生成的调账记录以 sourceType=MANUAL 写入 point_adjustment_ledgers，
 * sourceId 在事务内取该来源类型的最大值 +1 生成，(source_type, source_id)
 * 唯一索引兜底并发冲突；reason 必填留痕，accountingMonth 由实体缺省归入账当月。</p>
 */
@Service
public class PointManualAdjustmentService {
    private static final String SOURCE_TYPE = "MANUAL";
    private static final int MAX_POINTS = 100000;
    private static final int MAX_REASON = 500;
    /** 手动调账仅面向有积分资格的角色：设计师与供应链（子任务负责人发分机制）。 */
    private static final Set<String> ELIGIBLE_ROLES = Set.of("designer", "supplychain");

    private final PointAdjustmentLedgerRepository adjustments;
    private final UserRepository users;

    public PointManualAdjustmentService(PointAdjustmentLedgerRepository adjustments, UserRepository users) {
        this.adjustments = adjustments;
        this.users = users;
    }

    @Transactional
    public PointAdjustmentLedger adjust(String userId, Integer points, String reason, AuthController.AuthSession s) {
        requireRole(s);
        String uid = required(userId, "用户ID", 100);
        User user = users.findByUserId(uid).orElse(null);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if (!ELIGIBLE_ROLES.contains(PermissionCatalog.normalizeRole(user.getRole()))) {
            throw new IllegalArgumentException("只能为设计师或供应链成员调账");
        }
        if (points == null) throw new IllegalArgumentException("积分不能为空");
        if (points == 0) throw new IllegalArgumentException("积分必须为非零整数");
        if (Math.abs((long) points) > MAX_POINTS) throw new IllegalArgumentException("调账积分绝对值不得超过100000");
        String r = required(reason, "备注", MAX_REASON);
        long nextSourceId = adjustments.maxSourceIdByType(SOURCE_TYPE) + 1;
        PointAdjustmentLedger x = new PointAdjustmentLedger();
        x.setUserId(uid);
        x.setSourceType(SOURCE_TYPE);
        x.setSourceId(nextSourceId);
        x.setPoints(points);
        x.setReason(r);
        x.setCreatedBy(s.userId());
        try {
            return adjustments.save(x);
        } catch (DataIntegrityViolationException e) {
            // (source_type, source_id) 唯一索引兜底并发窗口内的重复 sourceId。
            throw new IllegalStateException("手动调账记录冲突，请重试", e);
        }
    }

    private void requireRole(AuthController.AuthSession s) {
        if (!"admin".equals(PermissionCatalog.normalizeRole(s.role()))) {
            throw new SecurityException("仅管理员可操作");
        }
    }

    private String required(String v, String n, int max) {
        String x = v == null ? "" : v.trim();
        if (x.isEmpty() || x.length() > max) throw new IllegalArgumentException(n + "不能为空且不得超过" + max + "字");
        return x;
    }
}
