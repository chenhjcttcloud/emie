package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色标识符（唯一，如 project_manager） */
    @Column(nullable = false, unique = true)
    private String name;

    /** 显示名称（如 项目经理） */
    @Column(nullable = false)
    private String displayName;

    /** 角色描述 */
    private String description;

    /** 权限列表 JSON 数组，如 ["project:view","task:view","admin:config"] */
    @Column(columnDefinition = "TEXT")
    private String permissions;

    /** 系统内置角色（不可删除） */
    @Builder.Default
    @Column(nullable = false)
    private Boolean isSystem = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
