package com.emie.designpm.recommendation.service;

import com.emie.designpm.recommendation.analysis.VisualProfile;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Transparent taxonomy/text/tag scoring; no unrelated filler is returned. */
@Component
public class ImageRecommendationMatcher {
    private static final Pattern WORDS = Pattern.compile("[a-z0-9][a-z0-9_-]{1,}|[\\p{IsHan}]{2,}");
    private static final Set<String> STOP = Set.of(
            "设计", "需求", "参考", "图片", "图档", "产品", "任务", "进行", "可以", "需要", "一个", "相关", "使用", "完成", "要求", "效果", "风格", "the",
            "and", "with", "this", "image", "design");
    private static final List<String> GENERIC_TAGS = List.of(
            "元素",
            "主题",
            "内容",
            "画面",
            "创意",
            "文件",
            "照片",
            "图案",
            "theme",
            "style",
            "reference",
            "picture",
            "photo",
            "asset",
            "product",
            "task",
            "file",
            "creative",
            "element",
            "content",
            "graphic");
    private static final Pattern SPECIFIC_CONTENT = Pattern.compile("[\\p{IsHan}]{2,}|[a-z][a-z0-9_-]{2,}");
    private static final Set<String> ALL_GENERIC;
    private static final Pattern GENERIC_ENGLISH;

    static {
        Set<String> words = new HashSet<>(STOP);
        words.addAll(GENERIC_TAGS);
        ALL_GENERIC = Set.copyOf(words);
        GENERIC_ENGLISH = Pattern.compile("(?<![a-z0-9_])(?:"
                + String.join(
                        "|",
                        words.stream()
                                .filter(word -> word.codePointAt(0) < 128)
                                .map(Pattern::quote)
                                .toList())
                + ")(?![a-z0-9_])");
    }

    public record Input(String ipName, List<String> subOptions, String text, List<VisualProfile> visuals) {}

    public record Match(double score, List<String> reasons) {}

