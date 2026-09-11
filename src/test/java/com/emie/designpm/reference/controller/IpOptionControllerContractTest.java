package com.emie.designpm.reference.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.emie.designpm.auth.AuthSession;
import com.emie.designpm.entity.IpOption;
import com.emie.designpm.reference.dto.IpOptionUpsertRequest;
import com.emie.designpm.reference.repository.IpOptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 契约测试：钉住 IpOptionController 现有 JSON 形状 + 校验/权限行为，尤其是二级 IP 选项
 * （逗号/换行分隔的原始文本 -&gt; subOptionsJson 数组字符串）这个隐藏在 body 里的迷你格式。
 */
class IpOptionControllerContractTest {

    private final IpOptionRepository repo = mock(IpOptionRepository.class);
    private final IpOptionController controller = new IpOptionController(repo);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void jsonShapeIncludesSubOptionFields() {
        IpOption option = new IpOption("熊猫计划", 4);
        option.setId(2L);
        option.setSubOptionsJson("[\"联名款\"]");
        option.setSubOptionSelectionMode("single");

        Map<String, Object> serialized = json.convertValue(option, new TypeReference<Map<String, Object>>() {});

        assertEquals(
                Map.of(
                        "id",
                        2L,
                        "name",
                        "熊猫计划",
                        "sortOrder",
                        4,
                        "active",
                        true,
                        "subOptionsJson",
                        "[\"联名款\"]",
                        "subOptionSelectionMode",
                        "single"),
                serialized);
    }

    /** 前端实际线上格式：sortOrder/active/subOptions 全部以字符串发送，见 admin-catalog.js saveIpOption。 */
    @Test
    void requestDtoAcceptsTheActualWireFormatsTheFrontendSends() throws Exception {
        IpOptionUpsertRequest createBody = json.readValue(
                "{\"name\":\"熊猫计划\",\"sortOrder\":\"3\",\"subOptions\":\"联名款\",\"subOptionSelectionMode\":\"single\"}",
                IpOptionUpsertRequest.class);
        assertEquals("3", createBody.sortOrder());
        assertEquals("联名款", createBody.subOptions());

        IpOptionUpsertRequest updateBody = json.readValue("{\"active\":\"false\"}", IpOptionUpsertRequest.class);
        assertEquals("false", updateBody.active());
    }

    @Test
    void listActiveReturnsRepositoryResultUnfiltered() {
        IpOption active = new IpOption("A", 1);
        when(repo.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(active));

        assertEquals(List.of(active), controller.listActive().getBody());
    }

    @Test
    void listAllRejectsNonAdmin() {
        assertEquals(
                HttpStatus.FORBIDDEN, controller.listAll(request("planner")).getStatusCode());
    }

    @Test
    void createRejectsNonAdminAndBlankName() {
        var forbidden =
                controller.create(new IpOptionUpsertRequest("熊猫计划", null, null, null, null), request("planner"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        verify(repo, never()).save(any());

        var blank = controller.create(new IpOptionUpsertRequest("  ", null, null, null, null), request("admin"));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        assertEquals(Map.of("error", "请输入IP名称"), blank.getBody());
    }

    @Test
    void createRejectsDuplicateNameAtApplicationLayer() {
        when(repo.findByName("已存在")).thenReturn(Optional.of(new IpOption("已存在", 0)));

        var response = controller.create(new IpOptionUpsertRequest("已存在", null, null, null, null), request("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "IP名称已存在"), response.getBody());
        verify(repo, never()).save(any());
    }

    @Test
    void createMapsUniqueConstraintViolationToBadRequest() {
        when(repo.findByName("并发重名")).thenReturn(Optional.empty());
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        var response = controller.create(new IpOptionUpsertRequest("并发重名", null, null, null, null), request("admin"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "IP名称已存在"), response.getBody());
    }

    @Test
    void createParsesCommaAndNewlineSeparatedSubOptionsIntoJsonArray() throws Exception {
        when(repo.findByName(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(
                new IpOptionUpsertRequest("熊猫计划", null, null, "联名款, 限定款\n联名款", "single"), // 逗号/换行分隔，重复项去重
                request("admin"));

        IpOption saved = (IpOption) response.getBody();
        List<?> subOptions = json.readValue(saved.getSubOptionsJson(), List.class);
        assertEquals(List.of("联名款", "限定款"), subOptions);
        assertEquals("single", saved.getSubOptionSelectionMode());
    }

    @Test
    void createDefaultsSubOptionSelectionModeToMultipleForUnrecognizedValue() throws Exception {
        when(repo.findByName(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.create(new IpOptionUpsertRequest("无二级选项", null, null, null, null), request("admin"));

        IpOption saved = (IpOption) response.getBody();
        assertEquals("multiple", saved.getSubOptionSelectionMode());
        assertEquals(List.of(), json.readValue(saved.getSubOptionsJson(), List.class));
    }

    @Test
    void updateReturnsNotFoundForMissingId() {
        when(repo.findById(404L)).thenReturn(Optional.empty());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller
                        .update(404L, new IpOptionUpsertRequest("x", null, null, null, null), request("admin"))
                        .getStatusCode());
    }

    @Test
    void updateOnlyTouchesFieldsPresentInBody() {
        IpOption existing = new IpOption("原名", 1);
        existing.setId(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response =
                controller.update(1L, new IpOptionUpsertRequest(null, null, "false", null, null), request("admin"));

        IpOption saved = (IpOption) response.getBody();
        assertEquals("原名", saved.getName());
        assertEquals(false, saved.getActive());
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
    void deleteSucceedsForAdminAndReturnsMessageBody() {
        when(repo.existsById(3L)).thenReturn(true);

        var response = controller.delete(3L, request("admin"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("message", "IP配置已删除"), response.getBody());
        verify(repo).deleteById(3L);
    }

    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authSession", new AuthSession("u1", role, "测试用户"));
        return request;
    }
}
