package com.emie.designpm.performance.service;

import com.emie.designpm.performance.service.DesignerMonthlyReport.Attachment;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Category;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Delivery;
import com.emie.designpm.performance.service.DesignerMonthlyReport.Item;
import com.emie.designpm.performance.service.PerformanceImageLoader.LoadedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFColor;

/**
 * 生成单个设计师嘅月度绩效 Excel：
 *
 * <ol>
 *   <li>「绩效考评表」：沿用设计部考评表格式，完成率由系统计分，
 *       协作、考勤、周报（黄色格）由人工填写，总分用公式自动汇总。
 *   <li>「任务详情」：按三类任务分组列出名称、日期、状态同交付图片。
 * </ol>
 */
final class DesignerPerformanceWorkbook {

    /** 考评表权重：完成率 70%，协作、考勤和周报合计 30%。 */
    static final double WEIGHT_COMPLETION = 0.70;

    static final double WEIGHT_COLLABORATION = 0.10;
    static final double WEIGHT_ATTENDANCE = 0.10;
    static final double WEIGHT_WEEKLY_REPORT = 0.10;

    private static final int IMAGE_COLUMN = 10;
    private static final int IMAGE_COLUMN_WIDTH_CHARS = 28;
    private static final float IMAGE_ROW_HEIGHT_PT = 150f;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M.d");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DesignerMonthlyReport report;
    private final Function<String, Optional<LoadedImage>> images;
    private final XSSFWorkbook workbook = new XSSFWorkbook();
    private final Styles styles;

    private DesignerPerformanceWorkbook(DesignerMonthlyReport report, Function<String, Optional<LoadedImage>> images) {
        this.report = report;
        this.images = images;
        this.styles = new Styles(workbook);
    }

    static void write(DesignerMonthlyReport report, Function<String, Optional<LoadedImage>> images, OutputStream out)
            throws IOException {
        DesignerPerformanceWorkbook book = new DesignerPerformanceWorkbook(report, images);
        try (XSSFWorkbook workbook = book.workbook) {
            book.appraisalSheet();
            book.detailSheet();
            book.deliverySheet();
            workbook.write(out);
        }
    }

    // ==================== 绩效考评表 ====================

