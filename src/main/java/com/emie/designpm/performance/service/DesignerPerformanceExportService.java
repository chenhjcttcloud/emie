package com.emie.designpm.performance.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 设计师月度绩效表：生成、快照保存、下载。
 *
 * <p>每月 1 号凌晨自动生成上月文件（已有快照就唔覆盖）；管理员可以手动重新生成任何月份。
 * 文件存喺上传目录嘅 {@code performance-exports/<yyyy-MM>/}（容器持久化卷），每人一个 xlsx，
 * 另有 {@code manifest.json} 记录生成时间同每人摘要。生成后数据再改都唔影响已存快照。
 */
@Service
public class DesignerPerformanceExportService {
    private static final Logger log = LoggerFactory.getLogger(DesignerPerformanceExportService.class);
    private static final String MANIFEST = "manifest.json";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DesignerMonthlyReportService reports;
    private final PerformanceImageLoader images;
    private final Path root;
    private final boolean autoEnabled;
    private final Executor taskExecutor;
    private final ConcurrentMap<YearMonth, GenerationStatus> generations = new ConcurrentHashMap<>();

    @Autowired
    public DesignerPerformanceExportService(
            DesignerMonthlyReportService reports,
            PerformanceImageLoader images,
            @Value("${app.upload.dir:./uploads}") String uploadDir,
            @Value("${app.performance-export.auto-enabled:true}") boolean autoEnabled) {
        this(reports, images, uploadDir, autoEnabled, Thread::startVirtualThread);
    }

    DesignerPerformanceExportService(
            DesignerMonthlyReportService reports,
            PerformanceImageLoader images,
            String uploadDir,
            boolean autoEnabled,
            Executor taskExecutor) {
        this.reports = reports;
        this.images = images;
        this.root = Path.of(uploadDir).toAbsolutePath().normalize().resolve("performance-exports");
        this.autoEnabled = autoEnabled;
        this.taskExecutor = taskExecutor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String userId,
            String name,
            String fileName,
            long createdCount,
            long completedCount,
            long onTimeCount,
            Double completionRate,
            int imageCount) {}

    public record Manifest(String month, String generatedAt, String generatedBy, List<Entry> designers) {}

    public record GenerationStatus(String month, String status, String message, int progress) {}

    public synchronized GenerationStatus startGeneration(YearMonth month, String generatedBy) {
        if (month.isAfter(YearMonth.now())) throw new IllegalArgumentException("不能生成未来月份的绩效表");
        GenerationStatus current = generations.get(month);
        if (current != null && "RUNNING".equals(current.status())) return current;
        GenerationStatus running = new GenerationStatus(month.toString(), "RUNNING", "正在准备月报", 0);
        generations.put(month, running);
        taskExecutor.execute(() -> {
            try {
                generate(month, generatedBy);
                generations.put(month, new GenerationStatus(month.toString(), "READY", "生成完成", 100));
            } catch (Exception e) {
                log.error("生成 {} 绩效表失败", month, e);
                generations.put(month, new GenerationStatus(month.toString(), "FAILED", "生成失败，请重试", 0));
            }
        });
        return running;
    }

    public GenerationStatus generationStatus(YearMonth month) {
        GenerationStatus current = generations.get(month);
        if (current != null) return current;
        boolean ready = manifest(month).isPresent();
        return new GenerationStatus(month.toString(), ready ? "READY" : "NOT_STARTED", "", ready ? 100 : 0);
    }

    @Scheduled(cron = "${app.performance-export.cron:0 30 2 1 * ?}")
    public void generatePreviousMonth() {
        if (!autoEnabled) return;
        YearMonth month = YearMonth.now().minusMonths(1);
        if (generations.containsKey(month)
                && "RUNNING".equals(generations.get(month).status())) {
            log.info("绩效表 {} 正在手动生成，自动任务跳过", month);
            return;
        }
        if (manifest(month).isPresent()) {
            log.info("绩效表 {} 已存在快照，自动任务跳过", month);
            return;
        }
        try {
            generate(month, "系统自动");
        } catch (Exception e) {
            log.error("自动生成 {} 绩效表失败", month, e);
        }
    }

