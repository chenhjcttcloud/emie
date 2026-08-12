package com.emie.designpm.repository;
import com.emie.designpm.entity.PoPointProject; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface PoPointProjectRepository extends JpaRepository<PoPointProject,Long>{List<PoPointProject> findByOwnerUserIdOrderByIdDesc(String ownerUserId);}
