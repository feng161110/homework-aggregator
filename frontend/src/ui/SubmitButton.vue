<script setup>
defineProps({
    text: { type: String, required: true },
    loading: { type: Boolean, default: false },
    loadingText: { type: String, default: '处理中…' },
    variant: { type: String, default: '' },
});
</script>

<template>
    <button class="btn" :class="variant" type="button" :disabled="loading">
        <span v-if="loading" class="spinner"></span>
        <span>{{ loading ? loadingText : text }}</span>
    </button>
</template>
