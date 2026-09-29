<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { platformStore } from '../api/platform';
import { ApiError } from '../api/http';
import { icons } from '../ui/icons';
import FacetFilter from '../components/FacetFilter.vue';
import HomeworkCard from '../components/HomeworkCard.vue';
import {
    countdownOf,
    formatDateTime,
    hasLink,
    isExpired,
    kindOf,
    kindText,
    linkClass,
    linkText,
    normalizeHomework,
    openItem,
    platformName,
    remainOf,
    rowTitle,
    stateOf,
    statusClass,
    statusText,
    urgencyOf,
} from '../ui/homework';
import { CACHE_KEYS, readCache, removeCache, writeCache } from '../ui/resourceCache';
import { PREF_KEYS, readPref, writePref } from '../ui/prefs';
import { useFetchHint } from '../ui/useFetchHint';

/**
 * 作业 / 考试：学习通与智慧树的条目合并成一份列表（不再按类型分两段），默认按截止时间排序。
 * 提供多套排版，用户选的排版记在本地，下次进来还是那一套。
 */

const PLATFORM_KEYS = ['xuexitong', 'zhihuishu'];

/** 学习通没有秒级时间，反推出来的时间要如实告知误差 */
const ESTIMATE_HINT = '学习通未提供精确到秒的截止时间，此处由「剩余时长」推算，误差约 1 分钟';

/** 可选的排版（默认「大卡」） */
const LAYOUTS = [
    { key: 'hero', label: '大卡' },
    { key: 'grid', label: '网格' },
    { key: 'list', label: '列表' },
];

/** 网格排版可选的每行卡片数 */
const COLUMN_OPTIONS = [2, 4];

/** 类型 / 状态两个筛选维度的固定取值 */
const KIND_VALUES = [
    { value: 'work', label: '作业' },
    { value: 'exam', label: '考试' },
];
const STATE_VALUES = [
    { value: 'pending', label: '待处理' },
    { value: 'over', label: '已过期' },
    { value: 'done', label: '已完成' },
];

// ==================== 排版偏好 ====================

const storedLayout = readPref(PREF_KEYS.homeworkLayout, 'hero');
const layout = ref(LAYOUTS.some((opt) => opt.key === storedLayout) ? storedLayout : 'hero');

/** 网格排版每行放几张：存的是数字，读到非法值时回落默认 4 列 */
const storedCols = Number(readPref(PREF_KEYS.homeworkGridCols, '4'));
const gridCols = ref(COLUMN_OPTIONS.includes(storedCols) ? storedCols : 4);

function pickLayout(key) {
    layout.value = key;
    writePref(PREF_KEYS.homeworkLayout, key);
}

function pickCols(value) {
    gridCols.value = value;
    writePref(PREF_KEYS.homeworkGridCols, value);
}

// ==================== 平台数据槽位 ====================

const slots = reactive(Object.fromEntries(PLATFORM_KEYS.map((key) => [key, {
    items: [],
    ts: 0,
    /** idle 未加载 / ready 已就绪 / blocked 未绑定或 Cookie 失效 / error 请求失败 */
    status: 'idle',
    message: '',
    busy: false,
}])));

// 进入页面先同步读一遍缓存，避免先闪一下加载态
for (const key of PLATFORM_KEYS) {
    const cached = readCache(CACHE_KEYS.homework(key));
    if (cached) {
        slots[key].items = cached.items;
        slots[key].ts = cached.ts;
        slots[key].status = 'ready';
    }
}

/** 每秒跳动一次的当前时间：所有排版的倒计时都由它统一驱动 */
const now = ref(Date.now());
let tickTimer = null;

onMounted(() => {
    ensureLoaded(PLATFORM_KEYS);
    tickTimer = setInterval(() => {
        now.value = Date.now();
    }, 1000);
});

onUnmounted(() => {
    if (tickTimer) {
        clearInterval(tickTimer);
    }
});

/** 只请求「还没有数据」的平台，已有缓存的直接跳过 */
async function ensureLoaded(keys) {
    const todo = keys.filter((key) => slots[key] && slots[key].status === 'idle');
    await Promise.allSettled(todo.map((key) => loadPlatform(key)));
}

