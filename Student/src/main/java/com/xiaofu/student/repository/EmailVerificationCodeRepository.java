package com.xiaofu.student.repository;

import com.xiaofu.student.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    /** 取该用户最近一条未使用的验证码。 */
    Optional<EmailVerificationCode> findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** 取该用户最近一条验证码（用于发送频率限制判断）。 */
    Optional<EmailVerificationCode> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, Instant after);
}
