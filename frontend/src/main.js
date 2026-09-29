import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import { setAuthExpiredHandler } from './api/http';
import { clearSession } from './stores/session';
import { toast } from './ui/toast';
import './styles/style.css';

// 兼容旧邮件里发出的 hash 式重置链接：/#/reset-password?token=xxx
// 转成 history 路由的 /reset-password?token=xxx 再启动路由
if (location.hash.startsWith('#/reset-password')) {
    const query = location.hash.includes('?') ? location.hash.slice(location.hash.indexOf('?') + 1) : '';
    const token = new URLSearchParams(query).get('token');
    history.replaceState(null, '', `/reset-password${token ? `?token=${encodeURIComponent(token)}` : ''}`);
}

// 登录态失效（会话被下线 / 过期且刷新失败）：清空本地状态并跳回登录页
setAuthExpiredHandler(() => {
    clearSession();
    if (router.currentRoute.value.name !== 'login') {
        toast('登录状态已失效，请重新登录', 'info');
        router.replace({ path: '/login' });
    }
});

createApp(App).use(router).mount('#app');
