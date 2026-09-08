package com.emie.designpm.repository;

import com.emie.designpm.entity.SubTaskRejectionCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubTaskRejectionCycleRepository extends JpaRepository<SubTaskRejectionCycle, Long> {
    Optional<SubTaskRejectionCycle> findFirstBySubTaskIdOrderBySequenceNoDesc(Long subTaskId);
    Optional<SubTaskRejectionCycle> findFirstBySubTaskIdAndStatusOrderBySequenceNoDesc(Long subTaskId, String status);
    List<SubTaskRejectionCycle> findBySubTaskIdInOrderBySubTaskIdAscSequenceNoAsc(Collection<Long> subTaskIds);
}
