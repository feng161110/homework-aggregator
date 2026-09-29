<script setup>
import { computed, onMounted, ref } from 'vue';
import PlatformCard from '../components/PlatformCard.vue';
import HomeworkMessageBar from '../components/HomeworkMessageBar.vue';
import BoardEmpty from '../components/BoardEmpty.vue';
import { PLATFORMS } from '../ui/platforms';
import { homeworkSummary, loadHomeworkSummary } from '../stores/homeworkSummary';
import { useFetchHint } from '../ui/useFetchHint';
import { platformStore } from '../api/platform';
import { toast } from '../ui/toast';

/** 同一个会话里只提醒一次，避免用户从配置页返回时被反复弹回 */
const NUDGE_KEY = 'hpa.configNudged';

const loading = ref(true);
/** 是否至少绑定了一个平台；一个都没有就只显示配置引导 */
const hasBound = ref(false);

/** 页头右侧的「数据 · 3分2秒前」：取作业数据最近一次获取时间，每秒跳动 */
const { hintText, hintTitle } = useFetchHint(computed(() => homeworkSummary.ts));

onMounted(async () => {
    // 汇总数据与作业页共用缓存：有缓存立刻渲染，缺的平台才去请求
    loadHomeworkSummary();

    try {
        const list = await platformStore.list();
        hasBound.value = list.some((item) => item.bound);
        if (!hasBound.value && !sessionStorage.getItem(NUDGE_KEY)) {
            sessionStorage.setItem(NUDGE_KEY, '1');
            toast('还没有配置任何平台账号，先配置一次吧', 'info');
        }
    } catch {
        // 后端不可用时不隐藏看板，交给卡片显示离线状态
        hasBound.value = true;
    } finally {
        loading.value = false;
    }
});
</script>

<template>
    <div class="page wide">
        <div class="page-head">
            <h1 class="page-title">作业信息总览</h1>
            <span v-if="hintText" class="data-hint" :title="hintTitle">{{ hintText }}</span>
        </div>

        <template v-if="!loading && hasBound">
            <HomeworkMessageBar />
            <div class="board-grid">
                <PlatformCard v-for="platform in PLATFORMS" :key="platform.key" :platform="platform" />
            </div>
        </template>

        <BoardEmpty v-else-if="!loading" />
    </div>
</template>
