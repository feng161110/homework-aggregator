/**
 * 首页「作业消息」汇总。
 *
 * 数据来源与作业页完全一致：先同步读本地缓存（打开首页立刻就有内容），
 * 只有「没有缓存」的平台才去请求接口，结果仍旧写回作业页共用的那份缓存 ——
 * 所以首页拉过的数据，进作业页是秒开的，反之亦然。
 *
 * 汇总只做三件事：数出待提交 / 即将截止 / 已完成，并挑出要在消息栏展示的待提交作业。
 */
import { reactive } from 'vue';
import { ApiError } from '../api/http';
import { platformStore } from '../api/platform';
import { normalizeHomework, platformName, stateOf, urgencyOf } from '../ui/homework';
import { CACHE_KEYS, readCache, removeCache, writeCache } from '../ui/resourceCache';

/** 与作业页保持一致：只有这两个平台提供作业 / 考试链接 */
const PLATFORM_KEYS = ['xuexitong', 'zhihuishu'];

/** 24 小时内截止算「即将截止」 */
const DUE_SOON_MS = 86400000;

export const homeworkSummary = reactive({
    loading: false,
    /** 缓存或接口至少给过一次数据（用来区分「还没拿到」和「确实没有」） */
    loaded: false,
    /** 待提交作业数（未完成且未截止） */
    pending: 0,
    /** 其中 24 小时内截止的数量 */
    dueSoon: 0,
    /** 已完成数 */
    done: 0,
    /** 各平台缓存里最新的一次获取时间（0 表示还没数据），界面据此显示「数据 · 多久前」 */
    ts: 0,
    /** 最近一项截止时间（ISO 字符串） */
    nextDueAt: null,
    /** 待提交的作业，按截止时间由近到远（全部，不截断；界面一条滑不完可横滑） */
    jobs: [],
    /** 数据版本号：每次「刷新」后 +1，界面据此重播一次卡片入场动画 */
    revision: 0,
    /** 系统消息：凭证失效、平台异常等，形如 [{ id, text }] */
    messages: [],
});

/** 把 ISO 时间说成人话：今天 / 明天 / 后天 / M 月 D 日 + 时分 */
export function formatDue(iso) {
    if (!iso) return '';
    const target = new Date(iso);
    if (Number.isNaN(target.getTime())) return '';

    const now = new Date();
    const dayStart = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    const dayDiff = Math.round((dayStart(target) - dayStart(now)) / 86400000);
    const time = target.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });

    if (dayDiff === 0) return `今天 ${time}`;
    if (dayDiff === 1) return `明天 ${time}`;
    if (dayDiff === 2) return `后天 ${time}`;
    return `${target.getMonth() + 1} 月 ${target.getDate()} 日 ${time}`;
}

function isoOf(ms) {
    return ms ? new Date(ms).toISOString() : null;
}

function pad(value) {
    return String(value).padStart(2, '0');
}

/**
 * 卡片底部那行的截止时间，拆成「日期 + 时刻」两段：2026-09-28 / 21:59。
 * 日期写全年月日，和作业页的时间格式一致；没有截止时间时两段都为空。
 * 拆开是为了让日期小字、时刻大字，扫一眼先看到「几点」。
 */
function dueParts(ms) {
    if (!ms) {
        return { day: '', clock: '', text: '无截止时间' };
    }
    const target = new Date(ms);
    const day = `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}`;
    const clock = `${pad(target.getHours())}:${pad(target.getMinutes())}`;
    return { day, clock, text: `${day} ${clock}` };
}

/** 用一批条目重算汇总。已过期的条目算「过期」而不是「待提交」，不进消息栏。 */
function applySummary(items) {
    const now = Date.now();
    let pending = 0;
    let dueSoon = 0;
    let done = 0;
    const jobs = [];

    for (const item of items) {
        const state = stateOf(item, now);
        if (state === 'done') {
            done += 1;
            continue;
        }
        if (state !== 'pending') {
            continue;
        }
        pending += 1;
        if (item.endTime && item.endTime - now < DUE_SOON_MS) {
            dueSoon += 1;
        }
        jobs.push(item);
    }

    // 截止时间由近到远；没有截止时间的排最后（排序稳定）
    jobs.sort((a, b) => (a.endTime || Number.MAX_SAFE_INTEGER) - (b.endTime || Number.MAX_SAFE_INTEGER));

    homeworkSummary.pending = pending;
    homeworkSummary.dueSoon = dueSoon;
    homeworkSummary.done = done;
    homeworkSummary.nextDueAt = jobs.length ? isoOf(jobs[0].endTime) : null;
    homeworkSummary.jobs = jobs.map((item) => {
        const name = platformName(item.platform);
        const title = item.title || '未命名条目';
        const due = formatDue(isoOf(item.endTime));
        const parts = dueParts(item.endTime);
        return {
            key: item.key,
            title,
            platform: item.platform,
            /** 课程名（提醒页「导入作业」时带出来） */
            courseName: item.courseName || '',
            /** 截止时间戳（毫秒）；提醒页用它算建议的提醒时间 */
            endTime: item.endTime || null,
            /** 平台名（卡片左上角的小标签） */
            platformText: name,
            /** 紧迫度：urgent / soon / normal，决定卡片底部那道状态色条 */
            urgency: urgencyOf(item, now),
            /** 截止时间的两段：日子（小字）与时刻（大字） */
            dueDay: parts.day,
            dueClock: parts.clock,
            /** 悬浮提示：完整信息 */
            hint: `${name} · ${title}${due ? ` · 截止 ${due}` : ''}`,
            url: item.url || item.mobileUrl || '',
        };
    });
    homeworkSummary.ts = latestTs();
    homeworkSummary.loaded = true;
}

