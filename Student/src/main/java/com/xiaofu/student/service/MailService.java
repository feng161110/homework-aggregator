package com.xiaofu.student.service;

import com.xiaofu.student.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    public MailService(JavaMailSender mailSender, AppProperties appProperties) {
        this.mailSender = mailSender;
        this.appProperties = appProperties;
    }

    /**
     * 发送邮箱验证码（6 位数字），邮件内容为数字框展示的 HTML 模板。
     */
    @Async("mailExecutor")
    public void sendVerificationEmail(String to, String username, String code) {
        String html = buildCodeHtml(code, appProperties.getSecurity().getVerificationCodeTtlMinutes());
        send(to, "【作业聚合平台】邮箱验证码", html, "验证码=" + code);
    }

    /**
     * 发送「重置密码」验证码邮件：用户在前端直接输入验证码即可设置新密码。
     */
    @Async("mailExecutor")
    public void sendPasswordResetCodeEmail(String to, String username, String code) {
        String html = buildCodeHtml(code, appProperties.getSecurity().getResetCodeTtlMinutes());
        send(to, "【作业聚合平台】重置密码验证码", html, "重置密码验证码=" + code);
    }

    @Async("mailExecutor")
    public void sendPasswordResetEmail(String to, String username, String token, String frontendOrigin) {
        String link = resetLink(token, frontendOrigin);
        int ttlMinutes = appProperties.getSecurity().getResetTokenTtlMinutes();
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
                  <h2>重置密码</h2>
                  <p>%s，您好：</p>
                  <p>我们收到了您的密码重置请求，请在 %d 分钟内点击以下链接设置新密码：</p>
                  <p><a href="%s">重置密码</a></p>
                  <p>如果按钮无法点击，请复制以下链接在浏览器中打开：</p>
                  <p style="word-break:break-all;color:#666">%s</p>
                  <p>如果这不是您的操作，请忽略本邮件，您的密码不会被修改。</p>
                </div>
                """.formatted(username, ttlMinutes, link, link);
        send(to, "【作业聚合平台】重置密码", html, link);
    }

    /**
     * 生成重置密码链接。
     * 优先使用触发「忘记密码」请求的前端地址（frontendOrigin），
     * 避免配置里的 localhost 链接在手机等其它设备上无法打开；
     * 没有来源信息时回退到 app.mail.base-url 配置。
     * 前端改用 history 路由，链接直接指向 /reset-password?token=xxx；
     * 因此静态服务器需要把未匹配的路径回退到 index.html（Vite dev / preview 已内置该行为，
     * 其它静态服务器需自行配置 access fallback）。
     */
    private String resetLink(String token, String frontendOrigin) {
        String base = frontendOrigin;
        if (base == null || base.isBlank()) {
            base = appProperties.getMail().getBaseUrl();
        }
        if (base == null || base.isBlank()) {
            base = "http://localhost:5173";
        }
        base = base.replaceAll("/+$", "");
        return base + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /**
     * 生成验证码邮件 HTML：将 6 位验证码渲染为独立的数字框。
     * 模板来源 qq.py，{@code __CODE_DIGITS__} 为验证码占位符，{@code __VALID_TEXT__} 为有效期占位符。
     */
    private String buildCodeHtml(String code, int ttlMinutes) {
        String digitStyle =
                "width:60px;height:66px;display:flex;align-items:center;justify-content:center;"
                        + "font-size:38px;font-weight:800;color:#2c2c2c;"
                        + "font-family:'SF Mono','Courier New',monospace;"
                        + "background-color:rgba(255,255,255,0.82);border:2.5px solid #c9a77b;border-radius:16px;"
                        + "box-shadow:0 4px 12px rgba(0,0,0,0.08);text-shadow:0 1px 3px rgba(255,255,255,0.7);"
                        + "flex-shrink:0;letter-spacing:0;line-height:1;";

        StringBuilder codeDigits = new StringBuilder();
        for (char digit : code.toCharArray()) {
            codeDigits.append("<div class=\"code-box\" style=\"").append(digitStyle).append("\">")
                    .append(digit).append("</div>");
        }

        String htmlTemplate = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>验证码</title>
                    <style>
                        @media screen and (max-width: 480px) {
                            .code-box {
                                width: 44px !important;
                                height: 50px !important;
                                font-size: 27px !important;
                                font-weight: 800 !important;
                                border-radius: 10px !important;
                                border-width: 2px !important;
                                background-color: rgba(255, 255, 255, 0.85) !important;
                            }
                            .code-gap {
                                gap: 6px !important;
                            }
                            .card-padding {
                                padding: 28px 16px 22px !important;
                            }
                            .outer-padding {
                                padding: 40px 12px 30px !important;
                            }
                            .card-radius {
                                border-radius: 24px !important;
                            }
                            .expire-text {
                                font-size: 15px !important;
                            }
                            .footer-note {
                                font-size: 11px !important;
                                padding: 5px 14px !important;
                            }
                        }

                        @media screen and (min-width: 481px) {
                            .code-box {
                                width: 60px;
                                height: 66px;
                                font-size: 38px;
                                font-weight: 800;
                                border-radius: 16px;
                                border-width: 2.5px;
                                background-color: rgba(255, 255, 255, 0.82);
                            }
                            .code-gap {
                                gap: 10px;
                            }
                            .card-padding {
                                padding: 42px 24px 34px;
                            }
                            .outer-padding {
                                padding: 80px 16px 40px;
                            }
                            .card-radius {
                                border-radius: 32px;
                            }
                            .expire-text {
                                font-size: 17px;
                            }
                            .footer-note {
                                font-size: 12px;
                                padding: 7px 24px;
                            }
                        }
                    </style>
                </head>
                <body style="margin:0; padding:0; background-color:#f5efe8; font-family:'Comic Sans MS','Chalkboard SE','PingFang SC','Microsoft YaHei',sans-serif;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="background-color:#f5efe8;
                    background-image:url('http://39.104.16.224/pictures/background.jpeg');
                    background-size:cover;
                    background-position:center;
                    background-repeat:no-repeat;
                    min-height:100vh;">
                    <tr>
                        <td align="center" class="outer-padding" style="padding:80px 16px 40px;">

                            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                                   style="max-width:480px; width:100%;
                            background-color:transparent;
                            border-radius:32px;
                            border:1px solid rgba(255,255,255,0.12);">
                                <tr>
                                    <td class="card-padding" style="padding:42px 24px 34px; text-align:center;">

                                        <div class="code-gap" style="display:flex; justify-content:center; align-items:center; gap:10px; margin-top:24px; margin-bottom:28px; flex-wrap:nowrap;">

                                            __CODE_DIGITS__

                                        </div>

                                        <p class="expire-text" style="margin:0 0 8px; font-size:17px; color:#4a4a4a; line-height:1.6; font-weight:500; text-shadow:0 1px 4px rgba(255,255,255,0.5);">
                                            验证码 <strong style="color:#e5484d; font-weight:800; background:transparent; padding:0 10px; border-radius:20px;">__VALID_TEXT__</strong> 内有效
                                        </p>
                                        <p style="margin:0 0 22px; font-size:15px; color:#b0a8a0; line-height:1.5; text-shadow:0 1px 4px rgba(255,255,255,0.5);">
                                            🔒 请勿向任何人泄露此验证码
                                        </p>
                                    </td>
                                </tr>
                            </table>

                            <p class="footer-note" style="margin-top:24px; font-size:12px; color:rgba(255,255,255,0.75); text-shadow:0 1px 4px rgba(0,0,0,0.15); letter-spacing:0.5px; background:rgba(0,0,0,0.1); display:inline-block; padding:7px 24px; border-radius:30px; backdrop-filter:blur(2px);">
                                📧 此邮件由系统自动发送，请勿回复
                            </p>

                        </td>
                    </tr>
                </table>
                </body>
                </html>""";

        return htmlTemplate.replace("__CODE_DIGITS__", codeDigits.toString())
                .replace("__VALID_TEXT__", ttlMinutes + " 分钟");
    }

    /**
     * 发送一封自定义邮件（作业提醒 / 自定义提醒任务用）。
     *
     * <p>与验证码邮件不同，这里要如实返回成功与否 —— 任务执行状态机靠它决定
     * 标记「已发送」还是「发送失败」。开发模式（app.mail.enabled=false）下不真发，
     * 但按成功返回，否则本地一联调所有任务都会变成发送失败。
     *
     * @param content 正文，html=true 时按 HTML 发送
     * @return 是否发送成功
     */
    public boolean sendCustom(String to, String subject, String content, boolean html) {
        if (!appProperties.getMail().isEnabled()) {
            log.warn("邮件服务未启用（app.mail.enabled=false），跳过发送。to={}, subject={}",
                    AuditLogger.maskEmail(to), subject);
            return true;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(appProperties.getMail().getFrom());
            helper.setSubject(subject);
            helper.setText(content, html);
            mailSender.send(message);
            log.info("提醒邮件发送成功：to={}, subject={}", AuditLogger.maskEmail(to), subject);
            return true;
        } catch (Exception e) {
            log.error("提醒邮件发送失败：to={}, subject={}, error={}",
                    AuditLogger.maskEmail(to), subject, e.getMessage(), e);
            return false;
        }
    }

    private void send(String to, String subject, String html, String devInfo) {
        if (!appProperties.getMail().isEnabled()) {
            // 开发模式：不发真实邮件，把验证码/链接输出到日志便于联调（生产开启 mail.enabled 后不会输出）
            log.warn("邮件服务未启用（app.mail.enabled=false），跳过发送。to={}，开发调试信息={}",
                    AuditLogger.maskEmail(to), devInfo);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(appProperties.getMail().getFrom());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("邮件发送成功：to={}, subject={}", AuditLogger.maskEmail(to), subject);
        } catch (Exception e) {
            log.error("邮件发送失败：to={}, subject={}, error={}", AuditLogger.maskEmail(to), subject, e.getMessage(), e);
        }
    }
}
