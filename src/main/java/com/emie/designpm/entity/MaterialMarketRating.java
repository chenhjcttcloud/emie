package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
        name = "material_market_ratings",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_material_rating_user",
                        columnNames = {"material_id", "user_id"}))
public class MaterialMarketRating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long materialId;

    @Column(nullable = false, length = 100)
    private String userId;

    @Column(nullable = false)
    private Integer stars;
}
