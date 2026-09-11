package com.emie.designpm.reference.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.PriceRange;
import com.emie.designpm.reference.repository.PriceRangeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约测试：钉住 PriceRangeController 现有 JSON 形状 + 校验/权限行为。参见 CategoryControllerContractTest。 */
class PriceRangeControllerContractTest {

    private final PriceRangeRepository repo = mock(PriceRangeRepository.class);
    private final PriceRangeController controller = new PriceRangeController(repo);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void jsonShapeIsExactlyIdNameSortOrderActive() {
        PriceRange range = new PriceRange("100-500元", 2);
        range.setId(7L);

        Map<String, Object> serialized = json.convertValue(range, new TypeReference<Map<String, Object>>() {});

        assertEquals(Map.of("id", 7L, "name", "100-500元", "sortOrder", 2, "active", true), serialized);
    }

    @Test
    void listActiveReturnsRepositoryResultUnfiltered() {
        PriceRange active = new PriceRange("0-100元", 1);
        when(repo.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(active));

        var response = controller.listActive();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(active), response.getBody());
    }

    @Test
    void listAllRejectsNonAdmin() {
        assertEquals(
                HttpStatus.FORBIDDEN, controller.listAll(request("planner")).getStatusCode());
    }

    @Test
    void createRejectsNonAdminAndBlankName() {
        var forbidden = controller.create(Map.of("name", "500-1000元"), request("planner"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        verify(repo, never()).save(any());

        var blank = controller.create(Map.of("name", "  "), request("admin"));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
    }

    @Test
    void createDefaultsSortOrderToZeroWhenUnparsable() {
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(Map.of("name", "1000元以上", "sortOrder", "abc"), request("admin"));

        assertEquals(0, response.getBody().getSortOrder());
    }

    @Test
    void updateReturnsNotFoundForMissingId() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller.update(99L, Map.of("name", "x"), request("admin")).getStatusCode());
    }

    @Test
    void updateOnlyTouchesFieldsPresentInBody() {
        PriceRange existing = new PriceRange("原区间", 1);
        existing.setId(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.update(1L, Map.of("sortOrder", "9"), request("admin"));

        assertEquals("原区间", response.getBody().getName());
        assertEquals(9, response.getBody().getSortOrder());
        assertEquals(true, response.getBody().getActive());
    }

    @Test
    void deleteRejectsNonAdminAndReturnsNotFoundForMissingId() {
        assertEquals(
                HttpStatus.FORBIDDEN, controller.delete(1L, request("sales")).getStatusCode());

        when(repo.existsById(2L)).thenReturn(false);
        assertEquals(
                HttpStatus.NOT_FOUND, controller.delete(2L, request("admin")).getStatusCode());
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

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("u1", role, "测试用户"));
        return request;
    }
}
