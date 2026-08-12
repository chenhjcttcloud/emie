package com.emie.designpm.repository;
import com.emie.designpm.entity.StandardPointConfig; import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional;
public interface StandardPointConfigRepository extends JpaRepository<StandardPointConfig,Long>{ Optional<StandardPointConfig> findByConfigCode(String code); }
