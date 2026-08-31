package com.emie.designpm.repository;

import com.emie.designpm.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

    interface IntegrityFileProjection {
        Long getId();
        String getStoredName();
    }

    @Query("SELECT f.id AS id, f.storedName AS storedName FROM FileRecord f " +
            "WHERE f.id > :afterId ORDER BY f.id ASC")
    List<IntegrityFileProjection> findIntegrityFilesAfter(@Param("afterId") Long afterId, Pageable pageable);

    Optional<FileRecord> findByStoredName(String storedName);

    Optional<FileRecord> findTopByOriginalNameOrderByCreatedAtDesc(String originalName);

    List<FileRecord> findByOriginalNameOrderByCreatedAtDesc(String originalName);

    List<FileRecord> findByTargetTypeOrderByCreatedAtDesc(String targetType);

    List<FileRecord> findByTargetTypeAndTargetId(String targetType, Long targetId);

    List<FileRecord> findByStorageTierAndCreatedAtBefore(String storageTier, LocalDateTime before);

    List<FileRecord> findByStorageTierOrderByCreatedAtDesc(String storageTier);

    long countByStorageTier(String storageTier);

    @Modifying
    @Query("DELETE FROM FileRecord f WHERE f.targetType = :targetType AND f.targetId IN :targetIds")
    void deleteByTargetTypeAndTargetIdIn(@Param("targetType") String targetType,
                                         @Param("targetIds") Collection<Long> targetIds);
}
