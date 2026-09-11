package com.emie.designpm.project.repository;

import com.emie.designpm.entity.SubTaskRejectionCycle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubTaskRejectionCycleRepository extends JpaRepository<SubTaskRejectionCycle, Long> {
    Optional<SubTaskRejectionCycle> findFirstBySubTaskIdOrderBySequenceNoDesc(Long subTaskId);

    Optional<SubTaskRejectionCycle> findFirstBySubTaskIdAndStatusOrderBySequenceNoDesc(Long subTaskId, String status);

    List<SubTaskRejectionCycle> findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(Collection<Long> subTaskIds);
}
