//审计日志（写入 AUDIT logger，账号脱敏）
package com.xiaofu.student.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void log(String event, Long userId, String account, String ip, String result) {
        AUDIT.info("event={} userId={} account={} ip={} result={}",
                event,
                userId == null ? "-" : userId,
                maskAccount(account),
                ip == null || ip.isBlank() ? "-" : ip,
                result);
    }

    public static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return "-";
        }
        String value = account.trim();
        if (value.contains("@")) {
            return maskEmail(value);
        }
        if (value.length() <= 2) {
            return value.charAt(0) + "***";
        }
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String name = email.substring(0, at);
        String masked = name.length() <= 1 ? name : name.charAt(0) + "***";
        return masked + email.substring(at);
    }
}
