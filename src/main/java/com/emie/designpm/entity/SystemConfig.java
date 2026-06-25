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
@Table(name = "system_configs")
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置键，如 smtp.host, app.logo, login.bg */
    @Column(nullable = false, unique = true)
    private String configKey;

    /** 配置值（TEXT 类型支持长文本） */
    @Column(columnDefinition = "TEXT")
    private String configValue;

    /** 配置分组：smtp / appearance / security / system / email */
    @Column(nullable = false)
    private String configGroup;

    /** 配置描述 */
    private String description;

    /** 值类型：text / password / image / number / boolean */
    private String valueType;

    /** 排序序号 */
    @Builder.Default
    private Integer sortOrder = 0;

    private LocalDateTime updatedAt;

    private String updatedBy;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
