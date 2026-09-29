/** 三个平台的展示配置（图标 / 名称 / 官网） */
import { icons } from './icons';

export const PLATFORMS = [
    { key: 'yuketang', name: '雨课堂', desc: '长江雨课堂', icon: icons.logoYuketang, url: 'https://www.yuketang.cn' },
    { key: 'zhihuishu', name: '智慧树', desc: '智慧树网', icon: icons.logoZhihuishu, url: 'https://www.zhihuishu.com' },
    { key: 'xuexitong', name: '学习通', desc: '超星学习通', icon: icons.logoXuexitong, url: 'https://i.chaoxing.com' },
];

export const PLATFORM_KEYS = PLATFORMS.map((p) => p.key);

export function findPlatform(key) {
    return PLATFORMS.find((p) => p.key === key) || null;
}
