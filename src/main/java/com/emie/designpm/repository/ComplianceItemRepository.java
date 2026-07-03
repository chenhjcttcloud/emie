package com.emie.designpm.repository;

import com.emie.designpm.entity.ComplianceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComplianceItemRepository extends JpaRepository<ComplianceItem, Long> {

    List<ComplianceItem> findByActiveTrueOrderBySortOrderAsc();

    List<ComplianceItem> findAllByOrderBySortOrderAsc();

    Optional<ComplianceItem> findByName(String name);
}
