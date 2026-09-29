<script setup>
/**
 * 自绘日期 / 时间选择器。
 *
 * 为什么不用 <input type="date|time">：点开后的日历与滚轮面板是浏览器原生画的，
 * 样式改不了，跟站内风格对不上。这里用「只读按钮 + 自绘面板」替代：
 *   mode="date" → 月历（周一起排，含「今天」）
 *   mode="time" → 时 / 分两列滚动
 * 输入输出都是字符串：date 为 yyyy-MM-dd，time 为 HH:mm。
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';

const props = defineProps({
    modelValue: { type: String, default: '' },
    /** date | time */
    mode: { type: String, default: 'date' },
    ariaLabel: { type: String, default: '' },
});

const emit = defineEmits(['update:modelValue']);

const WEEK = ['一', '二', '三', '四', '五', '六', '日'];
const pad = (value) => String(value).padStart(2, '0');

const open = ref(false);
const root = ref(null);
/** 面板被 teleport 到 body，位置自己算 */
const panelRef = ref(null);
const panelStyle = ref({ position: 'fixed', top: '0px', left: '0px', visibility: 'hidden' });

// ---------------- 日期 ----------------

const now = new Date();
const viewYear = ref(now.getFullYear());
const viewMonth = ref(now.getMonth());

const selected = computed(() => {
    const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(props.modelValue || '');
    return matched ? { y: +matched[1], m: +matched[2] - 1, d: +matched[3] } : null;
});

const dateText = computed(() => {
    const value = selected.value;
    return value ? `${value.y}/${pad(value.m + 1)}/${pad(value.d)}` : '选择日期';
});

const monthText = computed(() => `${viewYear.value} 年 ${viewMonth.value + 1} 月`);

/** 6 × 7 网格：从所在周的周一开始，跨月的日子标 other */
const cells = computed(() => {
    const first = new Date(viewYear.value, viewMonth.value, 1);
    const start = new Date(viewYear.value, viewMonth.value, 1 - ((first.getDay() + 6) % 7));
    const todayKey = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    const list = [];
    for (let i = 0; i < 42; i += 1) {
        const day = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
        const key = `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
        list.push({
            key,
            day: day.getDate(),
            other: day.getMonth() !== viewMonth.value,
            today: key === todayKey,
        });
    }
    return list;
});

function shiftMonth(delta) {
    const next = new Date(viewYear.value, viewMonth.value + delta, 1);
    viewYear.value = next.getFullYear();
    viewMonth.value = next.getMonth();
}

function pickDate(cell) {
    emit('update:modelValue', cell.key);
    open.value = false;
}

function pickToday() {
    pickDate({ key: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` });
}

// ---------------- 时间 ----------------

const HOURS = Array.from({ length: 24 }, (_, i) => i);
const MINUTES = Array.from({ length: 60 }, (_, i) => i);

/**
 * 时 / 分两列都是「环」：同一份数值重复 LOOP 遍渲染。
 * 滑出中间那一份就把滚动位置平移一份 —— 三份内容完全一样，
 * 所以平移前后画面一模一样，看不出接缝，滑到底会自然接回开头。
 */
const LOOP = 3;
const HOUR_WHEEL = Array.from({ length: LOOP }, () => HOURS).flat();
const MINUTE_WHEEL = Array.from({ length: LOOP }, () => MINUTES).flat();

const hourCol = ref(null);
const minuteCol = ref(null);

const timeParts = computed(() => {
    const matched = /^(\d{1,2}):(\d{2})$/.exec(props.modelValue || '');
    return matched ? { h: +matched[1], m: +matched[2] } : { h: 0, m: 0 };
});

const timeText = computed(() => `${pad(timeParts.value.h)}:${pad(timeParts.value.m)}`);

function pickHour(hour) {
    emit('update:modelValue', `${pad(hour)}:${pad(timeParts.value.m)}`);
}

function pickMinute(minute) {
    emit('update:modelValue', `${pad(timeParts.value.h)}:${pad(minute)}`);
    open.value = false;
}

function pickNow() {
    const d = new Date();
    emit('update:modelValue', `${pad(d.getHours())}:${pad(d.getMinutes())}`);
    open.value = false;
}

/** 把某个值滚到列中间；值每份里都有，取正中间那一份来定位 */
function centerValue(col, index, total) {
    if (!col || !total) return;
    const copyHeight = col.scrollHeight / LOOP;
    if (!copyHeight) return;
    const itemHeight = copyHeight / total;
    col.scrollTop = copyHeight + index * itemHeight - (col.clientHeight - itemHeight) / 2;
}

/**
 * 滑到两端就把位置平移一份，接成一圈。
 * 只挪一份、且三份内容一致，所以既不会闪也不会跳；
 * 平移后新的 scrollTop 落回中间那份，不会再次触发。
 */
function wrapColumn(col) {
    if (!col) return;
    const copyHeight = col.scrollHeight / LOOP;
    if (copyHeight <= 0) return;
    // 触底：末份一旦露出就回退一份；触顶：首份一旦露出就前进一份
    if (col.scrollTop < copyHeight) col.scrollTop += copyHeight;
    else if (col.scrollTop > copyHeight * (LOOP - 1)) col.scrollTop -= copyHeight;
}

// ---------------- 开关 ----------------

const displayText = computed(() => (props.mode === 'date' ? dateText.value : timeText.value));

