/**
 * 页面数据的本地缓存。
 *
 * 进入页面先读缓存直接展示，不再请求（切页回来不会重新加载）；
 * 只有缓存为空或用户手动「刷新」时才真正拉取。
 * 退出登录 / 登录态失效时由 clearSession() 统一清除，避免换号后串数据。
 */

/** 所有页面缓存的统一前缀，clearCaches() 依赖它整体清理 */
const PREFIX = 'hpa.cache.';

/** 各页面缓存的 key（v1 后缀便于日后格式变更时整体作废） */
export const CACHE_KEYS = {
    /** 作业 / 考试列表按平台分别缓存（未绑定的平台不会影响已缓存的那个） */
    homework: (platform) => `${PREFIX}homework.${platform}.v6`,
    /** 课程列表按平台分别缓存（接口新增字段时递增版本号，自动作废旧缓存） */
    courses: (platform) => `${PREFIX}courses.${platform}.v3`,
};

/** 读取缓存；无缓存或格式异常返回 null */
export function readCache(key) {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) {
            return null;
        }
        const obj = JSON.parse(raw);
        if (!obj || typeof obj.ts !== 'number' || !Array.isArray(obj.items)) {
            return null;
        }
        return obj;
    } catch {
        return null;
    }
}

/** 写入缓存（items 为空也照存，表示「当前确实没有数据」） */
export function writeCache(key, items) {
    try {
        localStorage.setItem(key, JSON.stringify({ ts: Date.now(), items }));
    } catch {
        // 隐私模式 / 配额不足时静默失败，不影响页面本身
    }
}

/** 删除单个缓存 */
export function removeCache(key) {
    try {
        localStorage.removeItem(key);
    } catch {
        // ignore
    }
}

/** 清空所有页面缓存（按前缀匹配，兼容按平台动态生成的 key） */
export function clearCaches() {
    try {
        Object.keys(localStorage)
            .filter((key) => key.startsWith(PREFIX))
            .forEach((key) => localStorage.removeItem(key));
    } catch {
        // ignore
    }
}
