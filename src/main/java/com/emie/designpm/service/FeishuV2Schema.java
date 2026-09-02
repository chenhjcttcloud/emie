package com.emie.designpm.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全新飞书 V2 Base 的固定八表契约。飞书只读，系统是唯一数据源。 */
final class FeishuV2Schema {
    static final int TEXT = 1;
    static final int NUMBER = 2;
    static final int DATE = 5;
    static final int ATTACHMENT = 17;
    static final int LINK = 18;

    record Field(String name, int type, String linkedTable, boolean percentage) {
        static Field text(String name) { return new Field(name, TEXT, null, false); }
        static Field number(String name) { return new Field(name, NUMBER, null, false); }
        static Field percentage(String name) { return new Field(name, NUMBER, null, true); }
        static Field date(String name) { return new Field(name, DATE, null, false); }
        static Field attachment(String name) { return new Field(name, ATTACHMENT, null, false); }
        static Field link(String name, String linkedTable) { return new Field(name, LINK, linkedTable, false); }
    }

    record Table(String key, String name, String identity, List<Field> fields) { }

    private FeishuV2Schema() { }

    static Map<String, Table> primaryTables() {
        Map<String, Table> tables = new LinkedHashMap<>();
        tables.put("project", new Table("project", "项目总表_V2", "系统项目ID", List.of(
                Field.text("项目编号"), Field.text("项目名称"), Field.text("状态"), Field.text("销售"),
                Field.text("产品企划"), Field.text("产品类目"), Field.text("参考零售价"),
                Field.attachment("参考图片"), Field.attachment("附件"), Field.number("子任务数"),
                Field.date("创建时间"), Field.date("计划完成时间"), Field.date("最近更新时间"),
                Field.link("关联子任务", "task"), Field.text("子任务阶段"), Field.percentage("子任务进度"),
                Field.text("项目阶段"), Field.percentage("项目总进度"), Field.text("备注"),
                Field.text("同步来源"), Field.date("系统最近同步时间"))));
        tables.put("task", new Table("task", "子任务表_V2", "系统子任务ID", List.of(
                Field.link("关联项目", "project"), Field.text("子任务编号"), Field.text("子任务名称"),
                Field.text("状态"), Field.attachment("参考图片"), Field.attachment("参考附件"),
                Field.text("子任务负责人"), Field.text("细节要求说明"), Field.date("创建子任务时间"),
                Field.date("计划完成时间"), Field.date("最后更新时间"), Field.attachment("交付图片"),
                Field.attachment("交付附件"), Field.number("设计师自评分"), Field.number("销售评分"),
                Field.number("产品企划评分"), Field.number("管理员评分"), Field.number("加权综合"),
                Field.link("关联评分", "scoring"), Field.text("子任务阶段"), Field.percentage("子任务进度"),
                Field.text("备注"), Field.text("同步来源"), Field.date("系统最近同步时间"))));
        tables.put("scoring", new Table("scoring", "评分记录表_V2", "系统评分ID", List.of(
                Field.text("评分编号"), Field.text("评分角色"), Field.text("评分人"),
                Field.link("所属子任务", "task"), Field.date("评分时间"), Field.percentage("权重"),
                Field.number("加权得分"), Field.text("备注"), Field.text("同步来源"),
                Field.date("系统最近同步时间"))));
        tables.put("log", new Table("log", "操作日志表_V2", "系统日志ID", List.of(
                Field.text("日志编号"), Field.date("时间"), Field.text("角色"), Field.text("操作人"),
                Field.text("行为记录"), Field.text("备注"), Field.text("同步来源"),
                Field.date("系统最近同步时间"))));
        return tables;
    }

    static Map<String, Table> backupTables() {
        Map<String, Table> result = new LinkedHashMap<>();
        primaryTables().forEach((key, table) -> {
            List<Field> fields = new java.util.ArrayList<>(table.fields());
            fields.add(Field.text("备份状态"));
            fields.add(Field.date("源数据删除时间"));
            result.put(key + "Backup", new Table(key + "Backup", table.name() + "_backup",
                    table.identity(), List.copyOf(fields)));
        });
        return result;
    }

    static Map<String, Table> allTables() {
        Map<String, Table> result = new LinkedHashMap<>(primaryTables());
        result.putAll(backupTables());
        return result;
    }
}
