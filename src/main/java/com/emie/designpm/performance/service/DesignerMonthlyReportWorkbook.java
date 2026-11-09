package com.emie.designpm.performance.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class DesignerMonthlyReportWorkbook {
    private static final List<String> HEADERS =
            List.of("月份", "设计师", "项目", "子任务编号", "子任务名称", "完成时间", "分类", "规则版本", "原规则编号", "任务累计积分");
    private static final List<Integer> COLUMN_WIDTHS = List.of(12, 18, 30, 14, 36, 22, 18, 18, 20, 16);

    private DesignerMonthlyReportWorkbook() {}

    @SuppressWarnings("unchecked")
    static byte[] write(Map<String, Object> report, String filter) throws IOException {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("设计师子任务");
            var header = sheet.createRow(0);
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            for (int column = 0; column < HEADERS.size(); column++) {
                var cell = header.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, COLUMN_WIDTHS.get(column) * 256);
            }
            sheet.createFreezePane(0, 1);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            int nextRow = 1;
            for (var designer : (List<Map<String, Object>>) report.get("designers")) {
                for (var task : (List<Map<String, Object>>) designer.get("tasks")) {
                    if (!"task".equals(task.get("kind"))) continue;
                    String version = Objects.toString(task.get("ruleVersion"), "");
                    if (!filter.isEmpty() && !filter.equals(version)) continue;
                    var row = sheet.createRow(nextRow++);
                    row.createCell(0).setCellValue(Objects.toString(report.get("month"), ""));
                    row.createCell(1).setCellValue(Objects.toString(designer.get("designer"), ""));
                    row.createCell(2).setCellValue(Objects.toString(task.get("project"), ""));
                    row.createCell(3).setCellValue(Objects.toString(task.get("id"), ""));
                    row.createCell(4).setCellValue(Objects.toString(task.get("name"), ""));
                    var completion = row.createCell(5);
                    completion.setCellValue((LocalDateTime) task.get("completedAt"));
                    completion.setCellStyle(dateStyle);
                    row.createCell(6).setCellValue(Objects.toString(task.get("category"), ""));
                    row.createCell(7)
                            .setCellValue(
                                    switch (version) {
                                        case "legacy" -> "其他历史规则";
                                        case "unbound" -> "未绑定";
                                        default -> version;
                                    });
                    row.createCell(8).setCellValue(Objects.toString(task.get("ruleCode"), ""));
                    row.createCell(9).setCellValue(((Number) task.get("score")).doubleValue());
                }
            }
            sheet.setAutoFilter(new CellRangeAddress(0, nextRow - 1, 0, HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
