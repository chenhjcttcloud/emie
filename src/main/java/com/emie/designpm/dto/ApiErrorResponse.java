package com.emie.designpm.dto;

/** 新增及改造中的 CRUD 接口统一错误结构。 */
public record ApiErrorResponse(String code, String message) {
    public static ApiErrorResponse invalidQuery(String message) {
        return new ApiErrorResponse("INVALID_QUERY", message);
    }
}
