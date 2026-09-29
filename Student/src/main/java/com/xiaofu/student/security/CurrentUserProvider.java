//当前登录用户解析
package com.xiaofu.student.security;

import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.entity.User;
import com.xiaofu.student.entity.UserStatus;
import com.xiaofu.student.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 的 Authentication 里取出当前用户实体。
 *
 * <p>JWT 过滤器把 principal 设成了 userId（Long），所以这里要回库查一次
 * —— 作业提醒需要用到 isAdmin 和 email，光有 id 不够。
 */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public User require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        /*
         * 被管理员禁用的账号：这里统一按「登录态失效」处理（而不是 403），
         * 前端收到 401 + UNAUTHORIZED 会自动清登录态并跳登录页，用户在登录页会看到
         * 「账号已被禁用」的真实原因。这是禁用生效的最后一道保险 ——
         * 主路径是禁用时直接撤销全部 refresh token，JWT 过滤器就会拦掉旧 access token。
         */
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 管理员专属入口：先按普通要求解析出当前用户，再确认确实是管理员，否则 403。
     *
     * <p>管理后台的每一个接口都从方法进来就调它 —— 前端「看不到入口 + 路由拦截」只是体验，
     * 服务端这道判断才是不能绕过的那把锁（直接拿 token 打接口同样会被拦）。
     * 注意是重新查库拿的 isAdmin，不采信 token 里的任何内容。
     */
    public User requireAdmin(Authentication authentication) {
        User user = require(authentication);
        if (!user.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return user;
    }
}
