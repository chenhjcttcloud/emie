package com.emie.designpm.repository;

import com.emie.designpm.entity.DesignRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DesignRequirementRepository extends JpaRepository<DesignRequirement, Long> {
    @Query("SELECT d FROM DesignRequirement d WHERE " +
            "(:keyword IS NULL OR LOWER(d.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(COALESCE(d.requirementCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:status IS NULL OR d.status = :status) " +
            "AND (:userId IS NULL OR d.ownerId = :userId OR d.plannerId = :userId OR d.designerId = :userId) " +
            "ORDER BY d.createdAt DESC")
    Page<DesignRequirement> findPage(@Param("keyword") String keyword, @Param("status") String status,
                                     @Param("userId") String userId, Pageable pageable);
}
