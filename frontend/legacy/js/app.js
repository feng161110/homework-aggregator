/**
 * app.js —— 单页应用：路由 + 视图
 * 视图：登录 / 注册 / 邮箱验证码 / 找回密码 / 重置密码 / 用户中心
 */
import { api, tokens, ApiError, resolveAssetUrl, platformApi, platformStore, PLATFORM_SERVICES } from './api.js';
import {
    icons, toast, $, $$, esc, maskEmail, rules, validate,
    passwordStrength, setLoading, inputControl, attachPasswordToggle, hardenCredentials,
} from './ui.js';

const app = document.getElementById('app');
const PENDING_EMAIL_KEY = 'hpa.pendingEmail';
const PENDING_RESET_EMAIL_KEY = 'hpa.pendingResetEmail';

/* 当前登录用户缓存（顶栏头像与用户中心共享） */
let currentUser = null;
const AVATAR_MAX_BYTES = 2 * 1024 * 1024;

/* 平台菜单（侧边抽屉） */
const PLATFORMS = [
    { key: 'home', name: '首页', desc: '返回用户中心', icon: icons.home },
    { key: 'yuketang', name: '雨课堂', desc: '长江雨课堂', icon: icons.logoYuketang, url: 'https://www.yuketang.cn' },
    { key: 'zhihuishu', name: '智慧树', desc: '智慧树网', icon: icons.logoZhihuishu, url: 'https://www.zhihuishu.com' },
    { key: 'xuexitong', name: '学习通', desc: '超星学习通', icon: icons.logoXuexitong, url: 'https://i.chaoxing.com' },
];

/* 平台凭证（账号 / 加密密码 / Cookie）统一保存在后端数据库，
   前端不再往 localStorage 写任何平台凭证，避免两份数据不一致。 */

/* 每次渲染前需要清理的定时器 / 监听 */
let cleanups = [];
const addCleanup = (fn) => cleanups.push(fn);
const runCleanups = () => { cleanups.forEach((fn) => { try { fn(); } catch { /* noop */ } }); cleanups = []; };

/* -------------------- 路由 -------------------- */
const routes = {
    '/login': renderLogin,
    '/register': renderRegister,
    '/verify': renderVerify,
    '/forgot': renderForgot,
    '/reset-password': renderReset,
    '/reset-code': renderResetCode,
    '/change-password': renderChangePassword,
    '/platform': renderPlatform,
    '/dashboard': renderDashboard,
    '/courses': renderCourses,
    '/homework': renderHomework,
    '/profile': renderProfile,
};

/* 顶部主导航 */
const NAV_ITEMS = [
    { path: '/dashboard', label: '平台概览' },
    { path: '/courses', label: '课程管理' },
    { path: '/homework', label: '作业查询' },
    { path: '/profile', label: '个人中心' },
];

function parseHash() {
    const raw = (location.hash || '').replace(/^#/, '') || '/login';
    const [path, query = ''] = raw.split('?');
    return { path: path || '/login', params: new URLSearchParams(query) };
}

export function navigate(path, params) {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    const target = `#${path}${qs}`;
    if (location.hash === target) render();
    else location.hash = target;
}

function mount(html) {
    app.classList.remove('page-mode');
    app.innerHTML = html;
    return app;
}

/** 页面级视图（平台概览 / 个人中心等）：顶部对齐、内容区整宽 */
function mountPage(html) {
    app.classList.add('page-mode');
    app.innerHTML = html;
    return app;
}

/* 通用密码输入框（带显示/隐藏） */
function passwordField(id, placeholder, autocomplete, withStrength = false) {
    return `
        <div class="control has-suffix">
            <span class="icon">${icons.lock}</span>
            <input id="${id}" name="${id}" type="password" placeholder="${esc(placeholder)}"
                   autocomplete="${autocomplete}" />
            <span class="suffix">
                <button type="button" class="toggle-eye" id="${id}-eye" aria-label="显示密码">${icons.eye}</button>
            </span>
        </div>
        ${withStrength ? `<div class="strength" id="${id}-strength" data-level="0"><i></i><i></i><i></i><i></i></div>` : ''}`;
}

/* -------------------- 当前用户与头像（顶栏 / 用户中心共享） -------------------- */
async function loadCurrentUser(force = false) {
    if (!tokens.isLoggedIn) { currentUser = null; return null; }
    if (currentUser && !force) return currentUser;
    try {
        currentUser = await api.me();
    } catch {
        currentUser = null;
    }
    return currentUser;
}

function avatarMediaHtml(user) {
    if (user?.avatarUrl) {
        return `<img src="${esc(resolveAssetUrl(user.avatarUrl))}" alt="头像" />`;
    }
    return esc((user?.username || '?').charAt(0).toUpperCase());
}

/** 头像遮罩内容：相机图标 + 文字提示 */
function overlayHtml() {
    return `<span class="ov-icon">${icons.camera}</span><span class="ov-text">更换头像</span>`;
}

/** 上传成功后刷新页面中的头像容器 */
function refreshAvatars() {
    if (!currentUser) return;
    const media = $('#avatarMedia');
    if (media) media.innerHTML = avatarMediaHtml(currentUser);
}

/**
 * 统一头像上传流程
 * @param {File} file
 * @param {{box?: HTMLElement, media?: HTMLElement}} targets box 为包裹元素（加载态），media 为展示容器
 */
async function handleAvatarFile(file, { box, media } = {}) {
    if (!file) return;
    if (!/^image\//.test(file.type)) { toast('请选择图片文件', 'error'); return; }
    if (file.size > AVATAR_MAX_BYTES) { toast('图片不能超过 2MB', 'error'); return; }

    const overlay = box ? box.querySelector('.avatar-overlay') : null;
    if (box) box.classList.add('uploading');
    if (overlay) overlay.innerHTML = '<span class="spinner"></span>';

    try {
        currentUser = await api.uploadAvatar(file);
        if (media) media.innerHTML = avatarMediaHtml(currentUser);
        refreshAvatars();
        toast('头像已更新', 'success');
    } catch (err) {
        toast(err instanceof ApiError ? err.message : '头像上传失败', 'error');
    } finally {
        if (box) box.classList.remove('uploading');
        if (overlay) overlay.innerHTML = overlayHtml();
    }
}

/** 渲染顶部主导航（登录后显示） */
function renderTopNav() {
    const nav = $('#topNav');
    if (!nav) return;

    if (!tokens.isLoggedIn) {
        nav.hidden = true;
        nav.innerHTML = '';
        currentUser = null;
        return;
    }

    nav.hidden = false;
    const { path } = parseHash();
    nav.innerHTML = NAV_ITEMS.map((item) => (
        `<a href="#${item.path}" class="${path === item.path ? 'active' : ''}">${esc(item.label)}</a>`
    )).join('');
}

/* 6 位验证码输入框 HTML */
function codeCellsHtml(count = 6) {
    return Array.from({ length: count }, () =>
        '<input class="code-cell" inputmode="numeric" maxlength="1" autocomplete="one-time-code" />').join('');
}

/* 绑定 6 位验证码输入框（自动跳格 / 退格 / 粘贴 / 填满回调） */
function bindCodeInputs(host, { onComplete } = {}) {
    const cells = $$('.code-cell', host);
    const getCode = () => cells.map((c) => c.value).join('');
    const focusCell = (i) => cells[Math.max(0, Math.min(cells.length - 1, i))]?.focus();

    cells.forEach((cell, i) => {
        cell.addEventListener('input', () => {
            cell.value = cell.value.replace(/\D/g, '').slice(-1);
            cell.classList.toggle('filled', Boolean(cell.value));
            host.classList.remove('invalid');
            if (cell.value && i < cells.length - 1) focusCell(i + 1);
            if (onComplete && getCode().length === cells.length) onComplete();
        });
        cell.addEventListener('keydown', (e) => {
            if (e.key === 'Backspace' && !cell.value && i > 0) focusCell(i - 1);
            if (e.key === 'ArrowLeft') focusCell(i - 1);
            if (e.key === 'ArrowRight') focusCell(i + 1);
        });
        cell.addEventListener('paste', (e) => {
            e.preventDefault();
            const digits = (e.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, cells.length);
            if (!digits) return;
            digits.split('').forEach((d, idx) => {
                if (cells[idx]) { cells[idx].value = d; cells[idx].classList.add('filled'); }
            });
            focusCell(digits.length - 1);
            if (onComplete && getCode().length === cells.length) onComplete();
        });
    });

    focusCell(0);

    return {
        getCode,
        markInvalid() {
            cells.forEach((c) => c.classList.add('invalid'));
            setTimeout(() => cells.forEach((c) => c.classList.remove('invalid')), 450);
        },
        clear() {
            cells.forEach((c) => { c.value = ''; c.classList.remove('filled'); });
            focusCell(0);
        },
        focus: () => focusCell(0),
    };
}

/* -------------------- 登录 -------------------- */
function renderLogin() {
    if (tokens.isLoggedIn) return navigate('/dashboard');

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">欢迎回来</span>
                <h1>登录账号</h1>
                <p>使用邮箱或用户名登录作业聚合平台</p>
            </div>
            <form class="form" id="loginForm" novalidate>
                ${inputControl({ id: 'account', placeholder: '邮箱或用户名', autocomplete: 'username', icon: icons.user })}
                <div class="hint" id="hint-account"></div>

                ${passwordField('password', '密码', 'current-password')}
                <div class="hint" id="hint-password"></div>

                <div class="row-between">
                    <span class="muted">还没有账号？<a class="link" href="#/register">立即注册</a></span>
                    <a class="link" href="#/forgot">忘记密码？</a>
                </div>
                <button class="btn" type="submit" id="submitBtn">登 录</button>
            </form>
        </section>
    `);

    attachPasswordToggle(app, 'password', 'password-eye');
    prefillAccount();

    $('#loginForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const account = $('#account').value.trim();
        const password = $('#password').value;
        if (!account) return toast('请输入邮箱或用户名', 'error');
        if (!password) return toast('请输入密码', 'error');

        const btn = $('#submitBtn');
        setLoading(btn, true, '登录中…');
        try {
            await api.login({ account, password });
            currentUser = null;
            localStorage.setItem('hpa.lastAccount', account);
            toast('登录成功，欢迎回来', 'success');
            navigate('/dashboard');
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '登录失败', 'error');
            setLoading(btn, false);
        }
    });
}

function prefillAccount() {
    const last = localStorage.getItem('hpa.lastAccount');
    if (last) { const el = $('#account'); if (el && !el.value) el.value = last; }
}

/* -------------------- 注册 -------------------- */
function renderRegister() {
    if (tokens.isLoggedIn) return navigate('/dashboard');

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">创建账号</span>
                <h1>注册</h1>
                <p>注册后需完成邮箱验证码校验方可登录</p>
            </div>
            <form class="form" id="registerForm" novalidate>
                ${inputControl({ id: 'username', placeholder: '用户名（4-32 位字母/数字/下划线）', autocomplete: 'username', icon: icons.user })}
                <div class="hint" id="hint-username"></div>

                ${inputControl({ id: 'email', type: 'email', placeholder: '邮箱', autocomplete: 'email', icon: icons.mail })}
                <div class="hint" id="hint-email"></div>

                ${passwordField('password', '密码（至少 8 位，含字母和数字）', 'new-password', true)}
                <div class="hint" id="hint-password"></div>

                ${passwordField('confirm', '确认密码', 'new-password')}
                <div class="hint" id="hint-confirm"></div>

                <button class="btn" type="submit" id="submitBtn">注 册</button>
                <div class="center muted" style="font-size:13.5px">
                    已有账号？<a class="link" href="#/login">返回登录</a>
                </div>
            </form>
        </section>
    `);

    attachPasswordToggle(app, 'password', 'password-eye');
    attachPasswordToggle(app, 'confirm', 'confirm-eye');

    const pwdInput = $('#password');
    pwdInput.addEventListener('input', () => {
        const level = passwordStrength(pwdInput.value);
        $('#password-strength').dataset.level = String(level);
    });

    $('#registerForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = $('#username').value.trim();
        const email = $('#email').value.trim();
        const password = $('#password').value;
        const confirm = $('#confirm').value;

        const okU = validate($('#username'), rules.username, $('#hint-username'));
        const okE = validate($('#email'), rules.email, $('#hint-email'));
        const okP = validate($('#password'), rules.password, $('#hint-password'));
        let okC = true;
        if (confirm !== password) {
            $('#hint-confirm').textContent = '两次输入的密码不一致';
            $('#hint-confirm').classList.add('error');
            $('#confirm').classList.add('invalid');
            okC = false;
        } else {
            $('#hint-confirm').textContent = '';
            $('#hint-confirm').classList.remove('error');
            $('#confirm').classList.remove('invalid');
        }
        if (!(okU && okE && okP && okC)) return;

        const btn = $('#submitBtn');
        setLoading(btn, true, '提交中…');
        try {
            const message = await api.register({ username, email, password });
            localStorage.setItem(PENDING_EMAIL_KEY, email);
            toast(message || '验证码已发送，请查收邮箱', 'success');
            navigate('/verify', { email });
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '注册失败', 'error');
            setLoading(btn, false);
        }
    });
}

