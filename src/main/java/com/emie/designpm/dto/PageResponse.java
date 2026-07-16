package com.emie.designpm.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 统一的分页读取响应。所有新增列表型 CRUD 接口均使用该稳定结构。
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
