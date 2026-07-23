package com.emie.designpm.repository;

import com.emie.designpm.entity.ProjectWorkflowAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectWorkflowAttemptRepository extends JpaRepository<ProjectWorkflowAttempt, Long> {
    List<ProjectWorkflowAttempt> findByProjectIdOrderByIdAsc(Long projectId);
    long countByProjectIdAndStageKey(Long projectId, String stageKey);
    Optional<ProjectWorkflowAttempt> findFirstByProjectIdAndStageKeyOrderByAttemptNoDesc(
            Long projectId, String stageKey);
}