/* -------------------- 邮箱验证码 -------------------- */
function renderVerify(params) {
    const email = params?.get('email') || localStorage.getItem(PENDING_EMAIL_KEY) || '';
    if (!email) { toast('请先填写邮箱完成注册', 'info'); return navigate('/register'); }

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">邮箱验证</span>
                <h1>输入验证码</h1>
                <p>验证码已发送至 <strong>${esc(maskEmail(email))}</strong><br />请在 5 分钟内完成验证</p>
            </div>

            <div class="code-block">
                <div class="code-inputs" id="codeInputs">
                    ${codeCellsHtml()}
                </div>
                <div class="resend-line">
                    没收到？<a class="link" id="resendLink" role="button" tabindex="0">重新发送验证码</a>
                </div>
            </div>

            <form class="form" id="verifyForm" novalidate style="margin-top:18px">
                <button class="btn" type="submit" id="submitBtn">验 证</button>
                <div class="center muted" style="font-size:13.5px">
                    <a class="link" id="changeEmail">邮箱填错了？返回修改</a>
                </div>
            </form>
        </section>
    `);

    const codeInputs = bindCodeInputs($('#codeInputs'), { onComplete: () => submitCode() });
    startResendCountdown($('#resendLink'), 60, email);

    $('#resendLink').addEventListener('click', async () => {
        const link = $('#resendLink');
        if (link.getAttribute('aria-disabled') === 'true') return;
        try {
            const message = await api.resendVerification({ email });
            toast(message || '验证码已重新发送', 'success');
            startResendCountdown(link, 60, email);
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
        }
    });

    $('#changeEmail').addEventListener('click', () => navigate('/register'));

    async function submitCode() {
        const code = codeInputs.getCode();
        if (code.length !== 6 || !/^\d{6}$/.test(code)) {
            codeInputs.markInvalid();
            return;
        }
        const btn = $('#submitBtn');
        if (btn.disabled) return;
        setLoading(btn, true, '验证中…');
        try {
            await api.verifyEmail({ email, code });
            localStorage.removeItem(PENDING_EMAIL_KEY);
            toast('邮箱验证成功，请登录', 'success');
            navigate('/login');
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '验证失败', 'error');
            codeInputs.markInvalid();
            codeInputs.focus();
            setLoading(btn, false);
        }
    }

    $('#verifyForm').addEventListener('submit', (e) => { e.preventDefault(); submitCode(); });
}

/** 60 秒倒计时（与后端 email-send-interval-seconds 一致） */
function startResendCountdown(link, seconds, email) {
    let left = seconds;
    const render = () => {
        if (left > 0) {
            link.setAttribute('aria-disabled', 'true');
            link.textContent = `${left}s 后可重新发送`;
        } else {
            link.removeAttribute('aria-disabled');
            link.textContent = '重新发送验证码';
        }
    };
    render();
    const timer = setInterval(() => {
        left -= 1;
        render();
        if (left <= 0) clearInterval(timer);
    }, 1000);
    const cancel = () => clearInterval(timer);
    addCleanup(cancel);
    return cancel;
}

/* -------------------- 找回密码（可选：邮件链接 / 邮箱验证码） -------------------- */
function renderForgot(params) {
    const initialMode = params?.get('mode') === 'code' ? 'code' : 'link';
    const descMap = {
        link: '输入注册邮箱，我们将发送重置链接（30 分钟内有效）',
        code: '输入注册邮箱，我们将发送 6 位验证码，可在当前页面直接设置新密码',
    };
    const btnMap = { link: '发送重置链接', code: '发送验证码' };

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">密码找回</span>
                <h1>忘记密码</h1>
                <p id="forgotDesc"></p>
            </div>

            <div class="method-switch" role="radiogroup" aria-label="选择找回方式">
                <button type="button" class="method" data-mode="link" role="radio">
                    <span class="m-title">${icons.mail} 邮件链接</span>
                    <span class="m-desc">收到邮件后点击链接设置新密码</span>
                </button>
                <button type="button" class="method" data-mode="code" role="radio">
                    <span class="m-title">${icons.shield} 邮箱验证码</span>
                    <span class="m-desc">输入验证码后直接在此修改密码</span>
                </button>
            </div>

            <form class="form" id="forgotForm" novalidate>
                ${inputControl({ id: 'email', type: 'email', placeholder: '注册邮箱', autocomplete: 'email', icon: icons.mail })}
                <div class="hint" id="hint-email"></div>
                <button class="btn" type="submit" id="submitBtn">发送</button>
                <div class="center" style="font-size:13.5px">
                    <a class="link" href="#/login">${icons.arrowLeft} 返回登录</a>
                </div>
            </form>
        </section>
    `);

    let mode = initialMode;
    const applyMode = (next) => {
        mode = next;
        $$('.method', app).forEach((b) => {
            const active = b.dataset.mode === next;
            b.classList.toggle('active', active);
            b.setAttribute('aria-checked', String(active));
        });
        $('#forgotDesc').textContent = descMap[next];
        const btn = $('#submitBtn');
        btn.textContent = btnMap[next];
        delete btn.dataset._label;
    };
    applyMode(mode);

    $$('.method', app).forEach((b) => b.addEventListener('click', () => applyMode(b.dataset.mode)));

    $('#forgotForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!validate($('#email'), rules.email, $('#hint-email'))) return;

        const email = $('#email').value.trim();
        const btn = $('#submitBtn');
        setLoading(btn, true, '发送中…');
        try {
            if (mode === 'code') {
                const message = await api.forgotPasswordByCode({ email });
                localStorage.setItem(PENDING_RESET_EMAIL_KEY, email);
                toast(message || '验证码已发送，请查收邮箱', 'success');
                navigate('/reset-code', { email });
            } else {
                const message = await api.forgotPassword({ email });
                showResult({
                    ok: true,
                    title: '邮件已发送',
                    desc: message || '如果该邮箱已注册，重置邮件已发送，请查收（含垃圾箱）。',
                    actionText: '返回登录',
                    onAction: () => navigate('/login'),
                });
            }
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
            setLoading(btn, false);
        }
    });
}

