//登录设备识别工具
package com.xiaofu.student.util;

import java.util.Locale;

/**
 * 从 User-Agent 解析设备名称，供「登录设备管理」展示。
 * 只做轻量识别，无法识别时返回兜底文案，不抛异常。
 */
public final class DeviceUtils {

    /** 设备名称最大长度，与 refresh_tokens.device_name 字段保持一致。 */
    private static final int MAX_DEVICE_NAME_LENGTH = 128;

    private static final String UNKNOWN_DEVICE = "未知设备";

    private DeviceUtils() {
    }

    /**
     * 解析设备名称，形如「Chrome · Windows」。
     *
     * @param userAgent 请求头 User-Agent，可为空
     * @return 可读的设备名称，兜底为「未知设备」
     */
    public static String parseDeviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_DEVICE;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String os = parseOperatingSystem(ua);
        String client = parseClient(ua);
        String name = client.isEmpty() ? os : client + " · " + os;
        return truncate(name, MAX_DEVICE_NAME_LENGTH);
    }

    private static String parseOperatingSystem(String ua) {
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("iphone")) {
            return "iPhone";
        }
        if (ua.contains("ipad")) {
            return "iPad";
        }
        if (ua.contains("harmony") || ua.contains("hongmeng")) {
            return "HarmonyOS";
        }
        if (ua.contains("android")) {
            // Android 平板通常不带 Mobile 关键字
            return ua.contains("mobile") ? "Android 手机" : "Android 平板";
        }
        if (ua.contains("mac os") || ua.contains("macintosh")) {
            return "Mac";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return "未知系统";
    }

    /**
     * 识别浏览器/客户端。判断顺序按「更具体的标识优先」排列：
     * 微信内置浏览器与 Edge 的 UA 同时包含 Chrome / Safari 标记，必须先判断。
     */
    private static String parseClient(String ua) {
        if (ua.contains("micromessenger")) {
            return "微信";
        }
        if (ua.contains("edg")) {
            return "Edge";
        }
        if (ua.contains("opr") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox")) {
            return "Firefox";
        }
        if (ua.contains("chrome") || ua.contains("crios")) {
            return "Chrome";
        }
        if (ua.contains("safari")) {
            return "Safari";
        }
        if (ua.contains("curl") || ua.contains("postman") || ua.contains("okhttp")) {
            return "客户端";
        }
        return "";
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
