//Bearer token 过滤器
package com.xiaofu.student.security;

import com.xiaofu.student.repository.RefreshTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;

    public JwtAuthenticationFilter(JwtService jwtService, RefreshTokenRepository refreshTokenRepository) {
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            jwtService.parseClaims(token)
                    .filter(this::isSessionActive)
                    .ifPresent(claims -> {
                        // principal = userId，credentials = sessionId（供设备管理识别当前设备）
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(claims.userId(), claims.sessionId(), List.of());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 校验登录会话是否仍然有效：access token 携带会话标识时，要求对应登录态未被撤销且未过期，
     * 使「设备下线 / 退出其它设备 / 修改密码」立即生效，而不必等 access token 自然过期。
     * 旧 token（本次改动前签发）不含会话标识，无法校验，保持放行。
     */
    private boolean isSessionActive(JwtService.TokenClaims claims) {
        String sessionId = claims.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        return refreshTokenRepository
                .findFirstByUserIdAndSessionIdAndRevokedAtIsNull(claims.userId(), sessionId)
                .map(token -> token.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }
}
