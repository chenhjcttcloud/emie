package com.emie.designpm.points.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.admin.repository.UserRepository;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.User;
import com.emie.designpm.points.repository.PointAdjustmentLedgerRepository;
import com.emie.designpm.points.service.PointManualAdjustmentService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PointGovernanceControllerTest {
    @Test
    void manualAdjustmentAcceptsDecimalsAndRejectsInvalidNumbers() {
        var ledgers = mock(PointAdjustmentLedgerRepository.class);
        var users = mock(UserRepository.class);
        var user = new User();
        user.setRole("designer");
        when(users.findByUserId("designer-1")).thenReturn(Optional.of(user));
        when(ledgers.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var controller =
                new PointGovernanceController(null, null, null, new PointManualAdjustmentService(ledgers, users));
        var request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("admin-1", "admin", "管理员"));
        for (Number points : new Number[] {10, -2.5, 0.29, -0.01}) {
            assertEquals(
                    200,
                    controller
                            .manualAdjustment(
                                    Map.of("userId", "designer-1", "points", points, "reason", "积分更正"), request)
                            .getStatusCode()
                            .value());
        }
        clearInvocations(ledgers);
        for (Object points : new Object[] {0, 0.001, 100000.01, 4294967297L, "2.5"}) {
            assertEquals(
                    400,
                    controller
                            .manualAdjustment(
                                    Map.of("userId", "designer-1", "points", points, "reason", "积分更正"), request)
                            .getStatusCode()
                            .value());
        }
        assertEquals(
                400,
                controller
                        .manualAdjustment(Map.of("userId", "designer-1", "reason", "积分更正"), request)
                        .getStatusCode()
                        .value());
        verify(ledgers, never()).saveAndFlush(any());
        request.setAttribute("authSession", new AuthSession("planner-1", "planner", "企划"));
        assertEquals(
                403,
                controller
                        .manualAdjustment(Map.of("userId", "designer-1", "points", 1.5, "reason", "积分更正"), request)
                        .getStatusCode()
                        .value());
    }
}
