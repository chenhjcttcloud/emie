package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTaskDeliveryVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubTaskDeliveryVersionRepository extends JpaRepository<SubTaskDeliveryVersion, Long> {
    List<SubTaskDeliveryVersion> findBySubTaskIdOrderByVersionNoDesc(Long subTaskId);
    long countBySubTaskId(Long subTaskId);
}
