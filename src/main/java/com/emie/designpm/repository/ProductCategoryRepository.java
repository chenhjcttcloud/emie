package com.emie.designpm.repository;

import com.emie.designpm.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    List<ProductCategory> findByActiveTrueOrderBySortOrderAsc();

    List<ProductCategory> findAllByOrderBySortOrderAsc();

    java.util.Optional<ProductCategory> findByName(String name);
}
