/**
 * 路由表
 *
 * 使用 history 模式（URL 里没有 #）。
 * 需要静态服务器把未匹配的路径回退到 index.html：
 *   Vite dev / preview 已内置该行为；其它服务器需自行配置 fallback。
 */
import { createRouter, createWebHistory } from 'vue-router';
import { tokens } from '../api/http';
import { loadCurrentUser, session } from '../stores/session';
import { toast } from '../ui/toast';

const routes = [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { guestOnly: true } },
    { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { guestOnly: true } },
    { path: '/verify', name: 'verify', component: () => import('../views/VerifyView.vue') },
    { path: '/forgot', name: 'forgot', component: () => import('../views/ForgotView.vue'), meta: { guestOnly: true } },
    { path: '/reset-password', name: 'resetPassword', component: () => import('../views/ResetPasswordView.vue') },
    { path: '/reset-code', name: 'resetCode', component: () => import('../views/ResetCodeView.vue') },
    { path: '/change-password', name: 'changePassword', component: () => import('../views/ChangePasswordView.vue'), meta: { auth: true } },
    { path: '/apps', name: 'apps', component: () => import('../views/AppsView.vue'), meta: { auth: true } },
    { path: '/home', name: 'home', component: () => import('../views/HomeView.vue'), meta: { auth: true } },
    { path: '/platform', name: 'platform', component: () => import('../views/PlatformView.vue'), meta: { auth: true } },
    { path: '/courses', name: 'courses', component: () => import('../views/CoursesView.vue'), meta: { auth: true } },
    { path: '/homework', name: 'homework', component: () => import('../views/HomeworkView.vue'), meta: { auth: true } },
    { path: '/profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { auth: true } },
    { path: '/devices', name: 'devices', component: () => import('../views/DevicesView.vue'), meta: { auth: true } },
    // 管理后台：入口只对管理员显示（apps 页的磁贴），路由守卫再拦一次，后端每个接口还会校验
    {
        path: '/admin',
        name: 'admin',
        component: () => import('../views/AdminView.vue'),
        meta: { auth: true, admin: true },
    },
    { path: '/reminder', name: 'reminder', component: () => import('../views/ReminderView.vue'), meta: { auth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/login' },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
});

router.beforeEach(async (to) => {
    if (to.meta.auth && !tokens.isLoggedIn) {
        return { path: '/login' };
    }
    if (to.meta.guestOnly && tokens.isLoggedIn) {
        return { path: '/home' };
    }
    /*
     * 管理后台：光「已登录」不够，必须确认当前账号是管理员。
     * 这里用后端 /auth/me 返回的 isAdmin 判断（不是本地存的开关，改不了），
     * 拿不到用户信息时按「无权限」处理并提示重试，不放行。
     * 这道守卫只解决体验（进不去页面），真正的权限判断在后端每个 /api/admin 接口里。
     */
    if (to.meta.admin) {
        /*
         * 每次都重新问一次后端（force=true，不用本地缓存）：
         * 管理页的身份必须按「此刻」的状态判定 —— 刚被提权或刚被降权的人
         * 都不该因为前端缓存而进错门。只是一次 /auth/me，进管理页时才发生。
         */
        const user = await loadCurrentUser(true);
        if (!user?.isAdmin) {
            toast(user ? '仅管理员可访问该页面' : '无法确认管理员身份，请刷新后重试', 'error');
            return { path: '/home' };
        }
    }
    return true;
});

export default router;
