<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { check, maskEmail, rules } from '../ui/validate';
import { useCountdown } from '../ui/useCountdown';
import TextField from '../ui/TextField.vue';
import SubmitButton from '../ui/SubmitButton.vue';
import AuthCard from '../ui/AuthCard.vue';

const route = useRoute();
const router = useRouter();

const PENDING_RESET_EMAIL_KEY = 'hpa.pendingResetEmail';

const MODES = {
    link: { desc: '输入注册邮箱，我们将发送重置链接（30 分钟内有效）', btn: '发送重置链接' },
    code: { desc: '输入注册邮箱，我们将发送 6 位验证码，可在当前页面直接设置新密码', btn: '发送验证码' },
};

const mode = ref('link');
const email = ref('');
const error = ref('');
const loading = ref(false);
/** 链接方式：发送成功后展示“请查收邮箱”提示页，不直接跳走 */
const linkSent = ref(false);
const { left, start } = useCountdown();

const current = computed(() => MODES[mode.value]);

onMounted(() => {
    if (route.query.mode === 'code') mode.value = 'code';
});

async function submit() {
    const result = check(email.value, rules.email);
    error.value = result.ok ? '' : result.message;
    if (!result.ok) return;

    const value = email.value.trim();
    loading.value = true;
    try {
        if (mode.value === 'code') {
            const message = await authApi.forgotPasswordByCode({ email: value });
            localStorage.setItem(PENDING_RESET_EMAIL_KEY, value);
            toast(message || '验证码已发送，请查收邮箱', 'success');
            router.push({ path: '/reset-code', query: { email: value } });
        } else {
            await authApi.forgotPassword({ email: value });
            linkSent.value = true;
            start(60);
        }
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    } finally {
        loading.value = false;
    }
}

/** 提示页上的“重新发送”（受 60 秒间隔限制） */
async function resendLink() {
    if (left.value > 0) return;
    loading.value = true;
    try {
        await authApi.forgotPassword({ email: email.value.trim() });
        toast('重置链接已重新发送，请查收邮箱', 'success');
        start(60);
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <!-- 链接已发送：引导用户去邮箱完成重置，再手动返回登录 -->
    <AuthCard v-if="linkSent" title="请查收邮件">
        <template #subtitle>重置链接已发送至 <strong>{{ maskEmail(email) }}</strong></template>

        <div class="stack-sm">
            <div class="notice success">
                <span v-html="icons.check"></span>
                <span>请前往邮箱点击邮件中的「重置密码」链接，在 30 分钟内完成新密码设置；若未收到，请检查垃圾箱。</span>
            </div>

            <SubmitButton text="返回登录" @click="router.replace('/login')" />
            <p class="center muted" style="font-size:12.5px; margin:6px 0 0">
                没收到邮件？
                <a class="link" role="button" tabindex="0"
                   :aria-disabled="left > 0 ? 'true' : 'false'" @click="resendLink">
                    {{ left > 0 ? `${left}s 后可重新发送` : '重新发送重置链接' }}
                </a>
            </p>
        </div>
    </AuthCard>

    <AuthCard v-else title="忘记密码">
        <template #subtitle>{{ current.desc }}</template>

        <div class="method-switch" role="radiogroup" aria-label="选择找回方式">
            <button type="button" class="method" :class="{ active: mode === 'link' }"
                    role="radio" :aria-checked="mode === 'link'" @click="mode = 'link'">
                <span class="m-title"><span v-html="icons.mail"></span> 邮件链接</span>
                <span class="m-desc">收到邮件后点击链接设置新密码</span>
            </button>
            <button type="button" class="method" :class="{ active: mode === 'code' }"
                    role="radio" :aria-checked="mode === 'code'" @click="mode = 'code'">
                <span class="m-title"><span v-html="icons.shield"></span> 邮箱验证码</span>
                <span class="m-desc">输入验证码后直接在此修改密码</span>
            </button>
        </div>

        <form class="form" novalidate @submit.prevent="submit">
            <div>
                <TextField v-model="email" type="email" placeholder="注册邮箱"
                           autocomplete="email" :icon="icons.mail" :invalid="Boolean(error)" />
                <div class="hint" :class="{ error: Boolean(error) }">{{ error }}</div>
            </div>

            <SubmitButton :text="current.btn" loading-text="发送中…" :loading="loading" @click="submit" />

            <div class="center" style="font-size:13.5px">
                <RouterLink class="link" to="/login">
                    <span v-html="icons.arrowLeft"></span> 返回登录
                </RouterLink>
            </div>
        </form>
    </AuthCard>
</template>
