<script setup>
/**
 * 未登录流程（登录 / 注册 / 验证 / 找回密码）统一的外壳：
 * 深色封面（标题 + 可选平台标识）+ 米色表单区，卡片自带一次入场编排。
 */
import { PLATFORMS } from './platforms';

defineProps({
    /** 封面大标题，如「登录」「注册」「找回密码」 */
    title: { type: String, required: true },
    /** 是否在封面展示三个平台标识（登录、注册这类入口页用） */
    marks: { type: Boolean, default: false },
});
</script>

<template>
    <div class="auth-shell">
        <section class="card auth-card">
            <header class="auth-cover">
                <span class="cover-rule" aria-hidden="true"></span>

                <ul v-if="marks" class="cover-marks">
                    <li v-for="p in PLATFORMS" :key="p.key" :class="`is-${p.key}`">
                        <span class="mark" v-html="p.icon" aria-hidden="true"></span>
                        <span>{{ p.name }}</span>
                    </li>
                </ul>

                <h1>{{ title }}</h1>
                <!-- 副标题用插槽而非 v-html：各页面会往里塞 <strong> 邮箱，避免注入风险 -->
                <p v-if="$slots.subtitle" class="cover-sub"><slot name="subtitle" /></p>
            </header>

            <div class="auth-body">
                <slot />
            </div>
        </section>
    </div>
</template>

<style scoped>
/* 在顶栏与页脚之间居中，并给上下留出对称的呼吸空间 */
.auth-shell {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    padding-block: clamp(10px, 2.4vh, 34px);
}

.auth-card {
    position: relative;
    width: min(500px, 94vw);
    /* 内边距交给封面与表单两段各自承担，卡片只负责裁剪与圆角 */
    padding: 0;
    /* 必须裁剪，封面顶部才跟随卡片圆角；因此光环不能挂在伪元素上，只能改卡片自身的阴影 */
    overflow: hidden;
    animation:
        authCardIn 0.62s cubic-bezier(0.22, 1, 0.36, 1) both,
        haloOut 1.15s ease-out 0.16s both;
}

@keyframes authCardIn {
    from { opacity: 0; transform: translateY(26px) scale(0.975); }
    to { opacity: 1; transform: translateY(0) scale(1); }
}

/* 入场时的金色光环：只播一次，把「卡片出现」这个时刻交代清楚。
   每一帧都叠上卡片基础阴影，动画结束与原样式无缝衔接 */
@keyframes haloOut {
    from { box-shadow: 0 0 0 0 rgba(201, 167, 123, 0.62), 0 0 30px 0 rgba(201, 167, 123, 0.45), var(--shadow-card); }
    to { box-shadow: 0 0 0 18px rgba(201, 167, 123, 0), 0 0 0 0 rgba(201, 167, 123, 0), var(--shadow-card); }
}

