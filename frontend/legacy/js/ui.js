/**
 * ui.js —— 通用 UI 工具：图标、轻提示、校验、DOM 辅助
 */

/* -------------------- 图标（内联 SVG） -------------------- */
export const icons = {
    user: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
    mail: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="16" rx="3"/><path d="m3 7 9 6 9-6"/></svg>',
    lock: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="10" width="16" height="11" rx="2.5"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>',
    eye: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>',
    eyeOff: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 8 10 8a18 18 0 0 1-2.29 3.36M6.6 6.6A18 18 0 0 0 2 12s3.5 7 10 7a9.3 9.3 0 0 0 4.4-1.1"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/><path d="m2 2 20 20"/></svg>',
    check: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
    x: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>',
    info: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/></svg>',
    shield: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 4 5v6c0 5 3.4 8.4 8 11 4.6-2.6 8-5.7 8-11V5l-8-3Z"/></svg>',
    arrowLeft: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>',
    logout: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/></svg>',
    camera: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 8.5h2.2l1.5-2.3h7.6l1.5 2.3h2.2a1.5 1.5 0 0 1 1.5 1.5v7.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V10a1.5 1.5 0 0 1 1.5-1.5Z"/><circle cx="12" cy="14" r="3.4"/></svg>',
    cameraSm: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 8.5h2.2l1.5-2.3h7.6l1.5 2.3h2.2a1.5 1.5 0 0 1 1.5 1.5v7.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V10a1.5 1.5 0 0 1 1.5-1.5Z"/><circle cx="12" cy="14" r="3.4"/></svg>',
    home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20a1 1 0 0 0 1 1H10v-5.5h4V21h3.5a1 1 0 0 0 1-1V9.5"/></svg>',
    clock: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/></svg>',
    calendar: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 9.5h17"/><path d="M8 3.5v3"/><path d="M16 3.5v3"/></svg>',
    clipboard: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4.5" width="14" height="16" rx="2.5"/><path d="M9 4.5V3h6v1.5"/><path d="M9 11h6"/><path d="M9 15h4"/></svg>',

    /* 第三方平台图标（按官方 logo 风格绘制的简化矢量图） */
    logoYuketang: '<svg viewBox="0 0 32 32" fill="none"><circle cx="16" cy="16" r="15" fill="#4a90d9"/><rect x="9" y="10" width="2.8" height="12" rx="1.4" fill="#fff"/><rect x="14.6" y="7.5" width="2.8" height="17" rx="1.4" fill="#fff" opacity="0.85"/><rect x="20.2" y="11" width="2.8" height="10" rx="1.4" fill="#fff"/></svg>',
    logoZhihuishu: '<svg viewBox="0 0 32 32" fill="none"><rect x="3" y="15" width="4.4" height="12" rx="2.2" fill="#5cb85c"/><rect x="10.2" y="9" width="4.4" height="18" rx="2.2" fill="#3b8ede"/><rect x="17.4" y="17" width="4.4" height="10" rx="2.2" fill="#f5b301"/><rect x="24.6" y="19" width="4.4" height="8" rx="2.2" fill="#e5484d"/></svg>',
    logoXuexitong: '<svg viewBox="0 0 32 32" fill="none"><rect width="32" height="32" rx="8" fill="#e5342b"/><path d="M5.5 25c5-13 13-16.5 19.5-17" stroke="#fff" stroke-width="2.6" stroke-linecap="round"/><path d="M8.5 28C13 16 20.5 13 26.5 12.5" stroke="#fff" stroke-width="2.6" stroke-linecap="round"/><circle cx="24.5" cy="8" r="2.6" fill="#fff"/></svg>',
};

/* -------------------- 轻提示 -------------------- */
const toastRoot = () => document.getElementById('toast-root');

export function toast(message, type = 'info', duration = 3600) {
    const root = toastRoot();
    if (!root) return;
    const iconMap = { success: icons.check, error: icons.x, info: icons.info };
    const node = document.createElement('div');
    node.className = `toast ${type}`;
    node.innerHTML = `<span class="t-icon">${iconMap[type] || icons.info}</span><span class="t-msg"></span>`;
    node.querySelector('.t-msg').textContent = message;
    root.appendChild(node);

    const remove = () => {
        node.classList.add('out');
        node.addEventListener('animationend', () => node.remove(), { once: true });
    };
    const timer = setTimeout(remove, duration);
    node.addEventListener('click', () => { clearTimeout(timer); remove(); });
}

/* -------------------- DOM 辅助 -------------------- */
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/** 转义 HTML，防止注入 */
export function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, (c) => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}

