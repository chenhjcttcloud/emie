package com.emie.designpm.materialmarket.repository;

import com.emie.designpm.entity.MaterialMarketLike;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMarketLikeRepository extends JpaRepository<MaterialMarketLike, Long> {
    Optional<MaterialMarketLike> findByMaterialIdAndUserId(Long materialId, String userId);

    List<MaterialMarketLike> findByMaterialIdInAndUserId(Collection<Long> materialIds, String userId);

    void deleteAllByMaterialId(Long materialId);
}
