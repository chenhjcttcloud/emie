package com.emie.designpm.repository;

import com.emie.designpm.entity.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

    Optional<FileRecord> findByStoredName(String storedName);

    List<FileRecord> findByStorageTierAndCreatedAtBefore(String storageTier, LocalDateTime before);

    List<FileRecord> findByStorageTierOrderByCreatedAtDesc(String storageTier);

    long countByStorageTier(String storageTier);
}
