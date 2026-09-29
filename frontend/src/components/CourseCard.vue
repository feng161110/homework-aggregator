<script setup>
/**
 * 课程卡片：学习通 / 雨课堂 / 智慧树共用，外观全部由 styles/style.css 的 .course-* 决定。
 * 传入 platform 时在卡片右上角显示平台角标（汇总页用），点击会 emit('pick-platform')。
 * 右上角还有一个置顶开关，点击 emit('toggle-pin')，置顶状态由数据持有方持久化。
 * 图片加载失败时 emit('image-error', 字段名)，由数据持有方清字段，组件自身不改 props。
 */
import { computed } from 'vue';
import { resolveAvatarUrl } from '../api/http';
import { icons } from '../ui/icons';
import { findPlatform } from '../ui/platforms';
import { toast } from '../ui/toast';

const props = defineProps({
    course: { type: Object, required: true },
    /** 平台 key；为空则不显示平台角标 */
    platform: { type: String, default: '' },
    /** 是否已置顶 */
    pinned: { type: Boolean, default: false },
});

const emit = defineEmits(['pick-platform', 'image-error', 'toggle-pin']);

const platformInfo = computed(() => (props.platform ? findPlatform(props.platform) : null));

/**
 * 只接受第三方绝对地址；页面跑在 https 下时把 http 图片升级为 https，
 * 避免混合内容被浏览器拦掉（拿到后还需要 no-referrer，绕过图床的防盗链）。
 */
function normalizeUrl(raw) {
    const url = resolveAvatarUrl(raw);
    if (url.startsWith('http://') && window.location.protocol === 'https:') {
        return `https://${url.slice('http://'.length)}`;
    }
    return url;
}

/** 课程封面（学习通 / 智慧树） */
const cover = computed(() => normalizeUrl(props.course.coverUrl));
/** 教师头像（雨课堂），显示在教师名字左边 */
const avatar = computed(() => normalizeUrl(props.course.teacherAvatar));
/** 学校校徽（雨课堂），直接放在卡片右侧 */
const schoolLogo = computed(() => normalizeUrl(props.course.schoolLogo));

const ended = computed(() => props.course.status === 'ended');
const pages = computed(() => props.course.pages || []);

/** 副标题：接口给了 description 就用它，否则按 学校 · 学期 · 人数 拼 */
const meta = computed(() => {
    const course = props.course;
    if (course.description) {
        return course.description;
    }
    const parts = [];
    if (course.schoolName) {
        parts.push(course.schoolName);
    }
    if (course.term) {
        parts.push(course.term);
    }
    if (course.studentsCount != null) {
        parts.push(`${course.studentsCount} 人`);
    }
    return parts.join(' · ');
});

/** 学习进度百分比（智慧树），收敛到 0-100 */
const progress = computed(() => {
    const value = Number(props.course.progress);
    if (!Number.isFinite(value)) {
        return 0;
    }
    return Math.min(100, Math.max(0, Math.round(value)));
});

/** 在新标签页打开对应入口 */
function open(url) {
    if (!url) {
        toast('该入口暂不可用', 'info');
        return;
    }
    window.open(url, '_blank', 'noopener,noreferrer');
}
</script>

<template>
    <article class="course-card" :class="{ ended, bare: !cover, pinned }">
        <!-- 右上角：平台角标（汇总页，点一下只看该平台）+ 置顶开关 -->
        <div class="course-corner">
            <button v-if="platformInfo" type="button" class="course-plat"
                    :title="`只看${platformInfo.name}课程`"
                    @click="emit('pick-platform', platformInfo.key)">
                <span v-html="platformInfo.icon"></span>{{ platformInfo.name }}
            </button>

            <button type="button" class="course-pin" :class="{ on: pinned }"
                    :aria-pressed="pinned" :aria-label="pinned ? '取消置顶' : '置顶'"
                    :title="pinned ? '取消置顶' : '置顶'"
                    @click="emit('toggle-pin')">
                <span v-html="icons.pin"></span>
            </button>
        </div>

        <button type="button" class="course-main"
                :title="`打开「${course.courseName || '未命名课程'}」`"
                @click="open(course.url)">
            <!-- 左：课程封面（学习通 / 智慧树） -->
            <span v-if="cover" class="course-lead">
                <img :src="cover" alt="" loading="lazy" referrerpolicy="no-referrer"
                     @error="emit('image-error', 'coverUrl')" />
                <span v-if="course.statusText" class="course-state" :class="{ ended }">
                    {{ course.statusText }}
                </span>
            </span>

            <span class="course-body">
                <strong class="course-name">{{ course.courseName || '未命名课程' }}</strong>

                <!-- 教师行：仅有真实头像时才在名字左边显示（雨课堂） -->
                <span class="course-teacher">
                    <img v-if="avatar" class="course-avatar" :src="avatar" alt="" loading="lazy"
                         referrerpolicy="no-referrer" @error="emit('image-error', 'teacherAvatar')" />
                    {{ course.teacher || '未知教师' }}
                </span>

                <span v-if="meta" class="course-desc">{{ meta }}</span>

                <!-- 学习进度（智慧树） -->
                <span v-if="course.progress != null" class="course-progress">
                    <span class="track">
                        <span class="fill" :style="{ width: `${progress}%` }"></span>
                    </span>
                    <span class="pct">{{ progress }}%</span>
                </span>
            </span>

            <!-- 右：校徽（雨课堂）—— 图片直接放在卡片右侧，不套容器 -->
            <img v-if="schoolLogo" class="course-logo" :src="schoolLogo" alt="" loading="lazy"
                 referrerpolicy="no-referrer" :title="course.schoolName || '学校'"
                 @error="emit('image-error', 'schoolLogo')" />
        </button>

        <div v-if="pages.length" class="course-pages">
            <button v-for="page in pages" :key="page.key" type="button" class="course-page"
                    :title="`进入「${page.title}」`" @click="open(page.url)">
                {{ page.title }}
            </button>
        </div>
    </article>
</template>
