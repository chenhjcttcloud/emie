package com.emie.designpm.repository;

import com.emie.designpm.entity.DesignRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DesignRequirementRepository extends JpaRepository<DesignRequirement, Long> {
    interface IntegrityRequirementProjection {
        Long getId();
        String getReferenceImagesJson();
        String getAttachmentsJson();
        String getDeliveryReferenceImagesJson();
        String getDeliveryAttachmentsJson();
    }

    @Query("SELECT d.id AS id, d.referenceImagesJson AS referenceImagesJson, " +
            "d.attachmentsJson AS attachmentsJson, " +
            "d.deliveryReferenceImagesJson AS deliveryReferenceImagesJson, " +
            "d.deliveryAttachmentsJson AS deliveryAttachmentsJson FROM DesignRequirement d " +
            "WHERE d.id > :afterId ORDER BY d.id ASC")
    java.util.List<IntegrityRequirementProjection> findIntegrityRequirementsAfter(
            @Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT d FROM DesignRequirement d WHERE " +
            "(:keyword IS NULL OR LOWER(d.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(COALESCE(d.requirementCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:status IS NULL OR d.status = :status) " +
            "AND (:userId IS NULL OR d.ownerId = :userId OR d.plannerId = :userId OR d.designerId = :userId) " +
            "ORDER BY d.createdAt DESC")
    Page<DesignRequirement> findPage(@Param("keyword") String keyword, @Param("status") String status,
                                     @Param("userId") String userId, Pageable pageable);

    @Query("SELECT d FROM DesignRequirement d WHERE d.status NOT IN ('completed', 'terminated') " +
            "AND (:userId IS NULL OR d.ownerId = :userId OR d.responsibleId = :userId " +
            "OR d.plannerId = :userId OR d.designerId = :userId) " +
            "ORDER BY CASE WHEN d.status = 'rejected' THEN 0 WHEN d.status = 'pending_self_score' THEN 1 " +
            "WHEN d.status = 'pending_review' THEN 2 ELSE 3 END, d.deadline ASC, d.updatedAt DESC")
    java.util.List<DesignRequirement> findDashboardItems(@Param("userId") String userId);

    /**
     * 查询当前用户可见的设计/送审需求是否引用了指定文件。
     * 覆盖需求参考图、需求附件、交付参考图和交付附件，兼容历史未绑定 target 的上传记录。
     */
    @Query("SELECT COUNT(d) FROM DesignRequirement d WHERE " +
            "(d.ownerId = :userId OR d.responsibleId = :userId OR d.plannerId = :userId OR d.designerId = :userId) " +
            "AND (d.referenceImagesJson LIKE CONCAT('%', :storedName, '%') " +
            "OR d.attachmentsJson LIKE CONCAT('%', :storedName, '%') " +
            "OR d.deliveryReferenceImagesJson LIKE CONCAT('%', :storedName, '%') " +
            "OR d.deliveryAttachmentsJson LIKE CONCAT('%', :storedName, '%'))")
    long countVisibleFileReferences(@Param("userId") String userId,
                                    @Param("storedName") String storedName);
}
