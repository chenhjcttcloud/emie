package com.emie.designpm.repository;

import com.emie.designpm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByRole(String role);
    Optional<User> findByUserId(String userId);
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
}
