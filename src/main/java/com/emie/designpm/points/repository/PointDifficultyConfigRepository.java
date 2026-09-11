package com.emie.designpm.points.repository;

import com.emie.designpm.entity.PointDifficultyConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointDifficultyConfigRepository extends JpaRepository<PointDifficultyConfig, Long> {
    Optional<PointDifficultyConfig> findByDifficultyCode(String difficultyCode);

    List<PointDifficultyConfig> findAllByOrderByMultiplierAscDifficultyCodeAsc();
}
