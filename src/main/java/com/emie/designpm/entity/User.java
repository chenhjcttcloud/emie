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
@Table(name = "users", indexes = {
    @Index(name = "idx_user_role", columnList = "role"),
    @Index(name = "idx_user_department", columnList = "departmentId"),
    @Index(name = "idx_user_status", columnList = "status")
})
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
    private String role; // sales, planner, designer, supplychain, admin

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

    /** 飞书 OpenID（SSO 关联用） */
    private String feishuOpenId;

    /** 所属部门 ID */
    private Long departmentId;

    /** 直属上级 userId */
    private String supervisorId;

    /** 职级：0=组员 1=组长 2=部门负责人 */
    @Builder.Default
    private Integer titleLevel = 0;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
