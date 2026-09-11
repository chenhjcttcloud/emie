package com.emie.designpm.reference.repository;

import com.emie.designpm.entity.Department;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByActiveTrueOrderBySortOrderAsc();

    List<Department> findAllByOrderBySortOrderAsc();

    Optional<Department> findByHeadUserId(String headUserId);
}
