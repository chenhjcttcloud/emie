package com.emie.designpm.repository;
import com.emie.designpm.entity.DesignerMarketEligibility; import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional;
public interface DesignerMarketEligibilityRepository extends JpaRepository<DesignerMarketEligibility,Long>{Optional<DesignerMarketEligibility> findByUserId(String userId);}
