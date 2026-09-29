<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError, resolveAssetUrl, tokens } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { formatTime, initialOf, maskEmail } from '../ui/validate';
import { clearSession, loadCurrentUser, session } from '../stores/session';

const router = useRouter();

const AVATAR_MAX_BYTES = 2 * 1024 * 1024;

const loading = ref(true);
const uploading = ref(false);
const fileInput = ref(null);

const user = computed(() => session.user);

const statusMap = {
    ACTIVE: { label: '正常', cls: 'ok' },
    PENDING: { label: '待验证', cls: 'warn' },
    LOCKED: { label: '已锁定', cls: 'bad' },
};
const status = computed(() => statusMap[user.value?.status] || { label: user.value?.status || '-', cls: 'warn' });

onMounted(async () => {
    const me = await loadCurrentUser(true);
    loading.value = false;
    if (me) {
        return;
    }
    if (tokens.isLoggedIn) {
        // 登录态还在，只是这次没取到（后端没起 / 网络抖动）：
        // 留在页面提示重试，别把人踢到登录页 —— 守卫会立刻又把他弹回来
        toast('账号信息加载失败，请稍后重试', 'error');
        return;
    }
    toast('登录状态已失效，请重新登录', 'info');
    router.replace('/login');
});

function openPicker() {
    fileInput.value?.click();
}

async function onFileChange(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!/^image\//.test(file.type)) return toast('请选择图片文件', 'error');
    if (file.size > AVATAR_MAX_BYTES) return toast('图片不能超过 2MB', 'error');

    uploading.value = true;
    try {
        session.user = await authApi.uploadAvatar(file);
        toast('头像已更新', 'success');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '头像上传失败', 'error');
    } finally {
        uploading.value = false;
    }
}

async function logout() {
    try {
        await authApi.logout();
    } catch {
        // 后端不可达也要把本地登录态清干净，不能把人卡在「已退出但界面还在」
    } finally {
        clearSession();
        toast('已退出登录', 'success');
        router.replace('/login');
    }
}
</script>

<template>
    <div class="page">
        <section class="card wide" style="width:100%;max-width:none">
            <div class="card-head" style="text-align:left;margin-bottom:18px">
                <span class="eyebrow">个人中心</span>
            </div>

            <div v-if="loading" class="result">
                <div class="badge" style="background:var(--gold-soft);color:var(--gold-strong)">
                    <span class="spinner" style="border-color:rgba(176,137,90,.3);border-top-color:var(--gold-strong);width:26px;height:26px;border-width:3px"></span>
                </div>
                <p class="muted">正在加载账号信息…</p>
            </div>

            <template v-else-if="user">
                <div class="user-hero">
                    <div class="avatar" :class="{ uploading }" role="button" tabindex="0"
                         title="点击更换头像" aria-label="更换头像"
                         @click="openPicker" @keydown.enter.prevent="openPicker" @keydown.space.prevent="openPicker">
                        <span class="avatar-media">
                            <img v-if="user.avatarUrl" :src="resolveAssetUrl(user.avatarUrl)" alt="头像" />
                            <template v-else>{{ initialOf(user.username) }}</template>
                        </span>
                        <span class="avatar-overlay">
                            <template v-if="uploading"><span class="spinner"></span></template>
                            <template v-else>
                                <span class="ov-icon" v-html="icons.camera"></span>
                                <span class="ov-text">更换头像</span>
                            </template>
                        </span>
                    </div>
                    <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                           @change="onFileChange" />
                    <div class="who">
                        <strong>{{ user.username }}</strong>
                        <span>{{ maskEmail(user.email) }}</span>
                        <button type="button" class="avatar-tip"
                                title="支持 jpg / png / gif / webp，不超过 2MB" @click="openPicker">
                            <span v-html="icons.cameraSm"></span> 点击头像可更换
                        </button>
                    </div>
                </div>

                <div class="info-list">
                    <div class="info-item"><span class="k">用户 ID</span><span class="v">{{ user.id }}</span></div>
                    <div class="info-item"><span class="k">用户名</span><span class="v">{{ user.username }}</span></div>
                    <div class="info-item"><span class="k">邮箱</span><span class="v">{{ user.email }}</span></div>
                    <div class="info-item">
                        <span class="k">邮箱验证</span>
                        <span class="v">
                            <span class="pill" :class="user.emailVerified ? 'ok' : 'warn'">
                                {{ user.emailVerified ? '已验证' : '未验证' }}
                            </span>
                        </span>
                    </div>
                    <div class="info-item">
                        <span class="k">账号状态</span>
                        <span class="v"><span class="pill" :class="status.cls">{{ status.label }}</span></span>
                    </div>
                    <div class="info-item"><span class="k">注册时间</span><span class="v">{{ formatTime(user.createdAt) }}</span></div>
                </div>

                <div class="sec-title">安全设置</div>
                <div class="info-list">
                    <button type="button" class="info-item clickable" @click="router.push('/change-password')">
                        <span class="k"><span v-html="icons.lock"></span> 登录密码</span>
                        <span class="v">修改 ›</span>
                    </button>
                    <button type="button" class="info-item clickable" @click="router.push('/devices')">
                        <span class="k"><span v-html="icons.monitor"></span> 登录设备</span>
                        <span class="v">管理 ›</span>
                    </button>
                </div>

                <div class="stack-sm" style="margin-top:22px">
                    <button class="btn ghost" type="button" @click="loadCurrentUser(true)">刷新信息</button>
                    <button class="btn plain" type="button" @click="logout">
                        <span v-html="icons.logout"></span> 退出登录
                    </button>
                </div>
            </template>
        </section>
    </div>
</template>