/** 已缓存平台的全部条目（单平台缓存缺失时按「这个平台还没数据」处理） */
function cachedItems() {
    return PLATFORM_KEYS.flatMap((key) => readCache(CACHE_KEYS.homework(key))?.items || []);
}

/** 各平台缓存里最新的一次获取时间；一个缓存都没有时为 0 */
function latestTs() {
    return Math.max(0, ...PLATFORM_KEYS.map((key) => readCache(CACHE_KEYS.homework(key))?.ts || 0));
}

/** 进页面先用缓存同步渲染一次，避免先闪一下空态 */
if (PLATFORM_KEYS.some((key) => readCache(CACHE_KEYS.homework(key)))) {
    applySummary(cachedItems());
}

/**
 * 单平台取数：已有缓存就直接用（返回 false），没有才请求并写回缓存（返回 true）。
 * force = true 时忽略缓存重拉，口径与作业页的「刷新」一致：
 * 拿不到有效数据就清掉旧缓存，网络异常则保留已展示的数据。
 */
async function loadPlatform(key, force) {
    if (!force && readCache(CACHE_KEYS.homework(key))) {
        return false;
    }
    try {
        const res = await platformStore.desktopLinks(key);
        if (!res?.success) {
            removeCache(CACHE_KEYS.homework(key));
            homeworkSummary.messages.push({
                id: `homework-${key}`,
                text: res?.message || `暂时无法获取${platformName(key)}作业`,
            });
            return false;
        }
        writeCache(CACHE_KEYS.homework(key), normalizeHomework(key, res.courses, Date.now()));
        return true;
    } catch (err) {
        homeworkSummary.messages.push({
            id: `homework-${key}`,
            text: err instanceof ApiError
                ? `${platformName(key)}：${err.message}`
                : `${platformName(key)}作业加载失败，请稍后重试`,
        });
        return false;
    }
}

/** 同一个请求只跑一遍：从别的路由切回首页时不会重复打接口 */
let inflight = null;

/**
 * 退出登录 / 换账号时复位汇总。
 * 这个 store 是模块级单例，不跟着 token 走，不清就会把上一个账号的作业留在首页。
 */
export function resetHomeworkSummary() {
    homeworkSummary.loading = false;
    homeworkSummary.loaded = false;
    homeworkSummary.pending = 0;
    homeworkSummary.dueSoon = 0;
    homeworkSummary.done = 0;
    homeworkSummary.ts = 0;
    homeworkSummary.nextDueAt = null;
    homeworkSummary.jobs = [];
    homeworkSummary.messages = [];
    homeworkSummary.revision += 1; // 让消息栏整行重建，动画与滚动状态一起归零
    inflight = null;
}

/**
 * 加载汇总：并行补拉「还没有缓存」的平台，随后按缓存重算一次。
 * 已有缓存、无需请求时不会重复算（渲染时已经算过了）。
 * force = true 时忽略缓存全部重拉，等于作业页点「刷新」的效果。
 */
export function loadHomeworkSummary(force = false) {
    if (inflight) {
        // 已有请求在跑：普通调用复用它；强制刷新等它收尾后再补拉一次，避免「点了没反应」
        return force ? inflight.then(() => (inflight || loadHomeworkSummary(true))) : inflight;
    }
    inflight = (async () => {
        homeworkSummary.loading = true;
        homeworkSummary.messages = [];
        if (force) {
            // 刷新期间先清空，避免旧数据与新结果混着显示
            homeworkSummary.jobs = [];
        }
        try {
            await Promise.allSettled(PLATFORM_KEYS.map((key) => loadPlatform(key, force)));
            // 缓存是唯一真源：无论这次有没有真的拉到数据都按缓存重算一遍。
            // 否则某个平台失败时会一直沿用上一次的结果（换账号后就是上一个账号的作业）。
            applySummary(cachedItems());
        } finally {
            homeworkSummary.loading = false;
            homeworkSummary.loaded = true;
            inflight = null;
            if (force) {
                // 手动刷新后让卡片重播一次入场动画，明确给出「已经拉过一遍」的反馈
                homeworkSummary.revision += 1;
            }
        }
    })();
    return inflight;
}
