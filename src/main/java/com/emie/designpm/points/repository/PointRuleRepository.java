package com.emie.designpm.points.repository;

import com.emie.designpm.entity.PointRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointRuleRepository extends JpaRepository<PointRule, Long> {
    Optional<PointRule> findByRuleCode(String ruleCode);

    List<PointRule> findAllByOrderByRuleCodeAsc();
}
