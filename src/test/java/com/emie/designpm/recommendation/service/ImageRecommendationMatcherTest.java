package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.designpm.recommendation.analysis.VisualProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageRecommendationMatcherTest {
    private final ImageRecommendationMatcher matcher = new ImageRecommendationMatcher();

    @Test
    void sameSeriesRanksAbovePrimaryIpOnly() {
        var input = new ImageRecommendationMatcher.Input("小黄人", List.of("太空系列"), "手机挂链", List.of());
        var series = matcher.match(input, "小黄人", List.of("太空系列"), "宇航员", List.of());
        var sameIp = matcher.match(input, "小黄人", List.of("校园系列"), "校园", List.of());
        assertTrue(series.score() > sameIp.score());
        assertTrue(series.reasons().stream().anyMatch(r -> r.contains("同系列")));
    }

    @Test
    void doesNotFillRecommendationsWithUnrelatedAssetsOrGenericWords() {
        var input = new ImageRecommendationMatcher.Input("小黄人", List.of(), "设计产品参考图片需求", List.of());
        var unrelated = matcher.match(input, "哈利波特", List.of(), "设计产品参考图片需求", List.of());
        assertEquals(0, unrelated.score());
    }

    @Test
    void visualReferenceFindsElementsAcrossDifferentIpsWithAnHonestReason() {
        var visual = profile(List.of("星星", "宇航员"), List.of("扁平插画"));
        var input = new ImageRecommendationMatcher.Input("小黄人", List.of(), "", List.of(visual));
        var match = matcher.match(input, "原创", List.of(), "", List.of(visual));
        assertTrue(match.score() > 0);
        assertTrue(match.reasons().stream().anyMatch(r -> r.contains("画面元素相近")));
        var noObservation = matcher.match(input, "原创", List.of(), "", List.of());
        assertEquals(0, noObservation.score());
    }

    @Test
    void meaningfulRequirementTextCanMatchWithoutIpOrReferenceImage() {
        var input = new ImageRecommendationMatcher.Input("", List.of(), "复古宇航员太空主题", List.of());
        assertTrue(matcher.match(input, "原创", List.of(), "复古宇航员太空主题", List.of()).score() > 0);
        assertEquals(0, matcher.match(input, "原创", List.of(), "复古", List.of()).score());
    }

    @Test
    void shortChineseRequirementMatchesACompleteObservedElementWithoutIpOrReferences() {
        var input = new ImageRecommendationMatcher.Input("", List.of(), "熊猫", List.of());
        var visual = new VisualProfile("", List.of(), List.of("熊猫"), List.of(), List.of(), List.of());
        var match = matcher.match(input, "原创", List.of(), "", List.of(visual));
        assertTrue(match.score() > 0);
        assertTrue(match.reasons().contains("图像内容匹配需求：熊猫"));
    }

    @Test
    void shortEnglishRequirementMatchesCompleteObservedThemeAndKeywordCaseInsensitively() {
        var input = new ImageRecommendationMatcher.Input("", List.of(), "Design a BLUE ROBOT.", List.of());
        var visual = new VisualProfile("", List.of("blue robot"), List.of(), List.of(), List.of(), List.of("robot"));
        var match = matcher.match(input, "原创", List.of(), "", List.of(visual));
        assertTrue(match.score() > 0);
        assertTrue(match.reasons().stream().anyMatch(reason -> reason.contains("blue robot")));
    }

    @Test
    void englishShortTagDoesNotMatchInsideAnotherWord() {
        var visual = new VisualProfile("", List.of(), List.of("cat"), List.of(), List.of(), List.of());
        var unrelated = new ImageRecommendationMatcher.Input("", List.of(), "education", List.of());
        assertEquals(
                0,
                matcher.match(unrelated, "原创", List.of(), "", List.of(visual)).score());
        var actual = new ImageRecommendationMatcher.Input("", List.of(), "a cat", List.of());
        assertTrue(matcher.match(actual, "原创", List.of(), "", List.of(visual)).score() > 0);
    }

    @Test
    void genericModelTagsAndSingleCharacterLabelsNeverCreateShortKeywordMatches() {
        var generic = new VisualProfile(
                "",
                List.of("设计", "图片", "参考"),
                List.of("图", "产品图片"),
                List.of(),
                List.of(),
                List.of("design", "image", "reference", "ai"));
        var input = new ImageRecommendationMatcher.Input("", List.of(), "设计图片参考图 AI design image reference", List.of());
        assertEquals(
                0, matcher.match(input, "原创", List.of(), "", List.of(generic)).score());
        var englishOnly = new VisualProfile(
                "reference picture theme",
                List.of("reference"),
                List.of("picture"),
                List.of(),
                List.of(),
                List.of("theme"));
        var genericEnglish = new ImageRecommendationMatcher.Input("", List.of(), "reference picture theme", List.of());
        assertEquals(
                0,
                matcher.match(genericEnglish, "原创", List.of(), "", List.of(englishOnly))
                        .score());
    }

    static VisualProfile profile(List<String> elements, List<String> styles) {
        return new VisualProfile("太空主题", List.of("太空"), elements, styles, List.of("蓝色"), List.of("宇航员"));
    }
}