    private void appraisalSheet() {
        XSSFSheet sheet = workbook.createSheet("绩效考评表");
        int[] widths = {6, 10, 14, 34, 40, 14, 52, 8, 10, 10};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        text(sheet, 0, 0, "设计部绩效考评表", styles.title);
        merge(sheet, 0, 0, 0, 9);
        text(
                sheet,
                1,
                0,
                "姓名：" + report.name() + "    所属部门：" + blankAs(report.department(), "—") + "    职位："
                        + blankAs(report.title(), "—"),
                styles.plain);
        merge(sheet, 1, 1, 0, 9);
        text(
                sheet,
                2,
                0,
                "考核周期（月度）：" + report.month().getYear() + "年" + report.month().getMonthValue() + "月" + "    生成时间："
                        + report.generatedAt().format(STAMP),
                styles.plain);
        merge(sheet, 2, 2, 0, 9);
        text(sheet, 3, 0, "说明：黄色单元格由人工填写；其余数据由产品管理系统自动导出，请勿手工改动。", styles.note);
        merge(sheet, 3, 3, 0, 9);

        String[] headers = {"序号", "类别", "考核指标", "目标", "评估标准", "考核依据", "实际结果", "权重（%）", "考核评分", "加权得分"};
        Row head = sheet.createRow(4);
        for (int i = 0; i < headers.length; i++) cell(head, i, headers[i], styles.header);

        int first = 5;
        indicatorRow(
                sheet,
                first,
                "1",
                "个人目标\n70%",
                "设计任务完成率",
                "目标：\n原创*1+外采*8，或者原创0+外采10，只核算单月接单/指派的任务；直接指派以创建时间估算",
                "完成率=每月按时完成任务数/当月设计任务总数，150%封顶",
                "产品管理系统",
                completionResult(),
                WEIGHT_COMPLETION,
                report.completionScore());
        indicatorRow(
                sheet,
                first + 1,
                "3",
                "沟通与协调\n10%",
                "协作能力",
                "积极配合公司各部门的工作，且有正确的价值观；\n思维灵活、主观能动性、全局思维、沟通能力、创新能力、团队协作能力；\n"
                        + "具备一定的工作敏锐度，善于通过趋势变化，数据解读部门工作进行优化，并能积极主动配合本部门和跨部门同事完成工作。",
                "1、遇到问题推卸自己部门责任，不愿意配合其他部门解决问题，0-50分；\n2、沟通基本顺畅，但不主动寻求解决问题的能力，得分51-70分；\n"
                        + "3、能做好本部门和跨部门沟通，遇到问题能即时沟通并解决问题，得分71-89分；\n"
                        + "4、协作能力顺畅，能积极主动解决部门内和跨部门工作障碍，主动提出解决方法，得分81-100分。",
                "周报OS",
                "",
                WEIGHT_COLLABORATION,
                null);
        indicatorRow(
                sheet,
                first + 2,
                "4",
                "价值观\n20%",
                "工作态度和考勤",
                "通过大管加正常打卡，外出和请假需向上级主管及人事部报批；\n不弄虚作假（包括但不限于：工作时间外出处理私事、未按打卡时间出现在办公室等）；\n" + "自己和团队都有积极的工作态度",
                "1、当月（包含迟到、早退、缺席、缺卡、外出不报批）未发生，得100分；\n2、发生3次以内，得分70-90分；\n3、发生4次的，得分60分；\n"
                        + "4、发生5次及以上，0-50分。\n凡发现考勤弄虚作假（第一次扣50分，第二次及以上该项不得分。",
                "考勤表+实际在岗情况（监控）",
                "",
                WEIGHT_ATTENDANCE,
                null);
        indicatorRow(
                sheet,
                first + 3,
                "",
                "",
                "周报考核",
                "标准：无正当理由未按节点提交周报，将扣除绩效5-10分。若导致总绩效分数低于60分，当期绩效等级视为不合格",
                "",
                "周报OS",
                "",
                WEIGHT_WEEKLY_REPORT,
                null);
        merge(sheet, first + 2, first + 3, 0, 0);
        merge(sheet, first + 2, first + 3, 1, 1);
        merge(sheet, first + 3, first + 3, 3, 4);

        int total = first + 4;
        Row totalRow = sheet.createRow(total);
        totalRow.setHeightInPoints(30);
        for (int i = 0; i <= 9; i++) cell(totalRow, i, "", styles.body);
        cell(totalRow, 0, "评估分数总计", styles.header);
        merge(sheet, total, total, 0, 6);
        formula(totalRow, 7, "SUM(H" + (first + 1) + ":H" + (first + 4) + ")", styles.percent);
        formula(totalRow, 9, "SUM(J" + (first + 1) + ":J" + (first + 4) + ")", styles.scoreBold);
        cell(totalRow, 8, "", styles.body);
        sheet.createFreezePane(0, 5);
    }

    private void indicatorRow(
            XSSFSheet sheet,
            int rowIndex,
            String seq,
            String category,
            String indicator,
            String goal,
            String criteria,
            String basis,
            String result,
            double weight,
            Double score) {
        Row row = sheet.createRow(rowIndex);
        cell(row, 0, seq, styles.center);
        cell(row, 1, category, styles.center);
        cell(row, 2, indicator, styles.center);
        cell(row, 3, goal, styles.center);
        cell(row, 4, criteria, styles.center);
        cell(row, 5, basis, styles.center);
        cell(row, 6, result, styles.left);
        Cell weightCell = row.createCell(7);
        weightCell.setCellValue(weight);
        weightCell.setCellStyle(styles.percent);
        Cell scoreCell = row.createCell(8);
        if (score != null) {
            scoreCell.setCellValue(score);
            scoreCell.setCellStyle(styles.score);
        } else {
            scoreCell.setCellStyle(styles.manual);
        }
        int excelRow = rowIndex + 1;
        formula(row, 9, "IF(I" + excelRow + "=\"\",\"\",I" + excelRow + "*H" + excelRow + ")", styles.score);
        int lines = Math.max(lineCount(result), Math.max(lineCount(goal) + 1, Math.max(lineCount(criteria) + 1, 3)));
        row.setHeightInPoints(Math.min(409, lines * 15f + 8));
    }

