<script setup>
import { useRouter } from 'vue-router';
import { icons } from '../ui/icons';
import { PLATFORMS } from '../ui/platforms';

const router = useRouter();

function configure(key) {
    router.push({ path: '/platform', query: { key } });
}
</script>

<template>
    <section class="board-empty">
        <span class="be-ic" v-html="icons.shield"></span>
        <h2>还没有配置任何平台账号</h2>
        <p>学习通、雨课堂、智慧树都还没有绑定。配置一次之后，这里会汇总三个平台的作业与凭证状态。</p>
        <div class="be-actions">
            <button v-for="item in PLATFORMS" :key="item.key" type="button" class="be-btn"
                    @click="configure(item.key)">
                <span class="be-logo" v-html="item.icon"></span>
                配置{{ item.name }}
            </button>
        </div>
    </section>
</template>