/* -------------------- 重置密码 -------------------- */
function renderReset(params) {
    const token = params?.get('token') || '';

    if (!token) {
        showResult({
            ok: false,
            title: '链接无效',
            desc: '重置链接缺少必要参数，请重新申请找回密码。',
            actionText: '重新申请',
            onAction: () => navigate('/forgot'),
        });
        return;
    }

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">安全设置</span>
                <h1>设置新密码</h1>
                <p>请设置一个至少 8 位、包含字母和数字的新密码</p>
            </div>
            <form class="form" id="resetForm" novalidate>
                ${passwordField('newPassword', '新密码', 'new-password', true)}
                <div class="hint" id="hint-newPassword"></div>

                ${passwordField('confirm', '确认新密码', 'new-password')}
                <div class="hint" id="hint-confirm"></div>

                <button class="btn" type="submit" id="submitBtn">确认重置</button>
                <div class="center" style="font-size:13.5px"><a class="link" href="#/login">返回登录</a></div>
            </form>
        </section>
    `);

    attachPasswordToggle(app, 'newPassword', 'newPassword-eye');
    attachPasswordToggle(app, 'confirm', 'confirm-eye');

    const pwdInput = $('#newPassword');
    pwdInput.addEventListener('input', () => {
        $('#newPassword-strength').dataset.level = String(passwordStrength(pwdInput.value));
    });

    $('#resetForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const newPassword = $('#newPassword').value;
        const confirm = $('#confirm').value;

        const okP = validate($('#newPassword'), rules.password, $('#hint-newPassword'));
        let okC = true;
        if (confirm !== newPassword) {
            $('#hint-confirm').textContent = '两次输入的密码不一致';
            $('#hint-confirm').classList.add('error');
            $('#confirm').classList.add('invalid');
            okC = false;
        } else {
            $('#hint-confirm').textContent = '';
            $('#hint-confirm').classList.remove('error');
            $('#confirm').classList.remove('invalid');
        }
        if (!(okP && okC)) return;

        const btn = $('#submitBtn');
        setLoading(btn, true, '提交中…');
        try {
            const message = await api.resetPassword({ token, newPassword });
            toast(message || '密码重置成功，请使用新密码登录', 'success');
            navigate('/login');
        } catch (err) {
            const code = err instanceof ApiError ? err.code : '';
            if (code === 'TOKEN_INVALID' || code === 'TOKEN_EXPIRED' || code === 'TOKEN_USED') {
                // 链接失效/过期/已使用：引导重新申请，避免用户卡在表单页
                showResult({
                    ok: false,
                    title: '链接已失效',
                    desc: err.message || '重置链接无效或已过期，请重新申请。',
                    actionText: '重新申请重置',
                    onAction: () => navigate('/forgot'),
                });
                return;
            }
            toast(err instanceof ApiError ? err.message : '重置失败，请重新申请', 'error');
            setLoading(btn, false);
        }
    });
}

/* -------------------- 重置密码（验证码方式） -------------------- */
function renderResetCode(params) {
    const email = params?.get('email') || localStorage.getItem(PENDING_RESET_EMAIL_KEY) || '';
    if (!email) {
        toast('请先填写邮箱获取验证码', 'info');
        return navigate('/forgot?mode=code');
    }

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">验证码重置</span>
                <h1>设置新密码</h1>
                <p>验证码已发送至 <strong>${esc(maskEmail(email))}</strong><br />请输入 6 位验证码并设置新密码</p>
            </div>

            <div class="code-block">
                <div class="code-inputs" id="codeInputs">
                    ${codeCellsHtml()}
                </div>
                <div class="resend-line">
                    没收到？<a class="link" id="resendLink" role="button" tabindex="0">重新发送验证码</a>
                </div>
            </div>

            <form class="form" id="resetCodeForm" novalidate style="margin-top:16px">
                ${passwordField('newPassword', '新密码（至少 8 位，含字母和数字）', 'new-password', true)}
                <div class="hint" id="hint-newPassword"></div>

                ${passwordField('confirm', '确认新密码', 'new-password')}
                <div class="hint" id="hint-confirm"></div>

                <button class="btn" type="submit" id="submitBtn">确认重置</button>
                <div class="center" style="font-size:13.5px">
                    <a class="link" id="switchToLink">改用「邮件链接」方式</a>
                </div>
            </form>
        </section>
    `);

    attachPasswordToggle(app, 'newPassword', 'newPassword-eye');
    attachPasswordToggle(app, 'confirm', 'confirm-eye');

    const pwdInput = $('#newPassword');
    pwdInput.addEventListener('input', () => {
        $('#newPassword-strength').dataset.level = String(passwordStrength(pwdInput.value));
    });

    const codeInputs = bindCodeInputs($('#codeInputs'));
    startResendCountdown($('#resendLink'), 60, email);

    $('#resendLink').addEventListener('click', async () => {
        const link = $('#resendLink');
        if (link.getAttribute('aria-disabled') === 'true') return;
        try {
            const message = await api.forgotPasswordByCode({ email });
            toast(message || '验证码已重新发送', 'success');
            startResendCountdown(link, 60, email);
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
        }
    });

    $('#switchToLink').addEventListener('click', () => navigate('/forgot'));

    $('#resetCodeForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const code = codeInputs.getCode();
        const newPassword = $('#newPassword').value;
        const confirm = $('#confirm').value;

        if (code.length !== 6 || !/^\d{6}$/.test(code)) {
            codeInputs.markInvalid();
            toast('请输入 6 位验证码', 'error');
            return;
        }
        const okP = validate($('#newPassword'), rules.password, $('#hint-newPassword'));
        let okC = true;
        if (confirm !== newPassword) {
            $('#hint-confirm').textContent = '两次输入的密码不一致';
            $('#hint-confirm').classList.add('error');
            $('#confirm').classList.add('invalid');
            okC = false;
        } else {
            $('#hint-confirm').textContent = '';
            $('#hint-confirm').classList.remove('error');
            $('#confirm').classList.remove('invalid');
        }
        if (!(okP && okC)) return;

        const btn = $('#submitBtn');
        setLoading(btn, true, '提交中…');
        try {
            const message = await api.resetPasswordByCode({ email, code, newPassword });
            localStorage.removeItem(PENDING_RESET_EMAIL_KEY);
            toast(message || '密码重置成功，请使用新密码登录', 'success');
            navigate('/login');
        } catch (err) {
            const errCode = err instanceof ApiError ? err.code : '';
            if (errCode === 'CODE_EXPIRED' || errCode === 'TOO_MANY_ATTEMPTS') {
                showResult({
                    ok: false,
                    title: errCode === 'CODE_EXPIRED' ? '验证码已过期' : '尝试次数过多',
                    desc: err.message || '请重新获取验证码。',
                    actionText: '重新获取验证码',
                    onAction: () => navigate('/forgot?mode=code'),
                });
                return;
            }
            if (errCode === 'CODE_INVALID') {
                codeInputs.clear();
            }
            toast(err instanceof ApiError ? err.message : '重置失败，请重试', 'error');
            setLoading(btn, false);
        }
    });
}

/* -------------------- 个人中心 -------------------- */
async function renderProfile() {
    if (!tokens.isLoggedIn) { toast('请先登录', 'info'); return navigate('/login'); }

    mountPage(`
        <section class="card wide">
            <div class="card-head" style="text-align:left;margin-bottom:18px">
                <span class="eyebrow">个人中心</span>
            </div>
            <div id="dashBody">
                <div class="result"><div class="badge" style="background:var(--gold-soft);color:var(--gold-strong)"><span class="spinner" style="border-color:rgba(176,137,90,.3);border-top-color:var(--gold-strong);width:26px;height:26px;border-width:3px"></span></div>
                <p class="muted">正在加载账号信息…</p></div>
            </div>
        </section>
    `);

    let me;
    try {
        me = await api.me();
    } catch (err) {
        if (err instanceof ApiError && (err.status === 401 || err.code === 'UNAUTHORIZED')) {
            tokens.clear();
            toast('登录状态已失效，请重新登录', 'info');
            return navigate('/login');
        }
        $('#dashBody').innerHTML = `<div class="notice error">${icons.info}<span>${esc(err.message || '加载失败')}</span></div>`;
        return;
    }

    const statusMap = {
        ACTIVE: { label: '正常', cls: 'ok' },
        PENDING: { label: '待验证', cls: 'warn' },
        LOCKED: { label: '已锁定', cls: 'bad' },
    };
    const st = statusMap[me.status] || { label: me.status, cls: 'warn' };
    currentUser = me;

    $('#dashBody').innerHTML = `
        <div class="user-hero">
            <div class="avatar" id="avatarBox" role="button" tabindex="0" title="点击更换头像" aria-label="更换头像">
                <span class="avatar-media" id="avatarMedia">${avatarMediaHtml(me)}</span>
                <span class="avatar-overlay" id="avatarOverlay">${overlayHtml(false)}</span>
            </div>
            <input type="file" id="avatarInput" accept="image/png,image/jpeg,image/gif,image/webp" hidden />
            <div class="who">
                <strong>${esc(me.username)}</strong>
                <span>${esc(maskEmail(me.email))}</span>
                <button type="button" class="avatar-tip" id="avatarTip" title="支持 jpg / png / gif / webp，不超过 2MB">${icons.cameraSm} 点击头像可更换</button>
            </div>
        </div>

        <div class="info-list">
            <div class="info-item"><span class="k">用户 ID</span><span class="v">${esc(me.id)}</span></div>
            <div class="info-item"><span class="k">用户名</span><span class="v">${esc(me.username)}</span></div>
            <div class="info-item"><span class="k">邮箱</span><span class="v">${esc(me.email)}</span></div>
            <div class="info-item"><span class="k">邮箱验证</span><span class="v">${me.emailVerified ? '<span class="pill ok">已验证</span>' : '<span class="pill warn">未验证</span>'}</span></div>
            <div class="info-item"><span class="k">账号状态</span><span class="v"><span class="pill ${st.cls}">${esc(st.label)}</span></span></div>
            <div class="info-item"><span class="k">注册时间</span><span class="v">${esc(formatTime(me.createdAt))}</span></div>
        </div>

        <div class="sec-title">安全设置</div>
        <div class="info-list">
            <button type="button" class="info-item clickable" id="changePwdBtn">
                <span class="k">${icons.lock} 登录密码</span>
                <span class="v">修改 ›</span>
            </button>
        </div>

        <div class="stack-sm" style="margin-top:22px">
            <button class="btn ghost" id="refreshBtn">刷新信息</button>
            <button class="btn plain" id="logoutBtn">${icons.logout} 退出登录</button>
        </div>
    `;

    $('#changePwdBtn').addEventListener('click', () => navigate('/change-password'));
    $('#refreshBtn').addEventListener('click', () => renderProfile());
    $('#logoutBtn').addEventListener('click', async () => {
        await api.logout();
        currentUser = null;
        toast('已退出登录', 'success');
        navigate('/login');
    });

    setupDashboardAvatar();
}

