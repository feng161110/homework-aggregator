/** 后端 /api/auth/* 接口：注册 / 登录 / 邮箱验证 / 找回密码 / 个人资料 */
import { API_BASE, ApiError, handleAuthExpired, request, tokens } from './http';
import { buildLoginPayload, buildRegisterPayload } from '../utils/loginCrypto';

/**
 * 设备管理接口的请求头：带上 refresh token，后端据此识别「当前设备」，
 * 兼容本次改动前签发、不含会话标识的旧 access token。
 */
function deviceHeaders() {
    return tokens.refresh ? { 'X-Refresh-Token': tokens.refresh } : {};
}

export const authApi = {
    /**
     * 注册：{ username, email, password } → 提示文案
     *
     * 与登录一致不明文上传：用户名与邮箱走 RC4（chunk.b.js）、密码走 RSA（chunk.a.js），
     * 字段名混淆为 { u, e, p }，各自带毫秒时间戳。
     */
    register: (data) =>
        request('/api/auth/register', {
            method: 'POST',
            body: buildRegisterPayload(data.username, data.email, data.password),
        }).then((r) => r.message),

    /** 邮箱验证码校验：{ email, code }，验证成功自动登录并返回 TokenResponse */
    async verifyEmail(data) {
        const r = await request('/api/auth/verify-email', { method: 'POST', body: data, auth: false });
        if (r.data?.accessToken) {
            tokens.set(r.data.accessToken, r.data.refreshToken);
        }
        return r.data;
    },

    /** 重发验证码：{ email } */
    resendVerification: (data) =>
        request('/api/auth/resend-verification', { method: 'POST', body: data }).then((r) => r.message),

    /**
     * 登录：{ account, password } → TokenResponse
     *
     * 参数不明文上传：账号走 RC4（2.js）、密码走 RSA（1.js），
     * 都拼上毫秒时间戳后以 { u, p } 发送，后端解密并校验 10 秒时效窗口。
     */
    async login(data) {
        const r = await request('/api/auth/login', {
            method: 'POST',
            body: buildLoginPayload(data.account, data.password),
            auth: false,
        });
        tokens.set(r.data.accessToken, r.data.refreshToken);
        return r.data;
    },

    /** 登出（需登录） */
    async logout() {
        try {
            if (tokens.refresh) {
                await request('/api/auth/logout', { method: 'POST', body: { refreshToken: tokens.refresh } });
            }
        } finally {
            tokens.clear();
        }
    },

    /** 找回密码（发邮件链接）：{ email } */
    forgotPassword: (data) =>
        request('/api/auth/forgot-password', { method: 'POST', body: data, auth: false }).then((r) => r.message),

    /** 重置密码（链接方式）：{ token, newPassword } */
    resetPassword: (data) =>
        request('/api/auth/reset-password', { method: 'POST', body: data, auth: false }).then((r) => r.message),

    /** 发送重置密码验证码：{ email } */
    forgotPasswordByCode: (data) =>
        request('/api/auth/forgot-password/code', { method: 'POST', body: data, auth: false }).then((r) => r.message),

    /** 重置密码（验证码方式）：{ email, code, newPassword } */
    resetPasswordByCode: (data) =>
        request('/api/auth/reset-password/code', { method: 'POST', body: data, auth: false }).then((r) => r.message),

    /** 校验重置链接是否有效：token → { email, expiresAt } */
    checkResetToken: (token) =>
        request(`/api/auth/reset-password/check?token=${encodeURIComponent(token)}`, { auth: false }).then((r) => r.data),

    /** 修改密码（需登录，凭原密码）：{ oldPassword, newPassword } */
    changePassword: (data) =>
        request('/api/auth/change-password', { method: 'POST', body: data }).then((r) => r.message),

    /** 当前用户信息（需登录） */
    me: () => request('/api/auth/me').then((r) => r.data),

    /** 当前账号的登录设备列表（需登录） */
    devices: () => request('/api/auth/devices', { headers: deviceHeaders() }).then((r) => r.data),

    /** 下线指定设备（需登录）：id → 提示文案 */
    revokeDevice: (id) =>
        request(`/api/auth/devices/${id}`, { method: 'DELETE', headers: deviceHeaders() }).then((r) => r.message),

    /** 下线除当前设备外的其它设备（需登录）→ 提示文案 */
    revokeOtherDevices: () =>
        request('/api/auth/devices/revoke-others', { method: 'POST', headers: deviceHeaders() }).then((r) => r.message),

    /** 上传头像（multipart/form-data）→ 更新后的用户信息 */
    async uploadAvatar(file) {
        const form = new FormData();
        form.append('file', file);

        const headers = {};
        if (tokens.access) headers['Authorization'] = `Bearer ${tokens.access}`;

        let res;
        try {
            res = await fetch(`${API_BASE}/api/auth/avatar`, { method: 'POST', headers, body: form });
        } catch {
            throw new ApiError(`无法连接服务器，请确认后端已启动（${API_BASE || '同源'}）`);
        }

        if (res.status === 401) {
            handleAuthExpired();
            throw new ApiError('登录状态已失效，请重新登录', { status: 401, code: 'UNAUTHORIZED' });
        }

        const text = await res.text();
        let payload = null;
        try { payload = text ? JSON.parse(text) : null; } catch { payload = { message: text }; }

        if (!res.ok || payload?.success === false) {
            throw new ApiError(payload?.message || `头像上传失败（${res.status}）`, {
                status: res.status,
                code: payload?.code,
                payload,
            });
        }
        return payload.data;
    },
};

export { ApiError };
