package com.emie.designpm.repository;

import com.emie.designpm.entity.PointDifficultyConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PointDifficultyConfigRepository extends JpaRepository<PointDifficultyConfig, Long> {
    Optional<PointDifficultyConfig> findByDifficultyCode(String difficultyCode);
    List<PointDifficultyConfig> findAllByOrderByMultiplierAscDifficultyCodeAsc();
}
