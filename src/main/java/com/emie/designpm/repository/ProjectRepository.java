package com.emie.designpm.repository;

import com.emie.designpm.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long>, ProjectSearchRepository {

    /** 列表页查询：仅加载当前页项目，避免首页渲染时读取全表。 */
    @EntityGraph(attributePaths = "productCategory")
    @Query("SELECT p FROM Project p WHERE (:type IS NULL OR p.type = :type) ORDER BY p.createdAt DESC")
    Page<Project> findPageLight(@Param("type") String type, Pageable pageable);

    @EntityGraph(attributePaths = "productCategory")
    @Query("SELECT p FROM Project p WHERE p.salesId IN :userIds AND (:type IS NULL OR p.type = :type) ORDER BY p.createdAt DESC")
    Page<Project> findBySalesIdsPage(@Param("userIds") List<String> userIds, @Param("type") String type, Pageable pageable);

    @EntityGraph(attributePaths = "productCategory")
    @Query("SELECT p FROM Project p WHERE (p.plannerId IN :userIds OR " +
            "(p.type = 'channel_custom' AND p.status = 'pending_planner' AND (p.plannerId IS NULL OR p.plannerId = ''))) " +
            "AND (:type IS NULL OR p.type = :type) ORDER BY p.createdAt DESC")
    Page<Project> findByPlannerIdsPage(@Param("userIds") List<String> userIds, @Param("type") String type, Pageable pageable);

    @EntityGraph(attributePaths = "productCategory")
    @Query(value = "SELECT DISTINCT p FROM Project p JOIN p.tasks t WHERE " +
            "((t.designerId IN :userIds) OR ((t.designerId IS NULL OR t.designerId = '') AND t.status = 'pending')) " +
            "AND (t.assigneeRole = :role OR (:role = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
            "AND (:type IS NULL OR p.type = :type) ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(DISTINCT p) FROM Project p JOIN p.tasks t WHERE " +
                    "((t.designerId IN :userIds) OR ((t.designerId IS NULL OR t.designerId = '') AND t.status = 'pending')) " +
                    "AND (t.assigneeRole = :role OR (:role = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
                    "AND (:type IS NULL OR p.type = :type)")
    Page<Project> findByAssigneeIdsPage(@Param("userIds") List<String> userIds, @Param("role") String role,
                                        @Param("type") String type, Pageable pageable);

    @EntityGraph(attributePaths = "productCategory")
    @Query(value = "SELECT DISTINCT p FROM Project p JOIN p.tasks t WHERE t.designerId IN :userIds AND t.status <> 'pending' " +
            "AND (t.assigneeRole = :role OR (:role = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
            "AND (:type IS NULL OR p.type = :type) ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(DISTINCT p) FROM Project p JOIN p.tasks t WHERE t.designerId IN :userIds AND t.status <> 'pending' " +
                    "AND (t.assigneeRole = :role OR (:role = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
                    "AND (:type IS NULL OR p.type = :type)")
    Page<Project> findParticipatingByAssigneeIdsPage(@Param("userIds") List<String> userIds, @Param("role") String role,
                                                     @Param("type") String type, Pageable pageable);

    List<Project> findByTypeOrderByCreatedAtDesc(String type);

    List<Project> findByIdInAndType(List<Long> ids, String type);

    List<Project> findByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT p FROM Project p WHERE p.type = ?1 AND p.status = ?2 ORDER BY p.createdAt DESC")
    List<Project> findByTypeAndStatus(String type, String status);

    /** 销售：查看自己发布的全部项目（渠道定制 + 公司常规品），JOIN FETCH 预加载子任务 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks WHERE p.salesId = ?1 ORDER BY p.createdAt DESC")
    List<Project> findBySalesId(String salesId);

    /** 企划：查看指派给自己的项目 + 未指定企划的渠道定制单 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks WHERE p.plannerId = ?1 OR (p.type = 'channel_custom' AND p.status = 'pending_planner' AND (p.plannerId IS NULL OR p.plannerId = '')) ORDER BY p.createdAt DESC")
    List<Project> findByPlannerView(String plannerId);

    /** 执行角色视图：仅包含本角色待认领或已指派给当前用户的子任务项目。空角色按历史设计师任务兼容。 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks t WHERE " +
            "((t.designerId = ?1) OR ((t.designerId IS NULL OR t.designerId = '') AND t.status = 'pending')) " +
            "AND (t.assigneeRole = ?2 OR (?2 = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
            "ORDER BY p.createdAt DESC")
    List<Project> findByAssigneeView(String userId, String assigneeRole);

    /** 销售：查看自己发布的全部项目（无 JOIN FETCH，配合计数查询优化） */
    @Query("SELECT p FROM Project p WHERE p.salesId = ?1 ORDER BY p.createdAt DESC")
    List<Project> findBySalesIdLight(String salesId);

    /** 企划视图：查看指派给自己的项目及待认领的渠道定制单（无 JOIN FETCH） */
    @Query("SELECT p FROM Project p WHERE p.plannerId = ?1 OR (p.type = 'channel_custom' AND p.status = 'pending_planner' AND (p.plannerId IS NULL OR p.plannerId = '')) ORDER BY p.createdAt DESC")
    List<Project> findByPlannerViewLight(String plannerId);

    /** 状态看板批量读取销售项目，避免按用户逐个查询。 */
    @Query("SELECT p FROM Project p WHERE p.salesId IN :userIds ORDER BY p.createdAt DESC")
    List<Project> findBySalesIdsLight(@Param("userIds") List<String> userIds);

    /** 状态看板批量读取企划项目，并保留待认领的渠道定制单。 */
    @Query("SELECT p FROM Project p WHERE p.plannerId IN :userIds OR " +
            "(p.type = 'channel_custom' AND p.status = 'pending_planner' AND (p.plannerId IS NULL OR p.plannerId = '')) " +
            "ORDER BY p.createdAt DESC")
    List<Project> findByPlannerIdsLight(@Param("userIds") List<String> userIds);

    /** 执行角色视图（无 JOIN FETCH） */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN p.tasks t WHERE " +
            "((t.designerId = ?1) OR ((t.designerId IS NULL OR t.designerId = '') AND t.status = 'pending')) " +
            "AND (t.assigneeRole = ?2 OR (?2 = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
            "ORDER BY p.createdAt DESC")
    List<Project> findByAssigneeViewLight(String userId, String assigneeRole);

    /** 悲观锁：企划接单时锁定项目行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = ?1")
    Optional<Project> findByIdForUpdate(Long id);

    /** 执行角色参与的（已接单/进行中/已完成）项目 — 无 JOIN FETCH */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN p.tasks t WHERE t.designerId = ?1 AND t.status != 'pending' " +
            "AND (t.assigneeRole = ?2 OR (?2 = 'designer' AND (t.assigneeRole IS NULL OR t.assigneeRole = ''))) " +
            "ORDER BY p.createdAt DESC")
    List<Project> findParticipatingByAssigneeLight(String userId, String assigneeRole);

    /** 全部项目（管理员）— 无 JOIN FETCH */
    @Query("SELECT p FROM Project p ORDER BY p.createdAt DESC")
    List<Project> findAllLight();

    /** 全部项目（管理员/上级），预加载子任务 */
    @Query("SELECT DISTINCT p FROM Project p LEFT JOIN FETCH p.tasks ORDER BY p.createdAt DESC")
    List<Project> findAllWithTasks();

    /** 对账只需要业务 ID，按主键分页读取，避免把整张项目表实体加载进内存。 */
    @Query("SELECT p.id FROM Project p WHERE p.id > :afterId ORDER BY p.id ASC")
    List<Long> findIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT p.id FROM Project p WHERE p.updatedAt > :after AND p.updatedAt <= :until ORDER BY p.updatedAt ASC, p.id ASC")
    List<Long> findIdsUpdatedBetween(@Param("after") LocalDateTime after, @Param("until") LocalDateTime until, Pageable pageable);
}
