/**
 * 作业 / 考试条目的工具函数。
 *
 * 与「当前时间」相关的判断统一把 now 当参数传入：
 * 页面只用一个每秒跳动的 now 驱动，所有排版复用同一份计算结果，不各起定时器。
 */
import { findPlatform } from './platforms';
import { toast } from './toast';

/** 整分钟毫秒数 */
export const MINUTE_MS = 60000;
const DAY_MS = 86400000;

const UNIT_MS = {
    天: 86400000, 小时: 3600000, 时: 3600000, 分钟: 60000, 分: 60000, 秒: 1000,
};

function pad(value) {
    return String(value).padStart(2, '0');
}

export function platformName(key) {
    return findPlatform(key)?.name || '平台';
}

/** 把「剩余3天2小时」这类文案换算成毫秒；解析不出来返回 null */
export function parseRemainMs(text) {
    const value = String(text || '');
    let total = 0;
    let matched = false;
    const re = /(\d+(?:\.\d+)?)\s*(天|小时|时|分钟|分|秒)/g;
    let m = re.exec(value);
    while (m !== null) {
        matched = true;
        total += Number(m[1]) * UNIT_MS[m[2]];
        m = re.exec(value);
    }
    return matched ? total : null;
}

/**
 * 用「剩余720小时46分钟」这类文案反推截止时间。
 *
 * 平台的剩余时长是把真实剩余「向下取整到分钟」后的结果（46分XX秒 → 46分钟），
 * 所以真实截止时间落在 `抓取时刻 + 时长` 这一分钟的**末尾**，且秒位为 00。
 * 因此先加上时长，再向上对齐到整分：
 * 15:33:37 + 46分 = 16:19:37 → 16:20:00。
 * 若抓取时刻恰好在整分上，结果自然就是对齐的，不会多推一分钟。
 */
export function deriveEndTime(remainText, baseTs) {
    const ms = parseRemainMs(remainText);
    if (ms === null) {
        return null;
    }
    const raw = baseTs + ms;
    // 文案只精确到分钟时才需要这次进位；带「秒」的文案本身就是精确值
    if (ms % MINUTE_MS !== 0) {
        return raw;
    }
    const remainder = raw % MINUTE_MS;
    return remainder === 0 ? raw : raw + (MINUTE_MS - remainder);
}

/**
 * 平台的作业 / 考试响应 → 摊平后的条目列表。
 *
 * 作业页与首页消息栏共用这个函数，也共用同一份缓存，所以两边的格式必须一致：
 *  - 给每条一个跨课程、跨平台唯一的 key；
 *  - 学习通只给「剩余720小时46分钟」这种时长文案，这里用抓取时刻反推出截止时间，
 *    并标记 estimated，让界面知道这是估算值。
 */
export function normalizeHomework(platform, courses, loadedAt) {
    return (courses || []).flatMap((course) => (course.items || []).map((item) => {
        const derived = item.endTime ? null : deriveEndTime(item.remainText, loadedAt);
        return {
            ...item,
            key: `${platform}_${course.courseId || ''}_${item.id || ''}`,
            platform,
            courseName: course.courseName || '',
            endTime: item.endTime || derived,
            estimated: derived !== null,
        };
    }));
}

