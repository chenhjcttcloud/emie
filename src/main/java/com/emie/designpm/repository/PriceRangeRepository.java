package com.emie.designpm.repository;

import com.emie.designpm.entity.PriceRange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PriceRangeRepository extends JpaRepository<PriceRange, Long> {
    List<PriceRange> findByActiveTrueOrderBySortOrderAsc();
    List<PriceRange> findAllByOrderBySortOrderAsc();
    Optional<PriceRange> findByName(String name);
}
