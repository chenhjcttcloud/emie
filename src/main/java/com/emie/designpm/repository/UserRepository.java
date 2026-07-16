package com.emie.designpm.repository;

import com.emie.designpm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
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
