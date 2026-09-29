<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { platformStore } from '../api/platform';
import { ApiError } from '../api/http';
import { icons } from '../ui/icons';
import CourseCard from '../components/CourseCard.vue';
import FacetFilter from '../components/FacetFilter.vue';
import { findPlatform } from '../ui/platforms';
import { CACHE_KEYS, readCache, removeCache, writeCache } from '../ui/resourceCache';
import { PREF_KEYS, readPref, readPrefJson, writePref, writePrefJson } from '../ui/prefs';
import { useFetchHint } from '../ui/useFetchHint';

/**
 * 课程页只有一条渲染路径。
 *
 * 数据源：每个平台一个槽位（slots）。
 * 「汇总」= 覆盖全部平台；某个平台 Tab = 把范围收窄到那一个平台。
 * 两者共用同一套「检索 / 筛选菜单 / 计数 / 列表渲染」。
 *
 * 检索只匹配课程名；教师、学期、状态由数据自动提取成折叠式筛选项，
 * 每个选项的数量都是「选中它能筛出多少门」（算某维度数量时排除该维度自身条件）。
 */

const PLATFORM_KEYS = ['xuexitong', 'yuketang', 'zhihuishu'];
const PLATFORMS = PLATFORM_KEYS.map((key) => findPlatform(key)).filter(Boolean);

/** 汇总 Tab 的 key */
const AGG_KEY = 'all';
const TABS = [{ key: AGG_KEY, name: '汇总', icon: icons.grid }, ...PLATFORMS];

/**
 * 默认排序的批次，严格按此先后：
 *   0 雨课堂·最新学期
 *   1 智慧树·最新学期
 *   2 学习通（不提供学期字段，整批夹在两批之间）
 *   3 雨课堂·旧学期
 *   4 智慧树·旧学期
 */
const TERM_PLATFORMS = ['yuketang', 'zhihuishu'];
/** 无学期平台（学习通）的批次：跟在各平台最新学期之后 */
const NO_TERM_BUCKET = TERM_PLATFORMS.length;
/** 旧学期批次的起点：紧跟在无学期平台之后 */
const OLD_TERM_BUCKET = NO_TERM_BUCKET + 1;

/** 可选的排版：网格（默认）与大卡（一排一个通栏宽卡） */
const COURSE_LAYOUTS = [
    { key: 'grid', label: '网格' },
    { key: 'hero', label: '大卡' },
];

/** 课程没填教师 / 学期时的归类文案（同时也是筛选项的取值） */
const NO_TEACHER = '未知教师';
const NO_TERM = '未标注学期';

/** 状态的展示文案（折叠时用它显示已选条件） */
const STATUS_LABELS = { active: '在学', ended: '已结束' };

function nameOf(key) {
    return findPlatform(key)?.name || '平台';
}

function textOf(raw) {
    return raw == null ? '' : String(raw).trim();
}

function teacherOf(course) {
    return textOf(course.teacher) || NO_TEACHER;
}

/**
 * 学期文案归一。
 * 同一个学期在不同平台写法不同（空格、「（秋）」、「学年」等），
 * 直接拿来当筛选项就会在菜单里重复，所以统一成「YYYY-YYYY学年 第N学期」。
 * 认不出的写法原样保留，不做强行合并。
 */
function canonicalTerm(raw) {
    const value = textOf(raw);
    if (!value) {
        return '';
    }
    const compact = value.replace(/[\s\u00a0\u3000]+/g, '');
    const matched = compact.match(/^(\d{4})-(\d{4})学年?第([一二三四1234])学期/);
    if (!matched) {
        return value;
    }
    const seq = { 一: 1, 二: 2, 三: 3, 四: 4 }[matched[3]] || Number(matched[3]);
    return `${matched[1]}-${matched[2]}学年 第${seq}学期`;
}

function termOf(course) {
    return canonicalTerm(course.term) || NO_TERM;
}

/**
 * 学期排序键：`2025-2026学年 第2学期` → 20252，越大越新。
 * 认不出学期的（学习通不提供学期）记为 -1，排在最后。
 */
