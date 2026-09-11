package com.emie.designpm.reference.repository;

import com.emie.designpm.entity.IpOption;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpOptionRepository extends JpaRepository<IpOption, Long> {
    List<IpOption> findByActiveTrueOrderBySortOrderAsc();

    List<IpOption> findAllByOrderBySortOrderAsc();

    Optional<IpOption> findByName(String name);

    Optional<IpOption> findTopByOrderBySortOrderDesc();
}
