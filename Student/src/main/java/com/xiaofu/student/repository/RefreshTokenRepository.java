package com.xiaofu.student.repository;

import com.xiaofu.student.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 按登录会话标识查询未撤销的登录态（用于识别「当前设备」）。 */
    Optional<RefreshToken> findFirstByUserIdAndSessionIdAndRevokedAtIsNull(Long userId, String sessionId);

    /** 查询用户当前有效的登录设备（未撤销且未过期），按最近活跃时间倒序。 */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(
            Long userId, Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /** 某个用户的全部登录记录（含已撤销 / 已过期），最近活跃在前 —— 管理后台看登录历史用。 */
    List<RefreshToken> findByUserIdOrderByLastUsedAtDesc(Long userId);

    /** 一批用户的登录记录（管理后台列表页一次查完，避免逐用户查询）。 */
    List<RefreshToken> findByUserIdInOrderByLastUsedAtDesc(Collection<Long> userIds);

    /** 当前在线设备总数（未撤销且未过期）—— 管理后台顶部统计用。 */
    long countByRevokedAtIsNullAndExpiresAtAfter(Instant now);

    /**
     * 某个用户当前真正在线的设备数。
     * 踢下线时用它算提示文案：revokeAllByUserId 会把「已过期但没撤销」的历史记录也一起标记，
     * 直接拿它的返回值报数会虚高（说踢了 5 台，其实只有 2 台在线）。
     */
    long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(Long userId, Instant now);

    /** 撤销该用户除指定设备外的所有登录态（用于「退出其它设备」）。 */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.userId = :userId and t.revokedAt is null and t.id <> :excludeId")
    int revokeAllExceptId(@Param("userId") Long userId, @Param("excludeId") Long excludeId,
                          @Param("now") Instant now);
}
