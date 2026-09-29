<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { check, maskEmail, rules } from '../ui/validate';
import { useCountdown } from '../ui/useCountdown';
import { clearSession, loadCurrentUser } from '../stores/session';
import CodeInput from '../ui/CodeInput.vue';
import PasswordField from '../ui/PasswordField.vue';
import SubmitButton from '../ui/SubmitButton.vue';

const route = useRoute();
const router = useRouter();

/** 步骤：send（发验证码）→ code（输验证码改密）/ linkSent（链接已发） */
const step = ref('send');
const mode = ref('password');
const linkSent = ref(false);

const email = ref('');
const code = ref('');
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' });
const errors = reactive({ oldPassword: '', newPassword: '', confirm: '' });
const loading = ref(false);
const codeInput = ref(null);
const { left, start } = useCountdown();

const confirmMismatch = computed(() => Boolean(form.confirm) && form.confirm !== form.newPassword);

onMounted(async () => {
    const me = await loadCurrentUser();
    if (!me?.email) {
        toast('登录状态已失效，请重新登录', 'info');
        router.replace('/login');
        return;
    }
    email.value = me.email;
    const queryMode = route.query.mode;
    mode.value = ['password', 'code', 'link'].includes(queryMode) ? queryMode : 'password';
});

watch(mode, () => {
    step.value = 'send';
    linkSent.value = false;
    code.value = '';
    form.oldPassword = '';
    form.newPassword = '';
    form.confirm = '';
    errors.oldPassword = '';
    errors.newPassword = '';
    errors.confirm = '';
});

async function sendCode() {
    loading.value = true;
    try {
        const message = await authApi.forgotPasswordByCode({ email: email.value });
        toast(message || '验证码已发送，请查收邮箱', 'success');
        step.value = 'code';
        start(60);
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    } finally {
        loading.value = false;
    }
}

async function resendCode() {
    if (left.value > 0) return;
    try {
        const message = await authApi.forgotPasswordByCode({ email: email.value });
        toast(message || '验证码已重新发送', 'success');
        start(60);
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    }
}

async function submitCode() {
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
        // 后端会撤销所有登录态，这里直接登出并跳转登录页
        clearSession();
        toast(message || '密码修改成功，请使用新密码重新登录', 'success');
        router.replace('/login');
    } catch (err) {
        const errCode = err instanceof ApiError ? err.code : '';
        if (errCode === 'CODE_EXPIRED' || errCode === 'TOO_MANY_ATTEMPTS') {
            toast(err.message || '请重新获取验证码。', 'error');
            step.value = 'send';
            return;
        }
        if (errCode === 'CODE_INVALID') codeInput.value?.clear();
        toast(err instanceof ApiError ? err.message : '修改失败，请重试', 'error');
    } finally {
        loading.value = false;
    }
}

async function sendLink() {
    loading.value = true;
    try {
        await authApi.forgotPassword({ email: email.value });
        linkSent.value = true;
        toast('重置链接已发送，请查收邮箱', 'success');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
    } finally {
        loading.value = false;
    }
}

