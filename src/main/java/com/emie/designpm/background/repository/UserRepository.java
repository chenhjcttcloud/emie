package com.emie.designpm.background.repository;
import com.emie.designpm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User, Long> {
 java.util.Optional<User> findByUserId(String userId);
 java.util.List<User> findByUserIdIn(java.util.Collection<String> ids);
}
