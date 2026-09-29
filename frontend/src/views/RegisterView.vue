<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import { check, rules } from '../ui/validate';
import TextField from '../ui/TextField.vue';
import PasswordField from '../ui/PasswordField.vue';
import SubmitButton from '../ui/SubmitButton.vue';
import AuthCard from '../ui/AuthCard.vue';

const router = useRouter();

const PENDING_EMAIL_KEY = 'hpa.pendingEmail';

const form = reactive({ username: '', email: '', password: '', confirm: '' });
const errors = reactive({ username: '', email: '', password: '', confirm: '' });
const loading = ref(false);

const confirmMismatch = computed(() => Boolean(form.confirm) && form.confirm !== form.password);

// 修改邮箱后清除“已被注册”的服务端错误提示
watch(() => form.email, () => {
    if (errors.email) errors.email = '';
});

function validateAll() {
    const u = check(form.username, rules.username);
    const e = check(form.email, rules.email);
    const p = check(form.password, rules.password);
    errors.username = u.ok ? '' : u.message;
    errors.email = e.ok ? '' : e.message;
    errors.password = p.ok ? '' : p.message;
    errors.confirm = form.confirm === form.password ? '' : '两次输入的密码不一致';
    return u.ok && e.ok && p.ok && !errors.confirm;
}

async function submit() {
    if (!validateAll()) return;
    loading.value = true;
    try {
        const message = await authApi.register({
            username: form.username.trim(),
            email: form.email.trim(),
            password: form.password,
        });
        localStorage.setItem(PENDING_EMAIL_KEY, form.email.trim());
        toast(message || '验证码已发送，请查收邮箱', 'success');
        router.push({ path: '/verify', query: { email: form.email.trim() } });
    } catch (err) {
        if (err instanceof ApiError && err.code === 'EMAIL_TAKEN') {
            // 邮箱已被注册：错误直接标到邮箱输入框下，便于用户定位
            errors.email = err.message;
            return;
        }
        if (err instanceof ApiError && (err.code === 'USERNAME_TAKEN' || err.code === 'DATA_CONFLICT')) {
            // 用户名被占用 / 并发注册撞唯一约束：标到用户名输入框下
            errors.username = err.message;
            return;
        }
        toast(err instanceof ApiError ? err.message : '注册失败', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <AuthCard title="注册" marks>
        <template #subtitle>注册后输入邮箱验证码，即可直接进入平台</template>

        <form class="form" novalidate @submit.prevent="submit">
            <div>
                <TextField v-model="form.username" placeholder="用户名（1-18 位中文/字母/数字/下划线）"
                           autocomplete="username" :icon="icons.user" :invalid="Boolean(errors.username)" />
                <div class="hint" :class="{ error: Boolean(errors.username) }">{{ errors.username }}</div>
            </div>

            <div>
                <TextField v-model="form.email" type="email" placeholder="邮箱"
                           autocomplete="email" :icon="icons.mail" :invalid="Boolean(errors.email)" />
                <div class="hint" :class="{ error: Boolean(errors.email) }">{{ errors.email }}</div>
            </div>

            <div>
                <PasswordField v-model="form.password" placeholder="密码（至少 8 位，含字母和数字）"
                               autocomplete="new-password" with-strength :invalid="Boolean(errors.password)" />
                <div class="hint" :class="{ error: Boolean(errors.password) }">{{ errors.password }}</div>
            </div>

            <div>
                <PasswordField v-model="form.confirm" placeholder="确认密码"
                               autocomplete="new-password" :invalid="confirmMismatch" />
                <div class="hint" :class="{ error: confirmMismatch }">{{ confirmMismatch ? '两次输入的密码不一致' : '' }}</div>
            </div>

            <SubmitButton text="注 册" loading-text="提交中…" :loading="loading" @click="submit" />

            <div class="center muted" style="font-size:13.5px">
                已有账号？<RouterLink class="link" to="/login">返回登录</RouterLink>
            </div>
        </form>
    </AuthCard>
</template>
