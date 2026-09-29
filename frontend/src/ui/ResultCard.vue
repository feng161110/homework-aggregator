<script setup>
import { icons } from './icons';

defineProps({
    ok: { type: Boolean, default: true },
    title: { type: String, default: '' },
    desc: { type: String, default: '' },
    actionText: { type: String, default: '' },
});

const emit = defineEmits(['action']);
</script>

<template>
    <section class="card">
        <div class="result" :class="{ error: !ok }">
            <div class="badge" v-html="ok ? icons.check : icons.x"></div>
            <h2>{{ title }}</h2>
            <p>{{ desc }}</p>
            <button v-if="actionText" class="btn" type="button" @click="emit('action')">{{ actionText }}</button>
        </div>
    </section>
</template>
