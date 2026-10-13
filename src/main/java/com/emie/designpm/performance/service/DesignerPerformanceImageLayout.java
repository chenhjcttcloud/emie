package com.emie.designpm.performance.service;

import com.emie.designpm.performance.service.DesignerMonthlyReport.Attachment;
import com.emie.designpm.performance.service.PerformanceImageLoader.LoadedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class DesignerPerformanceImageLayout {
    private static final int IMAGE_COLUMN_WIDTH_CHARS = 28;
    private static final float IMAGE_ROW_HEIGHT_PT = 150f;

    private DesignerPerformanceImageLayout() {}

    static List<LoadedImage> load(
            Attachment attachment,
            Function<String, Optional<LoadedImage>> images,
            Function<String, List<LoadedImage>> multiImages) {
        return attachment.name().toLowerCase(java.util.Locale.ROOT).endsWith(".ai")
                ? multiImages.apply(attachment.url())
                : images.apply(attachment.url()).stream().toList();
    }

    static int slots(List<Attachment> attachments, Function<String, List<LoadedImage>> multiImages) {
        return attachments.stream()
                .filter(Attachment::embeddableImage)
                .mapToInt(attachment ->
                        attachment.name().toLowerCase(java.util.Locale.ROOT).endsWith(".ai")
                                ? Math.max(
                                        1, multiImages.apply(attachment.url()).size())
                                : 1)
                .sum();
    }

    static void picture(
            XSSFWorkbook workbook,
            XSSFDrawing drawing,
            Map<LoadedImage, Integer> pictureIndexes,
            LoadedImage image,
            int column,
            int row) {
        int pictureIndex = pictureIndexes.computeIfAbsent(
                image, value -> workbook.addPicture(value.jpeg(), Workbook.PICTURE_TYPE_JPEG));
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

    static int estimatedLines(String value) {
        return value == null || value.isBlank()
                ? 1
                : Math.max(value.lines().toList().size(), (value.length() + 39) / 40);
    }
}
