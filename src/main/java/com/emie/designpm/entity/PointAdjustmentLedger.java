package com.emie.designpm.entity;
import jakarta.persistence.*;import lombok.*;import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="point_adjustment_ledgers",uniqueConstraints=@UniqueConstraint(name="uk_point_adjustment_source",columnNames={"source_type","source_id"}))
public class PointAdjustmentLedger {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false,length=100) private String userId;
 @Column(name="source_type",nullable=false,length=40) private String sourceType;
 @Column(name="source_id",nullable=false) private Long sourceId;
 @Column(nullable=false) private Integer points;
 @Column(nullable=false,length=500) private String reason;
 @Column(name="created_by",nullable=false,length=100) private String createdBy;
 @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
 @PrePersist void c(){if(createdAt==null)createdAt=LocalDateTime.now();}
}
