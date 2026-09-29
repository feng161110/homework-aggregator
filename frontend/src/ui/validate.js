/** 表单校验规则（与后端约束保持一致）与文本工具 */

export const rules = {
    username: (v) => /^[a-zA-Z0-9_\u4e00-\u9fa5]{1,18}$/.test(v) || '用户名需为 1-18 位中文、字母、数字或下划线',
    email: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) || '邮箱格式不正确',
    password: (v) => /^(?=.*[A-Za-z])(?=.*\d)\S{8,64}$/.test(v) || '密码至少 8 位且需同时包含字母和数字',
    code: (v) => /^\d{6}$/.test(v) || '验证码为 6 位数字',
};

/** 校验单个值：通过返回 true，失败返回错误文案 */
export function check(value, rule) {
    const result = rule(String(value ?? '').trim());
    return result === true ? { ok: true, message: '' } : { ok: false, message: result };
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

/** 邮箱脱敏：zhangsan@qq.com → z***n@qq.com */
export function maskEmail(email) {
    if (!email || !email.includes('@')) return email || '';
    const [name, domain] = email.split('@');
    const masked = name.length <= 2 ? name[0] + '*' : name[0] + '***' + name[name.length - 1];
    return `${masked}@${domain}`;
}

/** 时间格式化 */
export function formatTime(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
}

/**
 * 时长格式化（天/时/分/秒），用于「数据获取于多久前」的实时显示。
 * 只省略高位为 0 的单位：3天2时5分8秒 / 2时5分8秒 / 5分8秒 / 8秒。
 */
export function formatElapsed(ms) {
    const total = Math.max(0, Math.floor((ms || 0) / 1000));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    const parts = [];
    if (days) parts.push(`${days}天`);
    if (days || hours) parts.push(`${hours}时`);
    if (days || hours || minutes) parts.push(`${minutes}分`);
    parts.push(`${seconds}秒`);
    return parts.join('');
}

/** 账号首字母（头像兜底） */
export function initialOf(name) {
    return String(name || '?').charAt(0).toUpperCase();
}
