/**
 * 界面偏好：只存本地。
 * 与页面数据缓存分开存放（前缀不同），所以退出登录清缓存时不会把用户选的排版一起清掉。
 */

const PREFIX = 'hpa.pref.';

export const PREF_KEYS = {
    /** 作业查询页的默认排版 */
    homeworkLayout: 'homework.layout',
    /** 作业查询页网格排版每行的卡片数 */
    homeworkGridCols: 'homework.gridCols',
    /** 课程页的默认排版 */
    coursesLayout: 'courses.layout',
    /** 课程页置顶的课程键（数组，JSON） */
    coursesPinned: 'courses.pinned',
};

/** 读取偏好；不存在或读取失败时返回 fallback */
export function readPref(key, fallback = '') {
    try {
        return localStorage.getItem(PREFIX + key) || fallback;
    } catch {
        return fallback;
    }
}

/** 写入偏好；写失败（隐私模式 / 配额不足）时静默忽略 */
export function writePref(key, value) {
    try {
        localStorage.setItem(PREFIX + key, String(value));
    } catch {
        // ignore
    }
}

/** 读取结构化（数组 / 对象）偏好；不存在或格式异常时返回 fallback */
export function readPrefJson(key, fallback = null) {
    try {
        const raw = localStorage.getItem(PREFIX + key);
        return raw ? JSON.parse(raw) : fallback;
    } catch {
        return fallback;
    }
}

/** 写入结构化偏好；同样的静默失败策略 */
export function writePrefJson(key, value) {
    try {
        localStorage.setItem(PREFIX + key, JSON.stringify(value));
    } catch {
        // ignore
    }
}
