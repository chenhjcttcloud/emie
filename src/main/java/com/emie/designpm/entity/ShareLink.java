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
@Table(name = "share_links", indexes = {
    @Index(name = "idx_share_token", columnList = "token", unique = true),
    @Index(name = "idx_share_creator", columnList = "createdBy")
})
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分享令牌（32位UUID，公开访问用） */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** 分享类型：project / sub_task */
    @Column(nullable = false, length = 50)
    private String targetType;

    /** 目标对象 ID */
    @Column(nullable = false)
    private Long targetId;

    /** 创建者用户ID */
    @Column(nullable = false, length = 100)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 过期时间，NULL=永不过期 */
    private LocalDateTime expiresAt;

    /** 访问密码（SHA256），NULL=无密码 */
    private String password;

    /** 最大查看次数，0=不限 */
    @Builder.Default
    private Integer maxViews = 0;

    /** 当前查看次数 */
    @Builder.Default
    private Integer viewCount = 0;

    /** active / expired / revoked */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "active";

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
