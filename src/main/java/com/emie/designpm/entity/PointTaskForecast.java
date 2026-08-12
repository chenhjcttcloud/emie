package com.emie.designpm.entity;
import jakarta.persistence.*;import lombok.Data;import lombok.NoArgsConstructor;import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="point_task_forecasts") public class PointTaskForecast{
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY)private Long id;private String monthKey;private String title;@Column(columnDefinition="TEXT")private String description;private String pointRuleCode;private Integer estimatedCount=1;private String status="DRAFT";private String publishedBy;private LocalDateTime publishedAt;private LocalDateTime createdAt;private LocalDateTime updatedAt;@PrePersist void c(){createdAt=updatedAt=LocalDateTime.now();}@PreUpdate void u(){updatedAt=LocalDateTime.now();}
}
