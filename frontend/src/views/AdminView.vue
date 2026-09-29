<script setup>
/**
 * 管理后台：所有账号 + 登录设备（会话）管理。
 *
 * 左侧是账号列表（搜索 / 状态筛选 / 分页），点「详情」弹出该账号的全部登录记录，
 * 可以对单台设备踢下线，也可以一键禁用账号（禁用会同时撤销它的全部登录态）。
 * 非管理员打开这个页面时后端返回 403，页面会显示「仅管理员可见」。
 */
import { computed, onMounted, ref } from 'vue';
import { adminApi, agoText, userStatusInfo } from '../api/admin';
import { ApiError } from '../api/http';
import { session } from '../stores/session';
import { icons } from '../ui/icons';
import { toast } from '../ui/toast';

const STATUS_FILTERS = [
    { value: '', label: '全部' },
    { value: 'ACTIVE', label: '正常' },
    { value: 'PENDING', label: '待验证' },
    { value: 'LOCKED', label: '已锁定' },
    { value: 'DISABLED', label: '已禁用' },
];

const loading = ref(true);
const forbidden = ref(false);
const data = ref(null);
const page = ref(1);
const limit = 20;
const keyword = ref('');
const status = ref('');
const busyId = ref(null);
const busySessionId = ref(null);

const users = computed(() => data.value?.users || []);
const totalPages = computed(() => Math.max(1, Math.ceil((data.value?.total || 0) / limit)));
const myId = computed(() => session.user?.id);

/** 详情弹窗 */
const detail = ref({ open: false, loading: false, userId: null, data: null });

function describeError(err) {
    if (err instanceof ApiError && err.status === 403) {
        forbidden.value = true;
        return '仅管理员可访问该页面';
    }
    return err instanceof ApiError ? err.message : '操作失败，请重试';
}

async function load() {
    loading.value = true;
    try {
        data.value = await adminApi.users({
            page: page.value,
            limit,
            search: keyword.value.trim(),
            status: status.value,
        });
        forbidden.value = false;
    } catch (err) {
        const message = describeError(err);
        if (!forbidden.value) toast(message, 'error');
    } finally {
        loading.value = false;
    }
}

function applyFilters() {
    page.value = 1;
    load();
}

/**
 * 操作之后重新拉列表。
 * 需要退页：比如筛「正常」时禁用了最后一页最后一条，这一页就空了，
 * 停在空页上用户会以为列表坏了。
 */
async function reloadAfterAction() {
    await load();
    if (!users.value.length && page.value > 1) {
        await goPage(page.value - 1);
    }
}

function goPage(next) {
    const target = Math.min(Math.max(1, next), totalPages.value);
    if (target === page.value) return;
    page.value = target;
    load();
}

async function act(user, action) {
    if (busyId.value) return;
    const label = action === 'disable'
        ? `禁用「${user.username}」？该账号会被立刻踢下线且无法登录。`
        : `解除「${user.username}」的禁用？`;
    if (!window.confirm(label)) return;
    busyId.value = user.id;
    try {
        const message = action === 'disable'
            ? await adminApi.disable(user.id)
            : await adminApi.enable(user.id);
        toast(message || '操作成功', 'success');
        await reloadAfterAction();
        if (detail.value.open && detail.value.userId === user.id) await openDetail(user);
    } catch (err) {
        toast(describeError(err), 'error');
    } finally {
        busyId.value = null;
    }
}

async function kickAll(user) {
    if (busyId.value) return;
    if (!window.confirm(`把「${user.username}」的所有设备踢下线？对方需要重新登录。`)) return;
    busyId.value = user.id;
    try {
        const message = await adminApi.revokeSessions(user.id);
        toast(message || '已踢下线', 'success');
        await reloadAfterAction();
        if (detail.value.open && detail.value.userId === user.id) await openDetail(user);
    } catch (err) {
        toast(describeError(err), 'error');
    } finally {
        busyId.value = null;
    }
}

async function openDetail(user) {
    detail.value = { open: true, loading: true, userId: user.id, data: null };
    try {
        detail.value.data = await adminApi.detail(user.id);
    } catch (err) {
        toast(describeError(err), 'error');
        detail.value.open = false;
    } finally {
        detail.value.loading = false;
    }
}

