package com.emie.designpm.recommendation.repository;

import com.emie.designpm.entity.TaskImageLibraryLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskImageLibraryLinkRepository extends JpaRepository<TaskImageLibraryLink, Long> {
    List<TaskImageLibraryLink> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    boolean existsByTaskIdAndLibraryItemId(Long taskId, Long libraryItemId);

    long countByTaskId(Long taskId);
}