function termScoreOf(course) {
    const matched = termOf(course).match(/^(\d{4})-(\d{4})学年 第(\d)学期/);
    if (!matched) {
        return -1;
    }
    return Number(matched[1]) * 10 + Number(matched[3]);
}

/** 课程在默认排序里所属的批次：学期平台分「最新 / 旧」两批，其余平台整体一批 */
function bucketOf(platform, isLatestTerm) {
    const index = TERM_PLATFORMS.indexOf(platform);
    if (index < 0) {
        return NO_TERM_BUCKET;
    }
    return isLatestTerm ? index : OLD_TERM_BUCKET + index;
}

function statusOf(course) {
    return course.status === 'ended' ? 'ended' : 'active';
}

// ==================== 平台数据槽位 ====================

// ==================== 排版偏好 ====================

const storedLayout = readPref(PREF_KEYS.coursesLayout, 'grid');
const courseLayout = ref(COURSE_LAYOUTS.some((opt) => opt.key === storedLayout) ? storedLayout : 'grid');

function pickCourseLayout(key) {
    courseLayout.value = key;
    writePref(PREF_KEYS.coursesLayout, key);
}

// ==================== 置顶 ====================

/**
 * 置顶的课程键，存在本地偏好里（与页面数据缓存分开存放，退出登录清缓存不会把它清掉）。
 * 键与列表项一致（`平台_courseId_clazzId`），所以跨 Tab、跨刷新都能对上同一门课。
 */
const storedPins = readPrefJson(PREF_KEYS.coursesPinned, []);
const pinnedKeys = reactive(new Set(Array.isArray(storedPins)
    ? storedPins.filter((key) => typeof key === 'string')
    : []));

/** 置顶 / 取消置顶，并立刻落盘 */
function togglePin(key) {
    if (pinnedKeys.has(key)) {
        pinnedKeys.delete(key);
    } else {
        pinnedKeys.add(key);
    }
    writePrefJson(PREF_KEYS.coursesPinned, [...pinnedKeys]);
}

const platform = ref(AGG_KEY);
const isAggregate = computed(() => platform.value === AGG_KEY);
/** 当前 Tab 覆盖的平台 */
const scopeKeys = computed(() => (isAggregate.value ? PLATFORM_KEYS : [platform.value]));
const scopeName = computed(() => (isAggregate.value ? '汇总' : nameOf(platform.value)));

/**
 * 每个平台一份状态，是页面唯一的数据源。
 * items / ts 与单平台 Tab 共用同一份 localStorage 缓存，
 * 所以汇总拉过的平台，切到它自己的 Tab 是秒开的。
 */
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
    const cached = readCache(CACHE_KEYS.courses(key));
    if (cached) {
        slots[key].items = cached.items;
        slots[key].ts = cached.ts;
        slots[key].status = 'ready';
    }
}

/** 本 Tab 范围内还有平台没拿到数据 */
const pending = computed(() => scopeKeys.value
    .some((key) => slots[key].status === 'idle' || slots[key].busy));

/** 本 Tab 范围内拿不到数据的平台（未绑定 / Cookie 失效 / 请求失败） */
const issues = computed(() => scopeKeys.value
    .map((key) => ({ key, status: slots[key].status, message: slots[key].message }))
    .filter((item) => item.status === 'blocked' || item.status === 'error'));

/** 「数据获取于多久前」：取本 Tab 范围内最近一次 */
const hintTs = computed(() => Math.max(0, ...scopeKeys.value.map((key) => slots[key].ts)));
const { hintText, hintTitle } = useFetchHint(hintTs);

// ==================== 检索与筛选 ====================

const keyword = ref('');
const teacherFilter = ref('');
const termFilter = ref('');
const statusFilter = ref('');

/** 筛选区整块是否展开：进入界面默认折叠，需要时再展开 */
const filtersOpen = ref(false);

/**
 * 所有已加载平台的课程摊平成一份。
 * 检索文本、学期分值与排序批次都只在这里算一次，筛选时不再重复计算。
 */
