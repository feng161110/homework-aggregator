<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { check, maskEmail, rules } from '../ui/validate';
import { useCountdown } from '../ui/useCountdown';
import CodeInput from '../ui/CodeInput.vue';
import PasswordField from '../ui/PasswordField.vue';
import SubmitButton from '../ui/SubmitButton.vue';
import ResultCard from '../ui/ResultCard.vue';
import AuthCard from '../ui/AuthCard.vue';

const route = useRoute();
const router = useRouter();

const PENDING_RESET_EMAIL_KEY = 'hpa.pendingResetEmail';

const email = ref('');
const code = ref('');
const form = reactive({ newPassword: '', confirm: '' });
const errors = reactive({ newPassword: '', confirm: '' });
const loading = ref(false);
const codeInput = ref(null);
const broken = ref(null);
const { left, start } = useCountdown();

const confirmMismatch = computed(() => Boolean(form.confirm) && form.confirm !== form.newPassword);

onMounted(() => {
    email.value = route.query.email || localStorage.getItem(PENDING_RESET_EMAIL_KEY) || '';
    if (!email.value) {
        toast('请先填写邮箱获取验证码', 'info');
        router.replace({ path: '/forgot', query: { mode: 'code' } });
        return;
    }
    start(60);
});

async function resend() {
    if (left.value > 0) return;
    try {
        const message = await authApi.forgotPasswordByCode({ email: email.value });
        toast(message || '验证码已重新发送', 'success');
        start(60);
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    }
}

async function submit() {
    if (!/^\d{6}$/.test(code.value)) {
        codeInput.value?.markInvalid();
        return toast('请输入 6 位验证码', 'error');
    }
    const p = check(form.newPassword, rules.password);
    errors.newPassword = p.ok ? '' : p.message;
    errors.confirm = form.confirm === form.newPassword ? '' : '两次输入的密码不一致';
    if (!p.ok || errors.confirm) return;

    loading.value = true;
    try {
        const message = await authApi.resetPasswordByCode({
            email: email.value,
            code: code.value,
            newPassword: form.newPassword,
        });
        localStorage.removeItem(PENDING_RESET_EMAIL_KEY);
        toast(message || '密码重置成功，请使用新密码登录', 'success');
        router.replace('/login');
    } catch (err) {
        const errCode = err instanceof ApiError ? err.code : '';
        if (errCode === 'CODE_EXPIRED' || errCode === 'TOO_MANY_ATTEMPTS') {
            broken.value = {
                title: errCode === 'CODE_EXPIRED' ? '验证码已过期' : '尝试次数过多',
                desc: err.message || '请重新获取验证码。',
            };
            return;
        }
        if (errCode === 'CODE_INVALID') codeInput.value?.clear();
        toast(err instanceof ApiError ? err.message : '重置失败，请重试', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <ResultCard v-if="broken" :ok="false" :title="broken.title" :desc="broken.desc"
                action-text="重新获取验证码" @action="router.push({ path: '/forgot', query: { mode: 'code' } })" />

    <AuthCard v-else title="设置新密码">
        <template #subtitle>
            验证码已发送至 <strong>{{ maskEmail(email) }}</strong><br />请输入 6 位验证码并设置新密码
        </template>

        <div class="code-block">
            <CodeInput ref="codeInput" v-model="code" />
            <div class="resend-line">
                没收到？
                <a class="link" role="button" tabindex="0"
                   :aria-disabled="left > 0 ? 'true' : 'false'" @click="resend">
                    {{ left > 0 ? `${left}s 后可重新发送` : '重新发送验证码' }}
                </a>
            </div>
        </div>

        <form class="form" novalidate style="margin-top:16px" @submit.prevent="submit">
            <div>
                <PasswordField v-model="form.newPassword" placeholder="新密码（至少 8 位，含字母和数字）"
                               autocomplete="new-password" with-strength :invalid="Boolean(errors.newPassword)" />
                <div class="hint" :class="{ error: Boolean(errors.newPassword) }">{{ errors.newPassword }}</div>
            </div>

            <div>
                <PasswordField v-model="form.confirm" placeholder="确认新密码"
                               autocomplete="new-password" :invalid="confirmMismatch" />
                <div class="hint" :class="{ error: confirmMismatch }">{{ confirmMismatch ? '两次输入的密码不一致' : '' }}</div>
            </div>

            <SubmitButton text="确认重置" loading-text="提交中…" :loading="loading" @click="submit" />
            <div class="center" style="font-size:13.5px">
                <a class="link" role="button" @click="router.push('/forgot')">改用「邮件链接」方式</a>
            </div>
        </form>
    </AuthCard>
</template>
