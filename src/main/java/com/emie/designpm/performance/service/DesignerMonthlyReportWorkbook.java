package com.emie.designpm.performance.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class DesignerMonthlyReportWorkbook {
    private static final List<String> SUMMARY =
            List.of("月份", "设计师", "用户账号", "当月归属积分", "明细合计", "核对结果", "完成事项数", "完成事项筛选");
    private static final List<String> ENTRIES = List.of(
            "月份", "设计师", "用户账号", "流水编号", "归属月份", "入账时间", "来源", "项目", "事项类型", "事项编号", "事项名称", "规则编号", "规则说明", "原因", "积分",
            "完成时间");
    private static final List<String> COMPLETED = List.of(
            "月份", "设计师", "项目", "事项类型", "事项编号", "事项名称", "完成时间", "分类", "规则版本", "原规则编号", "当月归属积分", "其他月份积分", "事项累计积分");

    private DesignerMonthlyReportWorkbook() {}

    @SuppressWarnings("unchecked")
    static byte[] write(Map<String, Object> report, String filter) throws IOException {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var summary = sheet(workbook, "月度积分汇总", SUMMARY);
            var entries = sheet(workbook, "当月积分明细", ENTRIES);
            var completed = sheet(workbook, "当月完成事项", COMPLETED);
            var dates = workbook.createCellStyle();
            dates.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            var numbers = workbook.createCellStyle();
            numbers.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            for (var designer : (List<Map<String, Object>>) report.get("designers")) {
                var items = (List<Map<String, Object>>) designer.get("pointItems");
                if (items == null) throw new IllegalStateException("缺少月度积分明细，无法核对导出");
                BigDecimal total = MonthlyPointReportService.total(items);
                BigDecimal score = decimal(designer.get("score"));
                if (score.compareTo(total.setScale(2, RoundingMode.HALF_UP)) != 0)
                    throw new IllegalStateException("月度积分与明细不一致，无法导出");
                write(
                        summary,
                        dates,
                        numbers,
                        report.get("month"),
                        designer.get("designer"),
                        designer.get("userId"),
                        score,
                        total,
                        "一致",
                        designer.get("completedCount"),
                        filter.isEmpty() ? "全部" : version(filter) + "（仅完成事项）");
                for (var item : items) {
                    boolean requirement = item.get("requirementId") != null;
                    write(
                            entries,
                            dates,
                            numbers,
                            report.get("month"),
                            designer.get("designer"),
                            designer.get("userId"),
                            item.get("id"),
                            item.get("accountingMonth"),
                            item.get("createdAt"),
                            source(item.get("source")),
                            item.get("project"),
                            requirement ? "设计/送审需求" : item.get("taskId") != null ? "子任务" : "其他积分",
                            text(item.get(requirement ? "requirementId" : "taskId")),
                            item.get(requirement ? "requirementName" : "taskName"),
                            item.get("ruleCode"),
                            item.get("ruleDescription"),
                            item.get("reason"),
                            item.get("points"),
                            item.get("completedAt"));
                }
                for (var task : (List<Map<String, Object>>) designer.get("tasks")) {
                    String ruleVersion = text(task.get("ruleVersion"));
                    if (!filter.isEmpty() && !filter.equals(ruleVersion)) continue;
                    write(
                            completed,
                            dates,
                            numbers,
                            report.get("month"),
                            designer.get("designer"),
                            task.get("project"),
                            "requirement".equals(task.get("kind")) ? "设计/送审需求" : "子任务",
                            text(task.get("id")),
                            task.get("name"),
                            task.get("completedAt"),
                            task.get("category"),
                            version(ruleVersion),
                            task.get("ruleCode"),
                            task.get("periodPoints"),
                            task.get("otherPeriodPoints"),
                            task.get("score"));
                }
            }
            for (var sheet : List.of(summary, entries, completed)) {
                sheet.setAutoFilter(new CellRangeAddress(
                        0, sheet.getLastRowNum(), 0, sheet.getRow(0).getLastCellNum() - 1));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static Sheet sheet(XSSFWorkbook workbook, String name, List<String> headers) {
        var sheet = workbook.createSheet(name);
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        var row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            row.createCell(i).setCellValue(headers.get(i));
            row.getCell(i).setCellStyle(style);
            int width = headers.get(i).contains("时间")
                    ? 22
                    : headers.get(i).contains("名称")
                                    || headers.get(i).equals("项目")
                                    || headers.get(i).equals("原因")
                            ? 36
                            : 20;
            sheet.setColumnWidth(i, width * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void write(Sheet sheet, CellStyle dates, CellStyle numbers, Object... values) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            if (values[i] instanceof Number number) {
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(numbers);
            } else if (values[i] instanceof LocalDateTime date) {
                cell.setCellValue(date);
                cell.setCellStyle(dates);
            } else {
                cell.setCellValue(text(values[i]));
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(Objects.toString(value, "0")).setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }

    private static String version(String version) {
        return switch (version) {
            case "legacy" -> "其他历史规则";
            case "unbound" -> "未绑定";
            default -> version;
        };
    }

    private static String source(Object source) {
        return switch (text(source)) {
            case "TASK_BASE" -> "子任务基础分";
            case "TASK_REVISION" -> "子任务修改";
            case "TASK_MANUAL" -> "旧任务手动验收";
            case "TASK_QUALITY" -> "历史质量分";
            case "DESIGN_REQUIREMENT" -> "设计/送审需求";
            case "MANUAL" -> "手动调整";
            case "APPEAL" -> "积分异议";
            case "TASK_WITHDRAWAL" -> "退单扣分";
            case "MATERIAL_MARKET" -> "素材采纳";
            case "MATERIAL_IDEA_PUBLISH" -> "创意单发布奖励";
            case "MATERIAL_FREE_RATING" -> "自由单评星奖励";
            case "PO_PROGRESS" -> "PO进度";
            default -> text(source);
        };
    }
}
