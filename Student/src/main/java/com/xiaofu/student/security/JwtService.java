//JWT签发与解析
package com.xiaofu.student.security;

import com.xiaofu.student.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-token-ttl-seconds}") long accessTokenTtlSeconds,
                      @Value("${jwt.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    /**
     * 签发 access token，并附带本次登录会话标识（sid），用于识别「当前设备」。
     *
     * @param user      登录用户
     * @param sessionId 登录会话标识，可为空（为空时不写入 sid 声明）
     */
    public String generateAccessToken(User user, String sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.claim("sid", sessionId);
        }
        return builder.signWith(key).compact();
    }

    public Optional<TokenClaims> parseClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new TokenClaims(
                    Long.valueOf(claims.getSubject()),
                    claims.get("sid", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 解析后的令牌信息：用户 ID 与登录会话标识（旧令牌可能没有 sessionId）。 */
    public record TokenClaims(Long userId, String sessionId) {
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }
}