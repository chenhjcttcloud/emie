package com.emie.designpm.repository;

import com.emie.designpm.entity.MaterialMarketLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaterialMarketLikeRepository extends JpaRepository<MaterialMarketLike, Long> {
    Optional<MaterialMarketLike> findByMaterialIdAndUserId(Long materialId, String userId);
    List<MaterialMarketLike> findByMaterialIdInAndUserId(Collection<Long> materialIds, String userId);
    void deleteAllByMaterialId(Long materialId);
}
