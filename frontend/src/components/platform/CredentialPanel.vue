<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../../api/http';
import { toast } from '../../ui/toast';
import { icons } from '../../ui/icons';
import { usePlatformCredential } from '../../composables/usePlatformCredential';
import TextField from '../../ui/TextField.vue';
import PasswordField from '../../ui/PasswordField.vue';
import SubmitButton from '../../ui/SubmitButton.vue';
import NoticeBar from '../../ui/NoticeBar.vue';

const props = defineProps({
    platform: { type: Object, required: true },
});

const router = useRouter();
const cred = usePlatformCredential(props.platform);

const account = ref('');
const password = ref('');

/** Cookie 三态，界面按它决定该说什么：还没校验过 ≠ 已失效 */
const cookieStatus = computed(() => cred.detail.value?.cookieStatus || 'UNKNOWN');

/**
 * 账号密码栏的展开时机：未配置、库里没存密码、或 Cookie 已失效（需要重新登录一次）。
 * 换绑不另设入口 —— 要换账号先解绑，再登录一次。
 */
const showForm = computed(() => !cred.bound.value
    || !cred.hasPassword.value
    || cookieStatus.value === 'EXPIRED');

/** 已保存的 Cookie 字段，按「键 / 值」两列铺开，比一整块 JSON 好读 */
const cookieRows = computed(() => Object.entries(cred.detail.value?.cookies || {}));
/** 平台额外的校验参数（智慧树用），有才显示 */
const extraRows = computed(() => {
    const extra = cred.detail.value?.extra || {};
    return ['validate', 'fp'].filter((k) => extra[k]).map((k) => ({ k, v: extra[k] }));
});

function openSite() {
    window.open(props.platform.url, '_blank', 'noopener');
}

/** 校验：失效时后端会用库里保存的账号密码自动重登 */
async function check() {
    await cred.ensureValid();
    if (cred.account.value) account.value = cred.account.value;
}

async function submit() {
    const loginAccount = account.value.trim();
    const loginPassword = password.value;
    if (!loginAccount) return toast('请输入账号', 'error');
    if (!loginPassword) return toast('请输入密码', 'error');
    // 读到就用完即弃：输入框立刻清空，密码只存在于本次调用的局部变量里
    password.value = '';

    try {
        await cred.manualLogin(loginAccount, loginPassword);
        account.value = cred.account.value || loginAccount;
        toast('登录成功，凭证已加密保存到服务器', 'success');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '登录失败，请稍后重试', 'error');
    }
}

async function unbind() {
    if (!window.confirm(`确定要解绑 ${props.platform.name} 吗？\n\n服务器上保存的账号、密码与 Cookie 都会被删除。`)) return;
    try {
        await cred.unbind();
        password.value = '';
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '解绑失败', 'error');
    }
}

onMounted(async () => {
    await cred.reload();
    if (cred.bound.value) {
        account.value = cred.account.value || '';
        // 库里记录的 Cookie 不是有效态 → 静默校验一次，失效时自动重新登录
        if (cred.detail.value?.cookieStatus !== 'VALID') await cred.ensureValid({ silent: true });
    }
});
</script>

<template>
    <section class="card wide platform-panel">
        <div class="card-head">
            <span class="platform-logo" v-html="platform.icon"></span>
            <h1>{{ platform.name }}</h1>
            <p>{{ platform.desc }}</p>
        </div>

        <!-- 状态：同一时刻只呈现最相关的一条 -->
        <NoticeBar v-if="cred.offline.value" type="error">
            无法连接后端服务，请确认后端（8080）已启动。
        </NoticeBar>
        <NoticeBar v-else-if="cred.phase.value === 'relogining'" type="info" busy>
            {{ cred.message.value }}
        </NoticeBar>
        <NoticeBar v-else-if="cred.bound.value && cookieStatus === 'EXPIRED'" type="error">
            {{ platform.name }} Cookie 已失效（{{ cred.cookieCount.value }} 项）。
            {{ cred.hasPassword.value
                ? '自动重新登录未成功，请在下方用账号密码重新登录一次。'
                : '数据库中未保存密码，请在下方重新登录一次。' }}
        </NoticeBar>
        <NoticeBar v-else-if="cred.bound.value && cookieStatus === 'UNKNOWN'" type="info">
            尚未校验 {{ platform.name }} Cookie 是否仍然有效，点「校验 Cookie」确认一下。
        </NoticeBar>
        <NoticeBar v-else-if="cred.valid.value" type="success">
            {{ cred.phase.value === 'ready' ? cred.message.value : 'Cookie 有效' }}（Cookie {{ cred.cookieCount.value }} 项）
        </NoticeBar>
        <NoticeBar v-else type="info">
            还没有配置 {{ platform.name }} 账号。请在下方填写账号密码完成一次登录，
            之后默认用 Cookie 取数，失效时会自动重新登录。
        </NoticeBar>

        <template v-if="!cred.offline.value && cred.bound.value">
            <div class="xxt-summary">
                <div class="xxt-summary-row">
                    <span class="k">账号</span>
                    <span class="v">{{ cred.account.value || '—' }}</span>
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

            <details class="cookie-box">
                <summary>查看已保存的凭证（{{ cred.cookieCount.value }} 项）</summary>
                <div class="cred-keys">
                    <div v-for="row in cookieRows" :key="row[0]" class="cred-key">
                        <span class="k">{{ row[0] }}</span>
                        <span class="v">{{ row[1] }}</span>
                    </div>
                    <div v-for="row in extraRows" :key="row.k" class="cred-key">
                        <span class="k">{{ row.k }}</span>
                        <span class="v">{{ row.v }}</span>
                    </div>
                </div>
            </details>

            <div class="grid-2 xxt-actions">
                <button class="btn" type="button" :disabled="cred.busy.value" @click="check">校验 Cookie</button>
                <button class="btn ghost" type="button" @click="openSite">前往 {{ platform.name }} 官网</button>
            </div>
            <div class="center xxt-footlink">
                <a class="link" role="button" tabindex="0" @click="unbind">解绑并清除服务器上的凭证</a>
            </div>
            <p class="xxt-note">更换账号：先解绑清除旧凭证，再重新登录一次。</p>
        </template>

        <!-- 账号密码栏：未配置、没存密码、或 Cookie 失效时展开。
             未配置时它就是主操作，不再用按钮去和它抢位置 -->
        <div v-show="showForm" class="cred-form">
            <div class="form">
                <TextField v-model="account" :placeholder="`${platform.name}账号（手机号）`"
                           :icon="icons.user" harden />
                <PasswordField v-model="password" placeholder="密码" harden />
                <SubmitButton :text="`登录 ${platform.name}`" loading-text="登录中…"
                              :loading="cred.busy.value" @click="submit" />
            </div>
            <!-- 已绑定时官网入口在上一行的按钮里，这里不重复给 -->
            <div v-if="!cred.bound.value" class="center xxt-footlink">
                <a class="link" role="button" tabindex="0" @click="openSite">前往 {{ platform.name }} 官网</a>
            </div>
        </div>

        <div class="center xxt-back">
            <button type="button" class="btn ghost inline" @click="router.push('/home')">
                <span v-html="icons.arrowLeft"></span> 返回平台概览
            </button>
        </div>
    </section>
</template>
