package com.emie.designpm.compliance.repository;

import com.emie.designpm.entity.ComplianceItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceItemRepository extends JpaRepository<ComplianceItem, Long> {

    List<ComplianceItem> findByActiveTrueOrderBySortOrderAsc();

    List<ComplianceItem> findAllByOrderBySortOrderAsc();

    Optional<ComplianceItem> findByName(String name);
}
