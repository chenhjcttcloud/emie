package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);

    Optional<Role> findByNameIgnoreCase(String name);

    boolean existsByName(String name);
}
