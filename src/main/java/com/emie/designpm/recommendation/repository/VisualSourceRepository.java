package com.emie.designpm.recommendation.repository;

import com.emie.designpm.entity.FileRecord;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/** Read-only view of source records for bounded/bulk analysis lookup. */
public interface VisualSourceRepository extends Repository<FileRecord, Long> {
    List<FileRecord> findByStoredNameIn(Collection<String> storedNames);

    List<FileRecord> findByTargetTypeOrderByCreatedAtDesc(String targetType);
}
