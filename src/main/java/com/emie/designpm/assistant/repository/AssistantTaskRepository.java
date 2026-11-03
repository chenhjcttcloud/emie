package com.emie.designpm.assistant.repository;

import com.emie.designpm.entity.SubTask;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AssistantTaskRepository extends Repository<SubTask, Long> {
    @Query("SELECT t FROM SubTask t JOIN FETCH t.project p WHERE (t.designerId = :userId OR t.publisherId = :userId) "
            + "AND t.status NOT IN ('completed','approved','sales_approved','admin_approved') "
            + "ORDER BY t.plannedDate ASC, t.id DESC")
    List<SubTask> findOwnOpenTasks(@Param("userId") String userId, Pageable pageable);
}