/** 绑定个人中心的头像更换交互 */
function setupDashboardAvatar() {
    const box = $('#avatarBox');
    const input = $('#avatarInput');
    if (!box || !input) return;

    const openPicker = () => input.click();
    box.addEventListener('click', openPicker);
    box.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPicker(); }
    });

    // “点击头像可更换”提示条同样触发选择文件
    const tip = $('#avatarTip');
    if (tip) tip.addEventListener('click', openPicker);

    input.addEventListener('change', async () => {
        const file = input.files && input.files[0];
        input.value = '';
        await handleAvatarFile(file, { box, media: $('#avatarMedia') });
    });
}

function formatTime(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/* -------------------- 修改密码（已登录，可选验证码 / 邮件链接） -------------------- */
async function renderChangePassword(params) {
    if (!tokens.isLoggedIn) {
        toast('请先登录', 'info');
        return navigate('/login');
    }
    const me = await loadCurrentUser();
    if (!me) {
        toast('登录状态已失效，请重新登录', 'info');
        return navigate('/login');
    }
    const email = me.email;
    const initialMode = params?.get('mode') === 'link' ? 'link' : 'code';

    mount(`
        <section class="card">
            <div class="card-head">
                <span class="eyebrow">安全设置</span>
                <h1>修改密码</h1>
                <p>当前账号 <strong>${esc(maskEmail(email))}</strong><br />请选择一种修改方式</p>
            </div>

            <div class="method-switch" role="radiogroup" aria-label="选择修改方式">
                <button type="button" class="method" data-mode="code" role="radio">
                    <span class="m-title">${icons.shield} 邮箱验证码</span>
                    <span class="m-desc">输入验证码后直接修改密码</span>
                </button>
                <button type="button" class="method" data-mode="link" role="radio">
                    <span class="m-title">${icons.mail} 邮件链接</span>
                    <span class="m-desc">发送重置链接到我的邮箱</span>
                </button>
            </div>

            <div id="pwdPanel"></div>

            <div class="center" style="margin-top:18px; font-size:13.5px">
                <a class="link" id="backDash">${icons.arrowLeft} 返回用户中心</a>
            </div>
        </section>
    `);

    let cancelCountdown = null;

    /* 第一步：发送验证码 */
    const renderSendCode = () => {
        $('#pwdPanel').innerHTML = `
            <div class="stack-sm">
                <button class="btn" type="button" id="sendCodeBtn">发送验证码到邮箱</button>
                <p class="center muted" style="font-size:12.5px; margin:6px 0 0">
                    验证码将发送至 ${esc(maskEmail(email))}
                </p>
            </div>
        `;
        $('#sendCodeBtn').addEventListener('click', async () => {
            const btn = $('#sendCodeBtn');
            setLoading(btn, true, '发送中…');
            try {
                const message = await api.forgotPasswordByCode({ email });
                toast(message || '验证码已发送，请查收邮箱', 'success');
                renderCodeForm();
            } catch (err) {
                toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
                setLoading(btn, false);
            }
        });
    };

    /* 第二步：输入验证码 + 新密码 */
    const renderCodeForm = () => {
        $('#pwdPanel').innerHTML = `
            <div class="code-block">
                <div class="code-inputs" id="codeInputs">${codeCellsHtml()}</div>
                <div class="resend-line">
                    没收到？<a class="link" id="resendLink" role="button" tabindex="0">重新发送验证码</a>
                </div>
            </div>
            <form class="form" id="changeByCodeForm" novalidate style="margin-top:16px">
                ${passwordField('newPassword', '新密码（至少 8 位，含字母和数字）', 'new-password', true)}
                <div class="hint" id="hint-newPassword"></div>

                ${passwordField('confirm', '确认新密码', 'new-password')}
                <div class="hint" id="hint-confirm"></div>

                <button class="btn" type="submit" id="submitBtn">确认修改</button>
            </form>
        `;

        attachPasswordToggle(app, 'newPassword', 'newPassword-eye');
        attachPasswordToggle(app, 'confirm', 'confirm-eye');

        const pwdInput = $('#newPassword');
        pwdInput.addEventListener('input', () => {
            $('#newPassword-strength').dataset.level = String(passwordStrength(pwdInput.value));
        });

        const codeInputs = bindCodeInputs($('#codeInputs'));
        cancelCountdown = startResendCountdown($('#resendLink'), 60, email);

        $('#resendLink').addEventListener('click', async () => {
            const link = $('#resendLink');
            if (link.getAttribute('aria-disabled') === 'true') return;
            try {
                const message = await api.forgotPasswordByCode({ email });
                toast(message || '验证码已重新发送', 'success');
                cancelCountdown = startResendCountdown(link, 60, email);
            } catch (err) {
                toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
            }
        });

        $('#changeByCodeForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = codeInputs.getCode();
            const newPassword = $('#newPassword').value;
            const confirm = $('#confirm').value;

            if (code.length !== 6 || !/^\d{6}$/.test(code)) {
                codeInputs.markInvalid();
                toast('请输入 6 位验证码', 'error');
                return;
            }
            const okP = validate($('#newPassword'), rules.password, $('#hint-newPassword'));
            let okC = true;
            if (confirm !== newPassword) {
                $('#hint-confirm').textContent = '两次输入的密码不一致';
                $('#hint-confirm').classList.add('error');
                $('#confirm').classList.add('invalid');
                okC = false;
            } else {
                $('#hint-confirm').textContent = '';
                $('#hint-confirm').classList.remove('error');
                $('#confirm').classList.remove('invalid');
            }
            if (!(okP && okC)) return;

            const btn = $('#submitBtn');
            setLoading(btn, true, '提交中…');
            try {
                const message = await api.resetPasswordByCode({ email, code, newPassword });
                // 后端会撤销所有登录态，这里直接登出并跳转登录页
                tokens.clear();
                currentUser = null;
                toast(message || '密码修改成功，请使用新密码重新登录', 'success');
                navigate('/login');
            } catch (err) {
                const errCode = err instanceof ApiError ? err.code : '';
                if (errCode === 'CODE_EXPIRED' || errCode === 'TOO_MANY_ATTEMPTS') {
                    showResult({
                        ok: false,
                        title: errCode === 'CODE_EXPIRED' ? '验证码已过期' : '尝试次数过多',
                        desc: err.message || '请重新获取验证码。',
                        actionText: '返回个人中心',
                        onAction: () => navigate('/profile'),
                    });
                    return;
                }
                if (errCode === 'CODE_INVALID') codeInputs.clear();
                toast(err instanceof ApiError ? err.message : '修改失败，请重试', 'error');
                setLoading(btn, false);
            }
        });
    };

    /* 邮件链接方式 */
    const renderSendLink = () => {
        $('#pwdPanel').innerHTML = `
            <div class="stack-sm">
                <button class="btn" type="button" id="sendLinkBtn">发送重置链接到邮箱</button>
                <p class="center muted" style="font-size:12.5px; margin:6px 0 0">
                    链接将发送至 ${esc(maskEmail(email))}，30 分钟内有效
                </p>
            </div>
        `;
        $('#sendLinkBtn').addEventListener('click', async () => {
            const btn = $('#sendLinkBtn');
            setLoading(btn, true, '发送中…');
            try {
                await api.forgotPassword({ email });
                $('#pwdPanel').innerHTML = `
                    <div class="notice success">${icons.check}
                        <span>重置链接已发送到 ${esc(maskEmail(email))}。请在 30 分钟内点击邮件中的链接完成修改（若未收到请检查垃圾箱）。</span>
                    </div>
                `;
                toast('重置链接已发送，请查收邮箱', 'success');
            } catch (err) {
                toast(err instanceof ApiError ? err.message : '发送失败，请稍后重试', 'error');
                setLoading(btn, false);
            }
        });
    };

    const applyMode = (next) => {
        $$('.method', app).forEach((b) => {
            const active = b.dataset.mode === next;
            b.classList.toggle('active', active);
            b.setAttribute('aria-checked', String(active));
        });
        if (cancelCountdown) { cancelCountdown(); cancelCountdown = null; }
        if (next === 'code') renderSendCode();
        else renderSendLink();
    };

    $$('.method', app).forEach((b) => b.addEventListener('click', () => applyMode(b.dataset.mode)));
    $('#backDash').addEventListener('click', () => navigate('/profile'));
    applyMode(initialMode);
}

