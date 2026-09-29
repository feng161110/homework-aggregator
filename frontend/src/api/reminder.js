/**
 * 作业提醒 / 自定义提醒任务接口
 *
 * 后端在 Student 模块里（JPA + MySQL），接口沿用原型的 action 分发方式：
 *   读：GET  /api/task?action=list|detail|templates
 *   写：POST /api/task  body 里带 action
 * 模板同理走 /api/template。
 *
 * 收件人由后端锁定为当前账号邮箱，前端不用（也不能）传。
 */
import { request } from './http';

/** 把筛选参数拼成 query，空值不传（后端按缺省处理） */
function query(params) {
    const pairs = Object.entries(params)
        .filter(([, value]) => value !== '' && value !== null && value !== undefined)
        .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
    return pairs.length ? `&${pairs.join('&')}` : '';
}

export const reminderApi = {
    /** 任务列表：{tasks, total, page, limit, isAdmin} */
    list: (params = {}) => request(`/api/task?action=list${query(params)}`).then((r) => r.data),

    /** 任务详情：{task, logs} */
    detail: (id) => request(`/api/task?action=detail&id=${encodeURIComponent(id)}`).then((r) => r.data),

    /** 概览：{pending, sent, disabled, failed, total, nextScheduledTime} */
    stats: (params = {}) => request(`/api/task?action=stats${query(params)}`).then((r) => r.data),

    /** 创建任务，返回 {task_id} */
    create: (payload) => request('/api/task', { method: 'POST', body: { action: 'create', ...payload } }),

    /** 立即发送（手动触发，日志记 manual） */
    execute: (taskId) => request('/api/task', { method: 'POST', body: { action: 'execute', taskId } })
        .then((r) => r.message),

    /** 禁用 / 启用 */
    cancel: (taskId) => request('/api/task', { method: 'POST', body: { action: 'cancel', taskId } })
        .then((r) => r.message),
    enable: (taskId) => request('/api/task', { method: 'POST', body: { action: 'enable', taskId } })
        .then((r) => r.message),

    /** 只改发送时间与重复规则 */
    update: (payload) => request('/api/task', { method: 'POST', body: { action: 'update', ...payload } })
        .then((r) => r.message),

    /** 软删除 / 恢复（恢复仅管理员） */
    remove: (taskId) => request('/api/task', { method: 'POST', body: { action: 'delete', taskId } })
        .then((r) => r.message),
    restore: (taskId) => request('/api/task', { method: 'POST', body: { action: 'restore', taskId } })
        .then((r) => r.message),
};

/** 重复规则的中文说明（列表与详情共用） */
export function repeatText(repeat, repeatData) {
    const days = repeatData?.days || [];
    switch (repeat) {
        case 'daily':
            return '每天';
        case 'weekly': {
            if (!days.length) return '每周';
            const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
            return `每周 ${days.map((d) => names[d - 1]).join('、')}`;
        }
        case 'monthly':
            return `每月 ${repeatData?.day || 1} 日`;
        case 'immediate':
            return '立即发送';
        default:
            return '仅一次';
    }
}

/** 任务状态：文案 + 徽章样式类 */
export function statusInfo(status) {
    switch (status) {
        case 1:
            return { text: '已发送', cls: 'sent' };
        case 2:
            return { text: '已禁用', cls: 'off' };
        case 3:
            return { text: '发送失败', cls: 'failed' };
        default:
            return { text: '待发送', cls: 'pending' };
    }
}

/** 后端给的是 yyyy-MM-dd HH:mm:ss，转成 Date 时把空格换成 T，避免各浏览器解析差异 */
export function parseTime(text) {
    if (!text) return null;
    const date = new Date(String(text).replace(' ', 'T'));
    return Number.isNaN(date.getTime()) ? null : date;
}

/** 距离发送还有多久：已到点/已过期返回「已到点」 */
export function untilText(text) {
    const target = parseTime(text);
    if (!target) return '';
    const diff = target.getTime() - Date.now();
    if (diff <= 0) return '已到点';
    const minutes = Math.floor(diff / 60000);
    if (minutes < 60) return `${minutes} 分钟后`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时后`;
    return `${Math.floor(hours / 24)} 天后`;
}

/** 是否为「发完往后排」的重复规则（与后端 RepeatRuleService.isRepeat 一致） */
export function isRepeatRule(repeat) {
    return !!repeat && repeat !== 'none' && repeat !== 'immediate';
}

function pad2(value) {
    return String(value).padStart(2, '0');
}

/**
 * 按重复规则推算下一次发送时间 —— 算法与后端 RepeatRuleService.computeNext 对齐：
 * 从「今天 + 选的时刻」起往后找第一个满足规则、且晚于此刻的时间点。
 *
 * 重复规则下「哪一天」是由规则本身决定的，用户不需要（也不该）再挑一个日期；
 * 这个函数就是把规则翻译成一句人话给用户看，真正的发送时间仍以后端为准。
 */
export function nextSendDate(repeat, repeatData, clock, from = new Date()) {
    if (!isRepeatRule(repeat)) return null;
    const [hour, minute] = String(clock || '08:00').split(':').map((n) => Number(n) || 0);
    const days = repeatData?.days || [];
    const monthlyDay = Math.max(1, Math.min(31, Number(repeatData?.day) || 1));
    const at = (date) => new Date(date.getFullYear(), date.getMonth(), date.getDate(),
        hour, minute, 0, 0);
    let t = at(from);
    for (let i = 0; i < 3660; i += 1) {
        const weekday = t.getDay() === 0 ? 7 : t.getDay();
        const valid = repeat === 'weekly'
            ? (!days.length || days.includes(weekday))
            : repeat === 'monthly'
                ? t.getDate() === Math.min(monthlyDay,
                    new Date(t.getFullYear(), t.getMonth() + 1, 0).getDate())
                : true;
        if (valid && t.getTime() > Date.now()) return t;
        const next = new Date(t);
        if (repeat === 'monthly') {
            // 与后端一致：跳到次月的 min(几号, 当月最后一天)
            const month = new Date(t.getFullYear(), t.getMonth() + 1, 1);
            const last = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
            next.setFullYear(month.getFullYear(), month.getMonth(), Math.min(monthlyDay, last));
        } else {
            next.setDate(next.getDate() + 1);
        }
        t = at(next);
    }
    return null;
}

/** 把下次发送说成人话：今天 11:30 / 明天 11:30 / 10/14（周三）11:30 */
export function sendTimeText(date) {
    if (!date) return '';
    const clock = `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
    const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    const diff = Math.round((startOfDay(date) - startOfDay(new Date())) / 86400000);
    if (diff === 0) return `今天 ${clock}`;
    if (diff === 1) return `明天 ${clock}`;
    if (diff === 2) return `后天 ${clock}`;
    return `${date.getMonth() + 1}/${date.getDate()}（${names[date.getDay()]}）${clock}`;
}
