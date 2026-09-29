<script setup>
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import Toaster from './ui/Toaster.vue';
import brandMark from './assets/brand-mark.jpg';
import { resolveAssetUrl, tokens } from './api/http';
import { icons } from './ui/icons';
import { initialOf } from './ui/validate';
import { loadCurrentUser, session } from './stores/session';

const route = useRoute();

/** 顶栏导航（个人中心不在这里：它由右侧的头像入口承担） */
const NAV_ITEMS = [
    { path: '/home', label: '平台概览' },
    { path: '/courses', label: '课程管理' },
    { path: '/homework', label: '作业查询' },
    { path: '/reminder', label: '提醒任务' },
];

/** 右上角头像即个人中心入口：有头像用头像，否则退回首字母，再退到通用人像 */
const user = computed(() => session.user);
const avatarUrl = computed(() => resolveAssetUrl(user.value?.avatarUrl || ''));
const avatarText = computed(() => initialOf(user.value?.username || ''));
const meTitle = computed(() => (user.value?.username ? `${user.value.username} · 个人中心` : '个人中心'));
const avatarBroken = ref(false);
watch(avatarUrl, () => { avatarBroken.value = false; });

/** 品牌标图片加载失败时退回原来的「作」字，不留破图 */
const brandBroken = ref(false);

/** 整宽页面（看板 / 个人中心等）顶部对齐，其余居中 */
const PAGE_MODES = ['home', 'courses', 'homework', 'profile', 'platform', 'devices', 'apps',
    'reminder', 'admin'];
const pageMode = computed(() => PAGE_MODES.includes(route.name));
const loggedIn = computed(() => {
    // 依赖 route 以便登录/登出后重新求值
    void route.fullPath;
    return tokens.isLoggedIn;
});

watch(() => route.fullPath, () => {
    if (tokens.isLoggedIn && !session.user) loadCurrentUser();
}, { immediate: true });
</script>

<template>
    <div class="bg" aria-hidden="true">
        <span class="blob blob-1"></span>
        <span class="blob blob-2"></span>
        <span class="blob blob-3"></span>
    </div>

    <header class="topbar">
        <div class="brand">
            <!-- 品牌标现在就是「应用导航」的入口，点它进入 /apps -->
            <RouterLink class="brand-mark" to="/apps" title="应用导航" aria-label="应用导航"
                        :class="{ active: route.path === '/apps' }">
                <img v-if="!brandBroken" :src="brandMark" alt="" @error="brandBroken = true" />
                <template v-else>作</template>
            </RouterLink>
            <RouterLink class="brand-text" to="/home" aria-label="作业信息总览">
                <strong>作业信息聚合系统</strong>
                <small>Homework Aggregation</small>
            </RouterLink>
        </div>
        <nav v-if="loggedIn" class="topnav">
            <RouterLink v-for="item in NAV_ITEMS" :key="item.path" :to="item.path"
                        :class="{ active: route.path === item.path }">
                {{ item.label }}
            </RouterLink>

            <RouterLink class="topnav-me" to="/profile" :title="meTitle" aria-label="个人中心"
                        :class="{ active: route.path === '/profile' }">
                <img v-if="avatarUrl && !avatarBroken" :src="avatarUrl" alt=""
                     @error="avatarBroken = true" />
                <template v-else-if="avatarText">{{ avatarText }}</template>
                <span v-else class="topnav-me-ic" v-html="icons.user"></span>
            </RouterLink>
        </nav>
    </header>

    <main class="app" :class="{ 'page-mode': pageMode }">
        <RouterView v-slot="{ Component }">
            <component :is="Component" />
        </RouterView>
    </main>

    <footer class="foot">
        <span>© 2026 作业信息聚合系统</span>
        <span class="dot">·</span>
        <RouterLink to="/forgot">遇到问题？</RouterLink>
    </footer>

    <Toaster />
</template>