    public synchronized Manifest generate(YearMonth month, String generatedBy) throws IOException {
        if (month.isAfter(YearMonth.now())) throw new IllegalArgumentException("不能生成未来月份的绩效表");
        List<DesignerMonthlyReport> monthReports = reports.buildMonth(month);
        updateProgress(month, "正在生成设计师绩效表", monthReports.isEmpty() ? 90 : 5);
        Files.createDirectories(root);
        Path staging = root.resolve(month + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(staging);
        try {
            List<Entry> entries = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            int completed = 0;
            for (DesignerMonthlyReport report : monthReports) {
                String fileName = fileName(report, usedNames);
                try (OutputStream out = Files.newOutputStream(staging.resolve(fileName))) {
                    DesignerPerformanceWorkbook.write(report, images::load, out);
                }
                entries.add(new Entry(
                        report.userId(),
                        report.name(),
                        fileName,
                        report.createdCount(),
                        report.completedCount(),
                        report.onTimeCount(),
                        report.completionRate(),
                        report.items().stream()
                                .mapToInt(item -> (int) item.attachments().stream()
                                        .filter(DesignerMonthlyReport.Attachment::embeddableImage)
                                        .count())
                                .sum()));
                completed++;
                updateProgress(
                        month,
                        "正在生成设计师绩效表（" + completed + "/" + monthReports.size() + "）",
                        5 + completed * 85 / monthReports.size());
            }
            updateProgress(month, "正在整理导出文件", 95);
            Manifest manifest =
                    new Manifest(month.toString(), LocalDateTime.now().format(STAMP), generatedBy, entries);
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(staging.resolve(MANIFEST).toFile(), manifest);
            Path target = monthDir(month);
            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            updateProgress(month, "生成完成", 100);
            log.info("已生成 {} 绩效表，共 {} 位设计师（{}）", month, entries.size(), generatedBy);
            return manifest;
        } finally {
            deleteRecursively(staging);
        }
    }

    private void updateProgress(YearMonth month, String message, int progress) {
        GenerationStatus current = generations.get(month);
        if (current != null && "RUNNING".equals(current.status())) {
            generations.put(month, new GenerationStatus(month.toString(), "RUNNING", message, progress));
        }
    }

    public Optional<Manifest> manifest(YearMonth month) {
        Path file = monthDir(month).resolve(MANIFEST);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), Manifest.class));
        } catch (IOException e) {
            log.warn("读取绩效表清单失败 {}", file, e);
            return Optional.empty();
        }
    }

    /** 已生成嘅月份，新到旧。 */
    public List<Manifest> list() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("\\d{4}-\\d{2}"))
                    .sorted(Comparator.reverseOrder())
                    .map(name -> manifest(YearMonth.parse(name)))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Optional<Path> designerFile(YearMonth month, String userId) {
        return manifest(month).flatMap(manifest -> manifest.designers().stream()
                .filter(entry -> entry.userId().equals(userId))
                .findFirst()
                .map(entry -> monthDir(month).resolve(entry.fileName()))
                .filter(Files::isRegularFile));
    }

    public void writeZip(YearMonth month, OutputStream out) throws IOException {
        Manifest manifest = manifest(month).orElseThrow(() -> new IllegalArgumentException("该月份尚未生成绩效表"));
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : manifest.designers()) {
                Path file = monthDir(month).resolve(entry.fileName());
                if (!Files.isRegularFile(file)) continue;
                zip.putNextEntry(new ZipEntry(entry.fileName()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private Path monthDir(YearMonth month) {
        return root.resolve(month.toString());
    }

    static String fileName(DesignerMonthlyReport report, Set<String> usedNames) {
        String name = report.name() == null || report.name().isBlank() ? report.userId() : report.name();
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        String candidate = report.month() + "_" + safe + "_绩效考评表.xlsx";
        if (!usedNames.add(candidate)) {
            String userId = report.userId().replaceAll("[^A-Za-z0-9_-]", "_");
            candidate = report.month() + "_" + safe + "_" + userId + "_绩效考评表.xlsx";
            usedNames.add(candidate);
        }
        return candidate;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
