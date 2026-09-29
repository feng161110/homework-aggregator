/**
 * 登录 / 注册参数加密（依赖 public/chunk.a.js、public/chunk.b.js，由 index.html 引入）
 *
 * 传输格式（与后端 LoginPayloadCipher 严格对应）：
 *   把「明文参数.时间戳(毫秒)」**整体加密**后输出纯 hex，密文里不含可见时间戳。
 *   - 账号 / 用户名 / 邮箱：chunk.b.js 的 RC4 对称加密（window.jmEncrypt）
 *   - 密码：chunk.a.js 的 RSA 加密（window.jm，PKCS#1 v1.5）
 *
 * 同一次请求的多个字段共用同一个时间戳；后端解密后再从明文末尾拆出时间戳，
 * 与服务端时钟双向偏差超过 app.security.login-timestamp-window-seconds
 * （默认 10 秒）即返回登录超时。
 */

/* 模数按固定长度切成若干段分散存放，最终按顺序拼接得到，避免完整密钥出现在同一处 */
const _a = 'b959af7a02ed321f6308390e09c12de6b1532aec940331a44b45a1c803248174';

/** 加密脚本未加载时给出可读提示，避免抛 "jm is not a function" 这类费解错误。 */
function ensureCryptoReady() {
    if (typeof window.jm !== 'function' || typeof window.jmEncrypt !== 'function') {
        throw new Error('登录加密组件未加载，请强制刷新页面（Ctrl+F5）后重试');
    }
}

const _b = 'fced6d0c9b6945ed4ae4e01a1a5f8a84c16c19a531067ca7bac9a9c423d3befe';
const _ab = _a + _b;

/** RSA 公开指数。 */
const RSA_PUBLIC_EXPONENT = '10001';

const _c = 'f13238f988f908c37a5e1ac09744cc28a8213b9a5e775a75f281a747adc9718c';
const _abc = _ab + _c;

const _d = '8969bed598a4e3f39dfe75ff6b08577296baed3fb9982544db2ec183c0b11661';
const _abcd = _abc + _d;

const _e = 'b4721570b7cf57b3a974b8c2a77c04061334eedea3d6778b249f5a4b9ca414bb';
const _abcde = _abcd + _e;

const _f = 'f2c7536993e9347be06660a3bf08f5ca658fbece8b630d02f63c748035f59541';
const _abcdef = _abcde + _f;

const _g = '4027b60d101c9775e22d8b308c2ec7e1d1ffcf0731edadd5097eabe71f598ed9';
const _abcdefg = _abcdef + _g;

const _h = 'd1be9356d507bc8b83ca37c279015b6b368c92e548683ad6b0e10bfa20820101';

/** 完整模数（十六进制）：与后端 app.security.login-rsa-private-key 配对，换密钥需两边同步。 */
const RSA_PUBLIC_MODULUS = _abcdefg + _h;

/**
 * 把明文账号/密码转换为后端要求的加密请求体。
 *
 * 时间戳与参数拼成「参数.时间戳」后**整体加密**，所以密文里看不到明文时间戳；
 * 后端先用对应算法解密，再从明文末尾拆出时间戳做双向 10 秒窗口校验。
 *
 * @param {string} account  明文账号（用户名或邮箱）
 * @param {string} password 明文密码
 * @returns {{u: string, p: string}} 混淆后的请求体
 */
export function buildLoginPayload(account, password) {
    ensureCryptoReady();

    const timestamp = Date.now();

    return {
        u: window.jmEncrypt(`${account}.${timestamp}`),
        p: window.jm(RSA_PUBLIC_EXPONENT, RSA_PUBLIC_MODULUS, `${password}.${timestamp}`),
    };
}

/**
 * 把明文用户名 / 邮箱 / 密码转换为后端要求的注册请求体。
 * 用户名与邮箱走 RC4、密码走 RSA，三个字段都是「参数.时间戳」整体加密，
 * 共用同一个毫秒时间戳。
 *
 * @param {string} username 明文用户名
 * @param {string} email    明文邮箱
 * @param {string} password 明文密码
 * @returns {{u: string, e: string, p: string}} 混淆后的请求体
 */
export function buildRegisterPayload(username, email, password) {
    ensureCryptoReady();

    const timestamp = Date.now();

    return {
        u: window.jmEncrypt(`${username}.${timestamp}`),
        e: window.jmEncrypt(`${email}.${timestamp}`),
        p: window.jm(RSA_PUBLIC_EXPONENT, RSA_PUBLIC_MODULUS, `${password}.${timestamp}`),
    };
}