/** 拉取单个平台，并写入它自己的缓存 */
async function loadPlatform(key) {
    const slot = slots[key];
    if (slot.busy) {
        return;
    }
    const name = platformName(key);
    slot.busy = true;
    slot.message = '';
    try {
        const res = await platformStore.desktopLinks(key);
        if (!res?.success) {
            // 拿不到有效数据时不再信任旧缓存
            slot.items = [];
            slot.ts = 0;
            slot.status = 'blocked';
            slot.message = res?.message || `暂时无法获取${name}作业，请先在个人中心绑定${name}账号`;
            removeCache(CACHE_KEYS.homework(key));
            return;
        }
        // 摊平各课程，并给每条一个跨课程、跨平台唯一的 key（与首页消息栏共用同一份缓存）
        const loadedAt = Date.now();
        slot.items = normalizeHomework(key, res.courses, loadedAt);
        slot.ts = loadedAt;
        slot.status = 'ready';
        writeCache(CACHE_KEYS.homework(key), slot.items);
    } catch (err) {
        // 失败保留已展示的数据，只把错误提示出来
        slot.status = 'error';
        slot.message = err instanceof ApiError ? err.message : '加载失败，请稍后重试';
    } finally {
        slot.busy = false;
    }
}

async function refresh() {
    await Promise.allSettled(PLATFORM_KEYS.map((key) => loadPlatform(key)));
}

// ==================== 检索与筛选 ====================

const keyword = ref('');
const platformFilter = ref('');
const kindFilter = ref('');
const statusFilter = ref('');

/** 筛选区整块是否展开：进入界面默认折叠，需要时再展开 */
const filtersOpen = ref(false);

/**
 * 按当前条件过滤。
 * exclude 用于算「某维度选中后会有多少条」——算某个维度的数量时要把它自身的条件排除掉。
 */
function applyFilters(list, exclude = '') {
    const kw = keyword.value.trim().toLowerCase();
    return list.filter((item) => {
        if (kw && !String(item.title || '').toLowerCase().includes(kw)) {
            return false;
        }
        if (exclude !== 'platform' && platformFilter.value && item.platform !== platformFilter.value) {
            return false;
        }
        if (exclude !== 'kind' && kindFilter.value && kindOf(item) !== kindFilter.value) {
            return false;
        }
        if (exclude !== 'status' && statusFilter.value
            && stateOf(item, now.value) !== statusFilter.value) {
            return false;
        }
        return true;
    });
}

/** 生成某个维度的筛选项：数量按「当前其它条件」算，等于选中后真正能看到的条数 */
function buildOptions(exclude, allLabel, values, pick) {
    const rows = applyFilters(items.value, exclude);
    const counts = new Map();
    for (const item of rows) {
        const value = pick(item);
        counts.set(value, (counts.get(value) || 0) + 1);
    }
    return [
        { value: '', label: allLabel, count: rows.length },
        ...values.map((opt) => ({ ...opt, count: counts.get(opt.value) || 0 })),
    ];
}

const platformOptions = computed(() => buildOptions('platform', '全部平台',
    PLATFORM_KEYS.map((key) => ({ value: key, label: platformName(key) })), (item) => item.platform));
const kindOptions = computed(() => buildOptions('kind', '全部类型', KIND_VALUES, kindOf));
const statusOptions = computed(() => buildOptions('status', '全部状态', STATE_VALUES,
    (item) => stateOf(item, now.value)));

/** 已选条件：关键词在搜索框里看得见，不再重复列成标签 */
const activeChips = computed(() => {
    const chips = [];
    if (platformFilter.value) {
        chips.push({ key: 'platform', text: platformName(platformFilter.value) });
    }
    if (kindFilter.value) {
        chips.push({
            key: 'kind',
            text: KIND_VALUES.find((opt) => opt.value === kindFilter.value)?.label || kindFilter.value,
        });
    }
    if (statusFilter.value) {
        chips.push({
            key: 'status',
            text: STATE_VALUES.find((opt) => opt.value === statusFilter.value)?.label || statusFilter.value,
        });
    }
    return chips;
});

function removeChip(key) {
    if (key === 'platform') {
        platformFilter.value = '';
    } else if (key === 'kind') {
        kindFilter.value = '';
    } else if (key === 'status') {
        statusFilter.value = '';
    }
}

