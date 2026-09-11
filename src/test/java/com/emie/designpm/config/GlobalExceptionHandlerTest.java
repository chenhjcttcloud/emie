package com.emie.designpm.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 请求体解析失败之前没有专门处理，会跌到 catch-all 变成 500——客户端自己传错格式，
 * 却看到「系统处理失败」，且掩盖了真正的未预期系统错误。钉住它必须走 400。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unreadableRequestBodyReturnsBadRequestNotInternalServerError() {
        var ex = new HttpMessageNotReadableException("sortOrder 不是合法数字");
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/price-ranges");

        var response = handler.handleUnreadableBody(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("请求体格式不正确，请检查输入", response.getBody().get("error"));
        assertTrue(response.getBody().containsKey("traceId"));
    }
}