    private String completionResult() {
        StringBuilder text = new StringBuilder();
        text.append(report.month().getMonthValue()).append("月份绩效\n");
        text.append("本月接单/指派 ")
                .append(report.createdCount())
                .append(" 项，已完成 ")
                .append(report.completedCount())
                .append(" 项，其中按时完成 ")
                .append(report.onTimeCount())
                .append(" 项\n");
        Double rate = report.completionRate();
        text.append("按时完成率：")
                .append(rate == null ? "本月无任务，请人工评定" : String.format("%.1f%%", rate * 100))
                .append('\n');
        for (Category category : Category.values()) {
            List<Item> items = report.createdItems().stream()
                    .filter(item -> item.category() == category)
                    .toList();
            if (items.isEmpty()) continue;
            text.append('\n').append(category.label()).append("：\n");
            for (int i = 0; i < items.size(); i++) {
                text.append(i + 1).append('.').append(items.get(i).name()).append(resultSuffix(items.get(i)));
                text.append('\n');
            }
        }
        return text.toString().stripTrailing();
    }

    private String resultSuffix(Item item) {
        if (!item.completed()) {
            return item.plannedDate() == null
                    ? "（未完成）"
                    : "（未完成，计划" + item.plannedDate().format(SHORT_DATE) + "）";
        }
        String date = item.completedAt().format(SHORT_DATE);
        if (item.plannedDate() == null) return "（" + date + "，无计划日期）";
        return item.onTime()
                ? "（" + date + "）"
                : "（" + date + " 逾期，计划" + item.plannedDate().format(SHORT_DATE) + "）";
    }

    // ==================== 任务详情 ====================

