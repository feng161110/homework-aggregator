<script setup>
import { toastState, close } from './toast';
import { icons } from './icons';

const iconOf = { success: icons.check, error: icons.x, info: icons.info };
</script>

<template>
    <div class="toast-root">
        <div v-for="item in toastState.items" :key="item.id"
             class="toast" :class="[item.type, { out: item.out }]"
             :role="item.type === 'error' ? 'alert' : 'status'"
             title="点击关闭" @click="close(item.id)">
            <span class="t-icon" v-html="iconOf[item.type] || icons.info"></span>
            <span class="t-msg">{{ item.message }}</span>
        </div>
    </div>
</template>
