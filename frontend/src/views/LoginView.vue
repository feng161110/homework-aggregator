<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from '../api/auth';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { icons } from '../ui/icons';
import AuthCard from '../ui/AuthCard.vue';
import TextField from '../ui/TextField.vue';
import PasswordField from '../ui/PasswordField.vue';
import SubmitButton from '../ui/SubmitButton.vue';

const router = useRouter();

const account = ref('');
const password = ref('');
const loading = ref(false);

const LAST_ACCOUNT_KEY = 'hpa.lastAccount';

onMounted(() => {
    account.value = localStorage.getItem(LAST_ACCOUNT_KEY) || '';
});

async function submit() {
    if (!account.value.trim()) return toast('请输入账号', 'error');
    if (!password.value) return toast('请输入密码', 'error');

    loading.value = true;
    try {
        await authApi.login({ account: account.value.trim(), password: password.value });
        localStorage.setItem(LAST_ACCOUNT_KEY, account.value.trim());
        toast('登录成功，欢迎回来', 'success');
        router.replace('/home');
    } catch (err) {
        // 加密组件未加载等本地异常也带可读 message，一并展示
        toast(err instanceof ApiError || err instanceof Error ? err.message : '登录失败', 'error');
    } finally {
        loading.value = false;
    }
}
</script>

<template>
    <AuthCard title="登录" marks>
        <template #subtitle>使用账号或邮箱继续</template>

        <form class="form" novalidate @submit.prevent="submit">
            <TextField v-model="account" placeholder="账号或邮箱" autocomplete="username" :icon="icons.user" />
            <PasswordField v-model="password" placeholder="密码" autocomplete="current-password" />

            <div class="row-between">
                <span class="muted">还没有账号？<RouterLink class="link" to="/register">立即注册</RouterLink></span>
                <RouterLink class="link" to="/forgot">忘记密码？</RouterLink>
            </div>

            <SubmitButton text="登 录" loading-text="登录中…" :loading="loading"
                          @click="submit" />
        </form>
    </AuthCard>
</template>