const courseItems = computed(() => {
    // 每个平台自己的最新学期：用来把「当前学期的课」和「往期课程」分成两批
    const latestTerm = {};
    for (const key of PLATFORM_KEYS) {
        let best = -1;
        for (const course of slots[key].items) {
            best = Math.max(best, termScoreOf(course));
        }
        latestTerm[key] = best;
    }
    return PLATFORM_KEYS.flatMap((key) => slots[key].items.map((course) => {
        const termScore = termScoreOf(course);
        const itemKey = `${key}_${course.courseId || ''}_${course.clazzId || ''}`;
        return {
            key: itemKey,
            platform: key,
            course,
            name: textOf(course.courseName).toLowerCase(),
            termScore,
            bucket: bucketOf(key, termScore === latestTerm[key]),
            pinned: pinnedKeys.has(itemKey),
        };
    }));
});

/** 当前 Tab 范围内的课程 */
const scopedItems = computed(() => (isAggregate.value
    ? courseItems.value
    : courseItems.value.filter((item) => item.platform === platform.value)));

/**
 * 按当前条件过滤。
 * exclude 用于算「某维度选中后会有多少条」——算某个维度的数量时要把它自身的条件排除掉。
 */
function applyFilters(list, exclude = '') {
    const kw = keyword.value.trim().toLowerCase();
    return list.filter((item) => {
        if (kw && !item.name.includes(kw)) {
            return false;
        }
        if (exclude !== 'teacher' && teacherFilter.value
            && teacherOf(item.course) !== teacherFilter.value) {
            return false;
        }
        if (exclude !== 'term' && termFilter.value && termOf(item.course) !== termFilter.value) {
            return false;
        }
        if (exclude !== 'status' && statusFilter.value
            && statusOf(item.course) !== statusFilter.value) {
            return false;
        }
        return true;
    });
}

/**
 * 默认排序：置顶的课永远排在最前（置顶之间仍按下面的批次规则），其余按批次由前到后 ——
 * 雨课堂最新学期 → 智慧树最新学期 → 学习通（全部） → 雨课堂旧学期 → 智慧树旧学期，
 * 同批内按学期由新到旧。
 * 排序稳定，同平台同学期的课程保持接口返回的先后顺序。
 */
function byDefaultOrder(a, b) {
    if (a.pinned !== b.pinned) {
        return a.pinned ? -1 : 1;
    }
    if (a.bucket !== b.bucket) {
        return a.bucket - b.bucket;
    }
    return b.termScore - a.termScore;
}

/** 列表最终展示的课程 */
const visible = computed(() => applyFilters(scopedItems.value).sort(byDefaultOrder));

/**
 * 生成某个维度的筛选项：
 * 取值来自全部已加载课程（切 Tab 时菜单不跳变），
 * 数量按「当前 Tab + 关键词 + 其它维度」算，等于选中后真正能看到的门数。
 */
function facetOptions(key, pick) {
    const counts = new Map();
    for (const item of applyFilters(scopedItems.value, key)) {
        const value = pick(item.course);
        counts.set(value, (counts.get(value) || 0) + 1);
    }
    const values = new Set(counts.keys());
    for (const item of courseItems.value) {
        values.add(pick(item.course));
    }
    return [...values]
        .map((value) => ({ value, label: value, count: counts.get(value) || 0 }))
        .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh-Hans-CN'));
}

const teacherOptions = computed(() => [
    { value: '', label: '全部教师', count: applyFilters(scopedItems.value, 'teacher').length },
    ...facetOptions('teacher', teacherOf),
]);

const termOptions = computed(() => [
    { value: '', label: '全部学期', count: applyFilters(scopedItems.value, 'term').length },
    ...facetOptions('term', termOf),
]);

const statusOptions = computed(() => {
    const rows = applyFilters(scopedItems.value, 'status');
    let active = 0;
    for (const item of rows) {
        if (statusOf(item.course) === 'active') {
            active += 1;
        }
    }
    return [
        { value: '', label: '全部状态', count: rows.length },
        { value: 'active', label: '在学', count: active },
        { value: 'ended', label: '已结束', count: rows.length - active },
    ];
});