/* -------------------- 平台页（三个平台合并 + 图标轮换，当前平台居中） -------------------- */
const PLATFORM_TABS = ['zhihuishu', 'yuketang', 'xuexitong'];

function renderPlatform(params) {
    if (!tokens.isLoggedIn) {
        toast('请先登录', 'info');
        return navigate('/login');
    }

    const tabs = PLATFORM_TABS.filter((k) => PLATFORMS.some((p) => p.key === k));
    let key = params?.get('key') || '';
    if (!tabs.includes(key)) key = 'yuketang';   // 默认进入雨课堂

    // order[0] / order[1] / order[2] 分别是「左 / 中 / 右」三个槽位，索引 1 恒为当前平台
    const order = tabs.slice();
    const activeIndex = order.indexOf(key);
    if (activeIndex !== 1) [order[1], order[activeIndex]] = [order[activeIndex], order[1]];

    mountPage(`
        <div class="page">
            <div class="platform-switch" role="tablist" aria-label="平台切换">
                <span class="ps-sep ps-sep-left" aria-hidden="true"></span>
                <span class="ps-sep ps-sep-right" aria-hidden="true"></span>
                ${order.map((k) => {
                    const p = PLATFORMS.find((x) => x.key === k);
                    return `<button type="button" class="ps-item" role="tab" data-key="${k}">${p.icon}</button>`;
                }).join('')}
            </div>

            <div id="platHost"></div>
        </div>
    `);

    const host = $('#platHost');
    const renderPanel = (k) => {
        const p = PLATFORMS.find((x) => x.key === k);
        // 学习通：由浏览器助手脚本读取已登录的 Cookie（依据 script.user.js）
        if (k === 'xuexitong') renderXxtPanel(host, p);
        else renderPasswordPanel(host, p);
    };

    // 把每个图标写到各自的槽位（左右两侧的图标位置也会随之改变）
    const syncSlots = () => {
        $$('.ps-item', app).forEach((btn) => {
            const k = btn.dataset.key;
            const i = order.indexOf(k);
            const on = i === 1;
            btn.dataset.slot = on ? 'mid' : (i === 0 ? 'left' : 'right');
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-selected', String(on));
            btn.tabIndex = on ? 0 : -1;
            const p = PLATFORMS.find((x) => x.key === k);
            const name = p ? p.name : k;
            btn.title = on ? `${name}（当前）` : `切换到 ${name}`;
            btn.setAttribute('aria-label', name);
        });
    };

    // 点击图标：与被点击者交换槽位，使其移动到正中（只重排槽位，不整页重渲染）
    $$('.ps-item', app).forEach((btn) => {
        btn.addEventListener('click', () => {
            const next = btn.dataset.key;
            if (next === key) return;
            const i = order.indexOf(next);
            [order[1], order[i]] = [order[i], order[1]];
            key = next;
            syncSlots();
            history.replaceState(null, '', `#/platform?key=${next}`);
            renderPanel(next);
        });
    });

    syncSlots();
    renderPanel(key);
}

/* 账号密码登录面板（雨课堂 / 智慧树）：凭证统一存后端数据库 */
const COOKIE_STATUS_TEXT = { VALID: '有效', EXPIRED: '已失效', UNKNOWN: '未校验' };

function renderPasswordPanel(host, platform) {
    const key = platform.key;
    const svc = PLATFORM_SERVICES[key];

    host.innerHTML = `
        <section class="card wide" style="width:100%;max-width:520px;margin:0 auto">
            <div class="card-head">
                <span class="platform-logo">${platform.icon}</span>
                <h1>${esc(platform.name)}</h1>
                <p>${esc(platform.desc)} · 登录服务 <code class="code-chip">${esc(svc.base)}</code></p>
            </div>

            <div id="platStatus"></div>

            <!-- 账号密码输入栏：仅在「未配置」或「换绑中」时显示 -->
            <div id="platFormWrap" style="display:none">
                <!-- 刻意不用 <form>：没有 submit 事件，浏览器的“保存密码”提示就无从触发 -->
                <div class="form" id="platLoginForm" style="margin-top:18px">
                    ${inputControl({ id: 'platUser', type: 'text', placeholder: `${platform.name}账号（手机号）`, autocomplete: 'off', icon: icons.user })}
                    <div class="hint" id="hint-platUser"></div>

                    ${passwordField('platPwd', '密码', 'new-password')}
                    <div class="hint" id="hint-platPwd"></div>

                    <button class="btn" type="button" id="loginBtn">登录 ${esc(platform.name)}</button>
                    <div class="center" id="platCancelWrap" style="display:none">
                        <a class="link" id="cancelBindBtn" role="button" tabindex="0">取消，保留原凭证</a>
                    </div>
                </div>
            </div>

            <div id="platActions" style="margin-top:14px"></div>

            <div class="center" style="margin-top:20px; font-size:13.5px">
                <a class="link" id="backHome">${icons.arrowLeft} 返回首页</a>
            </div>
        </section>
    `;

    attachPasswordToggle(app, 'platPwd', 'platPwd-eye');
    // 第三方平台账号密码不允许被浏览器保存或回填
    hardenCredentials($('#platLoginForm'));

    const userInput = $('#platUser');
    const pwdInput = $('#platPwd');
    let current = null;   // 后端返回的凭证详情（含 Cookie，不含密码）
    let editing = false;  // 是否处于「换绑」状态（已绑定时才显示输入栏）
    let relogining = false;  // 正在自动重新登录（显示动画，不是报错）
    let reloginOk = false;   // 刚刚自动重新登录成功（显示绿色提示）

    const renderStatus = () => {
        const status = $('#platStatus');
        const actions = $('#platActions');
        const formWrap = $('#platFormWrap');
        const cancelWrap = $('#platCancelWrap');
        if (!status || !actions) return;

        const bound = Boolean(current?.bound);
        const showForm = !bound || editing;   // 已配置且不在换绑 → 不显示输入栏
        if (formWrap) formWrap.style.display = showForm ? '' : 'none';
        if (cancelWrap) cancelWrap.style.display = (bound && editing) ? '' : 'none';

        if (!bound) {
            status.innerHTML = `
                <div class="notice info">${icons.info}
                    <span>尚未配置。登录成功后账号、密码与 Cookie 会加密保存到服务器，一个平台只需配置一次；
                          之后默认用 Cookie 取数，失效时自动重新登录。</span>
                </div>
            `;
            actions.innerHTML = `<button class="btn plain" type="button" id="openSiteBtn">前往 ${esc(platform.name)} 官网</button>`;
            $('#openSiteBtn').addEventListener('click', () => window.open(platform.url, '_blank', 'noopener'));
            return;
        }

        const valid = current.cookieStatus === 'VALID';
        const cookieCount = Object.keys(current.cookies || {}).length;
        // 自动登录进行中/刚成功时不套用「已失效」的报错色，避免让人以为出错了
        const stateCls = relogining ? 'info busy' : (valid ? 'success' : (reloginOk ? 'success' : 'error'));
        const stateIcon = relogining ? '<span class="spinner"></span>' : (valid || reloginOk ? icons.check : icons.info);
        status.innerHTML = `
            <div class="notice ${stateCls}">${stateIcon}
                <span>账号已配置 <strong>${esc(current.account || '—')}</strong>，Cookie ${cookieCount} 项
                      （${esc(COOKIE_STATUS_TEXT[current.cookieStatus] || current.cookieStatus || '未知')}）
                      ${current.hasPassword ? '，已保存加密密码' : '，未保存密码'}</span>
            </div>
            ${relogining ? `<div class="notice info busy" style="margin-top:10px"><span class="spinner"></span>
                <span>Cookie 已失效，正在用数据库中的账号密码自动重新登录…</span></div>` : ''}
            ${reloginOk && !relogining ? `<div class="notice success" style="margin-top:10px">${icons.check}
                <span>已用数据库中的账号密码自动重新登录。</span></div>` : ''}
            ${editing ? `<div class="notice info" style="margin-top:10px">${icons.info}
                <span>换绑中：请输入新的账号密码并登录，成功后覆盖旧凭证；失败或取消都不会影响原凭证。</span>
            </div>` : ''}
            <details class="cookie-box">
                <summary>查看登录凭证（Cookie 与令牌）</summary>
                <pre>${esc(JSON.stringify({
                    cookies: current.cookies || {},
                    validate: current.extra?.validate,
                    fp: current.extra?.fp,
                }, null, 2))}</pre>
            </details>
        `;
        actions.innerHTML = `
            <div class="grid-2">
                <button class="btn ghost" type="button" id="checkBtn">校验 Cookie</button>
                <button class="btn plain" type="button" id="rebindBtn">${editing ? '取消换绑' : '换绑账号'}</button>
            </div>
            <div class="center" style="margin-top:12px; font-size:13px">
                <a class="link" id="unbindBtn" role="button" tabindex="0">解绑并清除服务器上的凭证</a>
            </div>
        `;
        $('#checkBtn').addEventListener('click', () => ensureValid());
        $('#rebindBtn').addEventListener('click', onToggleRebind);
        $('#unbindBtn').addEventListener('click', onUnbind);
    };

    /** 换绑 / 取消换绑：旧凭证保留到新登录成功才被覆盖 */
    const onToggleRebind = () => {
        if (editing) {
            editing = false;
            pwdInput.value = '';
            renderStatus();
            toast('已取消换绑，原凭证保留', 'info');
            return;
        }
        const ok = window.confirm(
            `确定要换绑 ${platform.name} 账号吗？\n\n需要重新输入该平台的账号密码。\n登录成功前旧凭证不会丢失。`);
        if (!ok) return;
        editing = true;
        pwdInput.value = '';
        renderStatus();
        userInput.focus();
        userInput.select();
    };

    const reload = async () => {
        try {
            current = await platformStore.detail(key);
            if (current?.account && !userInput.value) userInput.value = current.account;
        } catch {
            current = null;
        }
        renderStatus();
    };

    /**
     * 默认取数姿势：先用库里保存的 Cookie 校验；
     * 失效则调用后端 refresh 用库里的账号密码自动重新登录；
     * 后端返回 needManual（未保存密码等）时提示用户手动处理。
     */
    const ensureValid = async ({ silent = false } = {}) => {
        if (relogining) return false;   // 已经在自动重登了，别重复触发
        const btn = $('#checkBtn');
        if (btn) setLoading(btn, true, '校验中…');
        reloginOk = false;
        try {
            const result = await platformStore.check(key);
            if (result.valid) {
                toast(silent ? 'Cookie 有效' : `Cookie 有效：${result.name || current?.account || ''}`, 'success');
                return true;
            }

            // Cookie 失效 → 自动重新登录：期间显示动画，不用报错色
            relogining = true;
            await reload();   // 先把“自动登录中”渲染出来
            if (btn) setLoading(btn, true, '正在自动重新登录…');
            const refreshed = await platformStore.refresh(key);
            if (refreshed.ok) {
                reloginOk = true;
                toast(refreshed.message || '已自动重新登录', 'success');
            } else {
                toast(refreshed.message || 'Cookie 已失效', 'error');
            }
            return Boolean(refreshed.ok);
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '校验失败', 'error');
            return false;
        } finally {
            relogining = false;
            if (btn) setLoading(btn, false);
            await reload();
        }
    };

    const onUnbind = async () => {
        if (!window.confirm(`确定要解绑 ${platform.name} 吗？\n\n服务器上保存的账号、密码与 Cookie 都会被删除。`)) return;
        try {
            await platformStore.unbind(key);
            editing = false;
            pwdInput.value = '';
            toast('已解绑并清除服务器凭证', 'success');
            await reload();
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '解绑失败', 'error');
        }
    };

    const submitPlatformLogin = async (e) => {
        e.preventDefault();
        reloginOk = false;
        const username = userInput.value.trim();
        const password = pwdInput.value;
        if (!username) { toast('请输入账号', 'error'); return; }
        if (!password) { toast('请输入密码', 'error'); return; }
        // 读到就用完即弃：输入框立刻清空，密码只存在于本次调用的局部变量里
        pwdInput.value = '';

        const wasEditing = editing;
        const btn = $('#loginBtn');
        setLoading(btn, true, '登录中…');
        try {
            // 1) 直连平台本地服务完成登录，拿到 Cookie
            const result = await platformApi.login(key, username, password);
            if (!result.ok) {
                toast(result.message || '登录失败', 'error');
                return;
            }
            // 2) 把账号 / 密码 / Cookie 回传后端加密入库（成功后才覆盖旧凭证）
            current = await platformStore.bind(key, {
                account: result.account || username,
                password,
                cookies: result.cookies || {},
                extra: result.extra || {},
                source: 'password',
            });
            pwdInput.value = '';
            userInput.value = current?.account || username;
            editing = false;   // 配置完成，收起输入栏
            toast(wasEditing ? '换绑成功，已覆盖旧凭证' : '登录成功，凭证已加密保存到服务器', 'success');
            renderStatus();
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '登录失败，请稍后重试', 'error');
        } finally {
            setLoading(btn, false);
        }
    };

    // 不用 form 的原生提交，改为按钮点击 + 输入框回车两种方式触发
    $('#loginBtn').addEventListener('click', submitPlatformLogin);
    $('#platLoginForm').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && e.target.tagName === 'INPUT') submitPlatformLogin(e);
    });

    $('#backHome').addEventListener('click', () => navigate('/dashboard'));
    $('#cancelBindBtn').addEventListener('click', () => { if (editing) onToggleRebind(); });

    reload().then(() => {
        // 已配置但状态不是「有效」时自动校验一次，失效就走自动重登
        if (current?.bound && current.cookieStatus !== 'VALID') ensureValid({ silent: true });
    });
}

