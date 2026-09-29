/** 轻提示：全局响应式队列 + toast() 调用入口 */
import { reactive } from 'vue';

/** 退场动画时长（毫秒），需与 style.css 的 toastOut 保持一致 */
const EXIT_MS = 340;

let seed = 0;
const itemTimers = new Map();

export const toastState = reactive({ items: [] });

/**
 * @param {string} message 提示内容
 * @param {'info'|'success'|'error'} type 类型
 * @param {number} duration 自动关闭时间（毫秒）
 */
export function toast(message, type = 'info', duration = 1500) {
    const id = ++seed;
    toastState.items.push({ id, message: String(message ?? ''), type, out: false });

    const timer = setTimeout(() => close(id), duration);
    itemTimers.set(id, timer);
    return id;
}

export function close(id) {
    const item = toastState.items.find((t) => t.id === id);
    if (!item || item.out) return;
    item.out = true;
    const timer = itemTimers.get(id);
    if (timer) clearTimeout(timer);
    setTimeout(() => {
        const i = toastState.items.findIndex((t) => t.id === id);
        if (i >= 0) toastState.items.splice(i, 1);
        itemTimers.delete(id);
    }, EXIT_MS);
}
