package com.emie.designpm.project.repository;

import com.emie.designpm.entity.ProjectWorkflowAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectWorkflowAttemptRepository extends JpaRepository<ProjectWorkflowAttempt, Long> {
    List<ProjectWorkflowAttempt> findByProjectIdOrderByIdAsc(Long projectId);

    long countByProjectIdAndStageKey(Long projectId, String stageKey);

    Optional<ProjectWorkflowAttempt> findFirstByProjectIdAndStageKeyOrderByAttemptNoDesc(
            Long projectId, String stageKey);
}
