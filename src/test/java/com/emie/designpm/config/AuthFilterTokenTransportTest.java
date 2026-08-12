package com.emie.designpm.config;

import com.emie.designpm.controller.AuthController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthFilterTokenTransportTest {
    private final List<String> userIds = new ArrayList<>();
    private final AuthFilter filter = new AuthFilter();

    @AfterEach
    void clearSessions() {
        userIds.forEach(AuthController::clearUserTokens);
    }

    @Test
    void permanentSessionsUseZeroExpiry() {
        AuthController.AuthSession direct = new AuthController.AuthSession("user-1", "sales", "销售");
        AuthController.AuthSession impersonated = new AuthController.AuthSession(
                "user-2", "designer", "设计师", "admin-1", "admin");

        assertEquals(0L, direct.expiresAt());
        assertEquals(0L, impersonated.expiresAt());
    }

    @Test
    void queryTokenIsAcceptedForGetAndHeadOnFileReadRoutes() throws Exception {
        assertQueryTokenAccepted("GET", "/api/files/thumbnail/example.png");
        assertQueryTokenAccepted("HEAD", "/api/files/download/example.pdf");
        assertQueryTokenAccepted("GET", "/api/files/preview/example.pdf");
    }

    @Test
    void queryTokenIsRejectedOutsideAllowedFileReadRoutes() throws Exception {
        assertQueryTokenRejected("GET", "/api/projects");
        assertQueryTokenRejected("GET", "/api/files/upload");
        assertQueryTokenRejected("POST", "/api/files/download/example.pdf");
        assertQueryTokenRejected("PUT", "/api/files/thumbnail/example.png");
    }

    @Test
    void authHeaderStillWorksForOtherApiRoutes() throws Exception {
        String token = tokenFor("header-user");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("X-Auth-Token", token);
        MockFilterChain chain = execute(request);

        assertNotNull(chain.getRequest());
        assertNotNull(request.getAttribute("authSession"));
    }

    @Test
    void invalidHeaderIsNotReplacedByValidQueryToken() throws Exception {
        String token = tokenFor("header-precedence-user");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/preview/example.pdf");
        request.addHeader("X-Auth-Token", "invalid-token");
        request.setParameter("token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    private void assertQueryTokenAccepted(String method, String path) throws Exception {
        String token = tokenFor(method + path);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setParameter("token", token);
        MockFilterChain chain = execute(request);

        assertNotNull(chain.getRequest());
        assertNotNull(request.getAttribute("authSession"));
    }

    private void assertQueryTokenRejected(String method, String path) throws Exception {
        String token = tokenFor(method + path);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setParameter("token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    private MockFilterChain execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        return chain;
    }

    private String tokenFor(String suffix) {
        String userId = "auth-filter-" + Integer.toUnsignedString(suffix.hashCode());
        userIds.add(userId);
        return AuthController.generateToken(userId, "sales", "测试用户");
    }
}
