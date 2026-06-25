package com.emie.designpm.repository;

import com.emie.designpm.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByTypeOrderByCreatedAtDesc(String type);

    List<Project> findByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT p FROM Project p WHERE p.type = ?1 AND p.status = ?2 ORDER BY p.createdAt DESC")
    List<Project> findByTypeAndStatus(String type, String status);

    /** 销售：查看自己发布的全部项目（渠道定制 + 公司常规品），JOIN FETCH 预加载子任务 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks WHERE p.salesId = ?1 ORDER BY p.createdAt DESC")
    List<Project> findBySalesId(String salesId);

    /** 企划：查看指派给自己的项目 + 未指定企划的渠道定制单 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks WHERE p.plannerId = ?1 OR (p.type = 'channel_custom' AND (p.plannerId IS NULL OR p.plannerId = '')) ORDER BY p.createdAt DESC")
    List<Project> findByPlannerView(String plannerId);

    /** 设计师视图：包含待认领 + 已指派子任务的项目 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks t WHERE t.designerId = ?1 OR t.designerId IS NULL OR t.designerId = '' ORDER BY p.createdAt DESC")
    List<Project> findByDesignerId(String designerId);

    /** 悲观锁：企划接单时锁定项目行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = ?1")
    Optional<Project> findByIdForUpdate(Long id);

    /** 设计师参与的（已接单/进行中/已完成）项目 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks t WHERE t.designerId = ?1 AND t.status != 'pending' ORDER BY p.createdAt DESC")
    List<Project> findParticipatingByDesignerId(String designerId);

    /** 全部项目（管理员/上级），预加载子任务 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks ORDER BY p.createdAt DESC")
    List<Project> findAllWithTasks();
}
