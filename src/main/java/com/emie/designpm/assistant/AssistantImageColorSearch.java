package com.emie.designpm.assistant;

import com.emie.designpm.file.service.FileThumbnailService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reads only existing, bounded thumbnails; never restores originals or calls an image model. */
@Service
public class AssistantImageColorSearch {
    private static final int CACHE_LIMIT = 2048;
    private static final long MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final int SAMPLE_SIDE = 64;
    private final FileThumbnailService thumbnails;
    private final Path cacheRoot;
    private final Semaphore readers = new Semaphore(2);
    private final Map<Path, Cached> cache = new LinkedHashMap<>(128, .75f, true);

    public AssistantImageColorSearch(
            FileThumbnailService thumbnails, @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.thumbnails = thumbnails;
        this.cacheRoot = Path.of(uploadDir).toAbsolutePath().normalize().resolve("thumbnail-cache");
    }

    public enum ColorFamily {
        RED("红色", "红", "酒红", "砖红", "大红", "深红", "浅红", "red", "scarlet", "crimson"),
        ORANGE("橙色", "橙", "桔色", "orange"),
        YELLOW("黄色", "黄", "yellow", "golden"),
        GREEN(
                "绿色", "绿", "薄荷绿", "森林绿", "草绿", "橄榄绿", "军绿", "墨绿", "豆绿", "翠绿", "嫩绿", "苹果绿", "浅绿", "深绿", "green", "mint",
                "olive"),
        CYAN("青色", "青色", "青绿", "cyan", "teal", "turquoise"),
        BLUE("蓝色", "蓝", "天蓝", "海蓝", "蔚蓝", "藏蓝", "宝蓝", "湖蓝", "浅蓝", "深蓝", "blue", "navy"),
        PURPLE("紫色", "紫", "purple", "violet", "lavender"),
        PINK("粉色", "粉色", "粉红", "粉调", "粉系", "玫红", "pink", "rose", "magenta"),
        BROWN("棕色", "棕", "褐", "咖啡色", "brown"),
        BLACK("黑色", "黑", "黑白", "纯黑", "炭黑", "black"),
        WHITE("白色", "白", "黑白", "米白", "奶白", "象牙白", "纯白", "white"),
        GRAY("灰色", "灰", "银灰", "深灰", "浅灰", "gray", "grey");

        private final String label;
        private final List<String> aliases;

        ColorFamily(String label, String... aliases) {
            this.label = label;
            this.aliases = List.of(aliases);
        }

        public String label() {
            return label;
        }
    }

    public record ColorMatch(boolean available, double score, List<String> reasons) {
        private static final ColorMatch NONE = new ColorMatch(false, 0, List.of());
        private static final ColorMatch UNMATCHED = new ColorMatch(true, 0, List.of());
    }

    public List<ColorFamily> parseColors(String query) {
        if (query == null || query.isBlank()) return List.of();
        String text = query.toLowerCase(Locale.ROOT);
        // A negated clause is never a positive search instruction; exclusions are not inferred here.
        text = text.replaceAll("(?:不想要|不需要|不要|排除|避免|不带|不含|不用)[^，,。；;！!？?]*", " ")
                .replaceAll("\\b(?:without|exclude|excluding|not|avoid|no)\\s+[^,.;!?]*", " ");
        // Compound color names refer to one hue, not to two simultaneous color requirements.
        final String positiveText = text.replace("粉红", "粉色").replace("青绿", "青色").replace("蓝绿", "青色");
        List<ColorFamily> colors = new ArrayList<>();
        for (ColorFamily color : ColorFamily.values()) {
            boolean found = color.aliases.stream().anyMatch(alias -> Pattern.compile(aliasPattern(alias))
                    .matcher(positiveText)
                    .find());
            if (found) colors.add(color);
        }
        return List.copyOf(colors);
    }

