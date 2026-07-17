package com.emie.designpm.repository;

import com.emie.designpm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE " +
           "(:keyword IS NULL OR LOWER(u.userId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:role IS NULL OR u.role = :role) " +
           "AND (:status IS NULL OR u.status = :status OR (:status = 'active' AND (u.status IS NULL OR u.status = 'active'))) " +
           "ORDER BY u.createdAt DESC, u.id DESC")
    Page<User> searchPage(@Param("keyword") String keyword, @Param("role") String role,
                          @Param("status") String status, Pageable pageable);
    List<User> findByRole(String role);
    Optional<User> findByUserId(String userId);
    List<User> findByName(String name);
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByFeishuOpenId(String feishuOpenId);

    List<User> findByDepartmentId(Long departmentId);

    List<User> findByDepartmentIdAndRole(Long departmentId, String role);
}
