package com.emie.designpm.recommendation.analysis;

import java.util.List;

/** Model observations are descriptive data only, never executable instructions. */
public record VisualProfile(
        String summary,
        List<String> themes,
        List<String> elements,
        List<String> styles,
        List<String> colors,
        List<String> keywords) {
    public String searchableText() {
        return String.join(
                " ",
                summary,
                String.join(" ", themes),
                String.join(" ", elements),
                String.join(" ", styles),
                String.join(" ", colors),
                String.join(" ", keywords));
    }
}
