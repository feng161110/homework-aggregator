<script setup>
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { PLATFORM_KEYS, findPlatform } from '../ui/platforms';
import CredentialPanel from '../components/platform/CredentialPanel.vue';
import XxtPanel from '../components/platform/XxtPanel.vue';

const route = useRoute();
const router = useRouter();

/**
 * 三个图标分左 / 中 / 右三个槽位，索引 1 恒为当前平台。
 * 点击某个图标即与被点击者交换槽位，让它滑到正中。
 */
const order = ref(PLATFORM_KEYS.slice());
const activeKey = ref('yuketang');

function placeActive(key) {
    const index = order.value.indexOf(key);
    if (index > -1 && index !== 1) {
        [order.value[1], order.value[index]] = [order.value[index], order.value[1]];
    }
}

function applyRouteKey() {
    const key = route.query.key;
    activeKey.value = PLATFORM_KEYS.includes(key) ? key : 'yuketang';
    placeActive(activeKey.value);
}

applyRouteKey();
watch(() => route.query.key, applyRouteKey);

const activePlatform = computed(() => findPlatform(activeKey.value));

function select(key) {
    if (key === activeKey.value) return;
    const index = order.value.indexOf(key);
    [order.value[1], order.value[index]] = [order.value[index], order.value[1]];
    activeKey.value = key;
    router.replace({ path: '/platform', query: { key } });
}
</script>

<template>
    <div class="page">
        <div class="platform-switch" role="tablist" aria-label="平台切换">
            <span class="ps-sep ps-sep-left" aria-hidden="true"></span>
            <span class="ps-sep ps-sep-right" aria-hidden="true"></span>
            <button v-for="(key, i) in order" :key="key"
                    type="button" class="ps-item" role="tab"
                    :data-slot="i === 1 ? 'mid' : (i === 0 ? 'left' : 'right')"
                    :class="{ active: i === 1 }"
                    :aria-selected="i === 1"
                    :tabindex="i === 1 ? 0 : -1"
                    :title="i === 1 ? `${findPlatform(key).name}（当前）` : `切换到 ${findPlatform(key).name}`"
                    :aria-label="findPlatform(key).name"
                    @click="select(key)">
                <span v-html="findPlatform(key).icon"></span>
            </button>
        </div>

        <!-- 学习通：由浏览器助手读取已登录的 Cookie；其余平台走账号密码 -->
        <XxtPanel v-if="activeKey === 'xuexitong'" :key="activeKey" :platform="activePlatform" />
        <CredentialPanel v-else :key="activeKey" :platform="activePlatform" />
    </div>
</template>
