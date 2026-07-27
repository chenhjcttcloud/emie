package com.emie.designpm.repository;

import com.emie.designpm.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRoleId(Long roleId);

    void deleteByRoleId(Long roleId);

    @Query("""
            select rp.permission.code
            from RolePermission rp
            where rp.role.name = :roleName
              and rp.effect = 'allow'
              and rp.permission.enabled = true
            order by rp.permission.code
            """)
    List<String> findAllowedPermissionCodes(@Param("roleName") String roleName);

    @Query("""
            select rp.permission.code
            from RolePermission rp
            where rp.role.name = :roleName
              and rp.effect = 'deny'
              and rp.permission.enabled = true
            order by rp.permission.code
            """)
    List<String> findDeniedPermissionCodes(@Param("roleName") String roleName);
}
