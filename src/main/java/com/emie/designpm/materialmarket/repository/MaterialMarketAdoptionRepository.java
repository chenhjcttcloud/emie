package com.emie.designpm.materialmarket.repository;

import com.emie.designpm.entity.MaterialMarketAdoption;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMarketAdoptionRepository extends JpaRepository<MaterialMarketAdoption, Long> {
    List<MaterialMarketAdoption> findByMaterialIdInOrderByCreatedAtDesc(Collection<Long> materialIds);

    List<MaterialMarketAdoption> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    boolean existsByMaterialId(Long materialId);

    boolean existsByMaterialIdAndAdoptionType(Long materialId, String adoptionType);

    void deleteAllByMaterialId(Long materialId);
}
