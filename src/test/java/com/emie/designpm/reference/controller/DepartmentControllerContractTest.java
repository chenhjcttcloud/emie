package com.emie.designpm.reference.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.admin.service.UserService;
import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.Department;
import com.emie.designpm.entity.User;
import com.emie.designpm.reference.repository.DepartmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 契约测试：钉住 DepartmentController 现有 JSON 形状 + 权限/负责人联动行为。
 *
 * 注意：这个 controller 的 create/update 直接 {@code @RequestBody Department}
 * 绑定实体（不是 Map），且 update 没有 {@code containsKey} 式的按字段部分更新——
 * 前端如果只传 {name} 省略 sortOrder/active，会把它们静默重置为实体默认值
 * （0 / true，已用 updateWithOmittedFieldsResetsToEntityDefaults 钉死）。
 * 这是现状，不是这次测试引入的；写成 typed DTO 时要么保留这个语义、要么
 * 明确改成部分更新并在 PR 里说清楚。
 */
class DepartmentControllerContractTest {

    private final DepartmentRepository repo = mock(DepartmentRepository.class);
    private final UserService userService = mock(UserService.class);
    private final DepartmentController controller = new DepartmentController(repo, userService);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void jsonShapeIsExactlyIdNameRoleHeadUserIdSortOrderActive() {
        Department dept = Department.builder()
                .id(5L)
                .name("设计一部")
                .role("designer")
                .headUserId("u1")
                .sortOrder(2)
                .active(true)
                .build();

        Map<String, Object> serialized = json.convertValue(dept, new TypeReference<Map<String, Object>>() {});

        assertEquals(
                Map.of(
                        "id",
                        5L,
                        "name",
                        "设计一部",
                        "role",
                        "designer",
                        "headUserId",
                        "u1",
                        "sortOrder",
                        2,
                        "active",
                        true),
                serialized);
    }

    @Test
    void getAllReturnsRepositoryResultUnfiltered() {
        Department dept = Department.builder().name("销售部").role("sales").build();
        when(repo.findAllByOrderBySortOrderAsc()).thenReturn(List.of(dept));

        assertEquals(List.of(dept), controller.getAll().getBody());
    }

    @Test
    void createRejectsNonAdmin() {
        var response = controller.create(Department.builder().name("x").build(), request("planner"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        var response = controller.create(Department.builder().name("  ").build(), request("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createRejectsAdminAsDepartmentHead() {
        when(userService.getUserByUserId("admin-1")).thenReturn(user("admin-1", "admin"));

        var response = controller.create(
                Department.builder()
                        .name("设计一部")
                        .role("designer")
                        .headUserId("admin-1")
                        .build(),
                request("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void createSavesAndPromotesHeadToTitleLevelTwo() {
        User designer = user("d1", "designer");
        when(userService.getUserByUserId("d1")).thenReturn(designer);
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(
                Department.builder()
                        .name("设计一部")
                        .role("designer")
                        .headUserId("d1")
                        .build(),
                request("admin"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, designer.getTitleLevel());
        verify(userService).saveUser(designer);
    }

    @Test
    void updateReturnsNotFoundForMissingId() {
        when(repo.findById(404L)).thenReturn(Optional.empty());

        var response = controller.update(404L, Department.builder().name("x").build(), request("admin"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void updateWithOmittedFieldsResetsToEntityDefaults() {
        Department existing = Department.builder()
                .id(1L)
                .name("原名")
                .role("sales")
                .sortOrder(9)
                .active(false)
                .build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 只想改名字，但绑定的是整个实体：Department.sortOrder/active 走 Lombok 字段默认值 0/true。
        var response = controller.update(
                1L, Department.builder().name("新名").role("sales").build(), request("admin"));

        Department saved = (Department) response.getBody();
        assertEquals("新名", saved.getName());
        assertEquals(0, saved.getSortOrder(), "现状：省略字段被静默重置为默认值，不是保留原值");
        assertEquals(true, saved.getActive(), "现状：省略字段被静默重置为默认值，不是保留原值");
    }

    @Test
    void updateDemotesOldHeadWhenHeadChanges() {
        Department existing = Department.builder()
                .id(1L)
                .name("设计一部")
                .role("designer")
                .headUserId("old-head")
                .build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        User oldHead = user("old-head", "designer");
        oldHead.setTitleLevel(2);
        when(userService.getUserByUserId("old-head")).thenReturn(oldHead);
        User newHead = user("new-head", "designer");
        when(userService.getUserByUserId("new-head")).thenReturn(newHead);

        controller.update(
                1L,
                Department.builder()
                        .name("设计一部")
                        .role("designer")
                        .headUserId("new-head")
                        .build(),
                request("admin"));

        assertEquals(0, oldHead.getTitleLevel(), "旧负责人降级");
        assertEquals(2, newHead.getTitleLevel(), "新负责人升级");
    }

    @Test
    void deleteRejectsNonAdminWithErrorBody() {
        var response = controller.delete(1L, request("sales"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("error", "仅管理员可操作"), response.getBody());
    }

    private User user(String userId, String role) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(role);
        return u;
    }

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("u1", role, "测试用户"));
        return request;
    }
}
