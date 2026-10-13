package com.emie.designpm.file.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Runs PDF-compatible AI rendering outside the web application's heap. */
final class AiThumbnailRenderer {
    private static final long MIN_FREE_MEMORY = 896L * 1024 * 1024;

    static final class InsufficientMemoryException extends IOException {
        InsufficientMemoryException() {
            super("容器剩余内存不足，稍后重试 AI 缩略图生成");
        }
    }

    private AiThumbnailRenderer() {}

    static void generate(Path source, Path target) throws IOException {
        if (!hasMemoryForChild()) throw new InsufficientMemoryException();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m",
                "-XX:+UseSerialGC",
                "-Djava.awt.headless=true"));
        if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
            command.add("-Dloader.main=" + AiThumbnailRenderer.class.getName());
            command.addAll(List.of("-cp", classPath, "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else {
            command.addAll(List.of("-cp", classPath, AiThumbnailRenderer.class.getName()));
        }
        command.add(source.toString());
        command.add(target.toString());
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = builder.start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("AI 缩略图生成超时");
            }
            if (process.exitValue() != 0) throw new IOException("AI 缩略图生成失败，退出码 " + process.exitValue());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("AI 缩略图生成被中断", e);
        }
    }

    private static boolean hasMemoryForChild() {
        return hasMemoryForChild(Path.of("/proc/meminfo"), Path.of("/sys/fs/cgroup"));
    }

    static boolean hasMemoryForChild(Path meminfo, Path group) {
        try {
            if (Files.isRegularFile(meminfo)) {
                long availableKb = Files.readAllLines(meminfo).stream()
                        .filter(line -> line.startsWith("MemAvailable:"))
                        .map(line -> line.replaceAll("[^0-9]", ""))
                        .mapToLong(Long::parseLong)
                        .findFirst()
                        .orElse(0);
                if (availableKb * 1024 < MIN_FREE_MEMORY) return false;
            }
            Path limit = group.resolve("memory.max");
            Path usage = group.resolve("memory.current");
            if (!Files.isRegularFile(limit) || !Files.isRegularFile(usage)) return true;
            String max = Files.readString(limit).trim();
            if ("max".equals(max)) return true;
            return Long.parseLong(max) - Long.parseLong(Files.readString(usage).trim()) >= MIN_FREE_MEMORY;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要源文件和目标文件");
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Path temp = target.resolveSibling(
                target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (document.getNumberOfPages() == 0) throw new IOException("AI 文件没有可预览页面");
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage page = renderer.renderImageWithDPI(0, 36);
            try {
                double scale = Math.min(1d, 560d / Math.max(page.getWidth(), page.getHeight()));
                int width = Math.max(1, (int) Math.round(page.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(page.getHeight() * scale));
                BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                try {
                    Graphics2D graphics = thumbnail.createGraphics();
                    try {
                        graphics.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(page, 0, 0, width, height, null);
                    } finally {
                        graphics.dispose();
                    }
                    if (!ImageIO.write(thumbnail, "png", temp.toFile())) throw new IOException("无法写入 PNG 缩略图");
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    thumbnail.flush();
                }
            } finally {
                page.flush();
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
