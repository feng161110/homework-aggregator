<script setup>
/**
 * 自绘下拉选择。
 *
 * 为什么不用 <select>：它展开后的选项列表由浏览器/系统绘制，
 * CSS 完全改不了（深灰高亮、系统字体、无圆角），跟站内的暖色体系对不上。
 * 这里用「按钮 + 自绘面板」替代，外观和交互都收在自己手里。
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';

const props = defineProps({
    modelValue: { type: [String, Number], default: '' },
    /** [{ value, label }] */
    options: { type: Array, required: true },
    placeholder: { type: String, default: '请选择' },
    ariaLabel: { type: String, default: '' },
    /** 紧凑样式：用在列表行内（如「提前 [1] 天」） */
    compact: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    /** 面板向上弹（靠近视口底部时用） */
    dropUp: { type: Boolean, default: false },
});

const emit = defineEmits(['update:modelValue', 'change']);

const open = ref(false);
const root = ref(null);
/** 面板被 teleport 到 body，位置自己算；定位前的兜底值避免闪到左上角 */
const panelRef = ref(null);
const panelStyle = ref({ position: 'fixed', top: '0px', left: '0px', visibility: 'hidden' });

const current = computed(
    () => props.options.find((option) => String(option.value) === String(props.modelValue)),
);

/**
 * 把面板摆到触发按钮旁边。
 * 面板挂在 body 上而不是原地：留在列表/弹窗的滚动容器里会被裁掉，
 * 还会跟着外层一起滚（看起来就像和外层共用一个滚动条）。
 */
function place() {
    const anchor = root.value?.querySelector('.asel-btn');
    if (!anchor) return;
    const rect = anchor.getBoundingClientRect();
    const GAP = 6;
    const MARGIN = 10;
    const PANEL_MAX = 246;
    // 面板高度先夹进视口，宁可内部滚动，也不要被裁到看不见
    const maxHeight = Math.min(PANEL_MAX, window.innerHeight - MARGIN * 2);
    const below = window.innerHeight - rect.bottom - GAP;
    const up = props.dropUp || (below < maxHeight && rect.top - GAP > below);
    // 按钮很窄（如「提前 1 天」）时也保证面板够宽可读，并把左右夹在视口内
    const width = Math.max(rect.width, 132);
    const left = Math.min(Math.max(MARGIN, rect.left), Math.max(MARGIN, window.innerWidth - width - MARGIN));
    panelStyle.value = {
        position: 'fixed',
        left: `${left}px`,
        width: `${width}px`,
        maxHeight: `${maxHeight}px`,
        visibility: 'visible',
        ...(up
            ? { bottom: `${window.innerHeight - rect.top + GAP}px`, top: 'auto' }
            : { top: `${rect.bottom + GAP}px`, bottom: 'auto' }),
    };
}

function toggle() {
    open.value = !open.value;
    if (open.value) nextTick(place);
}

function pick(option) {
    emit('update:modelValue', option.value);
    emit('change', option.value);
    open.value = false;
}

/** 页面滚动/改尺寸时跟着挪（面板是 fixed，不会自己跟着走） */
function reposition() {
    if (open.value) place();
}

/** 点外面或按 Esc 关掉；面板在 body 上，要单独判断 */
function onPointerDown(event) {
    if (root.value?.contains(event.target) || panelRef.value?.contains(event.target)) {
        return;
    }
    open.value = false;
}

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
    <div ref="root" class="asel" :class="{ compact, open, disabled, 'drop-up': dropUp }">
        <button type="button" class="asel-btn" :disabled="disabled" :aria-label="ariaLabel"
                aria-haspopup="listbox" :aria-expanded="open"
                @click="toggle" @keydown.esc="open = false">
            <span class="asel-value" :class="{ placeholder: !current }">
                {{ current ? current.label : placeholder }}
            </span>
            <span class="asel-caret" aria-hidden="true"></span>
        </button>

        <!-- 面板挂到 body：不再受外层滚动容器裁剪，也不与外层共用滚动条 -->
        <Teleport to="body">
            <ul v-if="open" ref="panelRef" class="asel-list" role="listbox"
                :style="panelStyle" :aria-label="ariaLabel">
                <li v-for="option in options" :key="option.value">
                    <button type="button" class="asel-opt" role="option"
                            :aria-selected="String(option.value) === String(modelValue)"
                            :class="{ on: String(option.value) === String(modelValue) }"
                            @click="pick(option)">
                        {{ option.label }}
                    </button>
                </li>
            </ul>
        </Teleport>
    </div>
</template>
