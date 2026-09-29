<script setup>
/**
 * 折叠式筛选行（影视筛选风格）：一行标签，放不下时折行，
 * 默认只露一行，右侧「展开 / 收起」把剩余标签展开。
 * 是否出现「展开」按钮由实际内容高度决定，宽度变化会自动重算。
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { icons } from '../ui/icons';

const props = defineProps({
    /** 维度名，如「学期」「教师」 */
    label: { type: String, required: true },
    /** 选中值，'' 表示「全部」 */
    modelValue: { type: String, default: '' },
    /** [{ value, label, count }]，value 为 '' 的那项即「全部」 */
    options: { type: Array, default: () => [] },
});

const emit = defineEmits(['update:modelValue']);

/** 收起时可见的高度，必须与 style.css 里 .facet-items 的 max-height 一致 */
const COLLAPSED_PX = 30;
/** 只多出一两像素不值得给个「展开」，留点余量 */
const OVERFLOW_SLACK = 8;

const itemsEl = ref(null);
const expanded = ref(false);
const overflow = ref(false);
let observer = null;

function measure() {
    const el = itemsEl.value;
    if (!el) {
        return;
    }
    // scrollHeight 是内容总高度，不受 max-height 裁剪影响
    overflow.value = el.scrollHeight > COLLAPSED_PX + OVERFLOW_SLACK;
    if (!overflow.value) {
        expanded.value = false;
    }
}

onMounted(async () => {
    await nextTick();
    measure();
    if (typeof ResizeObserver !== 'undefined' && itemsEl.value) {
        observer = new ResizeObserver(measure);
        observer.observe(itemsEl.value);
    }
});

onBeforeUnmount(() => {
    observer?.disconnect();
});

// 选项变化后重新量一次（内容变了但盒子尺寸没变时 ResizeObserver 不触发）
watch(() => props.options, async () => {
    await nextTick();
    measure();
});
</script>

<template>
    <div class="facet">
        <span class="facet-label">{{ label }}</span>

        <div ref="itemsEl" class="facet-items" :class="{ expanded }">
            <button v-for="opt in options" :key="opt.value" type="button" class="facet-item"
                    :class="{ active: opt.value === modelValue, zero: opt.value !== '' && opt.count === 0 }"
                    @click="emit('update:modelValue', opt.value)">
                {{ opt.label }}<span v-if="opt.count != null" class="facet-n">{{ opt.count }}</span>
            </button>
        </div>

        <button v-if="overflow" type="button" class="facet-more" @click="expanded = !expanded">
            {{ expanded ? '收起' : '展开' }}
            <span class="facet-caret" :class="{ up: expanded }" v-html="icons.chevron"></span>
        </button>
    </div>
</template>