    public Match match(Input input, String ipName, List<String> subOptions, String text, List<VisualProfile> visuals) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        boolean sameIp = !normalize(input.ipName()).isEmpty()
                && normalize(input.ipName()).equals(normalize(ipName));
        if (sameIp) {
            score += 40;
            reasons.add("同一 IP：" + ipName);
            List<String> series = common(input.subOptions(), subOptions);
            if (!series.isEmpty()) {
                score += 24;
                reasons.add("同系列：" + String.join("、", series.stream().limit(2).toList()));
            }
        }
        Set<String> shared = tokens(input.text());
        shared.retainAll(tokens(text));
        if (!shared.isEmpty()) {
            score += Math.min(22, shared.size() * 4);
            if (shared.size() >= 2) reasons.add("需求内容相符：" + examples(shared));
        }
        List<String> elements = new ArrayList<>(), styles = new ArrayList<>(), themes = new ArrayList<>();
        for (VisualProfile reference : input.visuals()) {
            for (VisualProfile visual : visuals) {
                elements.addAll(common(reference.elements(), visual.elements()));
                styles.addAll(common(reference.styles(), visual.styles()));
                themes.addAll(common(reference.themes(), visual.themes()));
            }
        }
        if (!elements.isEmpty()) {
            score += Math.min(30, 16 + new HashSet<>(elements).size() * 4);
            reasons.add("画面元素相近：" + examples(new LinkedHashSet<>(elements)));
        }
        if (!styles.isEmpty()) {
            score += 16;
            reasons.add("视觉风格相近：" + examples(new LinkedHashSet<>(styles)));
        }
        if (!themes.isEmpty()) {
            score += 14;
            reasons.add("参考图主题相近：" + examples(new LinkedHashSet<>(themes)));
        }
        Set<String> taskTokens = tokens(input.text());
        Set<String> visualTokens = new LinkedHashSet<>();
        for (VisualProfile visual : visuals) visualTokens.addAll(tokens(profileText(visual)));
        taskTokens.retainAll(visualTokens);
        Set<String> namedContent = specificTagMatches(input.text(), visuals);
        if (taskTokens.size() >= 2 || !namedContent.isEmpty()) {
            score += namedContent.isEmpty()
                    ? Math.min(24, taskTokens.size() * 4)
                    : Math.min(24, 12 + namedContent.size() * 4);
            reasons.add("图像内容匹配需求：" + examples(namedContent.isEmpty() ? taskTokens : namedContent));
        }
        // A single incidental word cannot turn an unrelated asset into a recommendation.
        boolean relevant = sameIp
                || shared.size() >= 3
                || !elements.isEmpty()
                || !styles.isEmpty()
                || !themes.isEmpty()
                || !namedContent.isEmpty()
                || taskTokens.size() >= 3;
        return new Match(
                relevant ? score : 0, reasons.stream().distinct().limit(3).toList());
    }

    /** Complete model-observed concepts can match short requirements without incidental substring matches. */
    private static Set<String> specificTagMatches(String input, List<VisualProfile> visuals) {
        String requirement = normalize(input).replaceAll("\\s+", " ");
        Set<String> matches = new LinkedHashSet<>();
        for (VisualProfile visual : visuals) {
            for (List<String> group : List.of(
                    visual.themes() == null ? List.<String>of() : visual.themes(),
                    visual.elements() == null ? List.<String>of() : visual.elements(),
                    visual.keywords() == null ? List.<String>of() : visual.keywords())) {
                for (String raw : group) {
                    String tag = normalize(raw).replaceAll("\\s+", " ");
                    if (tag.isEmpty() || tag.length() > 128) continue;
                    boolean hasHan = tag.codePoints()
                            .anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
                    boolean present = hasHan ? requirement.contains(tag) : containsBoundedTag(requirement, tag);
                    if (present && isSpecificTag(tag)) matches.add(tag);
                }
            }
        }
        return matches;
    }

    private static boolean isSpecificTag(String tag) {
        String content = tag;
        for (String word : ALL_GENERIC) if (word.codePointAt(0) > 127) content = content.replace(word, " ");
        return SPECIFIC_CONTENT
                .matcher(GENERIC_ENGLISH.matcher(content).replaceAll(" "))
                .find();
    }

    private static boolean containsBoundedTag(String requirement, String tag) {
        int start = requirement.indexOf(tag);
        while (start >= 0) {
            int end = start + tag.length();
            boolean left = start == 0 || !wordPoint(requirement.codePointBefore(start));
            boolean right = end == requirement.length() || !wordPoint(requirement.codePointAt(end));
            if (left && right) return true;
            start = requirement.indexOf(tag, start + 1);
        }
        return false;
    }

    private static boolean wordPoint(int point) {
        return Character.isLetterOrDigit(point) || point == '_';
    }

    public static String profileText(VisualProfile p) {
        return String.join(
                " ",
                List.of(
                        p.summary() == null ? "" : p.summary(),
                        join(p.themes()),
                        join(p.elements()),
                        join(p.styles()),
                        join(p.colors()),
                        join(p.keywords())));
    }

    private static String join(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private static List<String> common(List<String> left, List<String> right) {
        if (left == null || right == null) return List.of();
        Set<String> normalized = new HashSet<>();
        for (String value : left) if (!normalize(value).isEmpty()) normalized.add(normalize(value));
        return right.stream()
                .filter(Objects::nonNull)
                .filter(v -> normalized.contains(normalize(v)))
                .distinct()
                .toList();
    }

    static Set<String> tokens(String input) {
        Set<String> result = new LinkedHashSet<>();
        String normalized = normalize(input);
        // Remove generic words before making Han bigrams, including artificial boundary pairs.
        for (String stop : ALL_GENERIC) if (stop.codePointAt(0) > 127) normalized = normalized.replace(stop, " ");
        var matcher = WORDS.matcher(normalized);
        while (matcher.find() && result.size() < 2000) {
            String word = matcher.group();
            if (word.codePointAt(0) > 127) {
                int[] points = word.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) {
                    String token = new String(points, i, 2);
                    if (!ALL_GENERIC.contains(token)) result.add(token);
                }
            } else if (!ALL_GENERIC.contains(word)) result.add(word);
        }
        return result;
    }

    private static String normalize(String s) {
        return s == null
                ? ""
                : Normalizer.normalize(s, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    private static String examples(Set<String> values) {
        return String.join("、", values.stream().limit(3).toList());
    }
}
