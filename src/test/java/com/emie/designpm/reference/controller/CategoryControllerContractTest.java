package com.emie.designpm.reference.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.ProductCategory;
import com.emie.designpm.reference.repository.ProductCategoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 契约测试：钉住 CategoryController 现有的 JSON 形状 + 校验/权限行为，
 * 在 POST/PUT body 由 Map&lt;String,String&gt; 换成 typed DTO 之前先立好基线——
 * 之后改动，这里任何一条断言变红都代表契约变了，要么是回归，要么是有意为之
 * （更新测试并在 PR 里说明）。
 */
class CategoryControllerContractTest {

    private final ProductCategoryRepository repo = mock(ProductCategoryRepository.class);
    private final CategoryController controller = new CategoryController(repo);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void jsonShapeIsExactlyIdNameSortOrderActive() throws Exception {
        ProductCategory cat = new ProductCategory("箱包", 3);
        cat.setId(9L);

        Map<String, Object> serialized = json.convertValue(cat, new TypeReference<Map<String, Object>>() {});

        assertEquals(Map.of("id", 9L, "name", "箱包", "sortOrder", 3, "active", true), serialized);
    }

    @Test
    void listActiveReturnsRepositoryResultUnfiltered() {
        ProductCategory active = new ProductCategory("家具", 1);
        when(repo.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(active));

        var response = controller.listActive();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(active), response.getBody());
    }

    @Test
    void listAllRejectsNonAdmin() {
        var response = controller.listAll(request("planner"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void createRejectsNonAdmin() {
        var response = controller.create(Map.of("name", "新类目"), request("planner"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        var response = controller.create(Map.of("name", "   "), request("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(repo, never()).save(any());
    }

    @Test
    void createDefaultsSortOrderToZeroWhenAbsentOrUnparsable() {
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(Map.of("name", "文具"), request("admin"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getSortOrder());

        response = controller.create(Map.of("name", "文具二", "sortOrder", "not-a-number"), request("admin"));
        assertEquals(0, response.getBody().getSortOrder());
    }

    @Test
    void createSanitizesAndTrimsName() {
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(Map.of("name", "  <b>饰品</b>  ", "sortOrder", "5"), request("admin"));

        assertEquals("饰品", response.getBody().getName());
        assertEquals(5, response.getBody().getSortOrder());
    }

    @Test
    void updateReturnsNotFoundForMissingId() {
        when(repo.findById(404L)).thenReturn(Optional.empty());

        var response = controller.update(404L, Map.of("name", "x"), request("admin"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void updateOnlyTouchesFieldsPresentInBody() {
        ProductCategory existing = new ProductCategory("原名", 1);
        existing.setId(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.update(1L, Map.of("active", "false"), request("admin"));

        assertEquals("原名", response.getBody().getName());
        assertEquals(1, response.getBody().getSortOrder());
        assertEquals(false, response.getBody().getActive());
    }

    @Test
    void deleteRejectsNonAdminAndReturnsNotFoundForMissingId() {
        var forbidden = controller.delete(1L, request("sales"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        when(repo.existsById(2L)).thenReturn(false);
        var notFound = controller.delete(2L, request("admin"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void deleteSucceedsForAdmin() {
        when(repo.existsById(3L)).thenReturn(true);

        var response = controller.delete(3L, request("admin"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
        verify(repo).deleteById(3L);
    }

    @Test
    void listActiveHasNoAuthGuard() {
        // 契约现状：GET / 本身不做鉴权，前端下拉框未登录也能拉。若以后要收紧，
        // 这条会先失败，提醒这是有意的行为变化。
        assertTrue(controller.listActive().getStatusCode().is2xxSuccessful());
    }

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("u1", role, "测试用户"));
        return request;
    }
}
