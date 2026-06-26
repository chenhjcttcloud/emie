package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SubTaskRepository extends JpaRepository<SubTask, Long> {

    List<SubTask> findByDesignerId(String designerId);

    List<SubTask> findByDesignerIdOrderByCreatedAtDesc(String designerId);

    List<SubTask> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<SubTask> findByStatusAndDesignerId(String status, String designerId);

    /** 悲观锁：设计师接单时锁定子任务行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SubTask t WHERE t.id = ?1")
    Optional<SubTask> findByIdForUpdate(Long id);
}
