/**
 * 单个平台的凭证状态机
 *
 * 把「读取凭证 → 校验 → 失效则自动重登 → 就绪」这条链路收在一处，
 * 看板卡片与平台详情页共用，避免两套实现各自演化。
 *
 * 自动重登有两条路径：
 *   学习通   —— 后端把库里保存的账号密码下发一次，交给浏览器助手在浏览器内登录；
 *              库里没存过密码就做不了自动重登，只能由用户点「重新获取 Cookie」
 *   其余平台 —— 后端直连各自的登录服务
 * 两条路径都不在浏览器里留下任何凭证。
 */
import { computed, ref } from 'vue';
import { platformApi, platformStore } from '../api/platform';
import { ApiError } from '../api/http';
import { toast } from '../ui/toast';
import { detectHelper } from '../ui/useHelper';

/** 进度轨的四个阶段 */
export const CRED_STEPS = ['读取凭证', '校验有效期', '自动重新登录', '凭证就绪'];

function errText(err, fallback) {
    return (err instanceof ApiError ? err.message : err?.message) || fallback;
}

export function usePlatformCredential(platform) {
    const key = platform.key;
    const isHelperPlatform = key === 'xuexitong';

    const detail = ref(null);
    /** 最近一次校验的原始结果（含平台返回的姓名/头像/userinfo），供界面直接展示 */
    const lastCheck = ref(null);
    /** loading | checking | relogining | ready | expired | error | offline */
    const phase = ref('loading');
    /** 当前所处阶段（0-3），等于 4 表示全部完成 */
    const stepIndex = ref(0);
    const failedStep = ref(-1);
    const message = ref('正在读取本地凭证…');
    const errorText = ref('');
    const busy = ref(false);
    const offline = ref(false);

    const bound = computed(() => Boolean(detail.value?.bound));
    const cookieCount = computed(() => (bound.value ? Object.keys(detail.value.cookies || {}).length : 0));
    const hasPassword = computed(() => Boolean(detail.value?.hasPassword));
    const account = computed(() => detail.value?.account || '');
    const valid = computed(() => detail.value?.cookieStatus === 'VALID');

    /** 每一步的状态，供进度轨渲染 */
    const stepStates = computed(() => CRED_STEPS.map((_, i) => {
        if (failedStep.value === i) return 'failed';
        if (i < stepIndex.value) return 'done';
        if (i === stepIndex.value) return phase.value === 'ready' ? 'done' : 'active';
        return 'todo';
    }));

    /** 进度轨只在已绑定时出现：没绑定就没有可推进的环节，别让第一步一直闪 */
    const showRail = computed(() => bound.value);

    /** 数据库里还没有这个平台的账号：需要先去配置页绑定一次（加载中不算） */
    const needConfig = computed(() => !bound.value && phase.value !== 'loading');

    function go(nextPhase, text, nextStep) {
        phase.value = nextPhase;
        message.value = text;
        if (typeof nextStep === 'number') stepIndex.value = nextStep;
    }

    function fail(nextPhase, text, step) {
        failedStep.value = step;
        stepIndex.value = step;
        phase.value = nextPhase;
        message.value = text;
        errorText.value = text;
    }

    async function reload() {
        try {
            detail.value = await platformStore.detail(key);
            offline.value = false;
            return detail.value;
        } catch {
            detail.value = null;
            offline.value = true;
            return null;
        }
    }

    /** 仅为学习通：从浏览器助手读取已登录的 Cookie 并入库（必须由用户点击触发） */
    async function resyncFromHelper({ silent = false } = {}) {
        if (busy.value) {
            return 0; // 同步较慢时连点，避免并发重复写库
        }
        busy.value = true;
        try {
            if (!detectHelper()) {
                throw new Error('未检测到浏览器助手，请先安装用户脚本');
            }
            const cookies = await window.__CX_HELPER__.listCookies();
            const count = Object.keys(cookies || {}).length;
            if (!count) throw new Error('浏览器里没有学习通登录态');
            detail.value = await platformStore.saveCookies(key, {
                cookies,
                source: 'plugin',
                account: account.value || undefined,
            });
            if (!silent) toast(`已从浏览器同步 ${count} 个 Cookie`, 'success');
            return count;
        } finally {
            busy.value = false;
        }
    }

    /**
     * 自动重登：库里没有账号密码时不做任何尝试，直接返回原因交给人工。
     * @returns {Promise<{ok: boolean, message: string}>}
     */
    async function autoRelogin() {
        failedStep.value = -1;
        go('relogining', '正在用保存的账号密码自动重新登录…', 2);

        if (isHelperPlatform) {
            if (!detectHelper()) {
                const text = '未检测到浏览器助手，请安装用户脚本后手动获取 Cookie。';
                fail('expired', text, 2);
                return { ok: false, message: text };
            }
            const secret = await platformStore.secret(key);
            const loginAccount = (secret?.account || '').trim();
            const loginPassword = secret?.password || '';
            if (!secret?.bound || !loginAccount || !loginPassword) {
                const text = '数据库中未保存学习通的账号或密码，填写一次后即可自动重新登录。';
                fail('expired', text, 2);
                return { ok: false, message: text };
            }
            try {
                const { cookies } = await window.__CX_HELPER__.login({ username: loginAccount, password: loginPassword });
                const count = Object.keys(cookies || {}).length;
                if (!count) throw new Error('未获取到 Cookie');
                detail.value = await platformStore.bind(key, {
                    account: loginAccount,
                    password: loginPassword,
                    cookies,
                    source: 'plugin',
                });
                return { ok: true, message: `已用保存的账号密码自动重新登录（${count} 项）` };
            } finally {
                secret.password = '';   // 用完即弃，尽快断开明文引用
            }
        }

        const refreshed = await platformStore.refresh(key);
        if (!refreshed.ok) {
            // 必须落到 expired：否则 phase 会一直停在 relogining，
            // 进度轨永远转圈、真正的失效原因被「正在自动重新登录」挡住
            const text = refreshed.message || '自动重新登录失败，请手动重新登录一次';
            fail('expired', text, 2);
            return { ok: false, message: text };
        }
        return { ok: true, message: refreshed.message || '已自动重新登录' };
    }

    /**
     * 默认取数姿势：先校验库里保存的 Cookie，失效则自动重登。
     * silent=true 用于进页面时的静默尝试，不弹提示。
     */
    async function ensureValid({ silent = false } = {}) {
        busy.value = true;
        failedStep.value = -1;
        errorText.value = '';
        try {
            const loaded = await reload();
            if (!loaded) {
                fail('offline', '无法连接后端服务，请确认后端（8080）已启动。', 0);
                if (!silent) toast('无法连接后端服务', 'error');
                return false;
            }
            if (!loaded.bound) {
                lastCheck.value = null;
                go('expired', '尚未绑定，请先获取一次凭证。', 0);
                return false;
            }

            go('checking', '正在校验 Cookie 有效期…', 1);
            const result = await platformStore.check(key);
            lastCheck.value = result;
            if (result.valid) {
                failedStep.value = -1;
                go('ready', 'Cookie 有效', 4);
                if (!silent) toast(`凭证有效：${result.name || account.value || '已登录'}`, 'success');
                return true;
            }

            const outcome = await autoRelogin();
            if (outcome.ok) {
                failedStep.value = -1;
                go('ready', outcome.message, 4);
                toast(outcome.message, 'success');
                await reload();
                // 重登后再校验一次：既确认新 Cookie 可用，也顺带取回姓名/头像
                try {
                    lastCheck.value = await platformStore.check(key);
                } catch {
                    // 身份信息拿不到不影响重登结果
                }
                return true;
            }
            // silent 是进页面时的静默尝试：学习通没有账号密码，重登必然失败，不该据此弹错误
            if (!silent) toast(outcome.message, 'error');
            await reload();
            return false;
        } catch (err) {
            const text = errText(err, '校验失败');
            fail('error', text, stepIndex.value);
            if (!silent) toast(text, 'error');
            return false;
        } finally {
            busy.value = false;
            await reload();
        }
    }

    /**
     * 人工提交账号密码。
     * 学习通走浏览器助手登录；其余平台走后端的平台登录服务。
     */
    async function manualLogin(loginAccount, loginPassword) {
        busy.value = true;
        failedStep.value = -1;
        go('relogining', '正在登录…', 2);
        try {
            if (isHelperPlatform) {
                if (!detectHelper()) throw new Error('未检测到浏览器助手，请先安装用户脚本');
                const { cookies } = await window.__CX_HELPER__.login({
                    username: loginAccount,
                    password: loginPassword,
                });
                const count = Object.keys(cookies || {}).length;
                if (!count) throw new Error('未获取到 Cookie');
                detail.value = await platformStore.bind(key, {
                    account: loginAccount,
                    password: loginPassword,
                    cookies,
                    source: 'plugin',
                });
                go('ready', `获取成功，已保存 ${count} 个 Cookie`, 4);
                return count;
            }

            const result = await platformApi.login(key, loginAccount, loginPassword);
            if (!result.ok) throw new Error(result.message || '登录失败');
            detail.value = await platformStore.bind(key, {
                account: result.account || loginAccount,
                password: loginPassword,
                cookies: result.cookies || {},
                extra: result.extra || {},
                source: 'password',
            });
            go('ready', '登录成功，凭证已加密保存到服务器', 4);
            return Object.keys(result.cookies || {}).length;
        } catch (err) {
            const text = errText(err, '登录失败');
            fail('error', text, 2);
            throw err;
        } finally {
            busy.value = false;
            await reload();
        }
    }

    async function unbind() {
        try {
            await platformStore.unbind(key);
            failedStep.value = -1;
            lastCheck.value = null;
            go('expired', '尚未绑定，请先获取一次凭证。', 0);
            toast('已解绑并清除服务器凭证', 'success');
        } finally {
            await reload();
        }
    }

    return {
        key,
        isHelperPlatform,
        detail,
        lastCheck,
        needConfig,
        phase,
        stepIndex,
        stepStates,
        showRail,
        message,
        errorText,
        busy,
        offline,
        bound,
        valid,
        cookieCount,
        hasPassword,
        account,
        reload,
        ensureValid,
        resyncFromHelper,
        manualLogin,
        unbind,
    };
}
