package com.emie.designpm.materialmarket.repository;

import com.emie.designpm.entity.MaterialMarketRating;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMarketRatingRepository extends JpaRepository<MaterialMarketRating, Long> {
    List<MaterialMarketRating> findByMaterialIdIn(Collection<Long> ids);

    Optional<MaterialMarketRating> findByMaterialIdAndUserId(Long materialId, String userId);

    void deleteAllByMaterialId(Long materialId);
}
