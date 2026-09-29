(function (global) {
    'use strict';

    // 对称密钥（与后端 jm.php 中的 JM_KEY_STR 保持一致）
    var KEY = 'jmAccount@2026#39.104';

    // 字符串转 UTF-8 字节数组
    function strToBytes(str) {
        var bytes = [];
        for (var i = 0; i < str.length; i++) {
            var c = str.charCodeAt(i);
            if (c < 0x80) {
                bytes.push(c);
            } else if (c < 0x800) {
                bytes.push(0xC0 | (c >> 6));
                bytes.push(0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
                // 代理对（emoji 等），取下一个码元组成 4 字节
                var c2 = str.charCodeAt(++i);
                var cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                bytes.push(0xF0 | (cp >> 18));
                bytes.push(0x80 | ((cp >> 12) & 0x3F));
                bytes.push(0x80 | ((cp >> 6) & 0x3F));
                bytes.push(0x80 | (cp & 0x3F));
            } else {
                bytes.push(0xE0 | (c >> 12));
                bytes.push(0x80 | ((c >> 6) & 0x3F));
                bytes.push(0x80 | (c & 0x3F));
            }
        }
        return bytes;
    }

    // 字节数组转 hex 字符串
    function bytesToHex(bytes) {
        var hex = '';
        for (var i = 0; i < bytes.length; i++) {
            hex += ('0' + (bytes[i] & 0xFF).toString(16)).slice(-2);
        }
        return hex;
    }

    // RC4 对称加密（加解密同函数）
    function rc4(key, data) {
        var S = [];
        var i, j = 0, t;
        for (i = 0; i < 256; i++) S[i] = i;
        for (i = 0; i < 256; i++) {
            j = (j + S[i] + key.charCodeAt(i % key.length)) % 256;
            t = S[i]; S[i] = S[j]; S[j] = t;
        }
        i = 0;
        j = 0;
        var out = new Array(data.length);
        for (var k = 0; k < data.length; k++) {
            i = (i + 1) % 256;
            j = (j + S[i]) % 256;
            t = S[i]; S[i] = S[j]; S[j] = t;
            out[k] = data[k] ^ S[(S[i] + S[j]) % 256];
        }
        return out;
    }

    global.jmEncrypt = function (plainText) {
        var data = strToBytes(plainText);
        var enc = rc4(KEY, data);
        return bytesToHex(enc);
    };
})(window);