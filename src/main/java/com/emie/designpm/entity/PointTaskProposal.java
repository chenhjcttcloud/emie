package com.emie.designpm.entity;
import jakarta.persistence.*;import lombok.Data;import lombok.NoArgsConstructor;import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="point_task_proposals") public class PointTaskProposal{
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY)private Long id;private Long projectId;private String applicantUserId;private String applicantName;private String title;@Column(columnDefinition="TEXT")private String description;@Column(columnDefinition="LONGTEXT")private String referenceImagesJson;private String pointRuleCode;private String difficultyCode;private String plannedDate;private String status="SUBMITTED";private String reviewComment;private String reviewedBy;private LocalDateTime reviewedAt;private Long createdTaskId;private LocalDateTime createdAt;private LocalDateTime updatedAt;@PrePersist void c(){createdAt=updatedAt=LocalDateTime.now();}@PreUpdate void u(){updatedAt=LocalDateTime.now();}
}
