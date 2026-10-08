package com.emie.designpm.points.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.PointRule;
import com.emie.designpm.points.repository.PointLedgerRepository;
import com.emie.designpm.points.repository.PointRuleRepository;
import com.emie.designpm.points.service.PointsService;
import com.emie.designpm.scoring.repository.ScoringRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PointRuleSubcategoryTest {
    private final PointRuleRepository rules = mock(PointRuleRepository.class);
    private final PointsService service =
            new PointsService(rules, mock(PointLedgerRepository.class), mock(ScoringRepository.class));
    private final PointsController controller = new PointsController(service, null, null);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final PointRule existing = new PointRule();

    @BeforeEach
    void setUp() {
        request.setAttribute("authSession", new AuthSession("admin-1", "admin", "管理员"));
        existing.setRuleCode("EXISTING");
        existing.setPoints(2d);
        existing.setCategory("产品设计");
        existing.setSubcategory("概念");
        when(rules.findByRuleCode("EXISTING")).thenReturn(Optional.of(existing));
        when(rules.save(any(PointRule.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void updateOmissionPreservesSubcategoryWhileBlankClearsIt() {
        assertEquals(
                200,
                controller
                        .updateRule("EXISTING", Map.of("points", 3d), request)
                        .getStatusCode()
                        .value());
        assertEquals("概念", existing.getSubcategory());
        controller.updateRule("EXISTING", Map.of("subcategory", " 深化 "), request);
        assertEquals("深化", existing.getSubcategory());
        controller.updateRule("EXISTING", Map.of("subcategory", "  "), request);
        assertNull(existing.getSubcategory());
    }

    @Test
    void createSavesOptionalTrimmedSubcategoryAndBlankAsNull() {
        PointRule created = new PointRule();
        created.setRuleCode("NEW");
        created.setSubcategory(" 概念 ");
        assertEquals(
                200, controller.createRule(created, request).getStatusCode().value());
        assertEquals("概念", created.getSubcategory());
        PointRule empty = new PointRule();
        empty.setRuleCode("EMPTY");
        empty.setSubcategory(" ");
        controller.createRule(empty, request);
        assertNull(empty.getSubcategory());
        PointRule absent = new PointRule();
        absent.setRuleCode("ABSENT");
        controller.createRule(absent, request);
        assertNull(absent.getSubcategory());
    }

    @Test
    void lengthBoundaryAllows50AndRejects51ForCreateAndUpdate() {
        assertEquals(
                200,
                controller
                        .updateRule("EXISTING", Map.of("subcategory", "中".repeat(50)), request)
                        .getStatusCode()
                        .value());
        clearInvocations(rules);
        assertEquals(
                400,
                controller
                        .updateRule("EXISTING", Map.of("subcategory", "中".repeat(51)), request)
                        .getStatusCode()
                        .value());
        PointRule created = new PointRule();
        created.setRuleCode("NEW");
        created.setSubcategory("中".repeat(51));
        assertEquals(
                400, controller.createRule(created, request).getStatusCode().value());
        verify(rules, never()).save(any());
    }
}
