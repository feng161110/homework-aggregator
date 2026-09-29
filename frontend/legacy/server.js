/**
 * server.js —— 零依赖静态服务器（Node.js）
 *
 * 用法： node server.js
 * 访问： http://localhost:5173
 *
 * 端口 5173 与后端 CORS 放行名单及 app.mail.base-url 一致。
 * 支持 SPA 回退：无扩展名且不存在的路径会返回 index.html（用于 /reset-password?token=...）。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const PORT = Number(process.env.PORT) || 5173;

/**
 * 学习通 Cookie 助手脚本（单一来源，不做复制，避免两份脚本版本漂移）。
 * 由前端直接提供，安装助手就不必依赖 1003 的 Python 登录服务是否已启动。
 */
const HELPER_SCRIPT = path.resolve(__dirname, '..', 'api', 'xxt', 'script.user.js');

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.ico': 'image/x-icon',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.txt': 'text/plain; charset=utf-8',
};

function sendFile(res, filePath, status = 200) {
    fs.readFile(filePath, (err, buf) => {
        if (err) {
            res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('500 读取文件失败');
            return;
        }
        res.writeHead(status, {
            'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
            'Cache-Control': 'no-cache',
        });
        res.end(buf);
    });
}

const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    const safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
    let filePath = path.join(ROOT, safePath);

    if (urlPath === '/script.user.js') {
        sendFile(res, HELPER_SCRIPT);
        return;
    }

    if (urlPath === '/' || urlPath === '') filePath = path.join(ROOT, 'index.html');

    fs.stat(filePath, (err, stat) => {
        if (!err && stat.isFile()) {
            sendFile(res, filePath);
            return;
        }
        // SPA 回退：仅当请求不带扩展名时返回 index.html
        if (path.extname(urlPath) === '') {
            sendFile(res, path.join(ROOT, 'index.html'));
            return;
        }
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('404 资源不存在');
    });
});

server.listen(PORT, () => {
    console.log(`\n  作业聚合平台 · 前端已启动`);
    console.log(`  本地访问： http://localhost:${PORT}`);
    console.log(`  后端地址： http://localhost:8080 （需先启动 StudentApplication）\n`);
});
