package com.emie.designpm.entity;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="po_point_projects")
public class PoPointProject {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=200) private String name;
 @Column(name="owner_user_id",nullable=false,length=100) private String ownerUserId;
 @Column(name="owner_name",nullable=false,length=100) private String ownerName;
 @Column(name="monthly_points",nullable=false) private Integer monthlyPoints;
 @Column(nullable=false) private Boolean enabled=true;
 @Column(name="created_by",nullable=false,length=100) private String createdBy;
 @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
 @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
 @PrePersist void c(){createdAt=updatedAt=LocalDateTime.now();} @PreUpdate void u(){updatedAt=LocalDateTime.now();}
}