/* -------------------- 平台概览（作业信息看板） -------------------- */
const BOARD_FIELDS = {
    xuexitong: ['姓名', '学号', '院系'],
    yuketang: ['姓名', '课程', '课堂'],
    zhihuishu: ['姓名', '学校', '专业'],
};
const BOARD_DOT = { xuexitong: 'xxt', yuketang: 'ykt', zhihuishu: 'zhs' };

function boardSkeleton(platform) {
    const fields = BOARD_FIELDS[platform.key] || ['姓名', '账号', '备注'];
    const dot = BOARD_DOT[platform.key] || 'xxt';
    return `
        <article class="pcard" data-card="${platform.key}">
            <div class="pcard-head"><span class="pcard-dot ${dot}"></span>${esc(platform.name)}</div>
            <div class="pcard-profile">
                <div class="pcard-rows">
                    ${fields.map((f, i) => `
                        <div class="pcard-row">
                            <span class="k">${esc(f)}：</span>
                            <span class="v" data-f="f${i}">加载中…</span>
                        </div>`).join('')}
                </div>
                <div class="pcard-avatar" data-f="avatar">·</div>
            </div>
            <div class="pcard-homework">
                <div class="pcard-hw-row"><span class="ic">${icons.clock}</span><span class="k">作业：</span><span class="v" data-f="hw">—</span></div>
                <div class="pcard-hw-row"><span class="ic">${icons.calendar}</span><span class="k">截止：</span><span class="v" data-f="due">—</span></div>
                <div class="pcard-hw-row"><span class="ic">${icons.clipboard}</span><span class="k">状态：</span><span class="v" data-f="status"><span class="pcard-status none">暂无数据</span></span></div>
            </div>
            <button type="button" class="pcard-foot" data-goto="${platform.key}">点击查看详情</button>
        </article>`;
}

async function loadBoardCard(platform) {
    const card = $(`[data-card="${platform.key}"]`);
    if (!card) return;
    const setField = (name, value) => {
        const el = $(`[data-f="${name}"]`, card);
        if (el && value) el.textContent = value;
    };
    const setAvatar = (url, name) => {
        const el = $('[data-f="avatar"]', card);
        if (!el) return;
        if (url) el.innerHTML = `<img src="${esc(url)}" alt="头像" />`;
        else el.textContent = String(name || '?').charAt(0).toUpperCase();
    };

    try {
        // 绑定状态来自后端数据库（三个平台统一）
        const status = await platformStore.detail(platform.key);
        if (!status?.bound) { setField('f0', '未绑定'); return; }

        // 默认姿势：用库里保存的 Cookie 取数
        const checked = await platformStore.check(platform.key);
        if (!checked.valid) {
            setField('f0', '凭证已失效');
            setAvatar(null, status.account);
            return;
        }

        const name = checked.name || status.account || '已登录';
        setField('f0', name);
        setAvatar(checked.avatar, name);

        if (platform.key === 'yuketang' && checked.userinfo) {
            const info = checked.userinfo;
            setField('f1', info.name || info.real_name || info.nickname || '—');
            setField('f2', info.term || info.class_name || '—');
        }
    } catch {
        setField('f0', '服务未启动');
    }
}

async function renderDashboard() {
    if (!tokens.isLoggedIn) { toast('请先登录', 'info'); return navigate('/login'); }

    const platforms = PLATFORMS.filter((p) => p.url);
    mountPage(`
        <div class="page">
            <h1 class="page-title">平台作业信息看板</h1>
            <div class="board-grid">
                ${platforms.map((p) => boardSkeleton(p)).join('')}
            </div>
        </div>
    `);

    $$('.pcard-foot', app).forEach((btn) => {
        btn.addEventListener('click', () => navigate('/platform', { key: btn.dataset.goto }));
    });

    platforms.forEach((p) => loadBoardCard(p));
}

/* -------------------- 课程管理 / 作业查询（待接入数据） -------------------- */
function placeholderPage(title, desc) {
    if (!tokens.isLoggedIn) { toast('请先登录', 'info'); return navigate('/login'); }
    mountPage(`
        <div class="page">
            <h1 class="page-title">${esc(title)}</h1>
            <section class="card wide" style="width:100%;max-width:none">
                <div class="notice info">${icons.info}<span>${esc(desc)}</span></div>
            </section>
        </div>
    `);
}

function renderCourses() {
    placeholderPage('课程管理', '绑定学习通 / 雨课堂 / 智慧树账号后，这里会汇总三个平台的课程。当前等待接入课程接口。');
}

function renderHomework() {
    placeholderPage('作业查询', '这里会按截止时间汇总三个平台的作业，并标注待提交 / 已提交 / 已完成。当前等待接入作业接口。');
}

