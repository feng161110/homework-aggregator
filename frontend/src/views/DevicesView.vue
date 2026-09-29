<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { formatTime } from '../ui/validate';
import { clearSession } from '../stores/session';

const router = useRouter();

const loading = ref(true);
const devices = ref([]);
const busyId = ref(null);
const revokingOthers = ref(false);

const othersCount = computed(() => devices.value.filter((d) => !d.current).length);

/** 简单区分移动端与桌面端图标 */
function deviceIcon(name) {
    return /(iPhone|Android|iPad|HarmonyOS|手机|平板|Mobile)/i.test(String(name || ''))
        ? icons.phone
        : icons.monitor;
}

async function load() {
    loading.value = true;
    try {
        devices.value = await authApi.devices();
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '设备列表加载失败', 'error');
    } finally {
        loading.value = false;
    }
}

onMounted(load);

async function revoke(device) {
    if (!device || busyId.value) return;
    busyId.value = device.id;
    try {
        await authApi.revokeDevice(device.id);
        if (device.current) {
            // 当前设备被下线：清理登录态并回到登录页（与主动退出登录一致）
            clearSession();
            toast('当前设备已退出登录', 'success');
            router.replace('/login');
            return;
        }
        toast('该设备已退出登录', 'success');
        await load();
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '操作失败，请重试', 'error');
    } finally {
        busyId.value = null;
    }
}

async function revokeOthers() {
    if (!othersCount.value || revokingOthers.value) return;
    revokingOthers.value = true;
    try {
        const message = await authApi.revokeOtherDevices();
        toast(message || '已退出其它设备', 'success');
        await load();
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '操作失败，请重试', 'error');
    } finally {
        revokingOthers.value = false;
    }
}
</script>

<template>
    <div class="page">
        <section class="card wide" style="width:100%;max-width:none">
            <div class="card-head" style="text-align:left;margin-bottom:18px">
                <span class="eyebrow">账号安全</span>
                <h1 style="margin:6px 0 6px;font-size:22px">登录设备管理</h1>
                <p style="text-align:left">查看当前账号最近登录过的设备，发现可疑设备可立即将其下线。</p>
            </div>

            <div v-if="loading" class="result">
                <div class="badge" style="background:var(--gold-soft);color:var(--gold-strong)">
                    <span class="spinner"
                          style="width:26px;height:26px;border:3px solid rgba(176,137,90,.3);border-top-color:var(--gold-strong);border-radius:50%;animation:spin .7s linear infinite"></span>
                </div>
                <p class="muted">正在加载设备列表…</p>
            </div>

            <template v-else>
                <div class="device-toolbar">
                    <span class="muted">共 {{ devices.length }} 台设备在线</span>
                    <button type="button" class="btn inline ghost"
                            :disabled="!othersCount || revokingOthers" @click="revokeOthers">
                        <span v-if="revokingOthers" class="spinner"></span>
                        <span v-else v-html="icons.logout"></span>
                        {{ othersCount ? `退出其它 ${othersCount} 台设备` : '没有其它设备' }}
                    </button>
                </div>

                <div v-if="!devices.length" class="notice info">
                    <span v-html="icons.info"></span>
                    <span>暂无登录设备记录。</span>
                </div>

                <div v-else class="device-list">
                    <div v-for="d in devices" :key="d.id" class="device-item" :class="{ current: d.current }">
                        <span class="device-ic" v-html="deviceIcon(d.deviceName)"></span>
                        <div class="device-main">
                            <div class="device-name">
                                {{ d.deviceName }}
                                <span v-if="d.current" class="pill ok">当前设备</span>
                            </div>
                            <div class="device-meta">
                                <span>IP：{{ d.ip || '未知' }}</span>
                                <span class="dot">·</span>
                                <span>最近活跃：{{ formatTime(d.lastUsedAt || d.loginAt) }}</span>
                            </div>
                            <div class="device-meta">登录时间：{{ formatTime(d.loginAt) }}</div>
                        </div>
                        <div class="device-act">
                            <button v-if="!d.current" type="button" class="dev-off"
                                    :disabled="busyId === d.id" @click="revoke(d)">
                                <span v-if="busyId === d.id" class="spinner"></span>
                                <template v-else>退出该设备</template>
                            </button>
                            <span v-else class="dev-cur">本机登录</span>
                        </div>
                    </div>
                </div>

                <p class="device-note muted">
                    提示：将设备下线后，该设备上的登录凭证将失效，需重新登录。修改密码会同时退出所有设备。
                </p>

                <div class="center" style="margin-top:18px;font-size:13.5px">
                    <RouterLink class="link" to="/profile"><span v-html="icons.arrowLeft"></span> 返回用户中心</RouterLink>
                </div>
            </template>
        </section>
    </div>
</template>
