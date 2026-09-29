//状态枚举
package com.xiaofu.student.entity;

public enum UserStatus {
    /** 已注册但邮箱未验证。 */
    PENDING,
    /** 正常可用。 */
    ACTIVE,
    /** 密码连续错误触发的临时锁定（与 lockedUntil 配合，会自动恢复）。 */
    LOCKED,
    /**
     * 管理员手动禁用：不能登录、不能刷新令牌，已有登录态会被立刻撤销，
     * 名下的提醒任务也停止发送（账号启用后自动恢复）。
     * 与 LOCKED 有意分开：LOCKED 是临时的、会自动到期，DISABLED 只能由管理员解除。
     */
    DISABLED
}