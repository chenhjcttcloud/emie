package com.emie.designpm.controller;

import com.emie.designpm.entity.DesignRequirement;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.DesignRequirementRepository;
import com.emie.designpm.repository.DesignRequirementScoreRepository;
import com.emie.designpm.service.DesignRequirementScoringService;
import com.emie.designpm.service.UserService;
import com.emie.designpm.service.PermissionService;
import com.emie.designpm.service.NotificationWorkflowService;
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
    void createRejectsRoleWhenNormalizedPermissionIsDenied() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("sales", "design_requirement.create")).thenReturn(false);
        DesignRequirementController controller = new DesignRequirementController(
                repository, mock(DesignRequirementScoreRepository.class),
                mock(DesignRequirementScoringService.class), mock(UserService.class), permissions);

        var response = controller.create(Map.of(
                "productName", "产品", "deadline", "2026-08-01", "productRequirements", "要求",
                "designerId", "designer-1", "plannerId", "planner-1"),
                authenticated("sales-1", "sales", "销售一"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("design_requirement.create",
                ((Map<?, ?>) response.getBody()).get("permission"));
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

    @Test
    void deliveryPermissionIsCheckedBeforeRequirementDataIsLoaded() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.has("designer", "design_requirement.deliver")).thenReturn(false);
        DesignRequirementController controller = new DesignRequirementController(
                repository, mock(DesignRequirementScoreRepository.class),
                mock(DesignRequirementScoringService.class), mock(UserService.class), permissions);

        var response = controller.deliver(9L, Map.of("deliveryContent", "成果"),
                authenticated("designer-1", "designer", "设计师"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("design_requirement.deliver", ((Map<?, ?>) response.getBody()).get("permission"));
        verifyNoInteractions(repository);
    }

    @Test
    void creationNotifiesAssignedPlannerAndDesignerAfterBusinessSave() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirementScoringService scoringService = mock(DesignRequirementScoringService.class);
        UserService userService = mock(UserService.class);
        PermissionService permissions = mock(PermissionService.class);
        NotificationWorkflowService notifications = mock(NotificationWorkflowService.class);
        when(permissions.has("sales", "design_requirement.create")).thenReturn(true);
        when(userService.getUserByUserId("designer-1")).thenReturn(User.builder()
                .userId("designer-1").name("设计师").role("designer").status("active").build());
        when(userService.getUserByUserId("planner-1")).thenReturn(User.builder()
                .userId("planner-1").name("产品企划").role("planner").status("active").build());
        when(repository.save(any())).thenAnswer(invocation -> {
            DesignRequirement saved = invocation.getArgument(0);
            saved.setId(66L);
            return saved;
        });
        DesignRequirementController controller = new DesignRequirementController(
                repository, mock(DesignRequirementScoreRepository.class), scoringService,
                userService, permissions, notifications);

        var response = controller.create(Map.of(
                "productName", "送审新品", "deadline", "2026-08-10",
                "productRequirements", "完成设计送审",
                "designerId", "designer-1", "plannerId", "planner-1"),
                authenticated("sales-1", "sales", "销售一"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(notifications).notifyUserAfterCommit(
                eq("DESIGN_REQUIREMENT_ASSIGNED"), eq("planner-1"),
                eq("design_requirement"), eq(66L), eq("sales-1"), anyMap());
        verify(notifications).notifyUserAfterCommit(
                eq("DESIGN_REQUIREMENT_DESIGNER_ASSIGNED"), eq("designer-1"),
                eq("design_requirement"), eq(66L), eq("sales-1"), anyMap());
    }

    @Test
    void dashboardReturnsActionableRequirementsVisibleToCurrentUser() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirement requirement = new DesignRequirement();
        requirement.setId(77L);
        requirement.setName("包装设计");
        requirement.setDeadline("2026-08-20");
        requirement.setDesignerId("designer-1");
        when(repository.findDashboardItems("designer-1")).thenReturn(List.of(requirement));
        DesignRequirementController controller = new DesignRequirementController(repository);

        var response = controller.dashboard(authenticated("designer-1", "designer", "设计师"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(repository).findDashboardItems("designer-1");
    }

    @Test
    void ownerCanTerminateRequirementAndPendingScoresAreDeactivated() {
        DesignRequirementRepository repository = mock(DesignRequirementRepository.class);
        DesignRequirementScoreRepository scoreRepository = mock(DesignRequirementScoreRepository.class);
        DesignRequirement requirement = new DesignRequirement();
        requirement.setId(88L);
        requirement.setName("终止测试");
        requirement.setStatus("in_progress");
        requirement.setOwnerId("sales-1");
        when(repository.findById(88L)).thenReturn(Optional.of(requirement));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scoreRepository.findByRequirementIdOrderByIdAsc(88L)).thenReturn(List.of());
        DesignRequirementController controller = new DesignRequirementController(
                repository, scoreRepository, mock(DesignRequirementScoringService.class), mock(UserService.class));

        var response = controller.terminate(88L, authenticated("sales-1", "sales", "销售一"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("terminated", requirement.getStatus());
        verify(scoreRepository).saveAll(List.of());
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
