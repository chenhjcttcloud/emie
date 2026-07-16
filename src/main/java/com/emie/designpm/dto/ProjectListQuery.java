package com.emie.designpm.dto;

import org.springframework.data.domain.Pageable;

/** 项目列表的只读筛选条件；角色范围由服务端会话决定，客户端不得传入。 */
public record ProjectListQuery(
        String type,
        String status,
        String category,
        String market,
        String keyword,
        String deadlineStart,
        String deadlineEnd,
        boolean participating,
        Pageable pageable) {
}
