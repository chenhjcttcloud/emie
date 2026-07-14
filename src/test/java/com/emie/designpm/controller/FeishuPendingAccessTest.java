package com.emie.designpm.controller;

import com.emie.designpm.config.AuthFilter;
import com.emie.designpm.entity.User;
import com.emie.designpm.repository.SystemConfigRepository;
import com.emie.designpm.repository.UserRepository;
import com.lark.oapi.service.authen.v1.model.GetUserInfoRespBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FeishuPendingAccessTest {

    private static final String PENDING_USER_ID = "feishu_pending_test";

    @AfterEach
    void clearSession() {
        AuthController.clearUserTokens(PENDING_USER_ID);
    }

    @Test
    void firstFeishuLoginCreatesPendingUserWithoutBusinessRole() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByFeishuOpenId("ou_new_employee")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("employee@emie.com")).thenReturn(Optional.empty());
        when(users.findByUserId(anyString())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeishuAuthController controller = new FeishuAuthController(mock(SystemConfigRepository.class), users);
        GetUserInfoRespBody identity = new GetUserInfoRespBody();
        identity.setOpenId("ou_new_employee");
        identity.setName("新员工");
        identity.setEnterpriseEmail("employee@emie.com");

        User created = controller.resolveFeishuUser(identity);

        assertNotNull(created);
        assertEquals("pending", created.getRole());
        assertEquals("pending", created.getStatus());
        assertEquals("待分配角色", created.getTitle());
        assertEquals("ou_new_employee", created.getFeishuOpenId());
        assertEquals("employee@emie.com", created.getEmail());
        assertTrue(created.getUserId().startsWith("feishu_"));
    }

    @Test
    void existingEmailAccountIsBoundInsteadOfCreatingDuplicate() {
        UserRepository users = mock(UserRepository.class);
        User existing = User.builder()
                .userId("designer_existing")
                .name("已有员工")
                .role("designer")
                .email("employee@emie.com")
                .status("active")
                .build();
        when(users.findByFeishuOpenId("ou_existing")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("employee@emie.com")).thenReturn(Optional.of(existing));
        when(users.save(existing)).thenReturn(existing);

        FeishuAuthController controller = new FeishuAuthController(mock(SystemConfigRepository.class), users);
        GetUserInfoRespBody identity = new GetUserInfoRespBody();
        identity.setOpenId("ou_existing");
        identity.setEnterpriseEmail("employee@emie.com");

        User resolved = controller.resolveFeishuUser(identity);

        assertSame(existing, resolved);
        assertEquals("ou_existing", existing.getFeishuOpenId());
        verify(users).save(existing);
    }

    @Test
    void pendingSessionCanReadOwnStateButCannotAccessBusinessApis() throws Exception {
        String token = AuthController.generateToken(PENDING_USER_ID, "pending", "待授权员工");
        AuthFilter filter = new AuthFilter();

        MockHttpServletRequest deniedRequest = new MockHttpServletRequest("GET", "/api/projects");
        deniedRequest.addHeader("X-Auth-Token", token);
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        MockFilterChain deniedChain = new MockFilterChain();
        filter.doFilter(deniedRequest, deniedResponse, deniedChain);

        assertEquals(403, deniedResponse.getStatus());
        assertTrue(deniedResponse.getContentAsString().contains("等待管理员分配角色"));
        assertNull(deniedChain.getRequest());

        MockHttpServletRequest meRequest = new MockHttpServletRequest("GET", "/api/auth/me");
        meRequest.addHeader("X-Auth-Token", token);
        MockHttpServletResponse meResponse = new MockHttpServletResponse();
        MockFilterChain meChain = new MockFilterChain();
        filter.doFilter(meRequest, meResponse, meChain);

        assertNotNull(meChain.getRequest());
    }

    @Test
    void legacyRegistrationHandlerIsRemoved() {
        boolean hasRegisterHandler = Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch("/register"::equals);

        assertFalse(hasRegisterHandler);
    }
}
