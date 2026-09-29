/**
 * 后端请求基础设施
 *
 * 统一响应体：{ success, code, message, data }
 * 认证方式：Authorization: Bearer <accessToken>
 *
 * 地址解析优先级：window.__API_BASE__ > VITE_API_BASE > 同源（dev 由 Vite 代理转发到 8080）
 */

export const API_BASE = (
    window.__API_BASE__ ?? import.meta.env.VITE_API_BASE ?? ''
).replace(/\/+$/, '');

const ACCESS_KEY = 'hpa.accessToken';
const REFRESH_KEY = 'hpa.refreshToken';

/** 令牌读写：只放 JWT，平台凭证一律不落浏览器 */
export const tokens = {
    get access() {
        return localStorage.getItem(ACCESS_KEY) || '';
    },
    get refresh() {
        return localStorage.getItem(REFRESH_KEY) || '';
    },
    get isLoggedIn() {
        return Boolean(this.access);
    },
    set(accessToken, refreshToken) {
        if (accessToken) localStorage.setItem(ACCESS_KEY, accessToken);
        if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken);
    },
    clear() {
        localStorage.removeItem(ACCESS_KEY);
        localStorage.removeItem(REFRESH_KEY);
    },
};

export class ApiError extends Error {
    constructor(message, {status, code, payload} = {}) {
        super(message || '请求失败');
        this.name = 'ApiError';
        this.status = status;
        this.code = code;
        this.payload = payload;
    }
}

/************* 登录态失效的统一处理 *************/

let authExpiredHandler = null;

/** 应用层注册登录态失效回调（清理缓存并跳转登录页） */
export function setAuthExpiredHandler(handler) {
    authExpiredHandler = handler;
}

/**
 * 清理本地登录态，并通知应用层跳回登录页。
 * 仅当此前确实处于登录态时才通知，避免游客访问受保护接口时被误跳转。
 */
export function handleAuthExpired() {
    const wasLoggedIn = Boolean(tokens.access || tokens.refresh);
    tokens.clear();
    if (wasLoggedIn && authExpiredHandler) authExpiredHandler();
}

async function parseBody(res) {
    const text = await res.text();
    if (!text) return null;
    try {
        return JSON.parse(text);
    } catch {
        return {success: false, message: text};
    }
}

/** 刷新 accessToken；成功返回 true */
let refreshing = null;

async function refreshAccessToken() {
    if (refreshing) return refreshing;
    const refreshToken = tokens.refresh;
    if (!refreshToken) return false;

    refreshing = (async () => {
        try {
            const res = await fetch(`${API_BASE}/api/auth/refresh`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({refreshToken}),
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
            setTimeout(() => {
                refreshing = null;
            }, 0);
        }
    })();

    return refreshing;
}

/**
 * 底层请求
 * @param {string} path    形如 /api/auth/login
 * @param {object} options { method, body, auth, headers, _retry }
 */
export async function request(path, {method = 'GET', body, auth = true, headers: extraHeaders, _retry = true} = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && tokens.access) headers['Authorization'] = `Bearer ${tokens.access}`;
    if (extraHeaders) Object.assign(headers, extraHeaders);

    let res;
    try {
        res = await fetch(API_BASE + path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch {
        throw new ApiError(`无法连接服务器，请确认后端已启动（${API_BASE || '同源'}）`);
    }

    // access token 失效：尝试刷新后重试一次（刷新失败的收尾统一交给下方逻辑处理）
    if (res.status === 401 && auth && _retry && tokens.refresh) {
        const ok = await refreshAccessToken();
        if (ok) return request(path, {method, body, auth, headers: extraHeaders, _retry: false});
    }

    const payload = await parseBody(res);

    // 会话被下线 / 过期：后端统一返回 401 + code=UNAUTHORIZED；
    // 刷新也救不回来时清空登录态并交给应用层跳转登录页。
    // 注意只在 code 为 UNAUTHORIZED 时处理，避免把「原密码错误」这类业务 401 误判成登录失效。
    if (res.status === 401 && auth && payload?.code === 'UNAUTHORIZED') {
        handleAuthExpired();
        throw new ApiError('登录状态已失效，请重新登录', {status: 401, code: 'UNAUTHORIZED'});
    }

    if (!res.ok || payload?.success === false) {
        throw new ApiError(payload?.message || `请求失败（${res.status}）`, {
            status: res.status,
            code: payload?.code,
            payload,
        });
    }
    return payload;
}

/** 把后端返回的相对资源路径补全为可访问地址 */
export function resolveAssetUrl(path) {
    if (!path) return '';
    if (/^https?:\/\//i.test(path)) return path;
    return API_BASE + (path.startsWith('/') ? path : '/' + path);
}

/**
 * 第三方平台返回的头像地址。
 * 只接受绝对 http(s) 地址：相对地址无法判断归属哪个平台的域名，
 * 早期版本直接当成本站路径使用，会打到 localhost 上变成 404（裂图）。
 * 这里直接丢弃，交给调用方的首字母兜底。
 */
export function resolveAvatarUrl(url) {
    if (!url) return '';
    const value = String(url).trim();
    if (/^https?:\/\//i.test(value)) return value;
    if (value.startsWith('//')) return `https:${value}`;
    return '';
}