    public String textWithoutColors(String query) {
        List<ColorFamily> colors = parseColors(query);
        if (colors.isEmpty()) return query;
        String text = query.replaceAll("(?:不想要|不需要|不要|排除|避免|不带|不含|不用)[^，,。；;！!？?]*", " ")
                .replaceAll("(?i)\\b(?:without|exclude|excluding|not|avoid|no)\\s+[^,.;!?]*", " ")
                .replace("粉红", "粉色")
                .replace("青绿", "青色")
                .replace("蓝绿", "青色");
        for (ColorFamily color : colors) {
            List<String> aliases = color.aliases.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            for (String alias : aliases) {
                String pattern = aliasPattern(alias) + (alias.charAt(0) < 128 ? "" : "(?:色调|色系|色|调|系)?");
                text = text.replaceAll(pattern, " ");
            }
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private String aliasPattern(String alias) {
        String quoted = Pattern.quote(alias);
        if (alias.charAt(0) < 128) return "(?i)(?<![a-z])" + quoted + "(?![a-z])";
        if (alias.length() > 1) return quoted;
        // Names such as 小红帽、白雪公主、黑猫警长 contain a color character, not a color request.
        return "(?:" + quoted + "(?:色(?:调|系)?|系|调)|(?<![\\p{IsHan}])" + quoted + "(?![\\p{IsHan}]))";
    }

    public ColorMatch match(String storedName, List<ColorFamily> colors) {
        if (storedName == null || storedName.isBlank() || colors == null || colors.isEmpty()) return ColorMatch.NONE;
        Optional<Path> thumbnail = thumbnails.cached(storedName, cacheRoot);
        if (thumbnail.isEmpty()) return ColorMatch.NONE;
        Histogram histogram = histogram(thumbnail.get());
        if (histogram == null) return ColorMatch.NONE;
        if (histogram.visible <= 0) return ColorMatch.UNMATCHED;
        double score = 0;
        List<String> reasons = new ArrayList<>();
        for (ColorFamily color : new LinkedHashSet<>(colors)) {
            double pixels = histogram.counts[color.ordinal()];
            boolean chromatic = color.ordinal() < ColorFamily.BLACK.ordinal();
            double content =
                    chromatic ? histogram.visible - histogram.counts[ColorFamily.WHITE.ordinal()] : histogram.visible;
            double coverage = content > 0 ? pixels / content : 0;
            double fullCoverage = pixels / histogram.visible;
            // White canvas must not dilute the artwork, nor may one tiny colored dot count as a theme.
            if (coverage < .18 || fullCoverage < .04) return ColorMatch.UNMATCHED;
            score += coverage * Math.sqrt(Math.min(1, fullCoverage / .20));
            reasons.add("缩略图" + color.label() + "约" + Math.round(fullCoverage * 100) + "%");
        }
        return new ColorMatch(true, score / new LinkedHashSet<>(colors).size(), List.copyOf(reasons));
    }

    private Histogram histogram(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(cacheRoot)) return null;
            BasicFileAttributes attributes =
                    Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() <= 0 || attributes.size() > MAX_BYTES) return null;
            synchronized (cache) {
                Cached value = cache.get(normalized);
                if (value != null
                        && value.size == attributes.size()
                        && value.modified.equals(attributes.lastModifiedTime())) {
                    return value.histogram;
                }
            }
            if (!readers.tryAcquire()) return null;
            Histogram value;
            try {
                value = read(normalized);
            } finally {
                readers.release();
            }
            synchronized (cache) {
                cache.put(normalized, new Cached(attributes.size(), attributes.lastModifiedTime(), value));
                while (cache.size() > CACHE_LIMIT)
                    cache.remove(cache.keySet().iterator().next());
            }
            return value;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private Histogram read(Path path) throws IOException {
        try (var source = Files.newInputStream(path);
                var input = new MemoryCacheImageInputStream(source)) {
            Iterator<ImageReader> candidates = ImageIO.getImageReaders(input);
            if (!candidates.hasNext()) return null;
            ImageReader reader = candidates.next();
            BufferedImage image = null;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return null;
                var parameters = reader.getDefaultReadParam();
                int stride = Math.max(1, (Math.max(width, height) + SAMPLE_SIDE - 1) / SAMPLE_SIDE);
                parameters.setSourceSubsampling(stride, stride, 0, 0);
                image = reader.read(0, parameters);
                if (image == null || image.getWidth() > SAMPLE_SIDE || image.getHeight() > SAMPLE_SIDE) return null;
                return pixels(image);
            } finally {
                if (image != null) image.flush();
                reader.dispose();
            }
        }
    }

    private Histogram pixels(BufferedImage image) {
        double[] counts = new double[ColorFamily.values().length];
        double visible = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                double alpha = (argb >>> 24) / 255.0;
                if (alpha < .10) continue;
                Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, hsb);
                counts[classify(hsb).ordinal()] += alpha;
                visible += alpha;
            }
        }
        return new Histogram(visible, counts);
    }

    private ColorFamily classify(float[] hsb) {
        float hue = hsb[0] * 360;
        float saturation = hsb[1];
        float brightness = hsb[2];
        if (brightness < .18) return ColorFamily.BLACK;
        if (saturation < .16) return brightness >= .90 ? ColorFamily.WHITE : ColorFamily.GRAY;
        if (hue >= 12 && hue < 48 && brightness < .68) return ColorFamily.BROWN;
        if ((hue >= 315 && hue < 345) || (hue < 15 || hue >= 345) && saturation < .48 && brightness > .65) {
            return ColorFamily.PINK;
        }
        if (hue < 18 || hue >= 345) return ColorFamily.RED;
        if (hue < 45) return ColorFamily.ORANGE;
        if (hue < 75) return ColorFamily.YELLOW;
        if (hue < 165) return ColorFamily.GREEN;
        if (hue < 200) return ColorFamily.CYAN;
        if (hue < 260) return ColorFamily.BLUE;
        return ColorFamily.PURPLE;
    }

    private record Cached(long size, FileTime modified, Histogram histogram) {}

    private record Histogram(double visible, double[] counts) {}
}
