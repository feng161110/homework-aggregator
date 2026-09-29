/**
 * 三大平台服务（api/ 目录下的 FastAPI 服务）与平台凭证存储
 *
 * 浏览器不直连 Python 服务，统一走同源前缀：/api/zhs、/api/ykt、/api/xxt
 *   开发：Vite dev server 把 /api 代理到网关（8080），网关再按前缀分发
 *   生产：Nginx / 网关用同一套前缀转发
 * 所以前端不带任何主机名与端口，换服务器只改后端配置，也不存在跨域。
 *
 * 凭证统一存在后端数据库，浏览器不留任何平台凭证。
 * 默认姿势：用库里保存的 Cookie 取数；失效时用库里保存的账号密码自动重登。
 */
import { ApiError, request } from './http';

export const PLATFORM_SERVICES = {
    zhihuishu: { base: '/api/zhs', name: '智慧树' },
    yuketang: { base: '/api/ykt', name: '雨课堂' },
    xuexitong: { base: '/api/xxt', name: '学习通' },
};

/** 向平台服务发送 JSON 请求，统一处理网络异常与 FastAPI 的 {detail} 错误 */
async function platformRequest(svc, path, body) {
    let res;
    try {
        res = await fetch(svc.base + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
    } catch {
        throw new ApiError(`无法连接${svc.name}登录服务，请确认后台服务已启动`);
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
 * 智慧树用户信息适配。
 * 新版：user_info = { realName, headPicUrl, uuid, username }
 * 旧版：顶层 real_name（失败时为 -1）
 * 两种形状都读，取不到姓名一律返回空，交给上层兜底。
 */
function zhihuishuProfile(payload) {
    const info = payload?.user_info;
    if (info && typeof info === 'object') {
        const name = info.realName ?? info.real_name ?? info.name;
        if (name && String(name) !== '-1') {
            return {
                name: String(name),
                avatar: info.headPicUrl || info.headPic || info.avatar || '',
                uuid: info.uuid || '',
            };
        }
    }
    const legacy = payload?.real_name;
    if (legacy && String(legacy) !== '-1') {
        return { name: String(legacy), avatar: '', uuid: '' };
    }
    return { name: '', avatar: '', uuid: '' };
}

/**
 * 平台登录：直连 api/ 下的两个 FastAPI 服务（雨课堂 / 智慧树），只负责「账号密码 → Cookie」。
 * 学习通不在此列：它的登录由浏览器助手在浏览器内完成（见 XxtPanel）。
 */
export const platformApi = {
    async login(key, username, password) {
        const svc = PLATFORM_SERVICES[key];
        if (!svc) throw new ApiError('未知平台');

        if (key === 'zhihuishu') {
            const d = await platformRequest(svc, '/api/login/password', { username, password });
            const profile = zhihuishuProfile(d);
            return {
                ok: Boolean(d.success),
                message: d.message || (d.success ? '登录成功' : '登录失败'),
                account: d.username || username,
                name: profile.name || null,
                avatar: profile.avatar || null,
                cookies: d.cookies || null,
                extra: { validate: d.validate || '', fp: d.fp || '', uuid: profile.uuid || '' },
            };
        }

        if (key === 'yuketang') {
            const d = await platformRequest(svc, '/api/login', { username, password });
            return {
                ok: Boolean(d.success),
                message: d.message || (d.success ? '登录成功' : '登录失败'),
                account: username,
                name: null,
                cookies: d.cookies || null,
                extra: { statusCode: d.status_code, raw: d.raw },
            };
        }

        const d = await platformRequest(svc, '/login', { username, password });
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

/** 平台凭证存储（Java 后端 /api/platform） */
export const platformStore = {
    /** 三个平台的绑定状态（含未绑定的） */
    list: () => request('/api/platform').then((r) => r.data),

    /** 单个平台的凭证详情（含 Cookie，不含密码明文） */
    detail: (key) => request(`/api/platform/${key}`).then((r) => r.data),

    /** 登录成功后回传 账号 / 密码 / Cookie 入库 */
    bind: (key, payload) => request(`/api/platform/${key}`, { method: 'POST', body: payload }).then((r) => r.data),

    /** 只回传 Cookie（学习通浏览器助手） */
    saveCookies: (key, payload) =>
        request(`/api/platform/${key}/cookies`, { method: 'PUT', body: payload }).then((r) => r.data),

    /**
     * 取平台账号 + 明文密码（仅学习通开放），仅供浏览器助手自动重登一次性使用。
     * 拿到的密码只允许在内存里传给助手，不得写入任何存储或渲染到界面。
     */
    secret: (key) => request(`/api/platform/${key}/secret`).then((r) => r.data),

    /** 用库里的 Cookie 校验是否仍然有效 */
    check: (key) => request(`/api/platform/${key}/check`, { method: 'POST' }).then((r) => r.data),

    /** Cookie 失效后触发后端自动重新登录（雨课堂 / 智慧树） */
    refresh: (key) => request(`/api/platform/${key}/refresh`, { method: 'POST' }).then((r) => r.data),

    /** 平台的作业 / 考试链接（学习通 / 智慧树；条目 url 即直达地址） */
    desktopLinks: (key) => request(`/api/platform/${key}/desktop-links`).then((r) => r.data),

    /** 平台的课程列表（学习通 / 雨课堂 / 智慧树；学习通另含任务 / 章节 / 作业 / 考试等入口） */
    courses: (key) => request(`/api/platform/${key}/courses`).then((r) => r.data),

    /** 解绑并清除服务端凭证 */
    unbind: (key) => request(`/api/platform/${key}`, { method: 'DELETE' }).then((r) => r.data),
};
