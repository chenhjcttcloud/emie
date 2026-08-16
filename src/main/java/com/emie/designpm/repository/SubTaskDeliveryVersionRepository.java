package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTaskDeliveryVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SubTaskDeliveryVersionRepository extends JpaRepository<SubTaskDeliveryVersion, Long> {
    List<SubTaskDeliveryVersion> findBySubTaskIdOrderByVersionNoDesc(Long subTaskId);
    long countBySubTaskId(Long subTaskId);

    @Query("SELECT COALESCE(MAX(v.versionNo), 0) FROM SubTaskDeliveryVersion v WHERE v.subTask.id = :subTaskId")
    int findMaxVersionNoBySubTaskId(@Param("subTaskId") Long subTaskId);

    @Modifying
    @Query("DELETE FROM SubTaskDeliveryVersion v WHERE v.subTask.id IN :subTaskIds")
    void deleteBySubTaskIds(@Param("subTaskIds") Collection<Long> subTaskIds);
}
