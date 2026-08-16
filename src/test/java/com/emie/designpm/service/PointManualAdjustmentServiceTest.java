package com.emie.designpm.service;

import com.emie.designpm.controller.AuthController;
import com.emie.designpm.entity.PointAdjustmentLedger;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 管理员手动调账（PointManualAdjustmentService）单元测试。
 * 覆盖：成功补分/扣分与审计字段、非管理员拒绝、积分非法值、备注必填、
 * 用户不存在、sourceId 递增以及唯一约束兜底转业务异常。
 */
class PointManualAdjustmentServiceTest {

    private PointAdjustmentLedgerRepository adjustments;
    private UserRepository users;
    private PointManualAdjustmentService service;

    @BeforeEach
    void setup() {
        adjustments = mock(PointAdjustmentLedgerRepository.class);
        users = mock(UserRepository.class);
        service = new PointManualAdjustmentService(adjustments, users);
    }

    @Test
    void adminCanAddAndDeductPointsWithAuditFields() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        when(adjustments.maxSourceIdByType("MANUAL")).thenReturn(3L);
        when(adjustments.save(any())).thenAnswer(i -> i.getArgument(0));

        PointAdjustmentLedger added = service.adjust("designer-1", 50, "  管理员补分  ", session("admin-1", "admin"));
        assertEquals("MANUAL", added.getSourceType());
        assertEquals(4L, added.getSourceId());
        assertEquals(50, added.getPoints());
        assertEquals("管理员补分", added.getReason());
        assertEquals("designer-1", added.getUserId());
        assertEquals("admin-1", added.getCreatedBy());

        PointAdjustmentLedger deducted = service.adjust("designer-1", -30, "管理员扣分", session("admin-1", "admin"));
        assertEquals(-30, deducted.getPoints());
    }

    @Test
    void nonAdminIsRejected() {
        assertThrows(SecurityException.class,
                () -> service.adjust("designer-1", 10, "备注", session("designer-1", "designer")));
        verify(adjustments, never()).save(any());
    }

    @Test
    void zeroAndOverLimitPointsAreRejected() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 0, "备注", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 100001, "备注", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", -100001, "备注", admin()));
        verify(adjustments, never()).save(any());
    }

    @Test
    void boundaryPointsAreAccepted() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        when(adjustments.maxSourceIdByType("MANUAL")).thenReturn(0L);
        when(adjustments.save(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(100000, service.adjust("designer-1", 100000, "上限补分", admin()).getPoints());
        assertEquals(-100000, service.adjust("designer-1", -100000, "下限扣分", admin()).getPoints());
    }

    @Test
    void emptyOrTooLongReasonIsRejected() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 10, null, admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 10, "", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 10, "   ", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("designer-1", 10, "长".repeat(501), admin()));
        verify(adjustments, never()).save(any());
    }

    @Test
    void unknownOrBlankUserIsRejected() {
        when(users.findByUserId("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.adjust("ghost", 10, "备注", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust("   ", 10, "备注", admin()));
        assertThrows(IllegalArgumentException.class, () -> service.adjust(null, 10, "备注", admin()));
        verify(adjustments, never()).save(any());
    }

    @Test
    void sourceIdIncrementsAcrossConsecutiveAdjustments() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        // 模拟两次独立调账事务：每次取 MAX(source_id)+1，分别得到 1、2。
        when(adjustments.maxSourceIdByType("MANUAL")).thenReturn(0L, 1L);
        when(adjustments.save(any())).thenAnswer(i -> i.getArgument(0));

        PointAdjustmentLedger first = service.adjust("designer-1", 20, "第一次补分", admin());
        PointAdjustmentLedger second = service.adjust("designer-1", -5, "第二次扣分", admin());
        assertEquals(1L, first.getSourceId());
        assertEquals(2L, second.getSourceId());
    }

    @Test
    void uniqueConstraintConflictTranslatesToBusinessException() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        when(adjustments.maxSourceIdByType("MANUAL")).thenReturn(5L);
        when(adjustments.save(any())).thenThrow(new DataIntegrityViolationException("uk_point_adjustment_source"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.adjust("designer-1", 10, "备注", admin()));
        assertEquals("手动调账记录冲突，请重试", e.getMessage());
    }

    @Test
    void trimmingKeepsUserIdAndReasonCanonical() {
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(new User()));
        when(adjustments.maxSourceIdByType(eq("MANUAL"))).thenReturn(0L);
        when(adjustments.save(any())).thenAnswer(i -> i.getArgument(0));

        PointAdjustmentLedger saved = service.adjust("  designer-1  ", 10, "  补发漏记积分  ", admin());
        assertEquals("designer-1", saved.getUserId());
        assertEquals("补发漏记积分", saved.getReason());
    }

    private AuthController.AuthSession admin() {
        return session("admin-1", "admin");
    }

    private AuthController.AuthSession session(String id, String role) {
        return new AuthController.AuthSession(id, role, id);
    }
}
