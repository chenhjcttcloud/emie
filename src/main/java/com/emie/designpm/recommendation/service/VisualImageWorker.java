package com.emie.designpm.recommendation.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Untrusted image/PDF decoders run only in a bounded child JVM, never in the web JVM. */
public final class VisualImageWorker {
    static final int MAX_SIDE = 1600;
    static final int MAX_IMAGES = 2;
    static final int MAX_JPEG_BYTES = 2 * 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = MAX_IMAGES * MAX_JPEG_BYTES + 64;
    static final long TIMEOUT_MILLIS = 45_000;
    private static final int MAGIC = 0x45564931;
    private static final long MIN_FREE_MEMORY = 384L * 1024 * 1024;

    private VisualImageWorker() {}

    static List<String> render(Path source, String name) throws Exception {
        return render(source, name, Path.of(System.getProperty("java.io.tmpdir")), TIMEOUT_MILLIS);
    }

    static List<String> render(Path source, String name, Path temporaryRoot, long timeoutMillis) throws Exception {
        if (timeoutMillis <= 0 || timeoutMillis > TIMEOUT_MILLIS) throw new IllegalArgumentException("图片解析时限无效");
        if (!hasMemoryForChild(Path.of("/proc/meminfo"), Path.of("/sys/fs/cgroup")))
            throw new IOException("可用内存不足，暂缓图片分析");
        Path work = Files.createTempDirectory(temporaryRoot, "emie-visual-analysis-");
        Process process = null;
        try {
            Path result = work.resolve("result.bin");
            ProcessBuilder builder = new ProcessBuilder(command(source, name, result, work))
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            // Decoders do not need database, model credentials, agents or inherited JVM options.
            builder.environment().keySet().removeIf(key -> !Set.of(
                            "PATH", "SystemRoot", "WINDIR", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP")
                    .contains(key));
            process = builder.start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("图片解析超时，已停止分析");
            if (process.exitValue() == 2) throw new IllegalArgumentException("图片分辨率、格式或页面尺寸超过分析限制");
            if (process.exitValue() != 0) throw new IOException("图片解析失败或超过资源限制");
            return readResult(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("图片分析已中断", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static List<String> command(Path source, String name, Path result, Path work) {
        String rawClassPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String classPath = String.join(
                File.pathSeparator,
                Arrays.stream(rawClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1))
                        .map(entry -> Path.of(entry.isEmpty() ? "." : entry)
                                .toAbsolutePath()
                                .normalize()
                                .toString())
                        .toList());
        List<String> args = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms16m",
                "-Xmx128m",
                "-Xss512k",
                "-XX:MaxMetaspaceSize=96m",
                "-XX:MaxDirectMemorySize=32m",
                "-XX:+UseSerialGC",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:-HeapDumpOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + work,
                "-Duser.home=" + work));
        if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
            args.add("-Dloader.main=" + VisualImageWorker.class.getName());
            args.addAll(List.of("-cp", classPath, "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else args.addAll(List.of("-cp", classPath, VisualImageWorker.class.getName()));
        args.addAll(List.of(
                source.toAbsolutePath().toString(),
                name,
                result.toAbsolutePath().toString()));
        return args;
    }

    static List<String> readResult(Path result) throws IOException {
        if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) > MAX_OUTPUT_BYTES)
            throw new IOException("图片解析输出无效或超过限制");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(result))) {
            if (input.readInt() != MAGIC) throw new IOException("图片解析输出无效");
            int count = input.readInt();
            if (count < 1 || count > MAX_IMAGES) throw new IOException("图片解析页数超过限制");
            List<String> images = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < count; i++) {
                int width = input.readInt(), height = input.readInt(), length = input.readInt();
                if (width < 1
                        || height < 1
                        || width > MAX_SIDE
                        || height > MAX_SIDE
                        || length < 4
                        || length > MAX_JPEG_BYTES
                        || (total += length) > MAX_IMAGES * MAX_JPEG_BYTES) throw new IOException("图片解析输出尺寸或大小超过限制");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length
                        || bytes[0] != (byte) 0xff
                        || bytes[1] != (byte) 0xd8
                        || bytes[length - 2] != (byte) 0xff
                        || bytes[length - 1] != (byte) 0xd9) throw new IOException("图片解析输出无效");
                images.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes));
            }
            if (input.read() != -1) throw new IOException("图片解析输出包含额外数据");
            return List.copyOf(images);
        }
    }

    static boolean hasMemoryForChild(Path meminfo, Path group) {
        try {
            if (Files.isRegularFile(meminfo)) {
                long available = Files.readAllLines(meminfo).stream()
                        .filter(line -> line.startsWith("MemAvailable:"))
                        .map(line -> line.replaceAll("[^0-9]", ""))
                        .mapToLong(Long::parseLong)
                        .findFirst()
                        .orElse(0);
                if (available * 1024 < MIN_FREE_MEMORY) return false;
            }
            Path limit = group.resolve("memory.max"), usage = group.resolve("memory.current");
            if (!Files.isRegularFile(limit)) {
                limit = group.resolve("memory/memory.limit_in_bytes");
                usage = group.resolve("memory/memory.usage_in_bytes");
            }
            if (!Files.isRegularFile(limit) || !Files.isRegularFile(usage)) return true;
            String maximum = Files.readString(limit).trim();
            return "max".equals(maximum)
                    || Long.parseLong(maximum)
                                    - Long.parseLong(Files.readString(usage).trim())
                            >= MIN_FREE_MEMORY;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private record Frame(int width, int height, byte[] jpeg) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || Runtime.getRuntime().maxMemory() > 128L * 1024 * 1024)
            throw new IllegalArgumentException("必须使用受限图片解析进程");
        try {
            Path source = Path.of(args[0]);
            if (!Files.isRegularFile(source) || Files.size(source) > VisualInputReader.MAX_BYTES)
                throw new IllegalArgumentException("源文件超过限制");
            List<Frame> frames = decode(source, args[1]);
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(Path.of(args[2])))) {
                output.writeInt(MAGIC);
                output.writeInt(frames.size());
                for (Frame frame : frames) {
                    output.writeInt(frame.width());
                    output.writeInt(frame.height());
                    output.writeInt(frame.jpeg().length);
                    output.write(frame.jpeg());
                }
            }
        } catch (IllegalArgumentException e) {
            System.exit(2);
        }
    }

    private static List<Frame> decode(Path source, String name) throws Exception {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ai") || lower.endsWith(".pdf")) {
            try (var document = Loader.loadPDF(source.toFile())) {
                if (document.isEncrypted()) throw new IllegalArgumentException("加密PDF暂不支持分析");
                PDFRenderer renderer = new PDFRenderer(document);
                renderer.setSubsamplingAllowed(true);
                List<Frame> images = new ArrayList<>();
                for (int i = 0; i < Math.min(MAX_IMAGES, document.getNumberOfPages()); i++) {
                    var box = document.getPage(i).getCropBox();
                    float longest = Math.max(box.getWidth(), box.getHeight());
                    if (!Float.isFinite(longest) || longest <= 0) throw new IllegalArgumentException("文件页面尺寸无效");
                    BufferedImage page = renderer.renderImage(i, Math.min(2f, MAX_SIDE / longest));
                    try {
                        images.add(jpeg(page));
                    } finally {
                        page.flush();
                    }
                }
                if (images.isEmpty()) throw new IllegalArgumentException("文件没有可分析页面");
                return images;
            }
        }
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片格式无法读取");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 80_000_000L)
                    throw new IllegalArgumentException("图片分辨率超过分析限制");
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / MAX_SIDE);
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                try {
                    return List.of(jpeg(image));
                } finally {
                    image.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static Frame jpeg(BufferedImage input) throws Exception {
        double scale = Math.min(1, (double) MAX_SIDE / Math.max(input.getWidth(), input.getHeight()));
        BufferedImage output = new BufferedImage(
                Math.max(1, (int) (input.getWidth() * scale)),
                Math.max(1, (int) (input.getHeight() * scale)),
                BufferedImage.TYPE_INT_RGB);
        try {
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(input, 0, 0, output.getWidth(), output.getHeight(), null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream bounded = new FilterOutputStream(bytes) {
                private int written;

                @Override
                public void write(int value) throws IOException {
                    if (++written > MAX_JPEG_BYTES) throw new IOException("图片编码超过限制");
                    out.write(value);
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    if (length > MAX_JPEG_BYTES - written) throw new IOException("图片编码超过限制");
                    written += length;
                    out.write(data, offset, length);
                }
            };
            if (!ImageIO.write(output, "jpeg", bounded)) throw new IllegalArgumentException("图片编码失败");
            return new Frame(output.getWidth(), output.getHeight(), bytes.toByteArray());
        } finally {
            output.flush();
        }
    }
}
