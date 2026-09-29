/**
 * api.js —— 后端接口封装
 * 对应 Spring Boot `AuthController`（/api/v1/auth/*）
 *
 * 统一响应体： { success, code, message, data }
 * 认证方式： Authorization: Bearer <accessToken>
 */

/** 后端地址；如需修改可直接改这里，或在页面引入前设置 window.__API_BASE__ */
export const API_BASE = (window.__API_BASE__ || 'http://localhost:8080').replace(/\/+$/, '');

const ACCESS_KEY = 'hpa.accessToken';
const REFRESH_KEY = 'hpa.refreshToken';

/** 令牌读写 */
export const tokens = {
    get access() { return localStorage.getItem(ACCESS_KEY) || ''; },
    get refresh() { return localStorage.getItem(REFRESH_KEY) || ''; },
    get isLoggedIn() { return Boolean(this.access); },
    set(accessToken, refreshToken) {
        if (accessToken) localStorage.setItem(ACCESS_KEY, accessToken);
        if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken);
    },
    clear() {
        localStorage.removeItem(ACCESS_KEY);
        localStorage.removeItem(REFRESH_KEY);
    },
};

/** 业务错误 */
export class ApiError extends Error {
    constructor(message, { status, code, payload } = {}) {
        super(message || '请求失败');
        this.name = 'ApiError';
        this.status = status;
        this.code = code;
        this.payload = payload;
    }
}

async function parseBody(res) {
    const text = await res.text();
    if (!text) return null;
    try { return JSON.parse(text); }
    catch { return { success: false, message: text }; }
}

/** 刷新 accessToken；成功返回 true */
let refreshing = null;
async function refreshAccessToken() {
    if (refreshing) return refreshing;
    const refreshToken = tokens.refresh;
    if (!refreshToken) return false;

    refreshing = (async () => {
        try {
            const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken }),
            });
            if (!res.ok) return false;
            const body = await parseBody(res);
            const data = body?.data;
            if (data?.accessToken) {
                tokens.set(data.accessToken, data.refreshToken);
                return true;
            }
            return false;
        } catch {
            return false;
        } finally {
            // 下一帧重置，避免并发请求复用已完成的 Promise
            setTimeout(() => { refreshing = null; }, 0);
        }
    })();

    return refreshing;
}

/**
 * 底层请求
 * @param {string} path    形如 /api/v1/auth/login
 * @param {object} options { method, body, auth, _retry }
 */
