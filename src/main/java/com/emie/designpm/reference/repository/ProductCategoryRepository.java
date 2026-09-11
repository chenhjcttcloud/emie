package com.emie.designpm.reference.repository;

import com.emie.designpm.entity.ProductCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    List<ProductCategory> findByActiveTrueOrderBySortOrderAsc();

    List<ProductCategory> findAllByOrderBySortOrderAsc();

    java.util.Optional<ProductCategory> findByName(String name);
}