/* ---------------- 封面 ---------------- */
.auth-cover {
    position: relative;
    padding: clamp(30px, 4.6vw, 42px) clamp(22px, 3.6vw, 36px) clamp(26px, 3.6vw, 34px);
    text-align: center;
    background:
        radial-gradient(120% 92% at 50% -24%, rgba(201, 167, 123, 0.34) 0%, transparent 62%),
        linear-gradient(158deg, #332c24 0%, #251f19 100%);
}

/* 封面底部那道金线：从中心向两侧展开，把上下两段缝合起来 */
.cover-rule {
    position: absolute;
    left: clamp(22px, 3.6vw, 36px);
    right: clamp(22px, 3.6vw, 36px);
    bottom: 0;
    height: 1px;
    background: linear-gradient(90deg, transparent, var(--gold), transparent);
    transform-origin: center;
    animation: ruleOut 0.85s cubic-bezier(0.22, 1, 0.36, 1) 0.26s both;
}

@keyframes ruleOut {
    from { transform: scaleX(0); opacity: 0; }
    to { transform: scaleX(1); opacity: 1; }
}

.cover-marks {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    margin: 0 0 22px;
    padding: 0;
    list-style: none;
}

.cover-marks li {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 6px 13px 6px 9px;
    border-radius: 999px;
    font-size: 12.5px;
    font-weight: 600;
    letter-spacing: 0.6px;
    color: rgba(247, 240, 228, 0.74);
    background: rgba(255, 255, 255, 0.06);
    border: 1px solid rgba(201, 167, 123, 0.26);
    /* 三个标识依次上浮，构成一次有序入场。
       fill-mode 用 backwards 而非 both：动画结束后要把 transform 交还给 CSS 规则，
       否则下面 hover 的 translateY 会被动画的结束状态锁死 */
    animation: markIn 0.5s cubic-bezier(0.22, 1, 0.36, 1) backwards;
    transition: color 0.22s, border-color 0.22s, background 0.22s, transform 0.22s;
}

.cover-marks li:nth-child(1) { animation-delay: 0.2s; }
.cover-marks li:nth-child(2) { animation-delay: 0.28s; }
.cover-marks li:nth-child(3) { animation-delay: 0.36s; }

@keyframes markIn {
    from { opacity: 0; transform: translateY(7px); }
    to { opacity: 1; transform: translateY(0); }
}

/* 平台 logo 本身是带品牌色填充的彩色 SVG：静态时压暗去饱和融进暖金胶囊，hover 才恢复全彩 */
.mark {
    display: inline-flex;
    line-height: 0;
    opacity: 0.6;
    filter: saturate(0.45);
    transition: opacity 0.22s, filter 0.22s;
}
.mark :deep(svg) { width: 14px; height: 14px; display: block; }

/* hover 点亮：logo 恢复品牌色，胶囊边框染上该平台主色 */
.cover-marks li:hover { transform: translateY(-1.5px); background: rgba(255, 255, 255, 0.1); }
.cover-marks li:hover .mark { opacity: 1; filter: saturate(1); }
.cover-marks li.is-yuketang:hover { border-color: rgba(108, 184, 255, 0.55); }
.cover-marks li.is-zhihuishu:hover { border-color: rgba(111, 216, 165, 0.55); }
.cover-marks li.is-xuexitong:hover { border-color: rgba(255, 171, 107, 0.55); }

.auth-cover h1 {
    margin: 0 0 10px;
    font-size: clamp(25px, 5.4vw, 31px);
    font-weight: 800;
    /* 中文标题拉开字距做出印章感；text-indent 抵消末字右侧多出的空白，维持居中 */
    letter-spacing: 0.24em;
    text-indent: 0.24em;
    color: #fdf8ef;
}

.cover-sub {
    margin: 0;
    font-size: 13.5px;
    line-height: 1.75;
    letter-spacing: 0.4px;
    color: rgba(247, 240, 228, 0.6);
}

/* 副标题里的邮箱等强调内容，在深色底上要更亮一档 */
.cover-sub :deep(strong) { color: #f3e8d5; font-weight: 700; }

/* ---------------- 表单区 ---------------- */
.auth-body {
    padding: clamp(24px, 3.6vw, 32px) clamp(22px, 3.6vw, 36px) clamp(26px, 3.8vw, 34px);
}

/* 按钮 hover 时一道柔光斜扫而过（点击前的一次确认感）。
   :deep 是必须的：按钮来自插槽里的子组件，不带本组件的 scope 标记 */
.auth-body :deep(.btn)::after {
    content: "";
    position: absolute;
    top: 0;
    bottom: 0;
    left: -60%;
    width: 42%;
    background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.42), transparent);
    transform: skewX(-18deg);
    pointer-events: none;
}

.auth-body :deep(.btn:not(:disabled):hover)::after { animation: sheen 0.85s ease-out; }

@keyframes sheen {
    from { left: -60%; }
    to { left: 118%; }
}

@media (max-width: 420px) {
    .auth-cover h1 { letter-spacing: 0.18em; text-indent: 0.18em; }
    .cover-marks { gap: 6px; }
    .cover-marks li { padding: 5px 10px 5px 8px; font-size: 11.5px; }
}

/* 尊重系统的「减少动态效果」设置：去掉所有入场与扫光，静态呈现 */
@media (prefers-reduced-motion: reduce) {
    .auth-card,
    .cover-rule,
    .cover-marks li { animation: none; }
    .auth-body :deep(.btn)::after { display: none; }
    .cover-marks li:hover { transform: none; }
}
</style>
