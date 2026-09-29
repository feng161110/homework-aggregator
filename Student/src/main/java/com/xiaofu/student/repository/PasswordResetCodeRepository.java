package com.xiaofu.student.repository;

import com.xiaofu.student.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    /** 取该用户最近一条未使用的重置验证码。 */
    Optional<PasswordResetCode> findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** 取该用户最近一条验证码（用于发送频率限制判断）。 */
    Optional<PasswordResetCode> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, Instant after);
}
