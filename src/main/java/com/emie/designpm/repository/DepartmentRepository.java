package com.emie.designpm.repository;

import com.emie.designpm.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByActiveTrueOrderBySortOrderAsc();

    List<Department> findAllByOrderBySortOrderAsc();

    Optional<Department> findByHeadUserId(String headUserId);
}