async function request(path, { method = 'GET', body, auth = true, _retry = true } = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && tokens.access) headers['Authorization'] = `Bearer ${tokens.access}`;

    let res;
    try {
        res = await fetch(API_BASE + path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch {
        throw new ApiError('无法连接服务器，请确认后端已启动（' + API_BASE + '）');
    }

    // access token 失效：尝试刷新后重试一次
    if (res.status === 401 && auth && _retry && tokens.refresh) {
        const ok = await refreshAccessToken();
        if (ok) return request(path, { method, body, auth, _retry: false });
        tokens.clear();
    }

    const payload = await parseBody(res);

    if (!res.ok || payload?.success === false) {
        throw new ApiError(payload?.message || `请求失败（${res.status}）`, {
            status: res.status,
            code: payload?.code,
            payload,
        });
    }
    return payload; // { success, code, message, data }
}

/* -------------------- 接口方法 -------------------- */

export const api = {
    /** 注册：{ username, email, password } */
    register: (data) => request('/api/v1/auth/register', { method: 'POST', body: data }).then(r => r.message),

    /** 邮箱验证码校验：{ email, code } */
    verifyEmail: (data) => request('/api/v1/auth/verify-email', { method: 'POST', body: data }).then(r => r.message),

    /** 重发验证码：{ email } */
    resendVerification: (data) => request('/api/v1/auth/resend-verification', { method: 'POST', body: data }).then(r => r.message),

    /** 登录：{ account, password } → TokenResponse */
    async login(data) {
        const r = await request('/api/v1/auth/login', { method: 'POST', body: data, auth: false });
        tokens.set(r.data.accessToken, r.data.refreshToken);
        return r.data;
    },

    /** 登出（需登录） */
    async logout() {
        try {
            if (tokens.refresh) {
                await request('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: tokens.refresh } });
            }
        } finally {
            tokens.clear();
        }
    },

    /** 找回密码（发邮件链接）：{ email } */
    forgotPassword: (data) => request('/api/v1/auth/forgot-password', { method: 'POST', body: data, auth: false }).then(r => r.message),

    /** 重置密码（链接方式）：{ token, newPassword } */
    resetPassword: (data) => request('/api/v1/auth/reset-password', { method: 'POST', body: data, auth: false }).then(r => r.message),

    /** 发送重置密码验证码：{ email } */
    forgotPasswordByCode: (data) => request('/api/v1/auth/forgot-password/code', { method: 'POST', body: data, auth: false }).then(r => r.message),

    /** 重置密码（验证码方式）：{ email, code, newPassword } */
    resetPasswordByCode: (data) => request('/api/v1/auth/reset-password/code', { method: 'POST', body: data, auth: false }).then(r => r.message),

    /** 当前用户信息（需登录） */
    me: () => request('/api/v1/auth/me').then(r => r.data),

    /** 上传头像（multipart/form-data）→ 返回更新后的用户信息 */
    async uploadAvatar(file) {
        const form = new FormData();
        form.append('file', file);

        const headers = {};
        if (tokens.access) headers['Authorization'] = `Bearer ${tokens.access}`;

        let res;
        try {
            res = await fetch(`${API_BASE}/api/v1/auth/avatar`, { method: 'POST', headers, body: form });
        } catch {
            throw new ApiError('无法连接服务器，请确认后端已启动（' + API_BASE + '）');
        }

        if (res.status === 401 && tokens.refresh) {
            const ok = await refreshAccessToken();
            if (ok) return api.uploadAvatar(file);
            tokens.clear();
        }

        const payload = await parseBody(res);
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

/** 把后端返回的相对资源路径补全为可访问的完整地址 */
export function resolveAssetUrl(path) {
    if (!path) return '';
    if (/^https?:\/\//i.test(path)) return path;
    return API_BASE + (path.startsWith('/') ? path : '/' + path);
}

/* ============================================================
   三大平台登录服务（api/ 目录下的 FastAPI 服务）
   - 智慧树  http://localhost:1001
   - 雨课堂  http://localhost:1002
   - 学习通  http://localhost:1003
   ============================================================ */

export const PLATFORM_SERVICES = {
    zhihuishu: { base: 'http://localhost:1001', name: '智慧树' },
    yuketang: { base: 'http://localhost:1002', name: '雨课堂' },
    xuexitong: { base: 'http://localhost:1003', name: '学习通' },
};

/** 向平台服务发送 JSON 请求，统一处理网络异常与 FastAPI 的 {detail} 错误 */
async function platformRequest(base, path, body) {
    let res;
    try {
        res = await fetch(base + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
    } catch {
        throw new ApiError(`无法连接服务 ${base}，请确认该平台的登录服务已启动`);
    }

    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { detail: text }; }

    if (!res.ok) {
        const msg = data && (data.detail || data.message);
        throw new ApiError(typeof msg === 'string' && msg ? msg : `请求失败（${res.status}）`, { status: res.status });
    }
    return data || {};
}

/**
 * 平台登录：直连 api/ 下的三个 FastAPI 服务，只负责「账号密码 → Cookie」。
 * 登录成功后由调用方把 account / password / cookies 回传 Java 后端加密入库（见 platformStore.bind）。
 * 归一化登录结果：{ ok, message, account, name, cookies, extra }
 */
export const platformApi = {
    async login(key, username, password) {
        const svc = PLATFORM_SERVICES[key];
        if (!svc) throw new ApiError('未知平台');

        if (key === 'zhihuishu') {
            const d = await platformRequest(svc.base, '/api/login/password', { username, password });
            return {
                ok: Boolean(d.success),
                message: d.message || (d.success ? '登录成功' : '登录失败'),
                account: d.username || username,
                name: d.real_name && d.real_name !== -1 ? String(d.real_name) : null,
                cookies: d.cookies || null,
                extra: { validate: d.validate || '', fp: d.fp || '' },
            };
        }

        if (key === 'yuketang') {
            const d = await platformRequest(svc.base, '/api/login', { username, password });
            return {
                ok: Boolean(d.success),
                message: d.message || (d.success ? '登录成功' : '登录失败'),
                account: username,
                name: null,
                cookies: d.cookies || null,
                extra: { statusCode: d.status_code, raw: d.raw },
            };
        }

        // 学习通
        const d = await platformRequest(svc.base, '/login', { username, password });
        return {
            ok: Boolean(d.status),
            message: d.message || (d.status ? '登录成功' : '登录失败'),
            account: username,
            name: d.name || null,
            cookies: d.cookies || null,
            extra: null,
        };
    },

};

/* ============================================================
   平台凭证存储（Java 后端 /api/v1/platform，凭证统一入库）
   默认姿势：用库里保存的 Cookie 取数；
   Cookie 失效：调 refresh 用库里的账号密码自动重登（学习通返回 needManual）。
   注意：三个平台的「校验」逻辑已全部搬到后端（用库里的 Cookie），
        前端不再直接调用 1001-1003 的校验接口。
   ============================================================ */
export const platformStore = {
    /** 三个平台的绑定状态（含未绑定的） */
    list: () => request('/api/v1/platform').then(r => r.data),

    /** 单个平台的凭证详情（含 Cookie，不含密码明文） */
    detail: (key) => request(`/api/v1/platform/${key}`).then(r => r.data),

    /** 登录成功后回传 账号 / 密码 / Cookie 入库 */
    bind: (key, payload) => request(`/api/v1/platform/${key}`, { method: 'POST', body: payload }).then(r => r.data),

    /** 只回传 Cookie（学习通浏览器助手 / 手动粘贴） */
    saveCookies: (key, payload) => request(`/api/v1/platform/${key}/cookies`, { method: 'PUT', body: payload }).then(r => r.data),

    /**
     * 取平台账号 + 明文密码（仅学习通开放），仅供浏览器助手自动重登一次性使用。
     * 拿到的密码只允许在内存里传给助手，不得写入任何存储或渲染到界面。
     */
    secret: (key) => request(`/api/v1/platform/${key}/secret`).then(r => r.data),

    /** 用库里的 Cookie 校验是否仍然有效 */
    check: (key) => request(`/api/v1/platform/${key}/check`, { method: 'POST' }).then(r => r.data),

    /** Cookie 失效后触发后端自动重新登录 */
    refresh: (key) => request(`/api/v1/platform/${key}/refresh`, { method: 'POST' }).then(r => r.data),

    /** 解绑并清除服务端凭证 */
    unbind: (key) => request(`/api/v1/platform/${key}`, { method: 'DELETE' }).then(r => r.data),
};
