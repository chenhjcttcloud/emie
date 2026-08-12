package com.emie.designpm.repository;

import com.emie.designpm.entity.PointRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PointRuleRepository extends JpaRepository<PointRule, Long> {
    Optional<PointRule> findByRuleCode(String ruleCode);
    List<PointRule> findAllByOrderByRuleCodeAsc();
}
