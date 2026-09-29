<script setup>
/**
 * 首页作业消息栏：把「待提交的作业」全部铺成一条卡片带。
 * 每张卡：平台名 + 标题 + 截止时间，底部一道状态色条（红 = 24 小时内、琥珀 = 3 天内、绿 = 还早）。
 *
 * 一行放不下时可以：滚轮横滚、按住拖动、触摸屏原生滑动；滚到两端后把滚动交回页面。
 * 加载时卡片带倾角从右侧飞入、依次落位，像一张张发牌。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { icons } from '../ui/icons';
import { openItem } from '../ui/homework';
import { formatDue, homeworkSummary, loadHomeworkSummary } from '../stores/homeworkSummary';

const router = useRouter();
const summary = homeworkSummary;

/** 刷新：与作业页的刷新同源同效 —— 忽略缓存重拉，并写回作业页共用的那份缓存 */
async function refresh() {
    // 整行会重建，先把滚动位置记下来，动画结束后回到原处
    const left = rowEl.value ? rowEl.value.scrollLeft : 0;
    await loadHomeworkSummary(true);
    await nextTick();
    if (rowEl.value && left) {
        rowEl.value.scrollLeft = left;
    }
}

const dueText = computed(() => formatDue(summary.nextDueAt));
const hasPending = computed(() => summary.pending > 0);

/** 卡片越多，逐张入场的间隔越短：排到几十张时也不会让最后一张等上好几秒 */
const staggerMs = computed(() => Math.min(55, Math.round(900 / Math.max(1, summary.jobs.length))));

/** 一句话概览：卡片带占满整条时不留文字，把它放在整条的悬浮提示上 */
const overview = computed(() => {
    const parts = [];
    if (hasPending.value) {
        parts.push(`你有 ${summary.pending} 项作业待提交`);
        if (summary.dueSoon > 0 && dueText.value) {
            parts.push(`其中 ${summary.dueSoon} 项将于 ${dueText.value} 截止`);
        }
    } else {
        parts.push('暂无待提交的作业');
    }
    if (summary.done > 0) {
        parts.push(`已完成 ${summary.done} 项`);
    }
    return parts.join('，');
});

/** 点卡片直接开对应平台的作业页；没有直达链接就退回作业查询页 */
function openJob(job) {
    if (job.url) {
        openItem(job);
    } else {
        router.push('/homework');
    }
}

// ==================== 横向滚动 ====================

const rowEl = ref(null);
/** 一行装不下（可滚）/ 已经滚到最右（右侧渐隐淡出） */
const scrollable = ref(false);
const atEnd = ref(false);

function syncScrollState() {
    const el = rowEl.value;
    if (!el) {
        scrollable.value = false;
        atEnd.value = true;
        return;
    }
    const max = el.scrollWidth - el.clientWidth;
    scrollable.value = max > 2;
    atEnd.value = el.scrollLeft >= max - 2;
}

/** 滚轮直接横滚；已经贴边滑不动时不再拦截，把滚动交回页面 */
function onWheel(e) {
    const el = rowEl.value;
    if (!el) {
        return;
    }
    const max = el.scrollWidth - el.clientWidth;
    if (max <= 2) {
        return;
    }
    const delta = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
    const next = Math.min(max, Math.max(0, el.scrollLeft + delta));
    if (next === el.scrollLeft) {
        return;
    }
    e.preventDefault();
    el.scrollLeft = next;
}

// 按住拖动：只接管鼠标 / 触控笔，触摸屏交给系统原生滑动。
// 移动与松手监听挂在 window 上（而不是 pointersetCapture），避免改掉 click 的目标元素。
let drag = null;
let dragged = false;

function onPointerDown(e) {
    const el = rowEl.value;
    if (!el || e.pointerType === 'touch' || e.button !== 0
        || el.scrollWidth - el.clientWidth <= 2) {
        return;
    }
    drag = { id: e.pointerId, x: e.clientX, left: el.scrollLeft };
    dragged = false;
    el.classList.add('dragging');
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerEnd);
    window.addEventListener('pointercancel', onPointerEnd);
}