/* -------------------- 学习通面板：助手脚本自动获取，失败引导手动 -------------------- */
function renderXxtPanel(host, platform) {
    const svc = PLATFORM_SERVICES.xuexitong;
    // 安装地址：优先走前端同源（不依赖 1003 的 Python 登录服务），打不开再回落到登录服务
    const scriptUrl = '/script.user.js';
    const scriptFallbackUrl = `${svc.base}/script.user.js`;
    const pluginUrl = 'https://scriptcat.org/zh-CN/';

    host.innerHTML = `
        <section class="card wide" style="width:100%;max-width:520px;margin:0 auto">
            <div class="card-head">
                <span class="platform-logo">${platform.icon}</span>
                <h1>学习通</h1>
                <p>由浏览器助手直接读取已登录的 Cookie · 服务 <code class="code-chip">${esc(svc.base)}</code></p>
            </div>

            <div id="xxtStatus"></div>
            <div id="xxtActions" style="margin-top:14px"></div>

            <!-- 账号密码输入栏：仅在「未配置」或「换绑中」时显示 -->
            <div id="xxtFormWrap" style="display:none">
                <!-- 刻意不用 <form>：没有 submit 事件，浏览器的“保存密码”提示就无从触发 -->
                <div class="form" id="xxtForm" style="margin-top:16px">
                    ${inputControl({ id: 'xxtUser', type: 'text', placeholder: '学习通账号（手机号）', autocomplete: 'off', icon: icons.user })}
                    ${passwordField('xxtPwd', '密码', 'new-password')}
                    <button class="btn" type="button" id="xxtBtn">一键获取 Cookie</button>
                    <p class="muted center" style="font-size:12.5px; margin:0">
                        助手脚本不保存任何凭证，账号密码在每次点击时由本页面传入，
                        仅在浏览器内加密后直连学习通，不经过本站服务器；
                        获取到的账号密码与 Cookie 会加密保存到本站数据库。
                    </p>
                    <div class="center" id="xxtCancelWrap" style="display:none">
                        <a class="link" id="cancelXxtBtn" role="button" tabindex="0">取消，保留原凭证</a>
                    </div>
                </div>
            </div>

            <details class="cookie-box" id="xxtHelp">
                <summary>安装浏览器助手（首次使用）</summary>
                <div style="padding:12px 14px; font-size:13.5px; line-height:1.9; color:var(--ink-soft)">
                    1. 安装脚本管理器
                    <a class="link" href="${pluginUrl}" target="_blank" rel="noopener">ScriptCat</a>
                    （或 Tampermonkey）<br />
                    2. 安装用户脚本
                    <a class="link" href="${scriptUrl}" target="_blank" rel="noopener">script.user.js</a>
                    （打不开？改用
                    <a class="link" href="${scriptFallbackUrl}" target="_blank" rel="noopener">备用地址</a>
                    ，需先启动 1003 学习通服务）<br />
                    3. <strong>在浏览器里正常登录学习通</strong>，然后回到本页点「重新获取 Cookie」同步到本站。
                </div>
            </details>

            <div class="center" style="margin-top:20px; font-size:13.5px">
                <a class="link" id="backHome">${icons.arrowLeft} 返回平台概览</a>
            </div>
        </section>
    `;

    $('#backHome').addEventListener('click', () => navigate('/dashboard'));

    const helperReady = () => Boolean(window.__CX_HELPER__);

    let saved = { bound: false };
    let editing = false;     // 换绑中
    let syncHint = '';       // 失败原因（需要人工介入时才显示，红色）
    let relogining = false;  // 正在自动重新登录（显示动画，不是报错）
    let reloginOk = false;   // 刚刚自动重新登录成功（显示绿色提示）

    /**
     * 读取浏览器里已登录的学习通 Cookie 并回传后端入库。
     * <p>
     * 只允许在用户点击「重新获取 Cookie」时调用。页面加载、脚本注入、轮询
     * 一律不得自动读取浏览器 Cookie —— 未经用户操作不得采集任何凭证。
     */
    const syncFromHelper = async () => {
        if (!helperReady()) throw new Error('未检测到浏览器助手');
        const cookies = await window.__CX_HELPER__.listCookies();
        const count = Object.keys(cookies || {}).length;
        if (!count) throw new Error('浏览器里没有学习通登录态');
        // 账号优先级：用户刚填的 > 库里已存的 > 不传（拿不到就如实留空，不编造）
        const typedAccount = $('#xxtUser')?.value?.trim();
        saved = await platformStore.saveCookies('xuexitong', {
            cookies,
            source: 'plugin',
            account: typedAccount || saved.account || undefined,
        });
        return count;
    };

    /** 用户主动同步：Cookie 采集的唯一入口 */
    const onResync = async (btn) => {
        if (relogining) return;   // 正在自动重登，等它跑完
        if (!helperReady()) {
            toast('未检测到浏览器助手，请先安装用户脚本（见下方「安装浏览器助手」）', 'error');
            return;
        }
        reloginOk = false;
        if (btn) setLoading(btn, true, '同步中…');
        try {
            const count = await syncFromHelper();
            syncHint = '';
            toast(`已从浏览器同步 ${count} 个 Cookie`, 'success');
        } catch (err) {
            syncHint = `获取 Cookie 失败：${err?.message || '未知原因'}。`
                + '请确认浏览器已登录学习通后重试，或用下方表单填写账号密码获取。';
            toast(syncHint, 'error');
        } finally {
            if (btn) setLoading(btn, false);
        }
        await renderXxtBody();
    };

    /**
     * 调助手完成登录并回传入库。账号密码由调用方提供，脚本与页面都不保存它们。
     * @returns {Promise<number>} 拿到的 Cookie 数量
     */
    const loginWithHelper = async (account, password) => {
        const { cookies } = await window.__CX_HELPER__.login({ username: account, password });
        const count = Object.keys(cookies || {}).length;
        if (!count) throw new Error('未获取到 Cookie');
        // 账号 + 密码 + Cookie 一起入库（成功后才覆盖旧凭证）
        saved = await platformStore.bind('xuexitong', {
            account,
            password,
            cookies,
            source: 'plugin',
        });
        return count;
    };

    /**
     * Cookie 失效后的自动重登（仅学习通）：
     *   1. 从后端取回数据库里保存的账号密码（明文只活在这一个局部作用域里）
     *   2. 交给浏览器助手登录学习通，拿到新的 Cookie
     *   3. 新 Cookie 回传入库
     * 助手不可用 / 库里没有账号密码 / 登录失败，一律转人工输入（输入栏会自动展开）。
     */
    const autoRelogin = async ({ silent = false } = {}) => {
        if (relogining) return false;   // 已经在跑了，别重复触发
        relogining = true;
        reloginOk = false;
        syncHint = '';
        const btn = $('#checkSavedBtn');
        if (btn) setLoading(btn, true, '正在自动重新登录…');
        await renderXxtBody();          // 先把“自动登录中”的动画渲染出来
        try {
            if (!helperReady()) throw new Error('未检测到浏览器助手，请先安装用户脚本后重试');

            // 账号密码只在此处短暂存在：不写 localStorage，也不渲染到界面
            const secret = await platformStore.secret('xuexitong');
            const account = (secret?.account || '').trim();
            const password = secret?.password || '';
            if (!secret?.bound || !account || !password) {
                throw new Error('数据库中未保存学习通的账号或密码，请在下方手动填写后重新获取');
            }

            try {
                const count = await loginWithHelper(account, password);
                reloginOk = true;
                toast(`Cookie 已失效，已用保存的账号密码自动重新登录（${count} 项）`, 'success');
            } finally {
                secret.password = '';   // 用完即弃，尽快断开明文引用
            }
            return true;
        } catch (err) {
            reloginOk = false;
            syncHint = (err instanceof ApiError ? err.message : err?.message)
                || '自动重新登录失败，请手动输入账号密码。';
            if (!silent) toast(syncHint, 'error');
            return false;
        } finally {
            relogining = false;
            if (btn) setLoading(btn, false);
            await renderXxtBody();
        }
    };

    const onCheckSaved = async () => {
        reloginOk = false;
        const btn = $('#checkSavedBtn');
        if (btn) setLoading(btn, true, '校验中…');
        try {
            // 后端用库里保存的 Cookie 调学习通校验
            const r = await platformStore.check('xuexitong');
            if (r.valid) {
                syncHint = '';
                toast(`凭证有效：${r.name || '已登录'}`, 'success');
                await renderXxtBody();
            } else {
                // 发现失效 → 取库里的账号密码交给助手自动重登，不行再提示人工
                await autoRelogin();
            }
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '校验失败', 'error');
        } finally {
            if (btn) setLoading(btn, false);
        }
    };

    /** 手动获取（也是换绑）：走助手脚本的登录能力，同时保存账号密码 */
    const onGetCookie = async (e) => {
        e.preventDefault();
        if (relogining) return;   // 正在自动重登，等它跑完
        if (!helperReady()) { toast('未检测到浏览器助手，请先安装用户脚本', 'error'); return; }
        reloginOk = false;
        const username = $('#xxtUser').value.trim();
        const password = $('#xxtPwd').value;
        if (!username) { toast('请输入账号', 'error'); return; }
        if (!password) { toast('请输入密码', 'error'); return; }
        // 读到就用完即弃：输入框立刻清空，密码只存在于本次调用的局部变量里
        $('#xxtPwd').value = '';

        const wasEditing = editing;
        const btn = $('#xxtBtn');
        setLoading(btn, true, '正在登录…');
        try {
            const count = await loginWithHelper(username, password);
            $('#xxtPwd').value = '';
            editing = false;
            syncHint = '';
            toast(wasEditing ? '换绑成功，已覆盖旧凭证' : `获取成功，已保存 ${count} 个 Cookie`, 'success');
            await renderXxtBody();
        } catch (err) {
            toast(err instanceof ApiError ? err.message : (err?.message || '获取失败，请重试'), 'error');
        } finally {
            setLoading(btn, false);
        }
    };

    const onToggleRebind = () => {
        const pwdInput = $('#xxtPwd');
        if (editing) {
            editing = false;
            if (pwdInput) pwdInput.value = '';
            renderXxtBody();
            toast('已取消换绑，原凭证保留', 'info');
            return;
        }
        const ok = window.confirm(
            '确定要换绑学习通吗？\n\n需要重新输入账号密码，或先在浏览器里切换学习通账号。\n获取成功前旧凭证不会丢失。');
        if (!ok) return;
        editing = true;
        if (pwdInput) pwdInput.value = '';
        renderXxtBody().then(() => {
            const input = $('#xxtUser');
            if (input) { input.focus(); input.select(); }
        });
    };

    const onUnbind = async () => {
        if (!window.confirm('确定要解绑学习通吗？\n\n服务器上保存的账号、密码与 Cookie 都会被删除。')) return;
        try {
            await platformStore.unbind('xuexitong');
            editing = false;
            syncHint = '';
            reloginOk = false;
            toast('已解绑并清除服务器凭证', 'success');
            await renderXxtBody();
        } catch (err) {
            toast(err instanceof ApiError ? err.message : '解绑失败', 'error');
        }
    };

    /** 「重新获取 Cookie」按钮 —— Cookie 采集只能由用户点击这里发起 */
    const bindResync = (sel) => {
        const btn = $(sel);
        if (!btn) return;
        btn.addEventListener('click', () => onResync(btn));
    };

    const renderXxtBody = async () => {
        const status = $('#xxtStatus');
        const actions = $('#xxtActions');
        const formWrap = $('#xxtFormWrap');
        const help = $('#xxtHelp');
        const cancelWrap = $('#xxtCancelWrap');
        if (!status || !actions) return;   // 面板已被切换到其它平台

        try {
            // 从后端数据库读取绑定状态（Cookie 也在这里）
            saved = await platformStore.detail('xuexitong');
        } catch {
            status.innerHTML = `<div class="notice error">${icons.info}<span>无法连接后端服务，请确认后端（8080）已启动。</span></div>`;
            actions.innerHTML = '';
            if (formWrap) formWrap.style.display = 'none';
            return;
        }

        const ready = helperReady();
        const version = ready && window.__CX_HELPER__.version ? ` v${window.__CX_HELPER__.version}` : '';
        const bound = Boolean(saved.bound);
        const expired = bound && saved.cookieStatus !== 'VALID';
        const cookieCount = bound ? Object.keys(saved.cookies || {}).length : 0;
        // 输入栏的显示条件：未绑定 / 换绑中 / 没保存过密码 / 自动重登失败需要人工介入
        const showForm = !bound || editing || !saved.hasPassword || Boolean(syncHint);

        if (formWrap) formWrap.style.display = showForm ? '' : 'none';
        if (help) help.style.display = showForm ? '' : 'none';
        if (cancelWrap) cancelWrap.style.display = (bound && editing) ? '' : 'none';

        // 通知条优先级：自动登录中（动画） > 需要人工介入（红） > 自动登录成功（绿） > 失效提示
        const blocks = [];
        if (relogining) {
            // 正在自动重新登录：这是后台动作，不是错误，用 info 色 + 动画
            blocks.push(`<div class="notice info busy"><span class="spinner"></span>
                <span>Cookie 已失效，正在用数据库中的账号密码自动重新登录…</span></div>`);
        } else if (syncHint) {
            blocks.push(`<div class="notice error">${icons.info}<span>${esc(syncHint)}</span></div>`);
        } else if (reloginOk) {
            blocks.push(`<div class="notice success">${icons.check}
                <span>已用数据库中的账号密码自动重新登录，Cookie ${cookieCount} 项。</span></div>`);
        } else if (expired) {
            blocks.push(`<div class="notice ${saved.hasPassword ? 'info' : 'error'}">${icons.info}
                <span>学习通 Cookie 已失效（${cookieCount} 项）。${saved.hasPassword
                    ? '正在用数据库中的账号密码自动重新登录…'
                    : '数据库中未保存账号密码，请在下方手动填写后重新获取。'}</span>
            </div>`);
        }
        blocks.push(ready
            ? `<div class="notice success">${icons.check}<span>已检测到浏览器助手${esc(version)}。凭证只在你点击「重新获取 Cookie」时读取，页面不会自动采集。</span></div>`
            : `<div class="notice info">${icons.info}<span>未检测到浏览器助手。请先安装用户脚本，安装后本页每 3 秒自动检测。</span></div>`);
        status.innerHTML = `<div style="display:flex;flex-direction:column;gap:10px">${blocks.join('')}</div>`;

        // 未绑定：给出可操作的入口（助手同步 / 前往官网），而不是一片空白
        if (!bound) {
            actions.innerHTML = `
                <div class="grid-2">
                    <button class="btn ghost" type="button" id="resyncBtn">重新获取 Cookie</button>
                    <button class="btn plain" type="button" id="openSiteBtn">前往学习通官网</button>
                </div>
                <p class="center muted" style="font-size:12.5px;margin:10px 0 0">
                    ${ready ? '请先在浏览器里登录学习通，再点「重新获取 Cookie」。'
                            : '未检测到浏览器助手：安装脚本后可点「重新获取 Cookie」，也可用下方表单填写账号密码获取。'}
                </p>
            `;
            bindResync('#resyncBtn');
            $('#openSiteBtn').addEventListener('click', () => window.open(platform.url, '_blank', 'noopener'));
            return;
        }

        actions.innerHTML = `
            <div class="notice ${expired ? 'error' : 'success'}">${expired ? icons.info : icons.check}
                <span>账号已配置（Cookie ${cookieCount} 项${saved.account ? ' · ' + esc(saved.account) : ' · 未填写账号'}${saved.hasPassword ? ' · 已保存加密密码' : ''}）</span>
            </div>
            ${saved.account ? '' : `<p class="center muted" style="font-size:12.5px;margin:8px 0 0">
                助手只读取浏览器里的 Cookie，无法得知登录账号；如需补全账号，请点「换绑账号」填写一次。
            </p>`}
            ${expired ? `<button class="btn ghost" type="button" id="resyncBtn" style="margin-top:12px">重新获取 Cookie</button>` : ''}
            <div class="grid-2" style="margin-top:12px">
                <button class="btn ghost" type="button" id="checkSavedBtn">校验凭证</button>
                <button class="btn plain" type="button" id="rebindBtn">${editing ? '取消换绑' : '换绑账号'}</button>
            </div>
            <div class="center" style="margin-top:12px; font-size:13px">
                <a class="link" id="unbindBtn" role="button" tabindex="0">解绑并清除服务器上的凭证</a>
            </div>
        `;
        bindResync('#resyncBtn');
        $('#checkSavedBtn').addEventListener('click', onCheckSaved);
        $('#rebindBtn').addEventListener('click', onToggleRebind);
        $('#unbindBtn').addEventListener('click', onUnbind);

        const userInput = $('#xxtUser');
        if (saved.account && userInput && !userInput.value) userInput.value = saved.account;
    };

    // 表单是常驻 DOM（只控制显隐），监听只绑一次
    attachPasswordToggle(app, 'xxtPwd', 'xxtPwd-eye');
    // 第三方平台账号密码不允许被浏览器保存或回填
    hardenCredentials($('#xxtForm'));
    // 不用 form 的原生提交，改为按钮点击 + 输入框回车两种方式触发
    $('#xxtBtn').addEventListener('click', onGetCookie);
    $('#xxtForm').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && e.target.tagName === 'INPUT') onGetCookie(e);
    });
    $('#cancelXxtBtn').addEventListener('click', () => { if (editing) onToggleRebind(); });

    // 首次渲染只做只读查询，任何情况下都不主动读取浏览器 Cookie
    renderXxtBody().then(() => {
        // 发现库里记录的 Cookie 已失效 → 用保存的账号密码 + 助手自动重登；
        // 助手没装或库里没账号密码时，autoRelogin 会给出原因并展开输入栏转人工
        if (saved?.bound && saved.cookieStatus !== 'VALID') {
            autoRelogin({ silent: true });
        }
    });

    // 脚本可能是延迟注入的：轮询只用于刷新“是否检测到助手”的提示，不触发任何采集
    let lastReady = helperReady();
    const poll = setInterval(() => {
        if (!$('#xxtStatus')) { clearInterval(poll); return; }   // 已切走，停止轮询
        const now = helperReady();
        if (now !== lastReady) {
            lastReady = now;
            renderXxtBody();
        }
    }, 3000);
    addCleanup(() => clearInterval(poll));
}

