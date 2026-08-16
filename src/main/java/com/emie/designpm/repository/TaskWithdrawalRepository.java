package com.emie.designpm.repository;
import com.emie.designpm.entity.TaskWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
public interface TaskWithdrawalRepository extends JpaRepository<TaskWithdrawal,Long> {
    List<TaskWithdrawal> findBySubTaskIdOrderByCreatedAtDesc(Long subTaskId);

    @Query("SELECT t FROM TaskWithdrawal t WHERE t.subTaskId IN :subTaskIds")
    List<TaskWithdrawal> findBySubTaskIdIn(@Param("subTaskIds") Collection<Long> subTaskIds);

    @Modifying
    @Query("DELETE FROM TaskWithdrawal t WHERE t.subTaskId IN :subTaskIds")
    void deleteBySubTaskIds(@Param("subTaskIds") Collection<Long> subTaskIds);
}
