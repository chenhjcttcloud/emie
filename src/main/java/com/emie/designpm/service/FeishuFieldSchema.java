package com.emie.designpm.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 飞书同步字段的集中契约。业务写入、表结构预检和兼容列降级必须共用本类，
 * 禁止再根据零散中文字段名分别猜测类型。
 */
final class FeishuFieldSchema {

    static final int TEXT = 1;
    static final int NUMBER = 2;
    static final int SELECT = 3;
    static final int DATE = 5;

    private static final Set<String> DATE_FIELDS = Set.of(
            "截止日期", "计划日期", "实际完成日期", "实际完成", "实际完成时间",
            "创建时间", "源数据删除时间", "时间");
    private static final Set<String> NUMBER_FIELDS = Set.of(
            "子任务数", "完成进度", "审核进度", "自评分", "一审得分", "二审得分",
            "审核得分", "评分", "权重");
    private static final Set<String> REFERENCE_FIELDS = Set.of("所属项目", "所属子任务");
    private static final Set<String> CRITICAL_FIELDS = Set.of(
            "项目ID", "子任务ID", "评分ID", "日志ID", "所属项目", "所属子任务",
            "同步来源", "备份状态", "源数据删除时间");

    private FeishuFieldSchema() { }

    static int expectedType(String fieldName) {
        if (DATE_FIELDS.contains(fieldName)) return DATE;
        if (NUMBER_FIELDS.contains(fieldName)) return NUMBER;
        return TEXT;
    }

    static boolean compatible(String source, int expected, int actual) {
        if (actual >= 1000) return expected == DATE && "创建时间".equals(source);
        if (REFERENCE_FIELDS.contains(source)) {
            return actual == TEXT || actual == SELECT || FeishuBaseService.isLinkField(actual);
        }
        if (expected == TEXT) return actual == TEXT || actual == SELECT;
        return expected == actual;
    }

    static boolean critical(String source) {
        return CRITICAL_FIELDS.contains(source);
    }

    static String fallbackName(String target, int expected) {
        String suffix = switch (expected) {
            case NUMBER -> "（系统数字）";
            case DATE -> "（系统日期）";
            default -> "（系统文本）";
        };
        return target.endsWith(suffix) ? target : target + suffix;
    }

    /** 管理端字段目录与后端契约同源，供结构预检使用。 */
    static Map<String, Map<String, Integer>> catalog() {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        result.put("project", fields(
                "项目ID", "项目编号", "项目名称", "产品名称", "类型", "状态", "销售", "产品企划",
                "截止日期", "产品类目", "参考价格", "子任务数", "完成进度", "项目流程", "子任务流程",
                "当前审核阶段", "审核进度", "创建时间", "项目描述", "同步来源", "目标市场", "合规项",
                "IP名称", "创意作者", "来源", "产品类目备注", "需求说明"));
        result.put("task", fields(
                "子任务ID", "任务名称", "状态", "负责人", "计划日期", "实际完成日期", "自评分", "所属项目",
                "一审角色", "一审状态", "一审得分", "一审审核人", "二审角色", "二审状态", "二审得分",
                "二审审核人", "审核得分", "创建时间", "所属阶段", "负责人类型", "发布人", "细节要求说明",
                "交付成果", "审核意见", "同步来源"));
        result.put("scoring", fields(
                "评分ID", "评分角色", "评分", "权重", "项目ID", "项目类型", "审核阶段", "审核状态",
                "审核人", "审核意见", "同步来源", "所属子任务"));
        result.put("log", fields("日志ID", "操作内容", "操作人", "角色", "所属项目", "时间", "同步来源"));
        return result;
    }

    private static Map<String, Integer> fields(String... names) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, expectedType(name));
        return result;
    }
}
