<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../../api/http';
import { toast } from '../../ui/toast';
import { icons } from '../../ui/icons';
import { useHelperWatch } from '../../ui/useHelper';
import { usePlatformCredential } from '../../composables/usePlatformCredential';
import HelperInstallSteps from './HelperInstallSteps.vue';
import TextField from '../../ui/TextField.vue';
import PasswordField from '../../ui/PasswordField.vue';
import SubmitButton from '../../ui/SubmitButton.vue';
import NoticeBar from '../../ui/NoticeBar.vue';

const props = defineProps({
    platform: { type: Object, required: true },
});

const router = useRouter();
const cred = usePlatformCredential(props.platform);
const { helperReady, helperVersion } = useHelperWatch();

/** 助手脚本由前端同源提供（Vite dev 中间件 / 构建产物） */
const scriptUrl = '/script.user.js';
const pluginUrl = 'https://scriptcat.org/zh-CN/';

const syncHint = ref('');
const account = ref('');
const password = ref('');

/** Cookie 三态，界面按它决定该说什么：还没校验过 ≠ 已失效 */
const cookieStatus = computed(() => cred.detail.value?.cookieStatus || 'UNKNOWN');

/**
 * 只有「还没装脚本」时才把安装步骤摊在页面上 —— 装好之后就不再提示，
 * 收进折叠区作为「重新安装 / 更换」的备用参考。
 */
const showStepsInline = computed(() => !helperReady.value);

/**
 * 账号密码栏的展开时机：未绑定、库里没存密码（存了才谈得上自动重登）、
 * 或上次同步失败。换绑不另设入口 —— 要换账号先解绑，再登录一次。
 */
const showForm = computed(() => !cred.bound.value
    || !cred.hasPassword.value
    || Boolean(syncHint.value));

function openSite() {
    window.open(props.platform.url, '_blank', 'noopener');
}

/**
 * 从浏览器助手同步 Cookie —— 凭证采集入口之一，必须由点击触发。
 * 这条路只搬 Cookie、不写密码，所以同步完不会让「自动重登」变得可用。
 */
async function resync() {
    if (!helperReady.value) {
        return toast('未检测到浏览器助手，请按上方步骤安装用户脚本', 'error');
    }
    try {
        await cred.resyncFromHelper({ silent: true });
        syncHint.value = '';
        toast('已从浏览器同步 Cookie', 'success');
    } catch (err) {
        syncHint.value = `获取 Cookie 失败：${err?.message || '未知原因'}。`
            + '请确认浏览器已登录学习通后重试，或用下方表单填写账号密码获取。';
        toast(syncHint.value, 'error');
    }
}

/**
 * 账号密码入口：由浏览器助手完成登录，并把账号密码一起入库。
 * 存下密码是关键 —— Cookie 失效时助手才能靠它自动重新登录。
 */
async function getCookie() {
    const loginAccount = account.value.trim();
    const loginPassword = password.value;
    if (!loginAccount) return toast('请输入账号', 'error');
    if (!loginPassword) return toast('请输入密码', 'error');
    // 读到就用完即弃：输入框立刻清空，密码只存在于本次调用的局部变量里
    password.value = '';

    try {
        await cred.manualLogin(loginAccount, loginPassword);
        account.value = cred.account.value || loginAccount;
        syncHint.value = '';
        toast('获取成功，凭证已保存', 'success');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : (err?.message || '获取失败，请重试'), 'error');
    }
}

async function check() {
    syncHint.value = '';
    await cred.ensureValid();
}

async function unbind() {
    if (!window.confirm('确定要解绑学习通吗？\n\n服务器上保存的账号、密码与 Cookie 都会被删除。')) return;
    try {
        await cred.unbind();
        password.value = '';
        syncHint.value = '';
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '解绑失败', 'error');
    }
}

onMounted(async () => {
    await cred.reload();
    if (cred.bound.value) {
        account.value = cred.account.value || '';
        // 库里记录的 Cookie 不是有效态 → 静默校验一次，失效时用保存的密码自动重登
        if (cred.detail.value?.cookieStatus !== 'VALID') await cred.ensureValid({ silent: true });
    }
});
</script>