const hasFilter = computed(() => keyword.value.trim() !== '' || platformFilter.value !== ''
    || kindFilter.value !== '' || statusFilter.value !== '');

function resetFilters() {
    keyword.value = '';
    platformFilter.value = '';
    kindFilter.value = '';
    statusFilter.value = '';
}

// ==================== 展示 ====================

const items = computed(() => PLATFORM_KEYS.flatMap((key) => slots[key].items));

/** 还有平台没拿到数据（或在请求中） */
const pending = computed(() => PLATFORM_KEYS
    .some((key) => slots[key].busy || slots[key].status === 'idle'));

/** 拿不到数据的平台（未绑定 / Cookie 失效 / 请求失败） */
const issues = computed(() => PLATFORM_KEYS
    .map((key) => ({ key, status: slots[key].status, message: slots[key].message }))
    .filter((item) => item.status === 'blocked' || item.status === 'error'));

/**
 * 排序档位：
 *   0 未完成且未截止 —— 真正要做的事
 *   1 未完成但已截止 —— 已经交不了了，但仍是「未完成」
 *   2 已完成
 * 「已完成」不看截止时间一律后移，所以截止时间在前面但已经做完的不会占着前排。
 */
function rankOf(item, nowTs) {
    if (statusClass(item) === 'done') {
        return 2;
    }
    return isExpired(item, nowTs) ? 1 : 0;
}

/**
 * 默认排序：先按档位（未完成在前、已完成在后），同档内以截止时间为基准 ——
 * 未截止的按截止时间由近到远（最急的在最上面），已截止与已完成的按最近在前；
 * 没有截止信息的在自己那一档里排最后。作业与考试混在一起排，不再分段。
 */
function byDeadline(a, b) {
    const rankA = rankOf(a, now.value);
    const rankB = rankOf(b, now.value);
    if (rankA !== rankB) {
        return rankA - rankB;
    }
    if (Boolean(a.endTime) !== Boolean(b.endTime)) {
        return a.endTime ? -1 : 1;
    }
    return rankA === 0 ? a.endTime - b.endTime : b.endTime - a.endTime;
}

/** 列表最终展示的条目（已过滤 + 已排序） */
const visible = computed(() => applyFilters(items.value).sort(byDeadline));

const pendingCount = computed(() => visible.value
    .filter((item) => stateOf(item, now.value) === 'pending').length);
const overCount = computed(() => visible.value
    .filter((item) => stateOf(item, now.value) === 'over').length);

const showLoading = computed(() => pending.value && !items.value.length);

const emptyText = computed(() => (hasFilter.value
    ? '没有符合条件的作业或考试'
    : '暂时没有查到作业或考试。'));

/** 列表里的倒计时文案：没有截止信息时给中性说明，不硬说「已过期」 */
function remainLabel(item) {
    const left = remainOf(item, now.value);
    if (left) {
        return left;
    }
    return isExpired(item, now.value) ? '已过期' : '无截止时间';
}

/** 「数据获取于多久前」：取最近一次 */
const hintTs = computed(() => Math.max(0, ...PLATFORM_KEYS.map((key) => slots[key].ts)));
const { hintText, hintTitle } = useFetchHint(hintTs);
</script>

