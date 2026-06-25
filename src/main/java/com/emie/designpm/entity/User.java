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
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用的用户ID（唯一，如 sales_sun） */
    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role; // sales, planner, designer, superior, admin

    /** 角色权限等级 0=系统管理员 1=销售 2=产品企划 3=设计师 */
    private Integer roleLevel;

    private String title;

    /** 登录密码 */
    private String password;

    /** 手机号 */
    @Column(unique = true)
    private String phone;

    /** 邮箱 */
    @Column(unique = true)
    private String email;

    /** 账号状态：active / disabled */
    @Builder.Default
    @Column(nullable = false)
    private String status = "active";

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
