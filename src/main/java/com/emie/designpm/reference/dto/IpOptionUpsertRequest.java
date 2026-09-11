package com.emie.designpm.reference.dto;

/**
 * 新建/编辑 IP 配置的请求体。字段全部可省略，语义同 {@link PriceRangeUpsertRequest}：
 * 省略代表"维持原值"（更新）或"用默认值"（新建）。{@code subOptions} 是逗号/换行分隔的原始文本，
 * 不是数组——保持前端一直以来的格式，转换成 JSON 数组字符串的逻辑留在 controller 里。
 */
public record IpOptionUpsertRequest(
        String name, String sortOrder, String active, String subOptions, String subOptionSelectionMode) {}
