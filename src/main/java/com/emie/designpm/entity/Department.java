package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 部门名称，如「设计一部」「销售部」 */
    @Column(nullable = false)
    private String name;

    /** 关联角色：sales/planner/designer/supplychain */
    @Column(nullable = false)
    private String role;

    /** 部门负责人的 userId */
    private String headUserId;

    /** 排序 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 是否启用 */
    @Builder.Default
    private Boolean active = true;
}