/** Tab 上的数量：跨全部平台统计命中数，未加载的平台不显示数字 */
const tabHits = computed(() => {
    const hits = applyFilters(courseItems.value);
    const counts = { [AGG_KEY]: hits.length };
    for (const key of PLATFORM_KEYS) {
        counts[key] = 0;
    }
    for (const item of hits) {
        counts[item.platform] += 1;
    }
    return counts;
});

function tabCount(key) {
    if (key === AGG_KEY) {
        return tabHits.value[AGG_KEY];
    }
    return slots[key].status === 'ready' ? tabHits.value[key] : null;
}

const hasFilter = computed(() => keyword.value.trim() !== '' || teacherFilter.value !== ''
    || termFilter.value !== '' || statusFilter.value !== '');

const showLoading = computed(() => pending.value && !visible.value.length);

const emptyText = computed(() => {
    if (hasFilter.value) {
        return '没有符合条件的课程';
    }
    return isAggregate.value
        ? '暂时没有查到任何平台的课程'
        : `暂时没有查到${scopeName.value}的课程`;
});
const emptyIcon = computed(() => (hasFilter.value ? icons.search : icons.clipboard));

function resetFilters() {
    keyword.value = '';
    teacherFilter.value = '';
    termFilter.value = '';
    statusFilter.value = '';
}

/** 已选条件：关键词在搜索框里看得见，不再重复列成标签 */
const activeChips = computed(() => {
    const chips = [];
    if (termFilter.value) {
        chips.push({ key: 'term', text: termFilter.value });
    }
    if (teacherFilter.value) {
        chips.push({ key: 'teacher', text: teacherFilter.value });
    }
    if (statusFilter.value) {
        chips.push({
            key: 'status',
            text: STATUS_LABELS[statusFilter.value] || statusFilter.value,
        });
    }
    return chips;
});

function removeChip(key) {
    if (key === 'term') {
        termFilter.value = '';
    } else if (key === 'teacher') {
        teacherFilter.value = '';
    } else if (key === 'status') {
        statusFilter.value = '';
    }
}

// ==================== 加载 ====================

