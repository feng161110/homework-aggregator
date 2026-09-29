/** 登录态与当前用户（顶栏、个人中心共享） */
import { reactive } from 'vue';
import { authApi } from '../api/auth';
import { ApiError, tokens } from '../api/http';
import { resetHomeworkSummary } from './homeworkSummary';
import { clearCaches } from '../ui/resourceCache';

export const session = reactive({
    user: null,
    loaded: false,
});

/** 同一个 /auth/me 只发一次：首屏路由守卫与页面 onMounted 会同时触发 */
let inflight = null;

/** 拉取当前用户；force=true 时忽略缓存 */
export async function loadCurrentUser(force = false) {
    if (!tokens.isLoggedIn) {
        session.user = null;
        session.loaded = true;
        return null;
    }
    if (session.user && !force) {
        return session.user;
    }
    if (inflight) {
        return inflight;
    }
    inflight = (async () => {
        try {
            session.user = await authApi.me();
        } catch (err) {
            // 只有明确的 401 才算登录态失效；后端没起、网络抖动不该把用户踢出登录
            if (err instanceof ApiError && err.status === 401) {
                clearSession();
            }
        } finally {
            session.loaded = true;
            inflight = null;
        }
        return session.user;
    })();
    return inflight;
}

export function clearSession() {
    session.user = null;
    session.loaded = true;
    inflight = null;
    tokens.clear();
    // 退出登录 / 登录态失效：清掉本地页面缓存，避免换号后看到上一个账号的数据
    clearCaches();
    // 首页消息栏是模块级单例，不跟着 token 走，必须一起清，
    // 否则换账号后它还会显示上一个账号的待提交作业
    resetHomeworkSummary();
}
