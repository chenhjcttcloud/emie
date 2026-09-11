package com.emie.designpm.reference.dto;

/** 新建/编辑类目的请求体；String 字段保留现有宽松解析和部分更新语义。 */
public record CategoryUpsertRequest(String name, String sortOrder, String active) {}