<template>
    <div class="page wide">
        <div class="page-head">
            <h1 class="page-title">作业查询</h1>

            <!-- 排版切换：选中的会记在本地，下次进来直接用 -->
            <div class="view-bar">
                <div class="view-switch" role="group" aria-label="选择排版">
                    <button v-for="opt in LAYOUTS" :key="opt.key" type="button"
                            :class="{ active: layout === opt.key }" :aria-pressed="layout === opt.key"
                            @click="pickLayout(opt.key)">{{ opt.label }}</button>
                </div>

                <!-- 网格排版才需要：每行放几张 -->
                <div v-if="layout === 'grid'" class="view-switch" role="group" aria-label="选择每行卡片数">
                    <button v-for="col in COLUMN_OPTIONS" :key="col" type="button"
                            :class="{ active: gridCols === col }" :aria-pressed="gridCols === col"
                            @click="pickCols(col)">{{ col }} 列</button>
                </div>
            </div>
        </div>

        <!-- 检索 + 筛选：作业与考试混排，条件对所有平台生效 -->
        <div class="agg-bar">
            <div class="agg-top">
                <label class="agg-search">
                    <span class="agg-search-ic" v-html="icons.search"></span>
                    <input v-model="keyword" type="search" autocomplete="off" spellcheck="false"
                           aria-label="搜索标题" placeholder="搜索标题" />
                    <button v-if="keyword" type="button" class="agg-clear" title="清空关键词"
                            @click="keyword = ''">
                        <span v-html="icons.x"></span>
                    </button>
                </label>

                <button type="button" class="flt-toggle" :class="{ on: filtersOpen }"
                        :aria-expanded="filtersOpen" aria-controls="homework-facets"
                        @click="filtersOpen = !filtersOpen">
                    <span class="flt-ic" v-html="icons.filter"></span>
                    <span class="flt-name">筛选</span>
                    <span v-if="activeChips.length" class="flt-badge">{{ activeChips.length }}</span>
                    <span class="flt-caret" :class="{ up: filtersOpen }" v-html="icons.chevron"></span>
                </button>
            </div>

            <div id="homework-facets" class="facet-wrap" :class="{ closed: !filtersOpen }">
                <div class="facet-inner">
                    <div class="facet-body">
                        <FacetFilter v-model="statusFilter" label="状态" :options="statusOptions" />
                        <FacetFilter v-model="kindFilter" label="类型" :options="kindOptions" />
                        <FacetFilter v-model="platformFilter" label="平台" :options="platformOptions" />
                    </div>
                </div>
            </div>

            <div v-if="hasFilter" class="flt-foot">
                <div v-if="activeChips.length" class="flt-chips">
                    <button v-for="chip in activeChips" :key="chip.key" type="button" class="flt-chip"
                            :title="`移除「${chip.text}」`" @click="removeChip(chip.key)">
                        {{ chip.text }}<span class="flt-chip-x" v-html="icons.x"></span>
                    </button>
                </div>
                <button type="button" class="flt-clear" @click="resetFilters">清除全部</button>
            </div>
        </div>

        <!-- 拿不到数据的平台：单独说明，不影响另一个平台展示 -->
        <div v-if="issues.length" class="agg-issues">
            <div v-for="issue in issues" :key="issue.key" class="notice"
                 :class="issue.status === 'error' ? 'error' : 'info'">
                <span v-html="icons.info"></span>
                <span class="agg-issue-text">{{ issue.message }}</span>
                <RouterLink v-if="issue.status === 'blocked'" class="agg-fix"
                            :to="{ path: '/platform', query: { key: issue.key } }">
                    去绑定
                </RouterLink>
            </div>
        </div>

        <!-- 首次加载：骨架屏，尺寸与卡片一致，出数据时不跳版 -->
        <div v-if="showLoading" class="hw-grid" aria-hidden="true">
            <div v-for="n in 6" :key="n" class="sk-card" :style="{ '--d': `${n * 90}ms` }"></div>
        </div>

        <template v-else>
            <div class="page-toolbar">
                <span class="muted">
                    共 {{ visible.length }} 项 · 待处理 {{ pendingCount }} · 已过期 {{ overCount }}
                </span>
                <div class="data-tools">
                    <span v-if="hintText" class="data-hint" :title="hintTitle">{{ hintText }}</span>
                    <button class="btn inline ghost" type="button" :disabled="pending" @click="refresh">
                        <span v-if="pending" class="spinner"></span>
                        <span v-else v-html="icons.refresh"></span>
                        {{ pending ? '刷新中' : '刷新' }}
                    </button>
                </div>
            </div>

            <!-- 大卡：一排一个通栏宽卡，倒计时在右侧当主角 -->
            <div v-if="visible.length && layout === 'hero'" class="hw-hero-list">
                <article v-for="item in visible" :key="item.key" class="hw-hero"
                         :class="[`u-${urgencyOf(item, now)}`, { off: !hasLink(item) }]">
                    <button type="button" class="hw-hero-main" :title="rowTitle(item, now)"
                            @click="openItem(item, now)">
                        <!-- 左：类型 / 平台 / 状态 + 标题 + 起止时间 -->
                        <span class="hw-hero-info">
                            <span class="hw-hero-badges">
                                <span class="hw-kind" :class="`kind-${kindOf(item)}`">
                                    {{ kindText(item) }}
                                </span>
                                <span class="hw-plat" :class="item.platform">
                                    {{ platformName(item.platform) }}
                                </span>
                                <span class="hw-tag" :class="statusClass(item)">{{ statusText(item) }}</span>
                            </span>

                            <strong class="hw-hero-title">{{ item.title || '未命名条目' }}</strong>

                            <span class="hw-hero-meta">
                                <span v-if="item.courseName" class="hw-course"
                                      :title="item.courseName">{{ item.courseName }}</span>
                                <span v-if="item.startTime" class="hw-time">
                                    开始 {{ formatDateTime(item.startTime) }}
                                </span>
                                <span v-if="item.endTime" class="hw-time">
                                    截止 {{ formatDateTime(item.endTime) }}<span v-if="item.estimated"
                                        class="hw-est" :title="ESTIMATE_HINT">估算</span>
                                </span>
                                <span v-if="!item.startTime && !item.endTime" class="hw-time">
                                    平台未提供时间信息
                                </span>
                            </span>
                        </span>

                        <!-- 右：巨型倒计时 + 链接 -->
                        <span class="hw-hero-side">
                            <span class="hw-hero-time">
                                <template v-if="countdownOf(item, now)">
                                    <!-- 不满一天时不出「0 天」，直接把倒计时放大当主角 -->
                                    <template v-if="countdownOf(item, now).days > 0">
                                        <span class="hw-hero-num">{{ countdownOf(item, now).days }}</span>
                                        <span class="hw-hero-unit">天</span>
                                    </template>
                                    <span class="hw-hero-clock"
                                          :class="{ solo: !countdownOf(item, now).days }"
                                          :title="item.estimated ? ESTIMATE_HINT : ''">
                                        {{ countdownOf(item, now).clock }}
                                    </span>
                                </template>
                                <span v-else class="hw-hero-num">
                                    {{ isExpired(item, now) ? '已过期' : (remainOf(item, now) || '无截止时间') }}
                                </span>
                            </span>

                            <span class="hw-tag" :class="linkClass(item, now)">
                                <span v-if="hasLink(item)" class="hw-tag-ic"
                                      v-html="icons.external"></span>{{ linkText(item, now) }}
                            </span>
                        </span>
                    </button>
                </article>
            </div>

            <!-- 网格：每行 2 列还是 4 列由用户选，卡片本身不变 -->
            <div v-else-if="visible.length && layout === 'grid'" class="hw-grid"
                 :class="`cols-${gridCols}`">
                <HomeworkCard v-for="item in visible" :key="item.key" :item="item" :now="now"
                              :estimate-hint="ESTIMATE_HINT" />
            </div>

            <!-- 列表：一屏看最多 -->
            <div v-else-if="visible.length && layout === 'list'" class="hw-rows">
                <button v-for="item in visible" :key="item.key" type="button" class="hw-row"
                        :class="{ off: !hasLink(item) }" :title="rowTitle(item, now)"
                        @click="openItem(item, now)">
                    <span class="hw-row-time" :class="urgencyOf(item, now)">
                        {{ remainLabel(item) }}
                    </span>
                    <span class="hw-row-title">{{ item.title || '未命名条目' }}</span>
                    <span v-if="item.courseName" class="hw-row-course"
                          :title="item.courseName">{{ item.courseName }}</span>
                    <span class="hw-kind" :class="`kind-${kindOf(item)}`">{{ kindText(item) }}</span>
                    <span class="hw-plat" :class="item.platform">{{ platformName(item.platform) }}</span>
                    <span class="hw-tag" :class="statusClass(item)">{{ statusText(item) }}</span>
                    <span class="hw-tag" :class="linkClass(item, now)">{{ linkText(item, now) }}</span>
                </button>
            </div>

            <div v-else class="agg-empty">
                <span class="agg-empty-ic" v-html="hasFilter ? icons.search : icons.clipboard"></span>
                <p class="muted">{{ emptyText }}</p>
                <button v-if="hasFilter" class="btn ghost inline" type="button" @click="resetFilters">
                    清除筛选
                </button>
                <button v-else class="btn ghost inline" type="button" :disabled="pending" @click="refresh">
                    重新查询
                </button>
            </div>
        </template>
    </div>
</template>