/* -------------------- 结果页 -------------------- */
function showResult({ ok, title, desc, actionText, onAction }) {
    mount(`
        <section class="card">
            <div class="result ${ok ? '' : 'error'}">
                <div class="badge">${ok ? icons.check : icons.x}</div>
                <h2>${esc(title)}</h2>
                <p>${esc(desc)}</p>
                <button class="btn" id="resultAction">${esc(actionText)}</button>
            </div>
        </section>
    `);
    $('#resultAction').addEventListener('click', onAction);
}

/* -------------------- 渲染入口 -------------------- */
function render() {
    runCleanups();
    window.scrollTo({ top: 0 });
    const { path, params } = parseHash();
    const view = routes[path] || (() => navigate('/login'));
    view(params);
    renderTopNav();
}

function init() {
    // 兼容两种重置链接：
    // 1) hash 式：/#/reset-password?token=xxx（当前邮件使用，任何静态服务器都能打开）
    // 2) 路径式：/reset-password?token=xxx（需静态服务器做 SPA 回退，保留以兼容旧邮件）
    const path = location.pathname.replace(/\/+$/, '');
    if (path.endsWith('/reset-password') && !location.hash) {
        const token = new URLSearchParams(location.search).get('token');
        if (token) {
            history.replaceState(null, '', location.origin + '/');
            window.addEventListener('hashchange', render);
            location.hash = `#/reset-password?token=${encodeURIComponent(token)}`;
            return;
        }
    }

    window.addEventListener('hashchange', render);
    if (!location.hash) {
        // 设置 hash 会触发 hashchange 完成首次渲染
        location.hash = tokens.isLoggedIn ? '#/dashboard' : '#/login';
    } else {
        render();
    }
}

init();
