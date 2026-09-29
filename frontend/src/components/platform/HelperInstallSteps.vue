<script setup>
import { computed } from 'vue';
import { icons } from '../../ui/icons';

const props = defineProps({
    /** 助手已注入页面：说明前两步（管理器 + 脚本）确实装好了 */
    helperReady: { type: Boolean, default: false },
    /** Cookie 已入库：第三步走完 */
    bound: { type: Boolean, default: false },
    pluginUrl: { type: String, required: true },
    scriptUrl: { type: String, required: true },
});

/**
 * 三步的真实进度，由状态推出来而不是写死的装饰：
 *   未注入 → 卡在第 1 步；已注入 → 前两步完成，当前该去登录；
 *   已绑定 → 三步全部走完。
 * 每一步的节点样式（虚线待办 / 金环当前 / 绿勾完成）都读这个数组。
 */
const states = computed(() => {
    if (props.bound) return ['done', 'done', 'done'];
    if (props.helperReady) return ['done', 'done', 'current'];
    return ['current', 'pending', 'pending'];
});

/**
 * 脚本只在页面加载时注入一次，「刚装完」的这次访问必然检测不到助手。
 * 刷新是最直接的重检测手段，比轮询更省事也更可靠。
 */
function reloadPage() {
    window.location.reload();
}
</script>

<template>
    <!-- 三步确实有先后顺序，所以用编号加一条贯穿到底的进度轨道 -->
    <ol class="xxt-steps">
        <li :class="states[0]">
            <span class="step-n" aria-hidden="true">
                <span v-if="states[0] === 'done'" v-html="icons.check"></span>
                <template v-else>1</template>
            </span>
            <div class="step-body">
                <strong>安装脚本管理器</strong>
                <span>
                    用 <a class="link" :href="pluginUrl" target="_blank" rel="noopener">ScriptCat</a>
                    或 Tampermonkey 浏览器扩展
                </span>
            </div>
        </li>

        <li :class="states[1]">
            <span class="step-n" aria-hidden="true">
                <span v-if="states[1] === 'done'" v-html="icons.check"></span>
                <template v-else>2</template>
            </span>
            <div class="step-body">
                <strong>安装用户脚本</strong>
                <span>装好后点「点我刷新」，助手会在页面重载后生效</span>
                <div class="step-actions">
                    <a class="btn sm" :href="scriptUrl" target="_blank" rel="noopener">安装脚本</a>
                    <button v-if="!helperReady" class="btn ghost sm" type="button" @click="reloadPage">
                        <i class="ico" aria-hidden="true" v-html="icons.refresh"></i>点我刷新
                    </button>
                </div>
            </div>
        </li>

        <li :class="states[2]">
            <span class="step-n" aria-hidden="true">
                <span v-if="states[2] === 'done'" v-html="icons.check"></span>
                <template v-else>3</template>
            </span>
            <div class="step-body">
                <strong>在浏览器里登录学习通</strong>
                <span v-if="bound" class="done-text">已完成，凭证已加密保存到本站数据库。</span>
                <span v-else-if="helperReady">登录后回到本页，点「重新获取 Cookie」同步。</span>
                <span v-else>登录后回到本页，助手就绪即可同步。</span>
            </div>
        </li>
    </ol>
</template>