/**
 * 把面板摆到触发按钮旁边。
 * 面板挂在 body 上而不是原地：留在列表/弹窗的滚动容器里会被裁掉，
 * 还会跟着外层一起滚（看起来就像和外层共用一个滚动条）。
 */
function place() {
    const anchor = root.value?.querySelector('.adt-btn');
    if (!anchor) return;
    const rect = anchor.getBoundingClientRect();
    const GAP = 6;
    const MARGIN = 10;
    const panelWidth = props.mode === 'date' ? 268 : 172;
    const panelHeight = props.mode === 'date' ? 330 : 250;
    // 高度先夹进视口：宁可面板内部滚动，也不要被裁到看不见
    const maxHeight = Math.min(panelHeight, window.innerHeight - MARGIN * 2);
    const below = window.innerHeight - rect.bottom - GAP;
    const up = below < maxHeight && rect.top - GAP > below;
    const left = Math.min(Math.max(MARGIN, rect.left),
        Math.max(MARGIN, window.innerWidth - panelWidth - MARGIN));
    panelStyle.value = {
        position: 'fixed',
        left: `${left}px`,
        maxHeight: `${maxHeight}px`,
        visibility: 'visible',
        ...(up
            ? { bottom: `${window.innerHeight - rect.top + GAP}px`, top: 'auto' }
            : { top: `${rect.bottom + GAP}px`, bottom: 'auto' }),
    };
}

function toggle() {
    open.value = !open.value;
    if (open.value && props.mode === 'date') {
        // 打开日历时定位到已选月份，没选过就停在当月
        const value = selected.value;
        viewYear.value = value ? value.y : now.getFullYear();
        viewMonth.value = value ? value.m : now.getMonth();
    }
    if (open.value) {
        nextTick(() => {
            place();
            // 时间模式：把已选的小时 / 分钟滚到列中间
            if (props.mode === 'time') {
                centerValue(hourCol.value, timeParts.value.h, HOURS.length);
                centerValue(minuteCol.value, timeParts.value.m, MINUTES.length);
            }
        });
    }
}

/** 页面滚动/改尺寸时跟着挪（面板是 fixed，不会自己跟着走） */
function reposition() {
    if (open.value) place();
}

function onPointerDown(event) {
    if (root.value?.contains(event.target) || panelRef.value?.contains(event.target)) {
        return;
    }
    open.value = false;
}

// 面板只在「点外面 / Esc / 选定结果」时关闭。
// 不要监听 modelValue 去关：时间模式是「先点小时再点分钟」两步，
// 选完小时值就变了，一关就得重新打开，根本没法选分钟。

onMounted(() => {
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
});
onUnmounted(() => {
    document.removeEventListener('pointerdown', onPointerDown);
    window.removeEventListener('scroll', reposition, true);
    window.removeEventListener('resize', reposition);
});
</script>

<template>
    <div ref="root" class="adt" :class="{ open }">
        <button type="button" class="adt-btn" :aria-label="ariaLabel"
                :aria-expanded="open" aria-haspopup="dialog" @click="toggle"
                @keydown.esc="open = false">
            <span class="adt-value">{{ displayText }}</span>
            <span class="adt-icon" :class="mode" aria-hidden="true"></span>
        </button>

        <!-- 面板挂到 body：不再受外层滚动容器裁剪，也不与外层共用滚动条 -->
        <Teleport to="body">
        <div v-if="open" ref="panelRef" class="adt-panel" :class="mode" :style="panelStyle">
            <!-- 日期：月历 -->
            <template v-if="mode === 'date'">
                <div class="adt-head">
                    <button type="button" class="adt-nav" aria-label="上个月" @click="shiftMonth(-1)">‹</button>
                    <span class="adt-month">{{ monthText }}</span>
                    <button type="button" class="adt-nav" aria-label="下个月" @click="shiftMonth(1)">›</button>
                </div>
                <div class="adt-week">
                    <span v-for="w in WEEK" :key="w">{{ w }}</span>
                </div>
                <div class="adt-grid">
                    <button v-for="cell in cells" :key="cell.key" type="button" class="adt-day"
                            :class="{ other: cell.other, today: cell.today, on: cell.key === modelValue }"
                            @click="pickDate(cell)">{{ cell.day }}</button>
                </div>
                <div class="adt-foot">
                    <button type="button" class="rmo-chip ghost" @click="pickToday">今天</button>
                </div>
            </template>

            <!-- 时间：时 / 分 两列 -->
            <template v-else>
                <div class="adt-cols">
                    <ul ref="hourCol" class="adt-col" @scroll.passive="wrapColumn(hourCol)">
                        <li v-for="(hour, i) in HOUR_WHEEL" :key="`h${i}`">
                            <button type="button" class="adt-cell" :class="{ on: hour === timeParts.h }"
                                    @click="pickHour(hour)">{{ pad(hour) }}</button>
                        </li>
                    </ul>
                    <ul ref="minuteCol" class="adt-col" @scroll.passive="wrapColumn(minuteCol)">
                        <li v-for="(minute, i) in MINUTE_WHEEL" :key="`m${i}`">
                            <button type="button" class="adt-cell" :class="{ on: minute === timeParts.m }"
                                    @click="pickMinute(minute)">{{ pad(minute) }}</button>
                        </li>
                    </ul>
                </div>
                <div class="adt-foot">
                    <span class="adt-tip">选分钟即确定</span>
                    <button type="button" class="rmo-chip ghost" @click="pickNow">现在</button>
                </div>
            </template>
        </div>
        </Teleport>
    </div>
</template>
