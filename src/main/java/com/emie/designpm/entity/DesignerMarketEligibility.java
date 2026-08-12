package com.emie.designpm.entity;
import jakarta.persistence.*; import lombok.Data; import lombok.NoArgsConstructor; import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="designer_market_eligibility")
public class DesignerMarketEligibility {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="user_id",nullable=false,unique=true,length=100) private String userId;
 @Column(name="suspended_until") private LocalDateTime suspendedUntil;
 @Column(length=500) private String reason;
 @Column(name="violation_count",nullable=false) private Integer violationCount=0;
 @Column(name="updated_by",length=100) private String updatedBy;
 @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
 @PrePersist @PreUpdate void update(){updatedAt=LocalDateTime.now();}
 public boolean isSuspended(){return suspendedUntil!=null&&suspendedUntil.isAfter(LocalDateTime.now());}
}
