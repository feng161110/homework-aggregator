package com.xiaofu.student.repository;

import com.xiaofu.student.entity.User;
import com.xiaofu.student.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    /** 管理后台顶部统计用。 */
    long countByStatus(UserStatus status);
}