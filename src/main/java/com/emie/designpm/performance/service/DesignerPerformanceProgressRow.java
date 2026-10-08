package com.emie.designpm.performance.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

final class DesignerPerformanceProgressRow {
    private DesignerPerformanceProgressRow() {}

    static void write(
            XSSFSheet sheet,
            int rowIndex,
            int lastColumn,
            long completed,
            long total,
            CellStyle header,
            CellStyle plain) {
        Row row = sheet.createRow(rowIndex);
        Cell label = row.createCell(0);
        label.setCellValue("本月完成进度");
        label.setCellStyle(header);
        Cell value = row.createCell(1);
        value.setCellValue(total == 0 ? 0d : (double) completed / total);
        CellStyle progress = sheet.getWorkbook().createCellStyle();
        progress.cloneStyleFrom(plain);
        progress.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("0%"));
        progress.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        progress.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        value.setCellStyle(progress);
        Cell detail = row.createCell(2);
        detail.setCellValue("已完成 " + completed + " / " + total + " 项");
        detail.setCellStyle(plain);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 2, lastColumn));
        var rule = sheet.getSheetConditionalFormatting()
                .createConditionalFormattingRule(
                        new XSSFColor(new java.awt.Color(46, 125, 50), new DefaultIndexedColorMap()));
        rule.getDataBarFormatting().getMinThreshold().setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
        rule.getDataBarFormatting().getMaxThreshold().setRangeType(ConditionalFormattingThreshold.RangeType.MAX);
        sheet.getSheetConditionalFormatting()
                .addConditionalFormatting(
                        new CellRangeAddress[] {new CellRangeAddress(rowIndex, rowIndex, 1, 1)}, rule);
    }
}
