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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void httpOnlyCookieIsAcceptedForFileReadRoutes() throws Exception {
        assertCookieAccepted("GET", "/api/files/thumbnail/example.png");
        assertCookieAccepted("HEAD", "/api/files/download/example.pdf");
        assertCookieAccepted("GET", "/api/files/preview/example.pdf");
    }

    @Test
    void queryTokenIsRejectedForAllRoutes() throws Exception {
        assertQueryTokenRejected("GET", "/api/projects");
        assertQueryTokenRejected("GET", "/api/files/thumbnail/example.png");
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
    void invalidHeaderIsNotReplacedByValidCookie() throws Exception {
        String token = tokenFor("header-precedence-user");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/preview/example.pdf");
        request.addHeader("X-Auth-Token", "invalid-token");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void siteWideCspHeaderIsPresentOnApiResponses() throws Exception {
        String token = tokenFor("csp-user");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("X-Auth-Token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        String csp = response.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("script-src 'self' 'unsafe-eval'"),
                "CSP 必须显式含 unsafe-eval（事件运行时 new Function 依赖，缺失会导致全站点击失效）");
        assertTrue(csp.contains("script-src-attr 'none'"), "CSP 必须包含 script-src-attr 'none'");
        assertTrue(csp.contains("object-src 'none'"));
    }

    @Test
    void sharePagesKeepTheirOwnCspWithoutSiteWideHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/share/abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Content-Security-Policy"),
                "站点级 CSP 应跳过 /share/**，由 PublicShareController 设置分享页自身 CSP");
        assertNotNull(response.getHeader("X-Content-Type-Options"));
    }

    @Test
    void anonymousAdminManagedImageDownloadIsWhitelisted() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/files/download/admin/admin_logo_a1b2c3d4.png");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(request.getAttribute("authSession"), "白名单路径不应要求认证");
    }

    @Test
    void adminPrefixPassesFilterButControllerEnforcesFailClosed() throws Exception {
        // 过滤器按目录前缀放行 /api/files/download/admin/**；
        // 非 ADMIN_MANAGED_IMAGE 文件名在 FileController.checkDownloadAccess 中被拒绝（匿名 → 401）。
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/files/download/admin/secret-plan.pdf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(request.getAttribute("authSession"), "过滤器不建立会话，fail-closed 由控制器兜底");
    }

    private void assertCookieAccepted(String method, String path) throws Exception {
        String token = tokenFor(method + path);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setCookies(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token));
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