<template>
    <section class="card wide platform-panel">
        <div class="card-head">
            <span class="platform-logo" v-html="platform.icon"></span>
            <h1>学习通</h1>
            <p>由浏览器助手直接读取已登录的 Cookie</p>
            <span class="helper-badge" :class="helperReady ? 'on' : 'off'">
                <i aria-hidden="true"></i>
                {{ helperReady ? `助手已就绪${helperVersion}` : '未检测到助手' }}
            </span>
        </div>

        <!-- 状态：同一时刻只呈现最相关的一条 -->
        <NoticeBar v-if="cred.offline.value" type="error">
            无法连接后端服务，请确认后端（8080）已启动。
        </NoticeBar>
        <NoticeBar v-else-if="cred.phase.value === 'relogining'" type="info" busy>
            {{ cred.message.value }}
        </NoticeBar>
        <NoticeBar v-else-if="syncHint" type="error">{{ syncHint }}</NoticeBar>
        <NoticeBar v-else-if="cred.bound.value && cookieStatus === 'EXPIRED'" type="error">
            学习通 Cookie 已失效（{{ cred.cookieCount.value }} 项）。
            {{ cred.hasPassword.value
                ? '自动重新登录未成功，请在浏览器里登录学习通后点「重新获取 Cookie」。'
                : '数据库中未保存账号密码，请在下方填写一次，之后就能自动重新登录。' }}
        </NoticeBar>
        <NoticeBar v-else-if="cred.bound.value && cookieStatus === 'UNKNOWN'" type="info">
            尚未校验学习通 Cookie 是否仍然有效，点「校验凭证」确认一下。
        </NoticeBar>
        <NoticeBar v-else-if="cred.valid.value" type="success">
            {{ cred.phase.value === 'ready' ? cred.message.value : 'Cookie 有效' }}（Cookie {{ cred.cookieCount.value }} 项）
        </NoticeBar>
        <NoticeBar v-else type="info">
            还没有绑定学习通。请在浏览器里登录学习通后点「重新获取 Cookie」，
            或填写下方的账号密码让助手直接登录。
        </NoticeBar>

        <template v-if="!cred.offline.value">
            <!-- 还没装脚本：把安装步骤摊开（装好后自动消失） -->
            <HelperInstallSteps v-if="showStepsInline"
                                :helper-ready="helperReady"
                                :bound="cred.bound.value"
                                :plugin-url="pluginUrl"
                                :script-url="scriptUrl" />
            <!-- 脚本已就绪：安装说明收进折叠区，只在需要重装时展开 -->
            <details v-else class="cookie-box">
                <summary>重新安装或更换浏览器助手</summary>
                <div class="xxt-help">
                    <HelperInstallSteps :helper-ready="helperReady"
                                        :bound="cred.bound.value"
                                        :plugin-url="pluginUrl"
                                        :script-url="scriptUrl" />
                </div>
            </details>

            <!-- 未绑定 -->
            <div v-if="!cred.bound.value" class="grid-2 xxt-actions">
                <button class="btn" type="button" :disabled="!helperReady || cred.busy.value" @click="resync">
                    重新获取 Cookie
                </button>
                <button class="btn ghost" type="button" @click="openSite">前往学习通官网</button>
            </div>

            <!-- 已绑定 -->
            <template v-else>
                <div class="xxt-summary">
                    <div class="xxt-summary-row">
                        <span class="k">账号</span>
                        <span class="v">{{ cred.account.value || '未填写' }}</span>
                    </div>
                    <div class="xxt-summary-row">
                        <span class="k">凭证</span>
                        <span class="v">
                            Cookie {{ cred.cookieCount.value }} 项
                            <span class="pill" :class="cred.valid.value ? 'ok' : 'bad'">
                                {{ cred.valid.value ? '有效' : '已失效' }}
                            </span>
                        </span>
                    </div>
                    <div class="xxt-summary-row">
                        <span class="k">密码</span>
                        <span class="v">{{ cred.hasPassword.value ? '已加密保存' : '未保存' }}</span>
                    </div>
                </div>

                <p v-if="!cred.hasPassword.value" class="xxt-note">
                    当前只存了 Cookie，没存密码 —— Cookie 失效后需要手动重新获取。
                    在下方填写一次账号密码，之后助手就能自动重新登录。
                </p>

                <div class="grid-2 xxt-actions">
                    <button class="btn" type="button" :disabled="!helperReady || cred.busy.value" @click="resync">
                        重新获取 Cookie
                    </button>
                    <button class="btn ghost" type="button" :disabled="cred.busy.value" @click="check">
                        校验凭证
                    </button>
                </div>
                <div class="center xxt-footlink">
                    <a class="link" role="button" tabindex="0" @click="unbind">解绑并清除服务器上的凭证</a>
                </div>
                <p class="xxt-note">更换账号：先解绑清除旧凭证，再重新登录一次。</p>
            </template>

            <!-- 账号密码栏：由助手在浏览器内登录，并把账号密码一并入库。
                 存下密码后，Cookie 失效时助手才能自动重新登录 -->
            <details class="cookie-box" :open="showForm">
                <summary>填写账号密码（保存后助手可自动重新登录）</summary>
                <div class="xxt-help">
                    <div class="form">
                        <TextField v-model="account" placeholder="学习通账号（手机号）" :icon="icons.user" harden />
                        <PasswordField v-model="password" placeholder="密码" harden />
                        <SubmitButton text="一键获取 Cookie" loading-text="正在登录…"
                                      :loading="cred.busy.value" @click="getCookie" />
                        <p class="muted center" style="font-size:12.5px; margin:0">
                            账号密码在每次点击时由本页面传入，仅在浏览器内加密后直连学习通，
                            不经过本站服务器；获取到的账号密码与 Cookie 会加密保存到本站数据库。
                        </p>
                    </div>
                </div>
            </details>
        </template>

        <div class="center xxt-back">
            <button type="button" class="btn ghost inline" @click="router.push('/home')">
                <span v-html="icons.arrowLeft"></span> 返回平台概览
            </button>
        </div>
    </section>
</template>
