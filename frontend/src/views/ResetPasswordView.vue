<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { check, rules } from '../ui/validate';
import PasswordField from '../ui/PasswordField.vue';
import SubmitButton from '../ui/SubmitButton.vue';
import ResultCard from '../ui/ResultCard.vue';
import AuthCard from '../ui/AuthCard.vue';

const route = useRoute();
const router = useRouter();

const token = ref('');
/** 页面状态：checking 校验中 / ready 可设置 / broken 链接失效 / done 已完成 */
const status = ref('checking');
const maskedEmail = ref('');
/** 链接剩余有效秒数 */
const leftSeconds = ref(0);

const form = reactive({ newPassword: '', confirm: '' });
const errors = reactive({ newPassword: '', confirm: '' });
const loading = ref(false);
/** 链接失效后展示结果页 */
const broken = ref(null);

const confirmMismatch = computed(() => Boolean(form.confirm) && form.confirm !== form.newPassword);

const countdownText = computed(() => {
    if (leftSeconds.value <= 0) return '';
    const m = Math.floor(leftSeconds.value / 60);
    const s = leftSeconds.value % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
});

let timer = null;
function stopTimer() {
    if (timer) clearInterval(timer);
    timer = null;
}

onUnmounted(stopTimer);

onMounted(async () => {
    token.value = route.query.token || '';
    if (!token.value) {
        status.value = 'broken';
        broken.value = {
            title: '链接无效',
            desc: '重置链接缺少必要参数，请重新申请找回密码。',
        };
        return;
    }
    await verifyToken();
});

// 兼容路径式链接：/reset-password?token=xxx（由 main.js 里的兜底逻辑跳过来）
watch(() => route.query.token, (value) => {
    if (value && value !== token.value) {
        token.value = value;
        verifyToken();
    }
});

async function verifyToken() {
    status.value = 'checking';
    try {
        const info = await authApi.checkResetToken(token.value);
        maskedEmail.value = info?.email || '';
        status.value = 'ready';
        startExpiryCountdown(info?.expiresAt);
    } catch (err) {
        status.value = 'broken';
        broken.value = {
            title: '链接已失效',
            desc: err instanceof ApiError && err.message ? err.message : '重置链接无效或已过期，请重新申请。',
        };
    }
}

function startExpiryCountdown(expiresAt) {
    stopTimer();
    const end = expiresAt ? new Date(expiresAt).getTime() : 0;
    if (!end || Number.isNaN(end)) return;
    const tick = () => {
        const remain = Math.floor((end - Date.now()) / 1000);
        if (remain <= 0) {
            stopTimer();
            status.value = 'broken';
            broken.value = { title: '链接已过期', desc: '重置链接已超时失效，请重新申请。' };
            return;
        }
        leftSeconds.value = remain;
    };
    tick();
    timer = setInterval(tick, 1000);
}

function validateAll() {
    const p = check(form.newPassword, rules.password);
    errors.newPassword = p.ok ? '' : p.message;
    errors.confirm = form.confirm === form.newPassword ? '' : '两次输入的密码不一致';
    return p.ok && !errors.confirm;
}

async function submit() {
    if (!validateAll()) return;
    loading.value = true;
    try {
        const message = await authApi.resetPassword({ token: token.value, newPassword: form.newPassword });
        stopTimer();
        form.newPassword = '';
        form.confirm = '';
        toast(message || '密码重置成功，请使用新密码登录', 'success');
        status.value = 'done';
    } catch (err) {
        const code = err instanceof ApiError ? err.code : '';
        if (['TOKEN_INVALID', 'TOKEN_EXPIRED', 'TOKEN_USED'].includes(code)) {
            stopTimer();
            status.value = 'broken';
            broken.value = {
                title: '链接已失效',
                desc: err.message || '重置链接无效或已过期，请重新申请。',
            };
            return;
        }
        toast(err instanceof ApiError ? err.message : '重置失败，请重新申请', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <!-- 链接失效 / 缺少参数 -->
    <ResultCard v-if="status === 'broken'" :ok="false" :title="broken.title" :desc="broken.desc"
                action-text="重新申请重置" @action="router.push('/forgot')" />

    <!-- 重置成功 -->
    <ResultCard v-else-if="status === 'done'" title="密码重置成功"
                desc="您的新密码已生效，原登录态已全部失效，请使用新密码重新登录。"
                action-text="去登录" @action="router.replace('/login')" />

    <!-- 正在校验链接 -->
    <section v-else-if="status === 'checking'" class="card">
        <div class="result">
            <div class="badge" v-html="icons.shield"></div>
            <h2>正在验证链接…</h2>
            <p>请稍候，我们正在检查重置链接的有效性</p>
        </div>
    </section>

    <!-- 设置新密码 -->
    <AuthCard v-else title="设置新密码">
        <template #subtitle>
            <template v-if="maskedEmail">正在为 <strong>{{ maskedEmail }}</strong> 设置新密码<br />请设置一个至少 8 位、包含字母和数字的新密码</template>
            <template v-else>请设置一个至少 8 位、包含字母和数字的新密码</template>
        </template>

        <div v-if="countdownText" class="notice info" style="margin-bottom:16px">
            <span v-html="icons.clock"></span>
            <span>本链接 <strong>{{ countdownText }}</strong> 后失效，请尽快完成设置</span>
        </div>

        <form class="form" novalidate @submit.prevent="submit">
            <div>
                <PasswordField v-model="form.newPassword" placeholder="新密码"
                               autocomplete="new-password" with-strength
                               :invalid="Boolean(errors.newPassword)" />
                <div class="hint" :class="{ error: Boolean(errors.newPassword) }">{{ errors.newPassword }}</div>
            </div>

            <div>
                <PasswordField v-model="form.confirm" placeholder="确认新密码"
                               autocomplete="new-password" :invalid="confirmMismatch" />
                <div class="hint" :class="{ error: confirmMismatch }">{{ confirmMismatch ? '两次输入的密码不一致' : '' }}</div>
            </div>

            <SubmitButton text="确认重置" loading-text="提交中…" :loading="loading" @click="submit" />

            <p class="center muted" style="font-size:12.5px; margin:0">
                <span v-html="icons.shield"></span>
                重置成功后所有已登录设备将被强制下线
            </p>
            <div class="center" style="font-size:13.5px"><RouterLink class="link" to="/login">返回登录</RouterLink></div>
        </form>
    </AuthCard>
</template>
