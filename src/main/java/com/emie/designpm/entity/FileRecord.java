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
@Table(name = "file_records", indexes = {
    @Index(name = "idx_file_tier", columnList = "storageTier"),
    @Index(name = "idx_file_created", columnList = "createdAt"),
    @Index(name = "idx_file_stored", columnList = "storedName"),
    @Index(name = "idx_file_owner", columnList = "ownerUserId")
})
public class FileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 磁盘文件名，如 uuid.png */
    @Column(nullable = false, length = 255)
    private String storedName;

    /** 原始文件名 */
    @Column(nullable = false, length = 500)
    private String originalName;

    /** 文件大小（字节） */
    @Column(nullable = false)
    private Long fileSize;

    /** MIME 类型 */
    @Column(length = 100)
    private String mimeType;

    /** 关联的目标类型（project / sub_task / admin），方便追踪 */
    @Column(length = 50)
    private String targetType;

    /** 关联的目标 ID */
    private Long targetId;

    /** 上传者账号，用于文件尚未绑定业务对象时限制预览权限 */
    @Column(length = 100)
    private String ownerUserId;

    /**
     * 存储层级：
     * local    - 本地磁盘
     * archived - 已归档到 NAS
     * restoring - 正在从 NAS 恢复
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String storageTier = "local";

    /** NAS 归档路径 */
    @Column(length = 1000)
    private String archivePath;

    /** 压缩后大小（字节） */
    private Long archiveSize;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 归档时间 */
    private LocalDateTime archivedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
