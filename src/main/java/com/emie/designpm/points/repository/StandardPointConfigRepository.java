package com.emie.designpm.points.repository;

import com.emie.designpm.entity.StandardPointConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StandardPointConfigRepository extends JpaRepository<StandardPointConfig, Long> {
    Optional<StandardPointConfig> findByConfigCode(String code);
}
