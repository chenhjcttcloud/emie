package com.emie.designpm.project.repository;

import com.emie.designpm.entity.ProjectCollaborator;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectCollaboratorRepository extends JpaRepository<ProjectCollaborator, Long> {
    List<ProjectCollaborator> findByProjectId(Long projectId);

    List<ProjectCollaborator> findByUserId(String userId);

    boolean existsByProjectIdAndUserId(Long projectId, String userId);
}
