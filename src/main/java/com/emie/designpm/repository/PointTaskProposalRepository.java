package com.emie.designpm.repository;import com.emie.designpm.entity.PointTaskProposal;import org.springframework.data.jpa.repository.JpaRepository;import java.util.List;
public interface PointTaskProposalRepository extends JpaRepository<PointTaskProposal,Long>{List<PointTaskProposal>findByApplicantUserIdOrderByCreatedAtDesc(String userId);List<PointTaskProposal>findAllByOrderByCreatedAtDesc();}
