package com.emie.designpm.recommendation.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualAnalysisServiceTest {
    private final VisualAnalysisService service = new VisualAnalysisService();

    @Test
    void legacyRecommendationReadsUseOnlyMetadataEvenForLargeOrUnsupportedSourceNames() {
        assertFalse(service.configured());
        for (String name : List.of("large.ai", "cover.png", "unsupported.psd")) {
            var view = service.profile(name);
            assertEquals("DISABLED", view.status());
            assertNull(view.profile());
            assertEquals(view, service.peek(name));
        }
        var views = service.peekAll(Arrays.asList("large.ai", null, "cover.png", "large.ai"));
        assertEquals(List.of("large.ai", "cover.png"), views.keySet().stream().toList());
        assertTrue(views.values().stream().allMatch(view -> view.profile() == null));
    }

    @Test
    void retiredServiceCannotLoadCredentialsReadFilesPersistProfilesOrScheduleAnalysis() throws Exception {
        assertEquals(0, VisualAnalysisService.class.getConstructor().getParameterCount());
        assertTrue(
                Arrays.stream(VisualAnalysisService.class.getDeclaredFields())
                        .allMatch(field -> Modifier.isStatic(field.getModifiers())),
                "legacy recommendations must not retain clients, repositories or executors");
        assertTrue(
                Arrays.stream(VisualAnalysisService.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().equals("indexLibrary")
                                || method.getName().equals("onLibraryChanged")),
                "bulk and automatic image-analysis entry points must be removed");
    }
}
