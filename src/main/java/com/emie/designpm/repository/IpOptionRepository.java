package com.emie.designpm.repository;

import com.emie.designpm.entity.IpOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IpOptionRepository extends JpaRepository<IpOption, Long> {
    List<IpOption> findByActiveTrueOrderBySortOrderAsc();
    List<IpOption> findAllByOrderBySortOrderAsc();
    Optional<IpOption> findByName(String name);
}
