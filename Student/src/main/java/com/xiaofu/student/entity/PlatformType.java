//支持的第三方平台
package com.xiaofu.student.entity;

import lombok.Getter;

import java.util.Optional;

/**
 * 三个第三方平台的枚举。
 * key 与前端 / 数据库 platform 字段一一对应。
 */
@Getter
public enum PlatformType {

    ZHIHUISHU("zhihuishu", "智慧树"),
    YUKETANG("yuketang", "雨课堂"),
    XUEXITONG("xuexitong", "学习通");

    private final String key;
    private final String displayName;

    PlatformType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public static Optional<PlatformType> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase();
        for (PlatformType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
