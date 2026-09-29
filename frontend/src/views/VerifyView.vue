<script setup>
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { maskEmail } from '../ui/validate';
import { useCountdown } from '../ui/useCountdown';
import CodeInput from '../ui/CodeInput.vue';
import SubmitButton from '../ui/SubmitButton.vue';
import AuthCard from '../ui/AuthCard.vue';

const route = useRoute();
const router = useRouter();

const PENDING_EMAIL_KEY = 'hpa.pendingEmail';

const email = ref('');
const code = ref('');
const loading = ref(false);
const codeInput = ref(null);
const { left, start } = useCountdown();

onMounted(() => {
    email.value = route.query.email || localStorage.getItem(PENDING_EMAIL_KEY) || '';
    if (!email.value) {
        toast('请先填写邮箱完成注册', 'info');
        router.replace('/register');
        return;
    }
    start(60);
});

async function submit() {
    if (!/^\d{6}$/.test(code.value)) {
        codeInput.value?.markInvalid();
        return;
    }
    loading.value = true;
    try {
        await authApi.verifyEmail({ email: email.value, code: code.value });
        localStorage.removeItem(PENDING_EMAIL_KEY);
        toast('邮箱验证成功，已自动登录', 'success');
        router.replace('/home');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '验证失败', 'error');
        codeInput.value?.markInvalid();
        codeInput.value?.focus();
    } finally {
        loading.value = false;
    }
}

async function resend() {
    if (left.value > 0) return;
    try {
        const message = await authApi.resendVerification({ email: email.value });
        toast(message || '验证码已重新发送', 'success');
        start(60);
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    }
}
</script>

<template>
    <AuthCard title="输入验证码">
        <template #subtitle>
            验证码已发送至 <strong>{{ maskEmail(email) }}</strong><br />请在 5 分钟内完成验证
        </template>

        <div class="code-block">
            <CodeInput ref="codeInput" v-model="code" @complete="submit" />
            <div class="resend-line">
                没收到？
                <a class="link" role="button" tabindex="0"
                   :aria-disabled="left > 0 ? 'true' : 'false'"
                   @click="resend">
                    {{ left > 0 ? `${left}s 后可重新发送` : '重新发送验证码' }}
                </a>
            </div>
        </div>

        <form class="form" novalidate style="margin-top:18px" @submit.prevent="submit">
            <SubmitButton text="验 证" loading-text="验证中…" :loading="loading" @click="submit" />
            <div class="center muted" style="font-size:13.5px">
                <a class="link" role="button" @click="router.push('/register')">邮箱填错了？返回修改</a>
            </div>
        </form>
    </AuthCard>
</template>
