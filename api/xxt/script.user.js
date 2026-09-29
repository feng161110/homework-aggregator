// ==UserScript==
// @name         学习通 Cookie 助手
// @namespace    local.cx.helper
// @version      1.6.3
// @author       大大大彬
// @description  账号密码由前端传入，脚本只负责加密和请求
// @homepage     https://scriptcat.org/
// @include      http://localhost:*/*
// @include      http://127.0.0.1:*/*
// 站点全路径：助手需要在「平台绑定」等页面被注入，不能只匹配 /xxt/ 这一个子路径
// （后端接口已收敛到 /api/**，不再占用任何页面路径）
// @include      http://39.104.16.224/*
// @include      https://39.104.16.224/*
// @grant        GM_cookie
// @grant        GM_xmlhttpRequest
// @grant        unsafeWindow
// @connect      chaoxing.com
// @connect      *.chaoxing.com
// @connect      passport2.chaoxing.com
// @connect      i.chaoxing.com
// @connect      mooc1.chaoxing.com
// @connect      mooc2-ans.chaoxing.com
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    const KEY       = 'u2oh6Vu^HWe4_AES';
    const LOGIN_API = 'https://passport2.chaoxing.com/fanyalogin';
    const REFERER   = 'https://passport2.chaoxing.com/login?fid=&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com';
    const UA        = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

    const pageWindow = (typeof unsafeWindow !== 'undefined') ? unsafeWindow : window;

    /* ==================== AES-128-CBC ====================
     * 优先使用原生 WebCrypto；HTTP 页面等非安全上下文没有 crypto.subtle，
     * 此时自动回退到下面的纯 JS 实现（结果与 WebCrypto 完全一致）。
     * ==================================================== */
    async function aesEncrypt(message, key) {
        const enc = new TextEncoder();
        const k = enc.encode(key), d = enc.encode(message);

        if (typeof crypto !== 'undefined' && crypto.subtle && typeof crypto.subtle.importKey === 'function') {
            const padLen = 16 - (d.length % 16);
            const padded = new Uint8Array(d.length + padLen);
            padded.set(d); padded.fill(padLen, d.length);
            const ck = await crypto.subtle.importKey('raw', k, { name: 'AES-CBC' }, false, ['encrypt']);
            const out = await crypto.subtle.encrypt({ name: 'AES-CBC', iv: k }, ck, padded);
            return btoa(String.fromCharCode(...new Uint8Array(out)));
        }

        console.warn('[CX] 当前页面非安全上下文（非 HTTPS/localhost），已切换为内置 AES 实现');
        const out = aesCbcEncryptBytes(d, k);
        let bin = '';
        for (let i = 0; i < out.length; i++) bin += String.fromCharCode(out[i]);
        return btoa(bin);
    }

    /* ---------- 纯 JS AES（FIPS-197） ---------- */
    const AES_SBOX = new Uint8Array([
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
    ]);

    function aesXtime(b) { return ((b << 1) ^ ((b & 0x80) ? 0x1b : 0)) & 0xff; }
    function aesMul3(b) { return aesXtime(b) ^ b; }

    function aesExpandKey(keyBytes) {
        const Nk = keyBytes.length >> 2;
        const Nr = Nk + 6;
        const RCON = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];
        const w = [];
        for (let i = 0; i < Nk; i++) {
            w.push([keyBytes[4 * i], keyBytes[4 * i + 1], keyBytes[4 * i + 2], keyBytes[4 * i + 3]]);
        }
        for (let i = Nk; i < 4 * (Nr + 1); i++) {
            let t = w[i - 1].slice();
            if (i % Nk === 0) {
                t = [t[1], t[2], t[3], t[0]].map(b => AES_SBOX[b]);
                t[0] ^= RCON[i / Nk];
            } else if (Nk > 6 && i % Nk === 4) {
                t = t.map(b => AES_SBOX[b]);
            }
            const p = w[i - Nk];
            w.push([p[0] ^ t[0], p[1] ^ t[1], p[2] ^ t[2], p[3] ^ t[3]]);
        }
        return { w, Nr };
    }

    function aesEncryptBlock(block, ks) {
        const { w, Nr } = ks;
        const s = Uint8Array.from(block);
        for (let c = 0; c < 4; c++)
            for (let r = 0; r < 4; r++) s[4 * c + r] ^= w[c][r];

        for (let round = 1; round <= Nr; round++) {
            for (let i = 0; i < 16; i++) s[i] = AES_SBOX[s[i]];

            const t = Uint8Array.from(s);
            for (let r = 1; r < 4; r++)
                for (let c = 0; c < 4; c++) s[4 * c + r] = t[4 * ((c + r) % 4) + r];

            if (round !== Nr) {
                for (let c = 0; c < 4; c++) {
                    const a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
                    s[4 * c]     = aesXtime(a0) ^ aesMul3(a1) ^ a2 ^ a3;
                    s[4 * c + 1] = a0 ^ aesXtime(a1) ^ aesMul3(a2) ^ a3;
                    s[4 * c + 2] = a0 ^ a1 ^ aesXtime(a2) ^ aesMul3(a3);
                    s[4 * c + 3] = aesMul3(a0) ^ a1 ^ a2 ^ aesXtime(a3);
                }
            }
            for (let c = 0; c < 4; c++)
                for (let r = 0; r < 4; r++) s[4 * c + r] ^= w[round * 4 + c][r];
        }
        return s;
    }

    function aesCbcEncryptBytes(plainBytes, keyBytes) {
        const ks = aesExpandKey(keyBytes);
        const iv = keyBytes.slice(0, 16);
        const padLen = 16 - (plainBytes.length % 16);
        const data = new Uint8Array(plainBytes.length + padLen);
        data.set(plainBytes);
        data.fill(padLen, plainBytes.length);

        const out = new Uint8Array(data.length);
        let prev = iv;
        for (let off = 0; off < data.length; off += 16) {
            const blk = new Uint8Array(16);
            for (let i = 0; i < 16; i++) blk[i] = data[off + i] ^ prev[i];
            const enc = aesEncryptBlock(blk, ks);
            out.set(enc, off);
            prev = enc;
        }
        return out;
    }

    function gmRequest(opts) {
        return new Promise((resolve, reject) => {
            try {
                GM_xmlhttpRequest({
                    ...opts,
                    onload: r => resolve(r),
                    onerror: e => reject(new Error('网络错误：' + (e.error || 'unknown'))),
                    ontimeout: () => reject(new Error('请求超时')),
                });
            } catch (e) { reject(e); }
        });
    }

    function gmCookieList(filter) {
        return new Promise((resolve) => {
            try {
                const ret = GM_cookie.list(filter, (cookies, error) => {
                    if (error) resolve({ error });
                    else resolve({ cookies });
                });
                if (ret && typeof ret.then === 'function') {
                    ret.then(r => {
                        if (Array.isArray(r)) resolve({ cookies: r });
                        else if (r && Array.isArray(r.cookies)) resolve({ cookies: r.cookies });
                        else resolve({ cookies: [] });
                    }).catch(e => resolve({ error: e }));
                }
            } catch (e) { resolve({ error: e }); }
        });
    }

    /* ==================== Cookie 读写 ====================
     * 只按 @connect 中声明过的学习通域名逐个查询。
     * 不要用无过滤的 GM_cookie.list({})：ScriptCat 会拿当前页面域名
     * 去校验权限，报 “hostname must be in the definition of connect”。
     * ==================================================== */
    const CX_COOKIE_URLS = [
        'https://passport2.chaoxing.com/',
        'https://i.chaoxing.com/',
        'https://mooc1.chaoxing.com/',
    ];

    function isChaoxingDomain(domain) {
        return !!domain && /(^|\.)chaoxing\.com$/i.test(String(domain).toLowerCase().replace(/^\./, ''));
    }

    async function listChaoxingCookies() {
        const map = new Map();
        for (const url of CX_COOKIE_URLS) {
            const r = await gmCookieList({ url });
            if (r.error) continue;                     // 该域无权限 / 查询失败：跳过，不报错
            (r.cookies || []).forEach(c => {
                if (!isChaoxingDomain(c.domain)) return;
                map.set((c.domain || '') + '|' + (c.path || '/') + '|' + c.name, c);
            });
        }
        return Array.from(map.values());
    }

    async function clearChaoxingCookies() {
        const list = await listChaoxingCookies();
        for (const c of list) {
            const path = c.path || '/';
            try {
                const ret = GM_cookie.delete({
                    url: 'https://i.chaoxing.com' + path,
                    name: c.name,
                    domain: c.domain,
                    path: path,
                }, () => {});
                if (ret && typeof ret.catch === 'function') ret.catch(() => {});
            } catch (e) {}
        }
        if (list.length) await new Promise(r => setTimeout(r, 400));
    }

    async function readAllChaoxingCookies() {
        const dict = {};
        const list = await listChaoxingCookies();
        list.forEach(c => { dict[c.name] = c.value; });
        return dict;
    }

    function readCredentialFromDOM() {
        const trySelectors = (list) => {
            for (const sel of list) {
                const el = pageWindow.document.querySelector(sel);
                if (el && el.value) return el.value;
            }
            return '';
        };
        return {
            username: trySelectors([
                '#username', '#user', '#account', '#uname',
                'input[name="username"]', 'input[name="user"]',
                'input[name="account"]', 'input[name="uname"]',
                'input[type="text"]',
            ]),
            password: trySelectors([
                '#password', '#pass', '#pwd',
                'input[name="password"]', 'input[name="pass"]',
                'input[name="pwd"]', 'input[type="password"]',
            ]),
        };
    }

    async function doLogin(opt = {}) {
        let { username, password, clearFirst } = opt;
        if (!username || !password) {
            const fromDOM = readCredentialFromDOM();
            username = username || fromDOM.username;
            password = password || fromDOM.password;
        }
        if (!username) throw new Error('未获取到用户名');
        if (!password) throw new Error('未获取到密码');

        if (clearFirst !== false) {
            await clearChaoxingCookies();
            await new Promise(r => setTimeout(r, 300));
        }

        const unameEnc    = await aesEncrypt(username, KEY);
        const passwordEnc = await aesEncrypt(password, KEY);

        const body = new URLSearchParams({
            fid: '-1', uname: unameEnc, password: passwordEnc,
            refer: 'https%3A%2F%2Fi.chaoxing.com',
            t: 'true', forbidotherlogin: '0', validate: '',
            doubleFactorLogin: '0', independentId: '0', independentNameId: '0',
        }).toString();

        console.log('[CX] POST fanyalogin，用户名:', username);
        const r = await gmRequest({
            method: 'POST',
            url: LOGIN_API,
            headers: {
                'User-Agent': UA,
                'Accept': 'application/json, text/javascript, */*; q=0.01',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Origin': 'https://passport2.chaoxing.com',
                'Referer': REFERER,
            },
            data: body,
            timeout: 15000,
        });

        let result;
        try { result = JSON.parse(r.responseText); }
        catch (e) { throw new Error('响应解析失败：' + r.responseText.slice(0, 200)); }

        console.log('[CX] 登录响应:', result);
        if (!result.status) throw new Error(result.message || JSON.stringify(result));

        await new Promise(r => setTimeout(r, 2000));
        let cookies = await readAllChaoxingCookies();
        if (!Object.keys(cookies).length) {
            // 登录响应里的 Set-Cookie 有时落盘稍慢，稍等再读一次
            await new Promise(r => setTimeout(r, 1200));
            cookies = await readAllChaoxingCookies();
        }
        console.log('[CX] 共 ' + Object.keys(cookies).length + ' 个 Cookie', cookies);
        return { cookies, raw: result };
    }

    const helper = {
        version: '1.6.3',
        login: doLogin,
        listCookies: readAllChaoxingCookies,
        clearCookies: clearChaoxingCookies,
        readCredentialFromDOM,
    };

    pageWindow.__CX_HELPER__ = helper;
    try {
        pageWindow.dispatchEvent(new pageWindow.CustomEvent('cx-helper-ready', { detail: helper }));
    } catch (e) {}
    console.log('%c[CX Helper] 已注入 v' + helper.version, 'color:#667eea;font-weight:bold');
})();