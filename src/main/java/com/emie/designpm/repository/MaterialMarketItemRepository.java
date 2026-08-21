package com.emie.designpm.repository;
import com.emie.designpm.entity.MaterialMarketItem;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface MaterialMarketItemRepository extends JpaRepository<MaterialMarketItem,Long>{
 interface IntegrityMaterialProjection { Long getId(); String getMaterialFilesJson(); String getReferenceImagesJson(); }
 List<MaterialMarketItem> findAllByOrderByCreatedAtDesc();
 @Query("select m.id as id, m.materialFilesJson as materialFilesJson, m.referenceImagesJson as referenceImagesJson from MaterialMarketItem m where m.id > :afterId order by m.id asc")
 List<IntegrityMaterialProjection> findIntegrityMaterialsAfter(@org.springframework.data.repository.query.Param("afterId") Long afterId, org.springframework.data.domain.Pageable pageable);
 @Query("select count(m) from MaterialMarketItem m where m.materialFilesJson like concat('%', :storedName, '%') or m.referenceImagesJson like concat('%', :storedName, '%')")
 long countFileReferencesByStoredName(@org.springframework.data.repository.query.Param("storedName") String storedName);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from MaterialMarketItem m where m.id=:id") Optional<MaterialMarketItem> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
