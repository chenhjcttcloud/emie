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
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class DesignerMonthlyReportWorkbook {
    private static final List<String> SUMMARY =
            List.of("月份", "设计师", "用户账号", "当月归属积分", "明细合计", "核对结果", "完成事项数", "关联事项数", "导出范围");
    private static final List<String> ENTRIES = List.of(
            "月份", "设计师", "用户账号", "流水编号", "归属月份", "入账时间", "来源", "项目", "事项类型", "事项编号", "事项名称", "规则编号", "规则说明", "原因", "积分",
            "完成时间");
    private static final List<String> RELATED = List.of(
            "月份",
            "设计师",
            "项目",
            "事项类型",
            "事项编号",
            "事项名称",
            "事项状态",
            "完成时间",
            "分类",
            "规则版本",
            "原规则编号",
            "事项累计积分（含其他月份）",
            "其他月份积分",
            "当月归属积分（核对用）");

    private DesignerMonthlyReportWorkbook() {}

    @SuppressWarnings("unchecked")
    static byte[] write(Map<String, Object> report, String completedListFilter) throws IOException {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var summary = sheet(workbook, "月度积分汇总", SUMMARY);
            var entries = sheet(workbook, "当月积分明细", ENTRIES);
            var related = sheet(workbook, "当月关联事项", RELATED);
            var dates = workbook.createCellStyle();
            dates.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            var numbers = workbook.createCellStyle();
            numbers.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            var monthlyFont = workbook.createFont();
            monthlyFont.setBold(true);
            monthlyFont.setColor(IndexedColors.INDIGO.getIndex());
            var monthlyNumbers = workbook.createCellStyle();
            monthlyNumbers.cloneStyleFrom(numbers);
            monthlyNumbers.setFont(monthlyFont);
            monthlyNumbers.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
            monthlyNumbers.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (var designer : (List<Map<String, Object>>) report.get("designers")) {
                var items = (List<Map<String, Object>>) designer.get("pointItems");
                if (items == null) throw new IllegalStateException("缺少月度积分明细，无法核对导出");
                var associated = (List<Map<String, Object>>) designer.get("relatedItems");
                if (associated == null) throw new IllegalStateException("缺少关联事项，无法核对导出");
                BigDecimal total = MonthlyPointReportService.total(items);
                BigDecimal score = decimal(designer.get("score"));
                if (score.compareTo(total.setScale(2, RoundingMode.HALF_UP)) != 0)
                    throw new IllegalStateException("月度积分与明细不一致，无法导出");
                BigDecimal relatedTotal = associated.stream()
                        .map(item -> new BigDecimal(Objects.toString(item.get("periodPoints"), "0")))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (score.compareTo(relatedTotal.setScale(2, RoundingMode.HALF_UP)) != 0)
                    throw new IllegalStateException("关联事项积分与月度积分不一致，无法导出");
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
                        associated.size(),
                        "全部关联事项（不受页面筛选影响）");
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
                // Every accounting item must remain visible, including older clients sending a rule filter.
                for (var task : associated) {
                    String ruleVersion = text(task.get("ruleVersion"));
                    write(
                            related,
                            dates,
                            numbers,
                            report.get("month"),
                            designer.get("designer"),
                            task.get("project"),
                            "requirement".equals(task.get("kind"))
                                    ? "设计/送审需求"
                                    : "task".equals(task.get("kind")) ? "子任务" : "独立积分事项",
                            text(task.get("id")),
                            task.get("name"),
                            periodStatus(task.get("periodStatus")),
                            task.get("completedAt"),
                            task.get("category"),
                            version(ruleVersion),
                            task.get("ruleCode"),
                            task.get("score"),
                            task.get("otherPeriodPoints"),
                            task.get("periodPoints"));
                    related.getRow(related.getLastRowNum()).getCell(13).setCellStyle(monthlyNumbers);
                }
            }
            for (var sheet : List.of(summary, entries, related)) {
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
                    : headers.get(i).contains("核对用") || headers.get(i).contains("含其他月份")
                            ? 32
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

    private static String periodStatus(Object status) {
        return switch (text(status)) {
            case "completed_in_period" -> "当月完成";
            case "completed_other_period" -> "其他月份完成";
            case "pending" -> "尚未完成";
            default -> "独立积分事项";
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