function onPointerMove(e) {
    if (!drag || e.pointerId !== drag.id || !rowEl.value) {
        return;
    }
    const dx = e.clientX - drag.x;
    if (Math.abs(dx) > 6) {
        dragged = true;
    }
    rowEl.value.scrollLeft = drag.left - dx;
}

function onPointerEnd(e) {
    if (!drag || e.pointerId !== drag.id) {
        return;
    }
    stopDrag();
}

/** 结束拖拽：清掉状态并摘掉挂在 window 上的监听 */
function stopDrag() {
    drag = null;
    if (rowEl.value) {
        rowEl.value.classList.remove('dragging');
    }
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerEnd);
    window.removeEventListener('pointercancel', onPointerEnd);
}

/** 拖动之后紧跟的那次 click 要吞掉，否则一松手就把某张卡打开了 */
function onClickCapture(e) {
    if (dragged) {
        e.stopPropagation();
        e.preventDefault();
        dragged = false;
    }
}

onMounted(() => {
    syncScrollState();
    window.addEventListener('resize', syncScrollState);
});

onUnmounted(() => {
    window.removeEventListener('resize', syncScrollState);
    stopDrag();
});

watch(() => summary.jobs.length, () => nextTick(syncScrollState));
// 刷新后整行重建，滚动状态要重新量一次
watch(() => summary.revision, () => nextTick(syncScrollState));
</script>

<template>
    <section class="msgbar" :title="overview">
        <!-- 刷新：行为与作业页的「刷新」一致，加载中图标自转 -->
        <button type="button" class="msg-refresh" :class="{ loading: summary.loading }"
                :disabled="summary.loading"
                :aria-label="summary.loading ? '刷新中' : '刷新作业数据'"
                :title="summary.loading ? '刷新中' : '刷新作业数据'"
                @click="refresh">
            <span v-html="icons.refresh"></span>
        </button>

        <div class="msg-body">
            <!-- 全部待提交作业：一条可横向滑动的卡片带 -->
            <div v-if="summary.jobs.length" ref="rowEl" class="msg-jobs"
                 :class="{ scrollable, 'at-end': atEnd }"
                 @scroll="syncScrollState"
                 @wheel="onWheel"
                 @pointerdown="onPointerDown"
                 @click.capture="onClickCapture">
                <!-- key 绑版本号：刷新后整行重挂载，卡片重新播一遍入场动画 -->
                <TransitionGroup :key="summary.revision" appear name="job-in" tag="div" class="msg-jobs-row">
                    <button v-for="(job, i) in summary.jobs" :key="job.key" type="button" class="msg-job"
                            :style="{ '--d': `${i * staggerMs}ms` }" :title="job.hint" @click="openJob(job)">
                        <span class="mj-plat" :class="job.platform">{{ job.platformText }}</span>
                        <span class="mj-title">{{ job.title }}</span>
                        <span class="mj-due" :class="job.urgency">
                            <template v-if="job.dueClock">
                                <span class="mj-due-day">{{ job.dueDay }}</span>
                                <span class="mj-due-clock">{{ job.dueClock }}</span>
                            </template>
                            <span v-else class="mj-due-none">无截止</span>
                        </span>
                        <!-- 只在真正紧张时才出现色条：24 小时内红、3 天内琥珀，其余不加 -->
                        <span v-if="job.urgency !== 'normal'" class="mj-bar" :class="job.urgency"></span>
                    </button>
                </TransitionGroup>
            </div>

            <p v-else class="msg-line">{{ overview }}</p>

            <p v-for="item in summary.messages" :key="item.id" class="msg-line sub">
                {{ item.text }}
            </p>
        </div>
    </section>
</template>
