<script setup>
/**
 * 作业 / 考试的标准卡片：网格排版与看板分组排版共用。
 * 只负责渲染，时间相关的计算全部来自 ui/homework.js，now 由父级统一驱动。
 */
import { icons } from '../ui/icons';
import {
    formatDateTime,
    hasLink,
    isExpired,
    kindOf,
    kindText,
    linkClass,
    linkText,
    openItem,
    platformName,
    remainOf,
    rowTitle,
    statusClass,
    statusText,
} from '../ui/homework';

defineProps({
    item: { type: Object, required: true },
    /** 当前时间戳（秒级刷新由父级统一驱动，避免每张卡片各起一个定时器） */
    now: { type: Number, required: true },
    /** 「估算」说明文案 */
    estimateHint: { type: String, default: '' },
});
</script>

<template>
    <article class="hw-card" :class="{ over: isExpired(item, now), off: !hasLink(item) }">
        <button type="button" class="hw-card-main" :title="rowTitle(item, now)"
                @click="openItem(item, now)">
            <!-- 顶行：类型 + 平台 …… 状态 -->
            <span class="hw-card-top">
                <span class="hw-kind" :class="`kind-${kindOf(item)}`">{{ kindText(item) }}</span>
                <span class="hw-plat" :class="item.platform">{{ platformName(item.platform) }}</span>
                <span class="hw-tag" :class="statusClass(item)">{{ statusText(item) }}</span>
            </span>

            <strong class="hw-card-title">{{ item.title || '未命名条目' }}</strong>

            <!-- 剩余时间：秒级实时跳动 -->
            <span v-if="remainOf(item, now) || isExpired(item, now)" class="hw-card-remain"
                  :class="{ urgent: item.endTime && item.endTime - now < 86400000, over: isExpired(item, now) }"
                  :title="item.estimated ? estimateHint : ''">
                {{ remainOf(item, now) || '已过期' }}
            </span>

            <!-- 起止时间 -->
            <span class="hw-card-meta">
                <span v-if="item.courseName" class="hw-course" :title="item.courseName">
                    {{ item.courseName }}
                </span>
                <span v-if="item.startTime" class="hw-time">
                    开始 {{ formatDateTime(item.startTime) }}
                </span>
                <span v-if="item.endTime" class="hw-time">
                    截止 {{ formatDateTime(item.endTime) }}<span v-if="item.estimated" class="hw-est"
                        :title="estimateHint">估算</span>
                </span>
                <span v-if="!item.startTime && !item.endTime" class="hw-time">平台未提供时间信息</span>
            </span>

            <span class="hw-card-foot">
                <span class="hw-tag" :class="linkClass(item, now)">
                    <span v-if="hasLink(item)" class="hw-tag-ic"
                          v-html="icons.external"></span>{{ linkText(item, now) }}
                </span>
            </span>
        </button>
    </article>
</template>