/** 邮箱脱敏：zhangsan@qq.com → z***n@qq.com 之类 */
export function maskEmail(email) {
    if (!email || !email.includes('@')) return email || '';
    const [name, domain] = email.split('@');
    const masked = name.length <= 2 ? name[0] + '*' : name[0] + '***' + name[name.length - 1];
    return `${masked}@${domain}`;
}

/* -------------------- 表单校验（与后端规则一致） -------------------- */
export const rules = {
    username: (v) => /^[a-zA-Z0-9_]{4,32}$/.test(v) || '用户名需为 4-32 位字母、数字或下划线',
    email: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) || '邮箱格式不正确',
    password: (v) => /^(?=.*[A-Za-z])(?=.*\d)\S{8,64}$/.test(v) || '密码至少 8 位且需同时包含字母和数字',
    code: (v) => /^\d{6}$/.test(v) || '验证码为 6 位数字',
};

/** 校验单个值，通过返回 true；失败把提示写入对应 hint 元素并返回 false */
export function validate(input, rule, hintEl) {
    const message = rule(input.value.trim());
    const ok = message === true;
    if (hintEl) {
        hintEl.textContent = ok ? '' : message;
        hintEl.classList.toggle('error', !ok);
    }
    input.classList.toggle('invalid', !ok);
    return ok;
}

/** 密码强度 0-4 */
export function passwordStrength(pwd) {
    if (!pwd) return 0;
    let score = 0;
    if (pwd.length >= 8) score++;
    if (/[A-Za-z]/.test(pwd) && /\d/.test(pwd)) score++;
    if (pwd.length >= 12) score++;
    if (/[^A-Za-z0-9]/.test(pwd)) score++;
    return Math.min(score, 4);
}

/** 按钮加载态 */
export function setLoading(btn, loading, loadingText = '处理中…') {
    if (!btn) return;
    if (loading) {
        btn.dataset._label = btn.dataset._label || btn.innerHTML;
        btn.disabled = true;
        btn.innerHTML = `<span class="spinner"></span><span>${esc(loadingText)}</span>`;
    } else {
        btn.disabled = false;
        if (btn.dataset._label) btn.innerHTML = btn.dataset._label;
    }
}

/** 渲染带图标的输入框 */
export function inputControl({ id, type = 'text', placeholder, autocomplete, icon }) {
    return `
        <div class="control">
            <span class="icon">${icon}</span>
            <input id="${id}" name="${id}" type="${type}" placeholder="${esc(placeholder)}"
                   ${autocomplete ? `autocomplete="${autocomplete}"` : ''} />
        </div>`;
}

/** 给密码框绑定“显示/隐藏”按钮 */
export function attachPasswordToggle(root, inputId, buttonId) {
    const input = $(`#${inputId}`, root);
    const btn = $(`#${buttonId}`, root);
    if (!input || !btn) return;
    btn.addEventListener('click', () => {
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.innerHTML = show ? icons.eyeOff : icons.eye;
        btn.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
    });
}

/* -------------------- 凭证输入：禁止浏览器留存 / 回填 -------------------- */

/** 一次性随机字段名：破掉密码管理器按固定 name 建立的识别规则 */
function randomFieldName(prefix) {
    return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 让表单里的第三方平台账号密码「用完即弃」：
 *   1. 上层已把 <form> 换成 <div>，没有 submit 事件，浏览器无从弹「保存密码」
 *   2. form 与 input 全部 autocomplete=off，密码框用 new-password
 *   3. name 随机化，破掉密码管理器按固定字段名建立的识别规则
 *   4. 初始 readonly，用户真正聚焦时才放开，避免自动填充面板弹出
 *
 * 约定：凭证只存在于用户当次输入与后端数据库中，浏览器不留痕。
 */
export function hardenCredentials(formEl) {
    if (!formEl) return;
    formEl.setAttribute('autocomplete', 'off');
    $$('input', formEl).forEach((input) => {
        const isPassword = input.type === 'password';
        input.setAttribute('autocomplete', isPassword ? 'new-password' : 'off');
        input.setAttribute('name', randomFieldName(isPassword ? 'pwd' : 'acct'));

        // 保持只读直到用户真正聚焦，既能挡住自动填充，又不影响正常输入
        input.setAttribute('readonly', 'readonly');
        const unlock = () => input.removeAttribute('readonly');
        input.addEventListener('focus', unlock, { once: true });
        input.addEventListener('pointerdown', unlock, { once: true });
    });
}