onMounted(() => {
    ensureLoaded(scopeKeys.value);
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
    const name = nameOf(key);
    slot.busy = true;
    slot.message = '';
    try {
        const res = await platformStore.courses(key);
        if (!res?.success) {
            // 拿不到有效数据时不再信任旧缓存
            slot.items = [];
            slot.ts = 0;
            slot.status = 'blocked';
            slot.message = res?.message || `暂时无法获取${name}课程，请先在个人中心绑定账号`;
            removeCache(CACHE_KEYS.courses(key));
            return;
        }
        slot.items = res.courses || [];
        slot.ts = Date.now();
        slot.status = 'ready';
        writeCache(CACHE_KEYS.courses(key), slot.items);
    } catch (err) {
        // 失败保留已展示的数据，只把错误提示出来
        slot.status = 'error';
        slot.message = err instanceof ApiError ? err.message : '加载失败，请稍后重试';
    } finally {
        slot.busy = false;
    }
}

/** 切换 Tab：只补拉这个 Tab 还没拿到的平台 */
function selectPlatform(key) {
    if (key === platform.value) {
        return;
    }
    platform.value = key;
    ensureLoaded(key === AGG_KEY ? PLATFORM_KEYS : [key]);
}

/** 刷新：汇总刷全部，单平台只刷自己 */
async function refresh() {
    await Promise.allSettled(scopeKeys.value.map((key) => loadPlatform(key)));
}

/** 图片加载失败：清掉对应字段，避免留一个破图占位 */
function clearImage(course, field) {
    course[field] = '';
}
</script>

<template>
    <div class="page wide">
        <div class="page-head">
            <h1 class="page-title">课程管理</h1>

            <!-- 排版切换：选中的记在本地，下次进来直接用 -->
            <div class="view-switch" role="group" aria-label="选择排版">
                <button v-for="opt in COURSE_LAYOUTS" :key="opt.key" type="button"
                        :class="{ active: courseLayout === opt.key }"
                        :aria-pressed="courseLayout === opt.key"
                        @click="pickCourseLayout(opt.key)">{{ opt.label }}</button>
            </div>
        </div>

        <!-- 平台切换：唯一的平台控制器（点卡片上的平台角标也走这里） -->
        <div class="course-tabs" role="tablist" aria-label="选择平台">
            <button v-for="tab in TABS" :key="tab.key" type="button" role="tab"
                    class="course-tab" :class="{ active: tab.key === platform }"
                    :aria-selected="tab.key === platform" @click="selectPlatform(tab.key)">
                <span v-html="tab.icon"></span>{{ tab.name }}
                <span v-if="tabCount(tab.key) !== null" class="course-tab-n">{{ tabCount(tab.key) }}</span>
            </button>
        </div>

        <!-- 检索 + 筛选：所有 Tab 通用，切 Tab 时条件会保留 -->
        <div class="agg-bar">
            <div class="agg-top">
                <label class="agg-search">
                    <span class="agg-search-ic" v-html="icons.search"></span>
                    <input v-model="keyword" type="search" autocomplete="off" spellcheck="false"
                           aria-label="搜索课程名" placeholder="搜索课程名" />
                    <button v-if="keyword" type="button" class="agg-clear" title="清空关键词"
                            @click="keyword = ''">
                        <span v-html="icons.x"></span>
                    </button>
                </label>

                <!-- 筛选按钮紧贴输入栏右侧，点一下展开 / 收起整块筛选区 -->
                <button type="button" class="flt-toggle" :class="{ on: filtersOpen }"
                        :aria-expanded="filtersOpen" aria-controls="course-facets"
                        @click="filtersOpen = !filtersOpen">
                    <span class="flt-ic" v-html="icons.filter"></span>
                    <span class="flt-name">筛选</span>
                    <span v-if="activeChips.length" class="flt-badge">{{ activeChips.length }}</span>
                    <span class="flt-caret" :class="{ up: filtersOpen }" v-html="icons.chevron"></span>
                </button>
            </div>

            <!-- 0fr → 1fr：整块筛选区高度自适应地展开 / 收起 -->
            <div id="course-facets" class="facet-wrap" :class="{ closed: !filtersOpen }">
                <div class="facet-inner">
                    <div class="facet-body">
                        <FacetFilter v-model="termFilter" label="学期" :options="termOptions" />
                        <FacetFilter v-model="teacherFilter" label="教师" :options="teacherOptions" />
                        <FacetFilter v-model="statusFilter" label="状态" :options="statusOptions" />
                    </div>
                </div>
            </div>

            <!-- 已选条件：折叠状态下也一眼看得清，可逐个移除 -->
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

        <!-- 拿不到数据的平台：单独说明，不影响其它平台展示 -->
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

        <!-- 首次加载：骨架屏，尺寸与真实卡片一致，出数据时不跳版 -->
        <div v-if="showLoading" class="course-grid" aria-hidden="true">
            <div v-for="n in 6" :key="n" class="sk-card" :style="{ '--d': `${n * 90}ms` }"></div>
        </div>

        <template v-else>
            <div class="page-toolbar">
                <span class="muted">
                    {{ scopeName }} · 共 {{ visible.length }} 门课程<template
                        v-if="hasFilter"> · 全部 {{ scopedItems.length }} 门</template>
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

            <TransitionGroup v-if="visible.length" name="agg" tag="div" class="course-grid agg-grid"
                             :class="{ 'hero-mode': courseLayout === 'hero' }">
                <div v-for="item in visible" :key="item.key" class="agg-cell">
                    <CourseCard :course="item.course"
                                :platform="isAggregate ? item.platform : ''"
                                :pinned="item.pinned"
                                @pick-platform="selectPlatform($event)"
                                @toggle-pin="togglePin(item.key)"
                                @image-error="(field) => clearImage(item.course, field)" />
                </div>
            </TransitionGroup>

            <div v-else class="agg-empty">
                <span class="agg-empty-ic" v-html="emptyIcon"></span>
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
