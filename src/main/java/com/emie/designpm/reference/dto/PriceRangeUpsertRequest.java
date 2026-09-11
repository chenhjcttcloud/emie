package com.emie.designpm.reference.dto;

/**
 * 新建/编辑价格区间的请求体。字段全部可省略，省略即代表"维持原值"（更新时）或"用默认值"（新建时）——
 * 和之前 {@code Map<String,String>} 的 {@code containsKey} 语义完全一致，只是换成有名字的字段。
 *
 * <p>{@code sortOrder} 刻意保留 {@code String} 类型：现有前端会传非法值（例如清空排序号输入框），
 * controller 里手动 parse、失败就 fallback 成 0，而不是让 Jackson 在反序列化阶段直接因类型
 * 不匹配而抛异常——那样客户端会从"静默用默认值成功"变成收到 400，是一次没必要的行为收紧。
 */
public record PriceRangeUpsertRequest(String name, String sortOrder, String active) {}
