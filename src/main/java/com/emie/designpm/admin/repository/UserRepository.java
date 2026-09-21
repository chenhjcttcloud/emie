package com.emie.designpm.admin.repository;

import com.emie.designpm.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query(
            "SELECT u FROM User u WHERE "
                    + "(:keyword IS NULL OR LOWER(u.userId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.feishuUserId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.feishuOpenId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
                    + "AND (:role IS NULL OR u.role = :role) "
                    + "AND (:status IS NULL OR u.status = :status OR (:status = 'active' AND (u.status IS NULL OR u.status = 'active')))")
    // 排序唔再写死：由 Pageable 带 Sort 入嚟，畀前端撳表头切换
    Page<User> searchPage(
            @Param("keyword") String keyword,
            @Param("role") String role,
            @Param("status") String status,
            Pageable pageable);

    List<User> findByRole(String role);

    Optional<User> findByUserId(String userId);

    List<User> findByUserIdIn(Collection<String> userIds);

    List<User> findByName(String name);

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByFeishuOpenId(String feishuOpenId);

    List<User> findByDepartmentId(Long departmentId);

    List<User> findByDepartmentIdAndRole(Long departmentId, String role);
}
