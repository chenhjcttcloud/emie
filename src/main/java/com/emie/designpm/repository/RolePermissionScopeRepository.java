package com.emie.designpm.repository;

import com.emie.designpm.entity.RolePermissionScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RolePermissionScopeRepository extends JpaRepository<RolePermissionScope, Long> {

    @Query("""
            select distinct scope.scopeType
            from RolePermissionScope scope
            join scope.rolePermission assignment
            where lower(assignment.role.name) = lower(:roleName)
              and assignment.permission.code = :permissionCode
              and assignment.effect = 'allow'
              and assignment.permission.enabled = true
            order by scope.scopeType
            """)
    List<String> findScopeTypes(@Param("roleName") String roleName,
                                @Param("permissionCode") String permissionCode);

    @Query("""
            select scope
            from RolePermissionScope scope
            join fetch scope.rolePermission assignment
            join fetch assignment.permission
            where assignment.role.id = :roleId
            """)
    List<RolePermissionScope> findByRoleId(@Param("roleId") Long roleId);
}
