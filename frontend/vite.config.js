import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('.', import.meta.url));

/**
 * 后端入口。所有后端请求都汇聚到两个前缀：
 *   /api      业务接口 + 平台登录服务
 *   /uploads  上传的头像等静态文件
 * 前端因此不需要知道任何端口，也不会产生跨域。
 *
 * 默认直连 Student 服务（8081）：本地只跑「MySQL + 后端 + 前端」即可，无需启动网关。
 * 代价：platformApi.login 用到的 /api/zhs、/api/ykt、/api/xxt 前缀由网关负责分发，
 * 直连 8081 时首次「用账号密码绑定平台」会 404；已绑定平台的课程/作业查看不受影响
 * （那些走 /api/platform/**，由 Student 内部转发给 Python 服务）。
 *
 * 需要完整网关链路时，用环境变量覆盖：
 *   $env:VITE_BACKEND='http://localhost:8080'; npm run dev
 */
const BACKEND = process.env.VITE_BACKEND || 'http://localhost:8081';

/** dev 与 preview 共用同一份代理规则 */
const proxy = {
    '/api': { target: BACKEND, changeOrigin: true },
    '/uploads': { target: BACKEND, changeOrigin: true },
};

/**
 * 学习通助手脚本与后端共用同一份文件（api/xxt/script.user.js），
 * 不做复制，避免两份脚本版本漂移。
 */
const HELPER_SCRIPT = resolve(ROOT, '../api/xxt/script.user.js');

function helperScriptPlugin() {
    return {
        name: 'serve-xxt-helper-script',
        configureServer(server) {
            server.middlewares.use('/script.user.js', (_req, res) => {
                if (!existsSync(HELPER_SCRIPT)) {
                    res.statusCode = 404;
                    res.end('script.user.js 不存在');
                    return;
                }
                res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
                res.setHeader('Cache-Control', 'no-cache');
                createReadStream(HELPER_SCRIPT).pipe(res);
            });
        },
        generateBundle() {
            if (existsSync(HELPER_SCRIPT)) {
                this.emitFile({
                    type: 'asset',
                    fileName: 'script.user.js',
                    source: readFileSync(HELPER_SCRIPT, 'utf-8'),
                });
            }
        },
    };
}

export default defineConfig({
    plugins: [vue(), helperScriptPlugin()],
    server: {
        host: true,  // 监听 0.0.0.0，允许手机等局域网设备通过 http://<本机IP>:5173 访问
        port: 5173,
        strictPort: true,
        proxy,
    },
    preview: {
        host: true,
        port: 5173,
        strictPort: true,
        proxy,
    },
    build: {
        outDir: 'dist',
        sourcemap: false,
        chunkSizeWarningLimit: 900,
    },
});
