/**
 * 学习通浏览器助手（用户脚本注入的 window.__CX_HELPER__）的探测。
 *
 * 模块级单例：多个卡片/面板共用同一个轮询，避免每个组件各起一个定时器。
 * 轮询只用于刷新“是否检测到助手”，不触发任何凭证采集。
 */
import { onUnmounted, ref } from 'vue';

export const helperReady = ref(false);
export const helperVersion = ref('');

export function detectHelper() {
    const helper = window.__CX_HELPER__;
    helperReady.value = Boolean(helper);
    helperVersion.value = helper?.version ? ` v${helper.version}` : '';
    return helperReady.value;
}

let timer = null;
let subscribers = 0;

/** 组件内调用：自动开始/结束探测 */
export function useHelperWatch() {
    subscribers += 1;
    if (!timer) {
        detectHelper();
        timer = setInterval(detectHelper, 3000);
    }
    detectHelper();

    onUnmounted(() => {
        subscribers -= 1;
        if (subscribers <= 0 && timer) {
            clearInterval(timer);
            timer = null;
        }
    });

    return { helperReady, helperVersion };
}
