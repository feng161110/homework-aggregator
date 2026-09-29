<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { resolveAvatarUrl } from '../api/http';
import { icons } from '../ui/icons';
import { toast } from '../ui/toast';
import { formatTime, initialOf } from '../ui/validate';
import { CRED_STEPS, usePlatformCredential } from '../composables/usePlatformCredential';

const props = defineProps({
    platform: { type: Object, required: true },
});

const router = useRouter();
/**
 * 卡片不常驻探测助手脚本：只在真正需要自动重登时同步探测一次，
 * 避免三张卡各起一个 3 秒轮询。
 */
const cred = usePlatformCredential(props.platform);

/** 校验结果里带出的实时身份，只在内存里用于展示 */
const identity = computed(() => {
    const result = cred.lastCheck.value;
    if (!result) return { name: '', avatar: '', userinfo: null };
    return { name: result.name || '', avatar: result.avatar || '', userinfo: result.userinfo || null };
});
const avatarBroken = ref(false);

const STATE_TEXT = {
    loading: '读取中',
    checking: '校验中',
    relogining: '登录中',
    ready: '有效',
    expired: '待处理',
    error: '异常',
    offline: '离线',
};
const stateLabel = computed(() => STATE_TEXT[cred.phase.value] || '未知');
const stateClass = computed(() => {
    if (cred.phase.value === 'ready') return 'ok';
    if (cred.phase.value === 'checking' || cred.phase.value === 'relogining') return 'run';
    if (cred.phase.value === 'offline' || cred.phase.value === 'error') return 'bad';
    return 'warn';
});

const displayName = computed(() => identity.value.name || cred.account.value || props.platform.name);
const avatarText = computed(() => initialOf(displayName.value));
const avatarUrl = computed(() => resolveAvatarUrl(identity.value.avatar));
/** 学习通只读取浏览器里的 Cookie，拿不到登录账号，需要在详情页填写一次 */
const accountText = computed(() => cred.account.value || (cred.isHelperPlatform ? '未填写' : '—'));
const cookieStatusText = computed(() => {
    if (!cred.bound.value) return '尚未绑定';
    return cred.valid.value ? 'Cookie 有效' : 'Cookie 已失效';
});
/**
 * 平台返回的补充信息，各平台形状不同：
 *   雨课堂 userinfo.data[0] → school_number / name / avatar
 *   其余平台直接放在 userinfo 顶层
 */
const extra = computed(() => {
    const info = identity.value.userinfo;
    if (!info) return null;
    const first = Array.isArray(info.data) ? (info.data[0] || {}) : info;
    if (first.school_number) return { label: '学号', value: String(first.school_number) };
    const classroom = first.class_name || first.term;
    if (classroom) return { label: '课堂', value: String(classroom) };
    return null;
});

/** 凭证来源：password（账号密码）/ plugin（浏览器助手）/ manual（手动粘贴） */
const SOURCE_TEXT = { password: '账号密码登录', plugin: '浏览器助手', manual: '手动粘贴' };
const sourceLabel = computed(() => SOURCE_TEXT[cred.detail.value?.cookieSource] || '—');
const lastChecked = computed(() => formatTime(cred.detail.value?.lastCheckedAt));

/** 头像地址变了（或加载失败后换了一个）时重新允许尝试加载 */
watch(avatarUrl, () => { avatarBroken.value = false; });

function openDetail() {
    router.push({ path: '/platform', query: { key: props.platform.key } });
}

/**
 * 未配置时跳去配置页。
 * 已配置的卡片不再提供「校验并自动重登」按钮：进页面（含刷新）时
 * 状态机本来就会校验一次，失效就地自动重登，不需要额外的手动入口。
 */
function goConfig() {
    toast(`${props.platform.name}还没有配置账号，先登录一次`, 'info');
    openDetail();
}

// 进页面即校验；Cookie 失效时 ensureValid 会当场完成自动重登
onMounted(() => cred.ensureValid({ silent: true }));
</script>

<template>
    <article class="pcard">
        <header class="pcard-head">
            <span class="pcard-logo" v-html="platform.icon"></span>
            <div class="pcard-id">
                <h2>{{ platform.name }}</h2>
                <span>{{ platform.desc }}</span>
            </div>
            <span class="pcard-state" :class="stateClass">{{ stateLabel }}</span>
        </header>

        <div class="pcard-body">
            <div class="pcard-profile">
                <div class="pcard-avatar">
                    <img v-if="avatarUrl && !avatarBroken" :src="avatarUrl" alt=""
                         referrerpolicy="no-referrer" @error="avatarBroken = true" />
                    <template v-else>{{ avatarText }}</template>
                </div>
                <div class="pcard-rows">
                    <div class="pcard-row">
                        <span class="k">姓名</span><span class="v">{{ displayName }}</span>
                    </div>
                    <div class="pcard-row">
                        <span class="k">账号</span><span class="v">{{ accountText }}</span>
                    </div>
                    <div class="pcard-row">
                        <span class="k">凭证</span>
                        <span class="v">
                            {{ cookieStatusText }}<template v-if="cred.bound.value"> · {{ cred.cookieCount.value }} 项</template>
                        </span>
                    </div>
                    <div v-if="extra" class="pcard-row">
                        <span class="k">{{ extra.label }}</span><span class="v">{{ extra.value }}</span>
                    </div>
                    <div v-if="cred.bound.value" class="pcard-row">
                        <span class="k">来源</span><span class="v">{{ sourceLabel }}</span>
                    </div>
                    <div v-if="cred.bound.value" class="pcard-row">
                        <span class="k">最近校验</span><span class="v">{{ lastChecked }}</span>
                    </div>
                </div>
            </div>
        </div>

        <ol v-if="cred.showRail.value" class="cred-rail">
            <li v-for="(label, i) in CRED_STEPS" :key="label" :class="cred.stepStates.value[i]">
                <span class="rail-dot"></span>
                <span class="rail-label">{{ label }}</span>
            </li>
        </ol>
        <p class="cred-note" :class="stateClass">{{ cred.message.value }}</p>

        <footer class="pcard-foot">
            <button v-if="cred.needConfig.value" type="button" class="pcard-act" @click="goConfig">
                <span v-html="icons.user"></span> 去登录
            </button>
            <button type="button" class="pcard-act"
                    :class="{ ghost: cred.needConfig.value }" @click="openDetail">
                查看详情
            </button>
        </footer>
    </article>
</template>
