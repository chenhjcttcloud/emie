package com.emie.designpm.recommendation.service;

import com.emie.designpm.recommendation.analysis.AnalysisView;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Metadata-only compatibility for task recommendations. The former automatic visual-analysis
 * feature is retired; this service never reads files, credentials or historical analysis caches.
 * Explicit assistant requests use their own authenticated path.
 */
@Service
public class VisualAnalysisService {
    private static final AnalysisView METADATA = new AnalysisView("DISABLED", null, "按图档名称、IP 和标签匹配");

    public boolean configured() {
        return false;
    }

    public AnalysisView profile(String storedName) {
        return METADATA;
    }

    public AnalysisView peek(String storedName) {
        return METADATA;
    }

    public Map<String, AnalysisView> peekAll(Collection<String> storedNames) {
        Map<String, AnalysisView> result = new LinkedHashMap<>();
        storedNames.stream().filter(Objects::nonNull).distinct().forEach(name -> result.put(name, METADATA));
        return result;
    }
}
