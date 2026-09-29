<script setup>
import { computed } from 'vue';
import { icons } from './icons';

const props = defineProps({
    /** info | success | error */
    type: { type: String, default: 'info' },
    /** 后台动作进行中：显示 spinner + 流光，不当作报错 */
    busy: { type: Boolean, default: false },
});

const iconHtml = computed(() => {
    if (props.busy) return '';
    if (props.type === 'success') return icons.check;
    return icons.info;
});
</script>

<template>
    <div class="notice" :class="[type, { busy }]">
        <span v-if="busy" class="spinner"></span>
        <span v-else v-html="iconHtml"></span>
        <span><slot /></span>
    </div>
</template>
