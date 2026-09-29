/**
 * 管理后台接口（仅管理员可用，服务端会用 is_admin 校验）
 *
 * GET    /api/admin/users?page=&limit=&search=&status=   用户列表 + 顶部统计
 * GET    /api/admin/users/{id}                           用户详情 + 登录记录
 * POST   /api/admin/users/{id}/disable                   禁用账号（同时踢下线全部设备）
 * POST   /api/admin/users/{id}/enable                    解除禁用
 * POST   /api/admin/users/{id}/sessions/revoke           踢下线该账号全部设备
 * DELETE /api/admin/sessions/{id}                        踢下线单个设备
 */
import { request } from './http';

/** 空值不传，交给后端按缺省处理 */
function query(params) {
    const pairs = Object.entries(params)
        .filter(([, value]) => value !== '' && value !== null && value !== undefined)
        .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
    return pairs.length ? `?${pairs.join('&')}` : '';
}

export const adminApi = {
    users: (params = {}) => request(`/api/admin/users${query(params)}`).then((r) => r.data),

    detail: (id) => request(`/api/admin/users/${encodeURIComponent(id)}`).then((r) => r.data),

    disable: (id) => request(`/api/admin/users/${encodeURIComponent(id)}/disable`, { method: 'POST' })
        .then((r) => r.message),
    enable: (id) => request(`/api/admin/users/${encodeURIComponent(id)}/enable`, { method: 'POST' })
        .then((r) => r.message),

    revokeSessions: (id) => request(`/api/admin/users/${encodeURIComponent(id)}/sessions/revoke`, { method: 'POST' })
        .then((r) => r.message),
    revokeSession: (sessionId) => request(`/api/admin/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
        .then((r) => r.message),
};

/** 账号状态 → 文案 + 徽章样式（与后端 UserStatus 枚举一一对应） */
export function userStatusInfo(status) {
    switch (status) {
        case 'ACTIVE':
            return { text: '正常', cls: 'ok' };
        case 'PENDING':
            return { text: '待验证', cls: 'pending' };
        case 'LOCKED':
            return { text: '已锁定', cls: 'warn' };
        case 'DISABLED':
            return { text: '已禁用', cls: 'off' };
        default:
            return { text: status || '未知', cls: 'pending' };
    }
}

/** 时间戳 → 相对时间（后端给的是 yyyy-MM-dd HH:mm:ss） */
export function agoText(text) {
    if (!text) return '从未登录';
    const time = new Date(String(text).replace(' ', 'T')).getTime();
    if (Number.isNaN(time)) return text;
    const diff = Date.now() - time;
    if (diff < 60000) return '刚刚';
    const minutes = Math.floor(diff / 60000);
    if (minutes < 60) return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时前`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days} 天前`;
    return text.slice(0, 10);
}
