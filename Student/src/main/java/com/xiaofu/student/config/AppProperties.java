//应用属性配置类
package com.xiaofu.student.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Mail mail = new Mail();
    private final Security security = new Security();
    private final Cors cors = new Cors();
    private final Upload upload = new Upload();
    private final Platform platform = new Platform();
    private final Remind remind = new Remind();

    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = false;
        private String from = "作业聚合平台 <noreply@example.com>";
        private String baseUrl = "http://localhost:5173";
    }

    @Getter
    @Setter
    public static class Security {
        private int verificationCodeTtlMinutes = 5;
        private int verificationMaxAttempts = 5;
        private int resetTokenTtlMinutes = 30;
        private int resetCodeTtlMinutes = 10;
        private int resetCodeMaxAttempts = 5;
        private int loginMaxFailures = 5;
        private int loginLockMinutes = 15;
        private int emailSendIntervalSeconds = 60;
        private int emailDailyLimit = 5;

        /**
         * 登录请求 RSA 私钥（PKCS#8，Base64；也兼容 PEM 文本）。
         * 与前端 1.js 内嵌的公钥配对，用于解密登录密码。
         * 生产必须用环境变量 LOGIN_RSA_PRIVATE_KEY 覆盖。
         */
        private String loginRsaPrivateKey = "";

        /**
         * 登录账号 RC4 对称密钥，必须与前端 2.js 中的 KEY 完全一致。
         * 仅用于账号字段的传输混淆，不承担强加密职责。
         */
        private String loginAccountKey = "jmAccount@2026#39.104";

        /** 登录参数时间戳允许的时钟偏移（秒）：双向超出即返回登录超时。 */
        private int loginTimestampWindowSeconds = 10;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:3000");
    }

    @Getter
    @Setter
    public static class Upload {
        /** 上传文件根目录（相对项目运行目录）。 */
        private String dir = "uploads";
        /** 头像允许的最大体积（KB）。 */
        private long maxAvatarSizeKb = 2048;
    }

    @Getter
    @Setter
    public static class Platform {
        /** 平台密码加密密钥，生产必须用环境变量 PLATFORM_SECRET_KEY 覆盖。 */
        private String secretKey = "dev-only-platform-secret-change-me";
        /** 智慧树登录服务地址（api/zhs）。 */
        private String zhihuishuBase = "http://localhost:2001";
        /** 雨课堂登录服务地址（api/ykt）。 */
        private String yuketangBase = "http://localhost:2002";
        /** 学习通登录服务地址（api/xxt）。 */
        private String xuexitongBase = "http://localhost:2003";
        /** 连接超时（毫秒）。 */
        private int connectTimeoutMillis = 5000;
        /** 读取超时（毫秒）：平台登录常含滑块/验证码识别，需要留足时间。 */
        private int readTimeoutMillis = 60000;
    }

    @Getter
    @Setter
    public static class Remind {
        /** 定时任务的时间基准时区，必须与 datasource 的 serverTimezone 一致。 */
        private String zoneId = "Asia/Shanghai";
        /** 调度器扫描间隔（毫秒）：到点任务最多延迟这么久发出。 */
        private long scanDelayMs = 30000;
        /** 每轮最多执行多少条到期任务。 */
        private int scanBatchSize = 100;
        /** HTML 邮件正文长度上限。 */
        private int maxContentHtml = 50000;
        /** 纯文本邮件正文长度上限。 */
        private int maxContentText = 5000;
        /** 邮件主题长度上限。 */
        private int maxSubject = 200;
    }
}