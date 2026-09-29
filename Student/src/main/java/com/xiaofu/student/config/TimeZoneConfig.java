//锁定 JVM 默认时区
package com.xiaofu.student.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 把 JVM 默认时区锁到 app.remind.zone-id（Asia/Shanghai）。
 *
 * <p>作业提醒的 scheduled_time 是 {@code LocalDateTime}（不带时区），前端选的本地时间直接入库；
 * 如果 JVM 跑在 UTC 容器里，{@code LocalDateTime.now()} 会比北京时间早 8 小时，
 * 任务就会全部提前 8 小时发出。原项目在 PHP 里用 date_default_timezone_set 做了同一件事。
 *
 * <p>用 {@code Instant} 存时间的既有业务（登录令牌、验证码有效期等）不受影响 ——
 * Instant 本身与时区无关，Jackson 也始终按 UTC 序列化。
 */
@Configuration
@RequiredArgsConstructor
public class TimeZoneConfig {

    private final AppProperties appProperties;

    @PostConstruct
    void lockTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(appProperties.getRemind().getZoneId())));
    }
}
