package com.emie.designpm.repository;

import com.emie.designpm.entity.MaterialMarketAdoption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MaterialMarketAdoptionRepository extends JpaRepository<MaterialMarketAdoption, Long> {
    List<MaterialMarketAdoption> findByMaterialIdInOrderByCreatedAtDesc(Collection<Long> materialIds);
    List<MaterialMarketAdoption> findByMaterialIdOrderByCreatedAtDesc(Long materialId);
    boolean existsByMaterialId(Long materialId);
    boolean existsByMaterialIdAndAdoptionType(Long materialId, String adoptionType);
    void deleteAllByMaterialId(Long materialId);
}
