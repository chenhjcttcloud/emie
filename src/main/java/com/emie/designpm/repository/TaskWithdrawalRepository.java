package com.emie.designpm.repository;
import com.emie.designpm.entity.TaskWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TaskWithdrawalRepository extends JpaRepository<TaskWithdrawal,Long> {
    List<TaskWithdrawal> findBySubTaskIdOrderByCreatedAtDesc(Long subTaskId);
}
