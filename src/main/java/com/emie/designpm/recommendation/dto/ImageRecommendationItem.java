package com.emie.designpm.recommendation.dto;

import java.util.List;
import java.util.Map;

public record ImageRecommendationItem(
        Long id,
        Long libraryItemId,
        String name,
        String ipName,
        List<String> subOptions,
        List<Map<String, Object>> images,
        List<String> reasons,
        double score,
        String analysisStatus) {}
