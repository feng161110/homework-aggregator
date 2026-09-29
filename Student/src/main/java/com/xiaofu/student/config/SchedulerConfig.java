//定时任务开关
package com.xiaofu.student.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 @Scheduled 支持（作业提醒的自动发信靠它）。
 * 放在配置类里而不是启动类上，免得以后想关掉自动发信还得改主类。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