/** 起止时间：显示到秒（平台的时间戳与反推结果秒位都是 00） */
export function formatDateTime(ms) {
    if (!ms) {
        return '';
    }
    const d = new Date(ms);
    if (Number.isNaN(d.getTime())) {
        return '';
    }
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
        + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/**
 * 已过期：平台标的过期标记，或截止时间（含反推出来的）已经过去。
 * 带实时判断，页面开着跨过截止点时会立刻反映出来。
 */
export function isExpired(item, now) {
    return item.expired === true || (Boolean(item.endTime) && item.endTime <= now);
}

/** 剩余时间的拆分：{ days, clock }；已截止或没有截止时间返回 null */
export function countdownOf(item, now) {
    if (!item.endTime) {
        return null;
    }
    const diff = item.endTime - now;
    if (diff <= 0) {
        return null;
    }
    return {
        days: Math.floor(diff / DAY_MS),
        clock: `${pad(Math.floor((diff % DAY_MS) / 3600000))}:`
            + `${pad(Math.floor((diff % 3600000) / 60000))}:${pad(Math.floor((diff % 60000) / 1000))}`,
    };
}

/**
 * 剩余时间文案：有截止时间的精确到时分秒并每秒跳动；
 * 已截止的交给其它位置表达，不重复；没有时间戳时退回平台给的原文案。
 */
export function remainOf(item, now) {
    const left = countdownOf(item, now);
    if (!left) {
        return item.endTime ? '' : (item.remainText || '');
    }
    return left.days > 0 ? `剩余 ${left.days}天 ${left.clock}` : `剩余 ${left.clock}`;
}

/** 紧迫度：over（已过期）/ urgent（24 小时内）/ soon（3 天内）/ normal */
export function urgencyOf(item, now) {
    if (isExpired(item, now)) {
        return 'over';
    }
    if (!item.endTime) {
        return 'normal';
    }
    const diff = item.endTime - now;
    if (diff < DAY_MS) {
        return 'urgent';
    }
    return diff < 3 * DAY_MS ? 'soon' : 'normal';
}

/**
 * 状态语义。
 * 凡是「待 xx」「未 xx」都还是要去做的事，一律算待办 ——
 * 待提交 / 待完成 / 待批阅 / 待批改 / 待互评 / 未提交 / 未交 / 未开始 / 进行中 都是待办，
 * 互评相关单列 review（同样是待办，只是换一种颜色）。
 * 其余（已提交 / 已完成 / 已批阅 / 状态为空）才算已完成。
 */
function deriveStatusClass(status) {
    const s = String(status || '');
    if (!s) {
        return 'done';
    }
    if (/互评/.test(s)) {
        return 'review';
    }
    return /待|未|进行中/.test(s) ? 'pending' : 'done';
}

/**
 * 状态语义：文案里能看出「待办 / 互评」时以文案为准 ——
 * 判定源头（后端 status_class）可能把「待批阅」这类当成已完成，
 * 文案优先还能顺带纠正浏览器缓存里存着的旧判定。
 * 文案判断不出来时再用后端的 status_class，都没有才落到「已完成」。
 */
export function statusClass(item) {
    const derived = deriveStatusClass(item.status);
    if (derived !== 'done') {
        return derived;
    }
    const cls = String(item.statusClass || '').toLowerCase();
    return ['pending', 'review', 'done'].includes(cls) ? cls : derived;
}

/** 状态文本：状态为空说明平台没打标，按「已完成」处理 */
export function statusText(item) {
    return item.status || '已完成';
}

/** 条目类型：work（含小组作业）/ exam */
export function kindOf(item) {
    return item.type === 'exam' ? 'exam' : 'work';
}

export function kindText(item) {
    return kindOf(item) === 'exam' ? '考试' : '作业';
}

/** 筛选用的状态：已过期 > 待处理 > 已完成（待互评也还算要处理） */
export function stateOf(item, now) {
    if (isExpired(item, now)) {
        return 'over';
    }
    return statusClass(item) === 'done' ? 'done' : 'pending';
}

/** 优先电脑版地址，缺失时回退手机版 */
export function linkOf(item) {
    return item.url || item.mobileUrl || '';
}

export function hasLink(item) {
    return Boolean(linkOf(item));
}

export function linkText(item, now) {
    if (isExpired(item, now)) {
        return '已过期';
    }
    if (item.url) {
        return '电脑版';
    }
    return item.mobileUrl ? '手机版' : '无链接';
}

export function linkClass(item, now) {
    if (isExpired(item, now)) {
        return 'expired';
    }
    if (item.url) {
        return 'desktop';
    }
    return item.mobileUrl ? 'mobile' : 'plain';
}

export function rowTitle(item, now) {
    if (!hasLink(item)) {
        return '暂无可用链接';
    }
    if (isExpired(item, now)) {
        return '已过期，仍可打开查看';
    }
    return item.url ? '在新标签页打开电脑版' : '在新标签页打开手机版';
}

/** 在新标签页打开；已过期同样允许打开，能否作答由对应平台自己限制 */
export function openItem(item, now) {
    const url = linkOf(item);
    if (!url) {
        toast('该项暂无可用链接', 'info');
        return;
    }
    window.open(url, '_blank', 'noopener,noreferrer');
}
