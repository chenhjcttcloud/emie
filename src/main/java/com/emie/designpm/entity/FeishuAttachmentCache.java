package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统文件到指定飞书 Base 附件 token 的持久化映射，避免全量对账重复上传。 */
@Data
@Entity
@Table(name = "feishu_attachment_cache", uniqueConstraints =
        @UniqueConstraint(name = "uk_feishu_attachment_base_file", columnNames = {"app_token", "stored_name"}))
public class FeishuAttachmentCache {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_token", nullable = false, length = 100)
    private String appToken;

    @Column(name = "stored_name", nullable = false, length = 500)
    private String storedName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "modified_millis", nullable = false)
    private Long modifiedMillis;

    @Column(name = "file_token", nullable = false, length = 255)
    private String fileToken;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
