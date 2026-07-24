package com.emie.designpm.controller;

import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.DesignRequirementScoreRepository;
import com.emie.designpm.service.DesignRequirementScoringService;
import com.emie.designpm.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class DesignRequirementControllerTest {

    @Test
    void createUsesAuthenticatedUserAndIgnoresSubmittedOwnerIdentity() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirementScoreRepository scoreRepository = mock(DesignRequirementScoreRepository.class);
        DesignRequirementScoringService scoringService = mock(DesignRequirementScoringService.class);
        UserService userService = mock(UserService.class);
        when(userService.getUserByUserId("designer-1")).thenReturn(User.builder()
                .userId("designer-1").name("真实设计师").role("designer").status("active").build());
        when(userService.getUserByUserId("planner-1")).thenReturn(User.builder()
                .userId("planner-1").name("真实企划").role("planner").status("active").build());
        when(repository.save(any())).thenAnswer(invocation -> {
            DesignRequirement saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        DesignRequirementController controller = new DesignRequirementController(
                repository, scoreRepository, scoringService, userService);
        MockHttpServletRequest request = authenticated("sales-1", "sales", "销售一");

        var response = controller.create(Map.of(
                "productName", "新产品", "deadline", "2026-08-01",
                "productRequirements", "完成包装设计", "ownerId", "forged-user",
                "designerId", "designer-1", "designerName", "伪造设计师",
                "plannerId", "planner-1", "plannerName", "伪造企划"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DesignRequirement saved = captureSaved(repository);
        assertEquals("sales-1", saved.getOwnerId());
        assertEquals("sales-1", saved.getResponsibleId());
        assertEquals("销售一", saved.getResponsibleName());
        assertEquals("真实设计师", saved.getDesignerName());
        assertEquals("真实企划", saved.getPlannerName());
        verify(scoringService).initialize(saved);
    }

    @Test
    void createRejectsUnauthorizedRoleAndMissingRequiredFields() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirementController controller = new DesignRequirementController(repository);

        assertEquals(HttpStatus.FORBIDDEN, controller.create(Map.of(
                "productName", "产品", "deadline", "2026-08-01", "productRequirements", "要求",
                "designerId", "designer-1", "plannerId", "planner-1"),
                authenticated("designer-1", "designer", "设计师")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.create(Map.of("productName", "产品"),
                authenticated("sales-1", "sales", "销售一")).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void pageScopesNonAdminButAllowsAdminToSeeAll() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        when(repository.findPage(isNull(), isNull(), nullable(String.class), any())).thenReturn(new PageImpl<>(List.of()));
        DesignRequirementController controller = new DesignRequirementController(repository);

        controller.page(null, null, 0, 15, authenticated("sales-1", "sales", "销售一"));
        controller.page(null, null, 0, 15, authenticated("admin-1", "admin", "管理员"));

        verify(repository).findPage(null, null, "sales-1", org.springframework.data.domain.PageRequest.of(0, 15));
        verify(repository).findPage(null, null, null, org.springframework.data.domain.PageRequest.of(0, 15));
    }

    @Test
    void detailAllowsParticipantButRejectsUnrelatedUser() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirement requirement = new DesignRequirement();
        requirement.setId(9L);
        requirement.setName("包装送审");
        requirement.setRequirements("完成包装方案");
        requirement.setDeadline("2026-08-01");
        requirement.setOwnerId("sales-1");
        requirement.setResponsibleId("promotion-1");
        when(repository.findById(9L)).thenReturn(Optional.of(requirement));
        DesignRequirementController controller = new DesignRequirementController(repository);

        assertEquals(HttpStatus.OK,
                controller.detail(9L, authenticated("promotion-1", "promotion", "推广一")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.detail(9L, authenticated("designer-1", "designer", "设计师")).getStatusCode());
    }

    private MockHttpServletRequest authenticated(String userId, String role, String name) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthController.AuthSession(userId, role, name));
        return request;
    }

    private DesignRequirement captureSaved(DesignRequirementRepository repository) {
        var captor = org.mockito.ArgumentCaptor.forClass(DesignRequirement.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
