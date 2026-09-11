package com.emie.designpm.project.service;

import com.emie.designpm.admin.repository.SystemConfigRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 SystemConfig 读取评分权重。原先 ProjectService 和 DefaultSubTaskCommandService
 * 各有一份逐字相同的 getScoringPct / scoringWeightMap / getScoringWeight，
 * 改一处漏改另一处的风险很高，收拢到这里。
 */
final class ScoringWeightConfig {

    private final SystemConfigRepository systemConfigRepository;

    ScoringWeightConfig(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    /** 按 项目类型+角色 读权重百分比（key: scoring.&lt;type&gt;.&lt;role&gt;），缺省 25.0。 */
    double pct(String projectType, String role) {
        String key = "scoring." + projectType + "." + role;
        return systemConfigRepository
                .findByConfigKey(key)
                .map(c -> {
                    try {
                        return Double.parseDouble(c.getConfigValue());
                    } catch (Exception e) {
                        return 25.0;
                    }
                })
                .orElse(25.0);
    }

    /** 当前系统设置中的角色权重（小数形式），用于历史评分重新核算。 */
    double weight(String projectType, String role) {
        return pct(projectType, role) / 100.0;
    }

    /** planner / sales / designer / admin 四个角色的权重（小数形式）。 */
    Map<String, Double> weightMap(String projectType) {
        Map<String, Double> weights = new HashMap<>();
        for (String role : List.of("planner", "sales", "designer", "admin")) {
            weights.put(role, pct(projectType, role) / 100.0);
        }
        return weights;
    }
}
