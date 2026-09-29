//平台登录凭证仓储
package com.xiaofu.student.repository;

import com.xiaofu.student.entity.PlatformAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformAccountRepository extends JpaRepository<PlatformAccount, Long> {

    Optional<PlatformAccount> findByUserIdAndPlatform(Long userId, String platform);

    List<PlatformAccount> findByUserId(Long userId);
}