/** 凭原密码直接修改密码。 */
async function submitPassword() {
    errors.oldPassword = form.oldPassword ? '' : '请输入原密码';
    const p = check(form.newPassword, rules.password);
    errors.newPassword = p.ok ? '' : p.message;
    errors.confirm = form.confirm === form.newPassword ? '' : '两次输入的密码不一致';
    if (errors.oldPassword || errors.newPassword || errors.confirm) return;

    loading.value = true;
    try {
        const message = await authApi.changePassword({
            oldPassword: form.oldPassword,
            newPassword: form.newPassword,
        });
        // 后端会撤销所有登录态，这里直接登出并跳转登录页
        clearSession();
        toast(message || '密码修改成功，请使用新密码重新登录', 'success');
        router.replace('/login');
    } catch (err) {
        const errCode = err instanceof ApiError ? err.code : '';
        if (errCode === 'INVALID_CREDENTIALS') {
            errors.oldPassword = err.message || '原密码错误，请重新输入';
            form.oldPassword = '';
            return;
        }
        toast(err instanceof ApiError ? err.message : '修改失败，请重试', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <section class="card">
        <div class="card-head">
            <span class="eyebrow">安全设置</span>
            <h1>修改密码</h1>
            <p>当前账号 <strong>{{ maskEmail(email) }}</strong><br />请选择一种修改方式</p>
        </div>

        <div class="method-switch triple" role="radiogroup" aria-label="选择修改方式">
            <button type="button" class="method" :class="{ active: mode === 'password' }"
                    role="radio" :aria-checked="mode === 'password'" @click="mode = 'password'">
                <span class="m-title"><span v-html="icons.lock"></span> 原密码</span>
                <span class="m-desc">输入当前密码直接修改</span>
            </button>
            <button type="button" class="method" :class="{ active: mode === 'code' }"
                    role="radio" :aria-checked="mode === 'code'" @click="mode = 'code'">
                <span class="m-title"><span v-html="icons.shield"></span> 邮箱验证码</span>
                <span class="m-desc">输入验证码后直接修改密码</span>
            </button>
            <button type="button" class="method" :class="{ active: mode === 'link' }"
                    role="radio" :aria-checked="mode === 'link'" @click="mode = 'link'">
                <span class="m-title"><span v-html="icons.mail"></span> 邮件链接</span>
                <span class="m-desc">发送重置链接到我的邮箱</span>
            </button>
        </div>

        <!-- 原密码方式：直接修改 -->
        <form v-if="mode === 'password'" class="form" novalidate @submit.prevent="submitPassword">
            <div>
                <PasswordField v-model="form.oldPassword" placeholder="原密码"
                               autocomplete="current-password" :invalid="Boolean(errors.oldPassword)" />
                <div class="hint" :class="{ error: Boolean(errors.oldPassword) }">{{ errors.oldPassword }}</div>
            </div>
            <div>
                <PasswordField v-model="form.newPassword" placeholder="新密码（至少 8 位，含字母和数字）"
                               autocomplete="new-password" with-strength :invalid="Boolean(errors.newPassword)" />
                <div class="hint" :class="{ error: Boolean(errors.newPassword) }">{{ errors.newPassword }}</div>
            </div>
            <div>
                <PasswordField v-model="form.confirm" placeholder="确认新密码"
                               autocomplete="new-password" :invalid="confirmMismatch" />
                <div class="hint" :class="{ error: confirmMismatch }">
                    {{ confirmMismatch ? '两次输入的密码不一致' : '' }}
                </div>
            </div>
            <SubmitButton text="确认修改" loading-text="提交中…" :loading="loading" @click="submitPassword" />
            <p class="center muted" style="font-size:12.5px; margin:0">修改成功后需使用新密码重新登录</p>
        </form>

        <!-- 验证码方式：第一步 -->
        <div v-else-if="mode === 'code' && step === 'send'" class="stack-sm">
            <SubmitButton text="发送验证码到邮箱" loading-text="发送中…" :loading="loading" @click="sendCode" />
            <p class="center muted" style="font-size:12.5px; margin:6px 0 0">
                验证码将发送至 {{ maskEmail(email) }}
            </p>
        </div>

        <!-- 验证码方式：第二步 -->
        <div v-else-if="mode === 'code'" class="code-block" style="align-items:stretch">
            <CodeInput ref="codeInput" v-model="code" />
            <div class="resend-line center">
                没收到？
                <a class="link" role="button" tabindex="0"
                   :aria-disabled="left > 0 ? 'true' : 'false'" @click="resendCode">
                    {{ left > 0 ? `${left}s 后可重新发送` : '重新发送验证码' }}
                </a>
            </div>

            <form class="form" style="margin-top:16px" novalidate @submit.prevent="submitCode">
                <div>
                    <PasswordField v-model="form.newPassword" placeholder="新密码（至少 8 位，含字母和数字）"
                                   autocomplete="new-password" with-strength :invalid="Boolean(errors.newPassword)" />
                    <div class="hint" :class="{ error: Boolean(errors.newPassword) }">{{ errors.newPassword }}</div>
                </div>
                <div>
                    <PasswordField v-model="form.confirm" placeholder="确认新密码"
                                   autocomplete="new-password" :invalid="confirmMismatch" />
                    <div class="hint" :class="{ error: confirmMismatch }">
                        {{ confirmMismatch ? '两次输入的密码不一致' : '' }}
                    </div>
                </div>
                <SubmitButton text="确认修改" loading-text="提交中…" :loading="loading" @click="submitCode" />
            </form>
        </div>

        <!-- 邮件链接方式 -->
        <div v-else class="stack-sm">
            <div v-if="linkSent" class="notice success">
                <span v-html="icons.check"></span>
                <span>重置链接已发送到 {{ maskEmail(email) }}。请在 30 分钟内点击邮件中的链接完成修改（若未收到请检查垃圾箱）。</span>
            </div>
            <SubmitButton v-else text="发送重置链接到邮箱" loading-text="发送中…" :loading="loading" @click="sendLink" />
            <p class="center muted" style="font-size:12.5px; margin:6px 0 0">
                链接将发送至 {{ maskEmail(email) }}，30 分钟内有效
            </p>
        </div>

        <div class="center" style="margin-top:18px; font-size:13.5px">
            <RouterLink class="link" to="/profile"><span v-html="icons.arrowLeft"></span> 返回用户中心</RouterLink>
        </div>
    </section>
</template>
