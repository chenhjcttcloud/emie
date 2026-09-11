package com.emie.designpm.points.repository;

import com.emie.designpm.entity.PoPointProject;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoPointProjectRepository extends JpaRepository<PoPointProject, Long> {
    List<PoPointProject> findByOwnerUserIdOrderByIdDesc(String ownerUserId);
}
