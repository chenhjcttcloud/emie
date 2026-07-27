package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "role_permission_scopes",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_permission_scope",
                columnNames = {"role_permission_id", "scope_type", "scope_value"}),
        indexes = @Index(name = "idx_role_permission_scope_assignment", columnList = "role_permission_id"))
public class RolePermissionScope {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_permission_id", nullable = false)
    private RolePermission rolePermission;

    @Column(name = "scope_type", nullable = false, length = 40)
    private String scopeType;

    @Column(name = "scope_value", nullable = false, length = 255)
    private String scopeValue = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