    private void detailSheet() {
        XSSFSheet sheet = workbook.createSheet("任务详情");
        int[] widths = {6, 40, 16, 16, 12, 12, 18, 24, 48, 24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        int maxImages = report.items().stream()
                .mapToInt(item -> (int) item.attachments().stream()
                        .filter(Attachment::embeddableImage)
                        .count())
                .max()
                .orElse(0);
        for (int i = 0; i < Math.max(maxImages, 1); i++)
            sheet.setColumnWidth(IMAGE_COLUMN + i, IMAGE_COLUMN_WIDTH_CHARS * 256);
        int lastColumn = IMAGE_COLUMN + Math.max(maxImages, 1) - 1;

        text(
                sheet,
                0,
                0,
                report.month().getYear() + "年" + report.month().getMonthValue() + "月任务详情 — " + report.name(),
                styles.title);
        merge(sheet, 0, 0, 0, lastColumn);
        int r = 1;
        r = summaryLine(
                sheet,
                r,
                lastColumn,
                "本月接单/指派任务：" + report.createdCount() + " 项；已完成 " + report.completedCount() + " 项；未完成 "
                        + (report.createdCount() - report.completedCount()) + " 项；按时完成 "
                        + report.onTimeCount() + " 项；往月接单/指派、本月完成 "
                        + report.carriedOverCompleted().size() + " 项");
        Row progress = sheet.createRow(r++);
        cell(progress, 0, "本月完成进度", styles.header);
        Cell progressValue = progress.createCell(1);
        progressValue.setCellValue(
                report.createdCount() == 0 ? 0d : (double) report.completedCount() / report.createdCount());
        progressValue.setCellStyle(styles.progress);
        text(
                sheet,
                progress.getRowNum(),
                2,
                report.createdCount() == 0
                        ? "本月无接单/指派任务"
                        : "已完成 " + report.completedCount() + " / " + report.createdCount() + " 项，未完成 "
                                + (report.createdCount() - report.completedCount()) + " 项",
                styles.plain);
        merge(sheet, progress.getRowNum(), progress.getRowNum(), 2, lastColumn);
        var progressRule = sheet.getSheetConditionalFormatting()
                .createConditionalFormattingRule(
                        new XSSFColor(new java.awt.Color(46, 125, 50), new DefaultIndexedColorMap()));
        progressRule
                .getDataBarFormatting()
                .getMinThreshold()
                .setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
        progressRule
                .getDataBarFormatting()
                .getMaxThreshold()
                .setRangeType(ConditionalFormattingThreshold.RangeType.MAX);
        sheet.getSheetConditionalFormatting()
                .addConditionalFormatting(
                        new CellRangeAddress[] {new CellRangeAddress(progress.getRowNum(), progress.getRowNum(), 1, 1)},
                        progressRule);
        progress.setHeightInPoints(24f);

        String[] headers = {"序号", "任务名称", "类别", "接单/指派日期", "计划完成", "实际完成", "当前状态", "统计归属", "交付成果描述", "附件"};
        Row head = sheet.createRow(r++);
        for (int i = 0; i < headers.length; i++) cell(head, i, headers[i], styles.header);
        for (int i = 0; i < Math.max(maxImages, 1); i++) cell(head, IMAGE_COLUMN + i, "交付图片 " + (i + 1), styles.header);

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        for (Category category : Category.values()) {
            List<Item> items = report.items().stream()
                    .filter(item -> item.category() == category)
                    .toList();
            Row section = sheet.createRow(r);
            cell(section, 0, category.label() + "（" + items.size() + " 项）", styles.section);
            for (int i = 1; i <= lastColumn; i++) cell(section, i, "", styles.section);
            merge(sheet, r, r, 0, lastColumn);
            r++;
            if (items.isEmpty()) {
                Row empty = sheet.createRow(r);
                cell(empty, 0, "本月无此类任务", styles.left);
                merge(sheet, r, r, 0, lastColumn);
                r++;
                continue;
            }
            for (int i = 0; i < items.size(); i++) r = taskRow(sheet, drawing, r, i + 1, items.get(i), maxImages);
        }
        sheet.createFreezePane(2, 5);
    }

    private int summaryLine(XSSFSheet sheet, int r, int lastColumn, String value) {
        text(sheet, r, 0, value, styles.plain);
        merge(sheet, r, r, 0, lastColumn);
        return r + 1;
    }

    private void deliverySheet() {
        XSSFSheet sheet = workbook.createSheet("交付成果明细");
        List<DeliveryRow> deliveries = report.items().stream()
                .flatMap(item -> item.deliveryVersions().stream().map(delivery -> new DeliveryRow(item, delivery)))
                .toList();
        int maxImages = deliveries.stream()
                .mapToInt(row -> (int) row.delivery().attachments().stream()
                        .filter(Attachment::embeddableImage)
                        .count())
                .max()
                .orElse(0);
        int imageColumn = 10;
        int lastColumn = imageColumn + Math.max(maxImages, 1) - 1;
        int[] widths = {6, 18, 38, 10, 22, 20, 18, 32, 52, 28};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        for (int i = 0; i < Math.max(maxImages, 1); i++)
            sheet.setColumnWidth(imageColumn + i, IMAGE_COLUMN_WIDTH_CHARS * 256);

        text(sheet, 0, 0, report.month() + "交付成果明细 — " + report.name(), styles.title);
        merge(sheet, 0, 0, 0, lastColumn);
        text(sheet, 1, 0, "包含本月接单/指派任务及往月接单、本月完成任务的全部已保存交付版本。", styles.note);
        merge(sheet, 1, 1, 0, lastColumn);
        String[] headers = {"序号", "类别", "子任务", "版本", "提交类型", "提交时间", "提交人", "修改说明", "交付成果描述", "附件下载"};
        Row head = sheet.createRow(2);
        for (int i = 0; i < headers.length; i++) cell(head, i, headers[i], styles.header);
        for (int i = 0; i < Math.max(maxImages, 1); i++) cell(head, imageColumn + i, "交付图片 " + (i + 1), styles.header);

        if (deliveries.isEmpty()) {
            text(sheet, 3, 0, "本月任务暂无已保存的交付成果", styles.left);
            merge(sheet, 3, 3, 0, lastColumn);
            return;
        }
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        int rowIndex = 3;
        int sequence = 1;
        for (DeliveryRow deliveryRow : deliveries) {
            Item item = deliveryRow.item();
            Delivery delivery = deliveryRow.delivery();
            Row row = sheet.createRow(rowIndex);
            cell(row, 0, String.valueOf(sequence++), styles.center);
            cell(row, 1, item.category().label(), styles.center);
            cell(row, 2, item.name(), styles.leftWrap);
            cell(row, 3, delivery.versionNo() == null ? "—" : String.valueOf(delivery.versionNo()), styles.center);
            cell(row, 4, delivery.submissionType(), styles.center);
            cell(row, 5, delivery.submittedAt() == null ? "—" : STAMP.format(delivery.submittedAt()), styles.center);
            cell(row, 6, delivery.submittedBy(), styles.center);
            cell(row, 7, delivery.changeSummary(), styles.leftWrap);
            cell(row, 8, delivery.deliverables(), styles.leftWrap);
            List<Attachment> links = delivery.attachments().stream()
                    .filter(a -> !a.embeddableImage())
                    .toList();
            cell(
                    row,
                    9,
                    links.stream().map(Attachment::name).collect(java.util.stream.Collectors.joining("\n")),
                    styles.leftWrap);
            for (int i = 0; i < Math.max(maxImages, 1); i++) cell(row, imageColumn + i, "", styles.body);

            int placed = 0;
            List<String> unreadable = new ArrayList<>();
            for (Attachment attachment : delivery.attachments()) {
                if (!attachment.embeddableImage()) continue;
                Optional<LoadedImage> image = images.apply(attachment.url());
                if (image.isEmpty()) {
                    unreadable.add(attachment.name());
                    continue;
                }
                placePicture(drawing, image.get(), imageColumn + placed++, rowIndex);
            }
            if (links.size() == 1) {
                Hyperlink link = workbook.getCreationHelper().createHyperlink(HyperlinkType.URL);
                link.setAddress(links.getFirst().url());
                row.getCell(9).setHyperlink(link);
            }
            if (!unreadable.isEmpty()) {
                String existing = row.getCell(9).getStringCellValue();
                String note = "图片无法读取：" + String.join("、", unreadable);
                row.getCell(9).setCellValue(existing.isBlank() ? note : existing + "\n" + note);
            }
            int wrappedLines = Math.max(
                    lineCount(item.name()),
                    Math.max(
                            lineCount(delivery.changeSummary()),
                            Math.max(
                                    estimatedLines(delivery.deliverables()),
                                    estimatedLines(links.stream()
                                            .map(Attachment::name)
                                            .collect(java.util.stream.Collectors.joining(" "))))));
            row.setHeightInPoints(placed > 0 ? IMAGE_ROW_HEIGHT_PT : Math.min(409f, Math.max(30f, wrappedLines * 15f)));
            rowIndex++;
        }
        sheet.createFreezePane(3, 3);
    }

    private record DeliveryRow(Item item, Delivery delivery) {}

    private static int estimatedLines(String value) {
        return value == null || value.isBlank() ? 1 : Math.max(lineCount(value), (value.length() + 39) / 40);
    }

    private int taskRow(XSSFSheet sheet, XSSFDrawing drawing, int r, int seq, Item item, int maxImages) {
        Row row = sheet.createRow(r);
        cell(row, 0, String.valueOf(seq), styles.center);
        cell(row, 1, item.name(), styles.left);
        cell(row, 2, item.category().label(), styles.center);
        cell(row, 3, date(item.receivedAt()) + (item.receivedAtEstimated() ? "（估算）" : ""), styles.center);
        cell(row, 4, item.plannedDate() == null ? "—" : item.plannedDate().format(FULL_DATE), styles.center);
        cell(row, 5, date(item.completedAt()), styles.center);
        cell(row, 6, item.statusLabel(), styles.center);
        cell(
                row,
                7,
                item.createdIn(report.month()) ? (item.receivedAtEstimated() ? "本月指派·时间估算" : "本月接单") : "往月接单·本月完成",
                styles.center);
        cell(row, 8, item.deliverables(), styles.leftWrap);
        List<Attachment> links =
                item.attachments().stream().filter(a -> !a.embeddableImage()).toList();
        cell(
                row,
                9,
                links.stream().map(Attachment::name).collect(java.util.stream.Collectors.joining("\n")),
                styles.left);
        for (int i = 0; i < Math.max(maxImages, 1); i++) cell(row, IMAGE_COLUMN + i, "", styles.body);

        List<Attachment> attachments =
                item.attachments().stream().filter(Attachment::embeddableImage).toList();
        List<String> unreadable = new ArrayList<>();
        int placed = 0;
        for (Attachment attachment : attachments) {
            Optional<LoadedImage> image = images.apply(attachment.url());
            if (image.isEmpty()) {
                unreadable.add(attachment.name());
                continue;
            }
            placePicture(drawing, image.get(), IMAGE_COLUMN + placed, r);
            placed++;
        }
        if (!unreadable.isEmpty()) {
            String existing = row.getCell(9).getStringCellValue();
            String note = "图片无法读取：" + String.join("、", unreadable);
            row.getCell(9).setCellValue(existing.isBlank() ? note : existing + "\n" + note);
        }
        int deliverableLines =
                item.deliverables() == null ? 1 : (item.deliverables().length() + 39) / 40;
        row.setHeightInPoints(
                placed > 0
                        ? IMAGE_ROW_HEIGHT_PT
                        : Math.min(409f, Math.max(30f, Math.max(lineCount(item.name()), deliverableLines) * 15f)));
        return r + 1;
    }

    /** 喺单个格仔入面按比例摆放图片（锚点起止同一格，用 EMU 偏移控制尺寸）；图片本身保留原分辨率。 */
    private void placePicture(XSSFDrawing drawing, LoadedImage image, int column, int row) {
        int pictureIndex = workbook.addPicture(image.jpeg(), Workbook.PICTURE_TYPE_JPEG);
        int pad = 4;
        int boxWidth = IMAGE_COLUMN_WIDTH_CHARS * 7 + 5 - pad * 2;
        int boxHeight = Math.round(IMAGE_ROW_HEIGHT_PT * 96 / 72) - pad * 2;
        double scale = Math.min((double) boxWidth / image.width(), (double) boxHeight / image.height());
        int width = Math.max(1, (int) Math.floor(image.width() * scale));
        int height = Math.max(1, (int) Math.floor(image.height() * scale));
        XSSFClientAnchor anchor = new XSSFClientAnchor(
                pad * Units.EMU_PER_PIXEL,
                pad * Units.EMU_PER_PIXEL,
                (pad + width) * Units.EMU_PER_PIXEL,
                (pad + height) * Units.EMU_PER_PIXEL,
                column,
                row,
                column,
                row);
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        drawing.createPicture(anchor, pictureIndex);
    }

    // ==================== 工具 ====================

    private static String date(LocalDateTime value) {
        return value == null ? "—" : value.toLocalDate().format(FULL_DATE);
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int lineCount(String value) {
        return value == null || value.isEmpty() ? 1 : value.split("\n", -1).length;
    }

    private static void text(XSSFSheet sheet, int rowIndex, int column, String value, CellStyle style) {
        Row row = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) : sheet.getRow(rowIndex);
        cell(row, column, value, style);
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void formula(Row row, int column, String formula, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private static void merge(XSSFSheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (firstRow == lastRow && firstCol == lastCol) return;
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
    }

    /** 统一样式，避免逐格新建（xlsx 样式数有上限）。 */
    private static final class Styles {
        final CellStyle title,
                plain,
                note,
                header,
                section,
                body,
                center,
                left,
                leftWrap,
                progress,
                percent,
                score,
                scoreBold,
                manual;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font noteFont = wb.createFont();
            noteFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            noteFont.setFontHeightInPoints((short) 9);

            title = wb.createCellStyle();
            title.setFont(titleFont);
            plain = wb.createCellStyle();
            note = wb.createCellStyle();
            note.setFont(noteFont);
            body = bordered(wb);
            center = bordered(wb);
            center.setAlignment(HorizontalAlignment.CENTER);
            left = bordered(wb);
            left.setAlignment(HorizontalAlignment.LEFT);
            leftWrap = bordered(wb);
            leftWrap.setAlignment(HorizontalAlignment.LEFT);
            leftWrap.setWrapText(true);
            progress = bordered(wb);
            progress.setAlignment(HorizontalAlignment.CENTER);
            progress.setDataFormat(wb.createDataFormat().getFormat("0%"));
            progress.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            progress.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header = bordered(wb);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            section = bordered(wb);
            section.setFont(bold);
            section.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            percent = bordered(wb);
            percent.setAlignment(HorizontalAlignment.CENTER);
            percent.setDataFormat(wb.createDataFormat().getFormat("0%"));
            score = bordered(wb);
            score.setAlignment(HorizontalAlignment.CENTER);
            score.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            scoreBold = bordered(wb);
            scoreBold.cloneStyleFrom(score);
            scoreBold.setFont(bold);
            manual = bordered(wb);
            manual.setAlignment(HorizontalAlignment.CENTER);
            manual.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            manual.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            manual.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static CellStyle bordered(XSSFWorkbook wb) {
            CellStyle style = wb.createCellStyle();
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            return style;
        }
    }
}
