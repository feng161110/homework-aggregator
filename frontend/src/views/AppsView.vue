<script setup>
/**
 * 应用导航：把站内每个页面铺成一枚圆角图标，配一个搜索框。
 * 图标走 iOS / Apple Watch 那种「渐变方块 + 白色符号 + 下方标签」的样式，
 * 但配色收在站点自己的暖色体系里，不用原图那套高饱和的彩虹色。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { reminderApi } from '../api/reminder';
import { session } from '../stores/session';
import { icons } from '../ui/icons';

/**
 * 图标下的名字尽量短（一行标签），keywords 用来撑搜索：
 * 名字、路径、关键词三处任一命中即可，所以输「密码」「devices」「/home」都能搜到。
 */
const ENTRIES = [
    {
        key: 'home', path: '/home', name: '首页', tone: 'home', glyph: icons.home,
        keywords: 'home 首页 总览 概览 看板 dashboard 作业信息',
    },
    {
        key: 'platform', path: '/platform', name: '平台', tone: 'platform', glyph: icons.key,
        keywords: 'platform 平台 账号 绑定 凭证 cookie 登录 授权',
    },
    {
        key: 'courses', path: '/courses', name: '课程', tone: 'courses', glyph: icons.book,
        keywords: 'courses 课程 课表 学习通 雨课堂 智慧树',
    },
    {
        key: 'homework', path: '/homework', name: '作业', tone: 'homework', glyph: icons.clipboard,
        keywords: 'homework 作业 考试 截止 提交 ddl',
    },
    {
        key: 'reminder', path: '/reminder', name: '提醒', tone: 'reminder', glyph: icons.mail,
        keywords: 'reminder remind 提醒 邮箱 邮件 email 通知 定时 群发 作业提醒 任务提醒',
    },
    {
        key: 'profile', path: '/profile', name: '我的', tone: 'profile', glyph: icons.user,
        keywords: 'profile 我的 个人 中心 头像 邮箱',
    },
    {
        key: 'password', path: '/change-password', name: '密码', tone: 'password', glyph: icons.lock,
        keywords: 'password 密码 修改 安全 改密',
    },
    {
        key: 'devices', path: '/devices', name: '设备', tone: 'devices', glyph: icons.monitor,
        keywords: 'devices 设备 登录 下线 终端 手机',
    },
];

/** 管理入口只对管理员出现；后端每个接口还会再校验一次，改 URL 也进不去 */
const ADMIN_ENTRY = {
    key: 'admin', path: '/admin', name: '管理', tone: 'admin', glyph: icons.shield,
    keywords: 'admin 管理 后台 用户 账号 禁用 封禁 登录记录 设备 会话',
};

const entries = computed(() => (session.user?.isAdmin ? [...ENTRIES, ADMIN_ENTRY] : ENTRIES));

const keyword = ref('');
const searchEl = ref(null);

/** 纯内存过滤，输入即出结果 */
const results = computed(() => {
    const kw = keyword.value.trim().toLowerCase();
    if (!kw) {
        return entries.value;
    }
    return entries.value.filter((entry) => `${entry.name} ${entry.path} ${entry.keywords}`
        .toLowerCase()
        .includes(kw));
});

/** 图标围成一圈：0° 即正上方，按数量顺时针均分 360° */
function angleOf(index, total) {
    return (360 / Math.max(1, total)) * index;
}

/**
 * 「提醒」图标上的角标：待发送条数。
 * 拿不到就当作 0 —— 这个接口是后加的，后端没重启过时不该影响整页图标。
 */
const pendingReminders = ref(0);

async function loadPendingReminders() {
    try {
        const data = await reminderApi.stats();
        pendingReminders.value = Number(data?.pending) || 0;
    } catch {
        pendingReminders.value = 0;
    }
}

/** 按 / 直接聚焦搜索框（这一页没有别的输入框，不会跟谁抢焦点） */
function onKeydown(e) {
    if (e.key !== '/' || /^(INPUT|TEXTAREA)$/.test(e.target.tagName)) {
        return;
    }
    e.preventDefault();
    searchEl.value?.focus();
}

onMounted(() => {
    window.addEventListener('keydown', onKeydown);
    loadPendingReminders();
});
onUnmounted(() => window.removeEventListener('keydown', onKeydown));
</script>

<template>
    <div class="page">
        <h1 class="page-title">应用导航</h1>

        <div class="nav-wrap">
            <label class="agg-search nav-search">
                <span class="agg-search-ic" v-html="icons.search"></span>
                <input ref="searchEl" v-model="keyword" type="search" autocomplete="off" spellcheck="false"
                       aria-label="搜索功能" placeholder="搜索功能" />
                <button v-if="keyword" type="button" class="agg-clear" title="清空关键词"
                        @click="keyword = ''">
                    <span v-html="icons.x"></span>
                </button>
            </label>

            <div v-if="results.length" class="nav-ring">
                <!-- 每枚图标按 --a 的角度摆到环上；搜索过滤后角度重算，会顺着环滑过去 -->
                <div v-for="(entry, i) in results" :key="entry.key" class="nav-ring-item"
                     :style="{ '--a': `${angleOf(i, results.length)}deg`, '--i': i }">
                    <RouterLink class="app-tile" :class="`tone-${entry.tone}`"
                                :to="entry.path" :title="entry.path">
                        <span class="app-tile-float">
                            <span class="app-tile-ic" v-html="entry.glyph"></span>
                            <span v-if="entry.key === 'reminder' && pendingReminders"
                                  class="app-tile-badge" :title="`${pendingReminders} 条提醒待发送`">
                                {{ pendingReminders }}
                            </span>
                        </span>
                        <span class="app-tile-name">{{ entry.name }}</span>
                    </RouterLink>
                </div>
            </div>

            <div v-else class="agg-empty">
                <span class="agg-empty-ic" v-html="icons.search"></span>
                <p class="muted">没有找到匹配的功能</p>
                <button type="button" class="btn ghost inline" @click="keyword = ''">清空关键词</button>
            </div>
        </div>
    </div>
</template>
