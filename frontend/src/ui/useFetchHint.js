/** 数据来源提示：显示「数据获取于多久前」，每秒实时跳动 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { formatElapsed, formatTime } from './validate';

/** 每秒一跳，保证「多久前」的秒数实时变化 */
const TICK_MS = 1000;

/**
 * @param {object} fetchedAt 数据获取时间戳的 ref（0 表示还没有数据）
 * @returns {{ hintText: import('vue').ComputedRef<string>, hintTitle: import('vue').ComputedRef<string> }}
 */
export function useFetchHint(fetchedAt) {
    const nowTick = ref(Date.now());
    let timer = null;

    onMounted(() => {
        timer = setInterval(() => {
            nowTick.value = Date.now();
        }, TICK_MS);
    });

    onUnmounted(() => {
        if (timer) {
            clearInterval(timer);
        }
    });

    const ageMs = computed(() => (fetchedAt.value ? nowTick.value - fetchedAt.value : 0));
    const hintText = computed(() => (fetchedAt.value ? `数据 · ${formatElapsed(ageMs.value)}前` : ''));
    const hintTitle = computed(() => (fetchedAt.value
        ? `数据获取于 ${formatTime(new Date(fetchedAt.value).toISOString())}`
        : ''));

    return { hintText, hintTitle };
}
