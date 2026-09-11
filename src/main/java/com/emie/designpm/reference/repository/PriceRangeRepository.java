package com.emie.designpm.reference.repository;

import com.emie.designpm.entity.PriceRange;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRangeRepository extends JpaRepository<PriceRange, Long> {
    List<PriceRange> findByActiveTrueOrderBySortOrderAsc();

    List<PriceRange> findAllByOrderBySortOrderAsc();

    Optional<PriceRange> findByName(String name);
}