async function kickSession(token) {
    if (busySessionId.value) return;
    busySessionId.value = token.id;
    try {
        const message = await adminApi.revokeSession(token.id);
        toast(message || '该设备已下线', 'success');
        const userId = detail.value.userId;
        const target = users.value.find((u) => u.id === userId) || { id: userId, username: '' };
        await reloadAfterAction();
        await openDetail(target);
    } catch (err) {
        toast(describeError(err), 'error');
    } finally {
        busySessionId.value = null;
    }
}

onMounted(() => load());
</script>

<template>
    <div class="page wide">
        <div class="page-head">
            <h1 class="page-title">
                <span class="adm-title-ic" v-html="icons.shield"></span>
                管理后台
            </h1>
            <button type="button" class="btn ghost inline" :disabled="loading" @click="load()">
                <span v-html="icons.refresh"></span> 刷新
            </button>
        </div>

        <!-- 身份确认：进得来就说明后端认了 is_admin，这里把身份摆出来，避免误操作 -->
        <p class="adm-who">
            <span class="adm-who-ic" v-html="icons.shield"></span>
            当前身份 <strong>{{ session.user?.username }}</strong>
            <span class="adm-tag admin">管理员</span>
            <span class="muted">· 本页所有操作都会写入审计日志</span>
        </p>

        <div v-if="forbidden" class="notice info">
            <span v-html="icons.info"></span>
            <span>仅管理员可访问该页面。如需权限请联系站点管理员。</span>
        </div>

        <template v-else>
            <!-- 顶部统计 -->
            <div v-if="data" class="adm-stats">
                <div class="adm-stat">
                    <span class="adm-stat-n">{{ data.totalUsers }}</span>
                    <span class="adm-stat-k">账号总数</span>
                </div>
                <div class="adm-stat">
                    <span class="adm-stat-n">{{ data.activeUsers }}</span>
                    <span class="adm-stat-k">正常</span>
                </div>
                <div class="adm-stat">
                    <span class="adm-stat-n warn">{{ data.disabledUsers }}</span>
                    <span class="adm-stat-k">已禁用</span>
                </div>
                <div class="adm-stat">
                    <span class="adm-stat-n">{{ data.onlineSessions }}</span>
                    <span class="adm-stat-k">在线设备</span>
                </div>
            </div>

            <!-- 筛选 -->
            <div class="adm-toolbar">
                <label class="adm-search">
                    <span class="adm-search-ic" v-html="icons.search"></span>
                    <input v-model="keyword" type="search" autocomplete="off" spellcheck="false"
                           aria-label="搜索用户名或邮箱" placeholder="搜索用户名或邮箱"
                           @keyup.enter="applyFilters" />
                </label>
                <div class="adm-filters">
                    <button v-for="item in STATUS_FILTERS" :key="item.value" type="button"
                            class="adm-filter" :class="{ on: status === item.value }"
                            @click="status = item.value; applyFilters()">{{ item.label }}</button>
                </div>
                <button type="button" class="btn inline" :disabled="loading" @click="applyFilters">查询</button>
            </div>

            <div v-if="loading && !data" class="adm-empty">加载中…</div>
            <div v-else-if="!users.length" class="notice info">
                <span v-html="icons.info"></span>
                <span>没有符合条件的账号。</span>
            </div>

            <!-- 列表 -->
            <div v-else class="adm-list">
                <div v-for="user in users" :key="user.id" class="adm-row" :class="{ off: user.status === 'DISABLED' }">
                    <div class="adm-id">
                        <span class="adm-name">
                            {{ user.username }}
                            <span v-if="user.isAdmin" class="adm-tag admin">管理员</span>
                            <span v-if="user.id === myId" class="adm-tag me">我</span>
                        </span>
                        <span class="adm-mail">{{ user.email }}</span>
                    </div>

                    <div class="adm-state">
                        <span class="adm-badge" :class="userStatusInfo(user.status).cls">
                            {{ userStatusInfo(user.status).text }}
                        </span>
                        <span v-if="!user.emailVerified" class="adm-badge pending">邮箱未验证</span>
                        <span v-if="user.locked" class="adm-badge warn">临时锁定</span>
                    </div>

                    <div class="adm-meta">
                        <span title="当前在线设备 / 累计登录记录">
                            {{ user.activeSessions }} / {{ user.totalSessions }} 台设备
                        </span>
                        <span :title="user.lastActiveAt || '从未登录'">
                            最近活跃 {{ agoText(user.lastActiveAt) }}
                        </span>
                        <span v-if="user.lastLoginIp" class="adm-ip">{{ user.lastLoginIp }}</span>
                    </div>

                    <div class="adm-actions">
                        <button type="button" class="btn ghost inline sm" @click="openDetail(user)">
                            详情
                        </button>
                        <button type="button" class="btn ghost inline sm"
                                :disabled="!user.activeSessions || busyId === user.id"
                                @click="kickAll(user)">
                            踢下线
                        </button>
                        <button v-if="user.status === 'DISABLED'" type="button" class="btn inline sm"
                                :disabled="busyId === user.id" @click="act(user, 'enable')">
                            解除禁用
                        </button>
                        <button v-else type="button" class="btn danger inline sm"
                                :disabled="user.id === myId || busyId === user.id"
                                :title="user.id === myId ? '不能禁用当前登录账号' : '禁用该账号'"
                                @click="act(user, 'disable')">
                            禁用
                        </button>
                    </div>
                </div>

                <div class="adm-pager">
                    <button type="button" class="btn ghost inline sm" :disabled="page <= 1"
                            @click="goPage(page - 1)">上一页</button>
                    <span class="muted">第 {{ page }} / {{ totalPages }} 页 · 共 {{ data?.total || 0 }} 个账号</span>
                    <button type="button" class="btn ghost inline sm" :disabled="page >= totalPages"
                            @click="goPage(page + 1)">下一页</button>
                </div>
            </div>
        </template>

        <!-- ===== 详情：登录记录 ===== -->
        <div v-if="detail.open" class="rmo-mask" @click.self="detail.open = false">
            <div class="rmo-modal">
                <div class="rmo-modal-head">
                    <strong>登录记录{{ detail.data?.user ? ` · ${detail.data.user.username}` : '' }}</strong>
                    <button type="button" class="rmo-x" @click="detail.open = false">×</button>
                </div>

                <div v-if="detail.loading" class="adm-empty">加载中…</div>
                <template v-else-if="detail.data">
                    <div class="adm-detail-head">
                        <div class="adm-detail-mail">{{ detail.data.user.email }}</div>
                        <div class="adm-detail-meta">
                            <span>注册于 {{ detail.data.user.createdAt }}</span>
                            <span v-if="detail.data.user.lastLoginIp">最近登录 IP {{ detail.data.user.lastLoginIp }}</span>
                            <span>{{ detail.data.sessions.length }} 条记录 · {{ detail.data.revokedSessions }} 条已下线</span>
                        </div>
                        <div class="adm-detail-actions">
                            <button type="button" class="btn ghost inline sm"
                                    :disabled="!detail.data.user.activeSessions || busyId === detail.data.user.id"
                                    @click="kickAll(detail.data.user)">
                                全部踢下线
                            </button>
                            <button v-if="detail.data.user.status === 'DISABLED'" type="button"
                                    class="btn inline sm" @click="act(detail.data.user, 'enable')">
                                解除禁用
                            </button>
                            <button v-else type="button" class="btn danger inline sm"
                                    :disabled="detail.data.user.id === myId"
                                    @click="act(detail.data.user, 'disable')">
                                禁用账号
                            </button>
                        </div>
                    </div>

                    <div v-if="!detail.data.sessions.length" class="notice info">
                        <span v-html="icons.info"></span>
                        <span>该账号还没有登录记录。</span>
                    </div>

                    <ul v-else class="adm-sessions">
                        <li v-for="token in detail.data.sessions" :key="token.id"
                            class="adm-session" :class="{ dead: !token.active }">
                            <span class="adm-session-ic" v-html="icons.monitor"></span>
                            <div class="adm-session-main">
                                <div class="adm-session-name">
                                    {{ token.deviceName || '未知设备' }}
                                    <span v-if="token.active" class="adm-badge ok">在线</span>
                                    <span v-else-if="token.revokedAt" class="adm-badge off">已下线</span>
                                    <span v-else class="adm-badge pending">已过期</span>
                                </div>
                                <div class="adm-session-meta">
                                    <span>{{ token.ip || 'IP 未知' }}</span>
                                    <span>最近活跃 {{ agoText(token.lastUsedAt) }}</span>
                                    <span>登录于 {{ token.createdAt }}</span>
                                    <span>有效期至 {{ token.expiresAt }}</span>
                                </div>
                                <div class="adm-session-ua" :title="token.userAgent">{{ token.userAgent }}</div>
                            </div>
                            <button type="button" class="btn ghost inline sm" :disabled="!token.active || busySessionId === token.id"
                                    @click="kickSession(token)">踢下线</button>
                        </li>
                    </ul>
                </template>
            </div>
        </div>
    </div>
</template>
