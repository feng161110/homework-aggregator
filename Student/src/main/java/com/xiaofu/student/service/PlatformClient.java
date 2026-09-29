//调用三个平台的本地登录服务（api/zhs、api/ykt、api/xxt）
package com.xiaofu.student.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaofu.student.common.BusinessException;
import com.xiaofu.student.common.ErrorCode;
import com.xiaofu.student.config.AppProperties;
import com.xiaofu.student.entity.PlatformType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把三个平台服务的请求/响应差异归一化：
 * <ul>
 *   <li>智慧树 2001：/api/login/password、/api/login/cookie</li>
 *   <li>雨课堂 2002：/api/login、/api/check_login、/api/courses</li>
 *   <li>学习通 2003：/login、/check_cookie、/desktop_links、/courses</li>
 * </ul>
 */
@Slf4j
@Service
public class PlatformClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 智慧树作业 / 考试直达地址模板，后面拼 courseId。 */
    private static final String ZHIHUISHU_WORK_URL_TEMPLATE =
            "https://wenda.zhihuishu.com/stu/courseInfo/homeworkExam/?courseId=";

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;

    public PlatformClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        AppProperties.Platform config = appProperties.getPlatform();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMillis());
        factory.setReadTimeout(config.getReadTimeoutMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /** 归一化登录结果。 */
    public record LoginResult(boolean ok, String message, String account, String name,
                              Map<String, String> cookies, Map<String, Object> extra) {
    }

    /** 归一化校验结果（userinfo 为平台返回的实时用户信息，如雨课堂的姓名/课程）。 */
    public record CheckResult(boolean valid, String message, String name, String avatar,
                              Map<String, Object> userinfo) {
    }

    /** 归一化后的作业 / 考试结果（学习通 /desktop_links、智慧树 /api/homework/reminder）。 */
    public record DesktopLinksResult(boolean ok, String message, List<CourseLinks> courses) {
    }

    /** 一门课程下的作业 / 考试链接；courseId 形如「课程ID_班级ID」，courseName 可能为空。 */
    public record CourseLinks(String courseId, String courseName, List<CourseLink> items) {
    }

    /**
     * 单条作业 / 考试。
     * url 为该条的直达地址（学习通取 desktop_url，智慧树取 link）；
     * status / statusClass / remainText 为归一化后的状态展示字段；
     * startTime / endTime 为毫秒时间戳（智慧树提供，学习通抓不到，为 null），
     * 前端据此做秒级倒计时与按剩余时间排序。
     */
    public record CourseLink(String id, String title, String type, String url, String mobileUrl,
                             Boolean expired, String status, String statusClass, String remainText,
                             Long startTime, Long endTime) {
    }

    /** 归一化后的课程列表结果（学习通 /courses）。 */
    public record CourseListResult(boolean ok, String message, List<CourseItem> courses) {
    }

    /**
     * 单门课程：url 为默认入口，pages 为各 pageHeader 入口。
     * teacherAvatar / schoolLogo / schoolName / term / studentsCount 目前由雨课堂提供；
     * schoolName / term / progress 由智慧树提供，其余平台对应字段为 null。
     */
    public record CourseItem(String courseName, String courseId, String clazzId, String url,
                             String teacher, String description, String coverUrl,
                             String status, String statusText, List<PageLinkItem> pages,
                             String teacherAvatar, String schoolLogo, String schoolName,
                             String term, Integer studentsCount, Integer progress) {
    }

    /** 课程内的一个入口（任务 / 章节 / 作业 / 考试 …）。 */
    public record PageLinkItem(String key, String title, String url) {
    }

    // ==================== 登录 ====================

    public LoginResult login(PlatformType type, String account, String password) {
        String base = baseUrl(type);
        return switch (type) {
            case ZHIHUISHU -> {
                Map<String, Object> body = post(base + "/api/login/password",
                        payload("username", account, "password", password), ErrorCode.PLATFORM_LOGIN_FAILED);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("validate", text(body.get("validate")));
                extra.put("fp", text(body.get("fp")));
                Map<String, Object> userInfo = zhihuishuUserInfo(body);
                if (userInfo != null) {
                    extra.put("uuid", text(userInfo.get("uuid")));
                }
                yield new LoginResult(
                        bool(body.get("success")),
                        message(body, "登录成功", "登录失败"),
                        firstNonBlank(text(body.get("username")), account),
                        zhihuishuRealName(body),
                        cookies(body.get("cookies")),
                        extra);
            }
            case YUKETANG -> {
                Map<String, Object> body = post(base + "/api/login",
                        payload("username", account, "password", password), ErrorCode.PLATFORM_LOGIN_FAILED);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("status_code", body.get("status_code"));
                yield new LoginResult(
                        bool(body.get("success")),
                        message(body, "登录成功", "登录失败"),
                        account,
                        null,
                        cookies(body.get("cookies")),
                        extra);
            }
            case XUEXITONG -> {
                Map<String, Object> body = post(base + "/login",
                        payload("username", account, "password", password), ErrorCode.PLATFORM_LOGIN_FAILED);
                yield new LoginResult(
                        bool(body.get("status")),
                        message(body, "登录成功", "登录失败"),
                        account,
                        text(body.get("name")),
                        cookies(body.get("cookies")),
                        null);
            }
        };
    }

    // ==================== 校验 ====================

    public CheckResult check(PlatformType type, Map<String, String> cookieMap,
                            Map<String, Object> extra, String account) {
        if (cookieMap == null || cookieMap.isEmpty()) {
            return new CheckResult(false, "尚未获取 Cookie，请先登录", null, null, null);
        }
        String base = baseUrl(type);
        return switch (type) {
            case ZHIHUISHU -> {
                Map<String, Object> body = payload(
                        "username", account == null ? "" : account,
                        "password", "",
                        "validate", extra == null ? "" : text(extra.get("validate")),
                        "fp", extra == null ? "" : text(extra.get("fp")));
                body.put("cookies", cookieMap);
                Map<String, Object> resp = post(base + "/api/login/cookie", body,
                        ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                boolean valid = bool(resp.get("success"));
                yield new CheckResult(valid, message(resp, "Cookie 有效", "Cookie 已失效"),
                        zhihuishuRealName(resp), zhihuishuAvatar(resp), zhihuishuUserInfo(resp));
            }
            case YUKETANG -> {
                Map<String, Object> resp = post(base + "/api/check_login", payload("cookies", cookieMap),
                        ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                boolean valid = bool(resp.get("logined"));
                Map<String, Object> userinfo = asMap(resp.get("userinfo"));
                Map<String, Object> top = userinfo == null ? Map.of() : userinfo;
                // 雨课堂把用户信息包在 userinfo.data[0]：{school_number, user_id, name, avatar}
                Map<String, Object> profile = firstOfData(userinfo);
                String name = firstNonBlank(text(profile.get("name")), text(profile.get("real_name")),
                        text(profile.get("nickname")),
                        text(top.get("name")), text(top.get("real_name")));
                String avatar = firstNonBlank(text(profile.get("avatar")), text(top.get("avatar")));
                yield new CheckResult(valid, valid ? "Cookie 有效" : "Cookie 已失效", name, avatar, userinfo);
            }
            case XUEXITONG -> {
                Map<String, Object> resp = post(base + "/check_cookie", payload("cookies", cookieMap),
                        ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                boolean valid = bool(resp.get("status"));
                yield new CheckResult(valid, message(resp, "Cookie 有效", "Cookie 已失效"),
                        text(resp.get("name")), text(resp.get("avatar")), null);
            }
        };
    }

    // ==================== 电脑版链接（学习通 / 智慧树） ====================

    /**
     * 取平台的作业 / 考试链接：学习通 POST /desktop_links、智慧树 POST /api/homework/reminder。
     * 两边结构不同（学习通 status + 课程字典，智慧树 success + data.rt.Reminder），
     * 统一归一化成按课程分组的 {@link CourseLinks}。
     */
    public DesktopLinksResult desktopLinks(PlatformType type, Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            throw new BusinessException(ErrorCode.PLATFORM_NOT_BOUND,
                    "尚未获取 Cookie，请先登录" + type.getDisplayName());
        }
        return switch (type) {
            case XUEXITONG -> {
                Map<String, Object> body = post(baseUrl(type) + "/desktop_links",
                        payload("cookies", cookies), ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                yield new DesktopLinksResult(
                        bool(body.get("status")),
                        message(body, "获取成功", "获取失败"),
                        parseCourses(body.get("courses")));
            }
            case ZHIHUISHU -> {
                Map<String, Object> body = post(baseUrl(type) + "/api/homework/reminder",
                        payload("cookies", cookies), ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                yield new DesktopLinksResult(
                        bool(body.get("success")),
                        message(body, "获取成功", "获取失败"),
                        groupZhihuishuReminders(asMap(body.get("data"))));
            }
            case YUKETANG -> throw new BusinessException(ErrorCode.PLATFORM_NOT_SUPPORTED,
                    type.getDisplayName() + "暂未提供作业 / 考试链接接口");
        };
    }

    /**
     * 智慧树：条目在 data.rt.Reminder[*].HomeworkExamReminder[*]，
     * 按 courseId 分组并保持接口给出的先后顺序。
     */
    private static List<CourseLinks> groupZhihuishuReminders(Map<String, Object> data) {
        Map<String, Object> rt = data == null ? null : asMap(data.get("rt"));
        if (rt == null) {
            return List.of();
        }
        Map<String, List<CourseLink>> grouped = new LinkedHashMap<>();
        Map<String, String> courseNames = new LinkedHashMap<>();
        for (Map<String, Object> reminder : asMapList(rt.get("Reminder"))) {
            for (Map<String, Object> item : asMapList(reminder.get("HomeworkExamReminder"))) {
                String courseId = text(item.get("courseId"));
                if (courseId == null) {
                    continue;
                }
                courseNames.putIfAbsent(courseId, text(item.get("courseName")));
                grouped.computeIfAbsent(courseId, key -> new ArrayList<>())
                        .add(toZhihuishuLink(item));
            }
        }
        List<CourseLinks> courses = new ArrayList<>();
        grouped.forEach((courseId, items) -> courses.add(
                new CourseLinks(courseId, courseNames.get(courseId), items)));
        return courses;
    }

    /**
     * 智慧树的单条作业 / 考试。
     * 链接取接口新增的 link 字段；type 1 作业、2 考试、3 小组作业（归到作业）。
     * <p>
     * 状态：平台未公开状态码含义，这里只按可推断的部分映射——
     * 考试按起止时间推导「未开始 / 进行中 / 已结束」；
     * 作业在 isEndSubmit=1 时视为「已结束提交」，status=2 是「仍可作答」的分支，视为「待完成」，
     * 其余视为「已提交」。
     */
    private static CourseLink toZhihuishuLink(Map<String, Object> item) {
        Integer itemType = intOrNull(item.get("type"));
        boolean exam = itemType != null && itemType == 2;

        long now = System.currentTimeMillis();
        Long startTime = longOrNull(item.get("startTime"));
        Long endTime = longOrNull(item.get("endTime"));
        boolean expired = endTime != null && endTime > 0 && endTime < now;

        String status;
        boolean pending;
        if (exam) {
            if (startTime != null && startTime > now) {
                status = "未开始";
                pending = true;
            } else if (expired) {
                status = "已结束";
                pending = false;
            } else {
                status = "进行中";
                pending = true;
            }
        } else {
            Integer endSubmit = intOrNull(item.get("isEndSubmit"));
            Integer rawStatus = intOrNull(item.get("status"));
            if (endSubmit != null && endSubmit == 1) {
                status = "已结束提交";
                pending = false;
            } else if (rawStatus != null && rawStatus == 2) {
                status = "待完成";
                pending = true;
            } else {
                status = "已提交";
                pending = false;
            }
        }

        return new CourseLink(
                text(item.get("id")),
                text(item.get("title")),
                exam ? "exam" : "work",
                text(item.get("link")),
                null,
                expired,
                status,
                pending ? "pending" : "done",
                remainText(endTime, now),
                startTime,
                endTime);
    }

    /** 截止时间文案：已截止 / 剩余X天 / 剩余X小时；没有截止时间时返回 null。 */
    private static String remainText(Long endTime, long now) {
        if (endTime == null || endTime <= 0) {
            return null;
        }
        long diff = endTime - now;
        if (diff <= 0) {
            return "已截止";
        }
        long hours = diff / 3_600_000L;
        if (hours >= 24) {
            return "剩余" + (hours / 24) + "天";
        }
        return hours >= 1 ? "剩余" + hours + "小时" : "剩余不足1小时";
    }

    /** 三态长整数：字段缺失或不是数字时返回 null。 */
    private static Long longOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把 courses 字段（课程ID_班级ID → {条目ID → 条目}）摊平成有序列表。 */
    private static List<CourseLinks> parseCourses(Object raw) {
        Map<String, Object> coursesMap = asMap(raw);
        if (coursesMap == null || coursesMap.isEmpty()) {
            return List.of();
        }
        List<CourseLinks> courses = new ArrayList<>();
        coursesMap.forEach((courseId, itemsRaw) -> {
            Map<String, Object> itemsMap = asMap(itemsRaw);
            if (itemsMap == null || itemsMap.isEmpty()) {
                return;
            }
            List<CourseLink> items = new ArrayList<>();
            itemsMap.forEach((itemId, itemRaw) -> {
                Map<String, Object> item = asMap(itemRaw);
                if (item == null) {
                    return;
                }
                // 链接统一取电脑版地址；手机版地址一并返回供前端兜底
                items.add(new CourseLink(
                        itemId,
                        text(item.get("title")),
                        text(item.get("type")),
                        text(item.get("desktop_url")),
                        text(item.get("mobile_url")),
                        boolOrNull(item.get("expired")),
                        text(item.get("status")),
                        text(item.get("status_class")),
                        text(item.get("remainText")),
                        null,
                        null));
            });
            courses.add(new CourseLinks(courseId, null, items));
        });
        return courses;
    }

    // ==================== 课程列表（学习通 / 雨课堂 / 智慧树） ====================

    /**
     * 取平台的课程列表：学习通 POST /courses、雨课堂 POST /api/courses、智慧树 POST /api/course/list。
     * 三方响应结构不同（学习通 status + snake_case，雨课堂 success，智慧树 success + data.rt），
     * 这里统一归一化成 {@link CourseItem}。
     */
    public CourseListResult courses(PlatformType type, Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            throw new BusinessException(ErrorCode.PLATFORM_NOT_BOUND,
                    "尚未获取 Cookie，请先登录" + type.getDisplayName());
        }
        return switch (type) {
            case XUEXITONG -> {
                Map<String, Object> body = post(baseUrl(type) + "/courses",
                        payload("cookies", cookies), ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                yield new CourseListResult(
                        bool(body.get("status")),
                        message(body, "获取成功", "获取失败"),
                        parseXuexitongCourses(body.get("courses")));
            }
            case YUKETANG -> {
                Map<String, Object> body = post(baseUrl(type) + "/api/courses",
                        payload("cookies", cookies), ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                yield new CourseListResult(
                        bool(body.get("success")),
                        message(body, "获取成功", "获取失败"),
                        parseYuketangCourses(body.get("courses")));
            }
            case ZHIHUISHU -> {
                Map<String, Object> body = post(baseUrl(type) + "/api/course/list",
                        payload("cookies", cookies), ErrorCode.PLATFORM_SERVICE_UNAVAILABLE);
                // 课程数据在 data 里：{status: 200 表示成功, message: "OK", rt: {studyCourses, archiveCourses}}
                Map<String, Object> data = asMap(body.get("data"));
                Integer innerStatus = data == null ? null : intOrNull(data.get("status"));
                boolean ok = bool(body.get("success")) && (innerStatus == null || innerStatus == 200);
                yield new CourseListResult(
                        ok,
                        ok ? message(body, "获取成功", "获取失败")
                                : firstNonBlank(text(data == null ? null : data.get("message")),
                                        text(body.get("message")), "获取失败"),
                        parseZhihuishuCourses(data));
            }
        };
    }

    /** 学习通：courses 数组 → 课程列表；每门课带默认入口与各 pageHeader 入口。 */
    private static List<CourseItem> parseXuexitongCourses(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<CourseItem> courses = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> course = asMap(element);
            if (course == null) {
                continue;
            }
            courses.add(new CourseItem(
                    text(course.get("course_name")),
                    text(course.get("course_id")),
                    text(course.get("clazz_id")),
                    text(course.get("url")),
                    text(course.get("teacher")),
                    text(course.get("description")),
                    text(course.get("cover_url")),
                    text(course.get("status")),
                    text(course.get("status_text")),
                    parsePageLinks(course.get("page_links")),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return courses;
    }

    /**
     * 雨课堂：courses 数组 → 课程列表。
     * 雨课堂没有课程封面、说明与模块入口（coverUrl / description / pages 留空），
     * 教师头像、学校头像等原始字段单独带出，交给前端排版。
     */
    private static List<CourseItem> parseYuketangCourses(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<CourseItem> courses = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> course = asMap(element);
            if (course == null) {
                continue;
            }
            courses.add(new CourseItem(
                    firstNonBlank(text(course.get("course_name")), text(course.get("name"))),
                    text(course.get("course_id")),
                    text(course.get("classroom_id")),
                    text(course.get("course_url")),
                    text(course.get("teacher_name")),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    text(course.get("teacher_avatar")),
                    text(course.get("university_mini_logo")),
                    text(course.get("university_name")),
                    text(course.get("term_readable")),
                    intOrNull(course.get("students_count")),
                    null));
        }
        return courses;
    }

    /**
     * 智慧树：课程数据在 data 下，层级在不同网关下不一致，已知
     * rt.studyCourses / rt.archiveCourses（在学在前、已归档在后）、rt.courses、courses、data.courses 几种形态。
     */
    private static List<CourseItem> parseZhihuishuCourses(Map<String, Object> data) {
        if (data == null) {
            return List.of();
        }
        List<CourseItem> courses = new ArrayList<>();
        for (Map<String, Object> course : zhihuishuCourseNodes(data)) {
            courses.add(toZhihuishuCourse(course));
        }
        return courses;
    }

    /**
     * 收集智慧树的课程对象：兼容 rt.studyCourses / rt.archiveCourses、rt.courses、
     * courses、data.courses 几种层级，按 courseId 去重（先出现者优先）。
     */
    private static List<Map<String, Object>> zhihuishuCourseNodes(Map<String, Object> data) {
        Map<String, Object> rt = asMap(data.get("rt"));
        Map<String, Object> nested = asMap(data.get("data"));

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.addAll(asMapList(rt == null ? null : rt.get("studyCourses")));
        nodes.addAll(asMapList(rt == null ? null : rt.get("archiveCourses")));
        nodes.addAll(asMapList(rt == null ? null : rt.get("courses")));
        nodes.addAll(asMapList(data.get("courses")));
        nodes.addAll(asMapList(nested == null ? null : nested.get("courses")));

        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = firstNonBlank(text(node.get("courseId")), text(node.get("code")));
            unique.putIfAbsent(id == null ? "unknown_" + unique.size() : id, node);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 单门智慧树课程 → {@link CourseItem}。
     * 字段：courseId / courseCover / courseName / courseCreator / learningProgress /
     * courseStatus（2 在学、3 已归档）/ yearTerm / homepage / schoolName / classId / workurl …
     */
    private static CourseItem toZhihuishuCourse(Map<String, Object> course) {
        String courseId = text(course.get("courseId"));
        Integer courseStatus = intOrNull(course.get("courseStatus"));
        String status = null;
        String statusText = null;
        if (courseStatus != null && courseStatus == 3) {
            status = "ended";
            statusText = "已结束";
        } else if (courseStatus != null && courseStatus == 2) {
            // 在学是常态，不再逐张卡片挂「在学」标签，改由学习进度条表达
            status = "active";
        }
        return new CourseItem(
                text(course.get("courseName")),
                courseId,
                text(course.get("classId")),
                // 智慧树卡片默认直达「作业 / 考试」页；取不到时回落到课程主页
                firstNonBlank(zhihuishuWorkUrl(course, courseId), text(course.get("homepage"))),
                text(course.get("courseCreator")),
                null,
                text(course.get("courseCover")),
                status,
                statusText,
                List.of(),
                null,
                null,
                text(course.get("schoolName")),
                text(course.get("yearTerm")),
                null,
                progressOrZero(course.get("learningProgress")));
    }

    /**
     * 智慧树「作业 / 考试」直达地址：
     * 优先用接口下发的 workurl，缺失时按 courseId 兜底拼接。
     */
    private static String zhihuishuWorkUrl(Map<String, Object> course, String courseId) {
        String fromApi = text(course.get("workurl"));
        if (fromApi != null) {
            return fromApi;
        }
        if (courseId == null || courseId.isBlank()) {
            return null;
        }
        return ZHIHUISHU_WORK_URL_TEMPLATE + courseId;
    }

    /** 把 JSON 数组转成 Map 列表；非数组或元素不是对象时跳过。 */
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> node = asMap(element);
            if (node != null) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** 学习进度：智慧树用 null 表示 0%，统一收敛成 0-100 的整数。 */
    private static Integer progressOrZero(Object raw) {
        Integer value = intOrNull(raw);
        if (value == null) {
            return 0;
        }
        return Math.min(100, Math.max(0, value));
    }

    /** 三态整数：字段缺失或不是数字时返回 null。 */
    private static Integer intOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** page_links 是 {pageHeader: {title, url}} 的有序字典，转成列表以保留原顺序。 */
    private static List<PageLinkItem> parsePageLinks(Object raw) {
        Map<String, Object> links = asMap(raw);
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        List<PageLinkItem> pages = new ArrayList<>();
        links.forEach((key, value) -> {
            Map<String, Object> link = asMap(value);
            if (link == null) {
                return;
            }
            pages.add(new PageLinkItem(key, text(link.get("title")), text(link.get("url"))));
        });
        return pages;
    }

    // ==================== 内部工具 ====================

    private String baseUrl(PlatformType type) {
        AppProperties.Platform config = appProperties.getPlatform();
        String base = switch (type) {
            case ZHIHUISHU -> config.getZhihuishuBase();
            case YUKETANG -> config.getYuketangBase();
            case XUEXITONG -> config.getXuexitongBase();
        };
        if (base == null || base.isBlank()) {
            throw new BusinessException(ErrorCode.PLATFORM_SERVICE_UNAVAILABLE,
                    type.getDisplayName() + "服务地址未配置");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private Map<String, Object> post(String url, Map<String, Object> body, ErrorCode httpErrorCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<>() {
                    });
            Map<String, Object> result = response.getBody();
            return result == null ? new LinkedHashMap<>() : result;
        } catch (RestClientResponseException e) {
            String detail = extractDetail(e.getResponseBodyAsString());
            log.warn("平台服务返回错误 url={} status={} detail={}", url, e.getStatusCode(), detail);
            throw new BusinessException(httpErrorCode, detail);
        } catch (RestClientException e) {
            log.warn("无法连接平台服务 url={} err={}", url, e.getMessage());
            throw new BusinessException(ErrorCode.PLATFORM_SERVICE_UNAVAILABLE,
                    "无法连接平台登录服务 " + url + "，请确认该平台服务已启动");
        }
    }

    /** 从 FastAPI 的错误体里取出 detail 文案。 */
    private static String extractDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "平台登录服务返回异常";
        }
        try {
            JsonNode node = MAPPER.readTree(responseBody);
            JsonNode detail = node.get("detail");
            if (detail != null && !detail.isNull()) {
                return detail.isTextual() ? detail.asText() : detail.toString();
            }
            JsonNode message = node.get("message");
            if (message != null && !message.isNull()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON，直接返回原文
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
    }

    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] == null || keyValues[i + 1] == null) {
                continue;
            }
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, String> cookies(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return result;
    }

    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? castMap(map) : null;
    }

    /**
     * 雨课堂的用户信息在 userinfo.data[0] 里，其余平台直接放在 userinfo 顶层。
     * 统一取「可能装着用户资料的那一层」，找不到就退回原对象。
     */
    private static Map<String, Object> firstOfData(Map<String, Object> userinfo) {
        if (userinfo == null) {
            return Map.of();
        }
        if (userinfo.get("data") instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> first = asMap(list.get(0));
            if (first != null) {
                return first;
            }
        }
        return userinfo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static boolean bool(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof Number value) {
            return value.intValue() != 0;
        }
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim());
    }

    /** 三态布尔：字段缺失返回 null，避免把「未提供」误当成 false。 */
    private static Boolean boolOrNull(Object raw) {
        return raw == null ? null : bool(raw);
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static String message(Map<String, Object> body, String whenTrue, String whenFalse) {
        // 三个服务的失败文案字段不统一：message / msg，两个都读，避免丢掉上游给的具体原因
        String value = firstNonBlank(text(body.get("message")), text(body.get("msg")));
        return value != null ? value : (bool(body.get("success")) || bool(body.get("status")) ? whenTrue : whenFalse);
    }

    /**
     * 智慧树用户信息：新版放在 user_info 里
     * （{@code {realName, headPicUrl, uuid, username}}），
     * 旧版直接把姓名放在顶层 real_name 字段上，两种形状都要能读。
     */
    private static Map<String, Object> zhihuishuUserInfo(Map<String, Object> body) {
        return body == null ? null : asMap(body.get("user_info"));
    }

    /** 智慧树姓名：优先 user_info.realName，回落到旧的 real_name。 */
    private static String zhihuishuRealName(Map<String, Object> body) {
        Map<String, Object> info = zhihuishuUserInfo(body);
        if (info != null) {
            String name = realName(firstNonBlank(text(info.get("realName")), text(info.get("real_name")),
                    text(info.get("name"))));
            if (name != null) {
                return name;
            }
        }
        return realName(body == null ? null : body.get("real_name"));
    }

    /** 智慧树头像：user_info.headPicUrl。 */
    private static String zhihuishuAvatar(Map<String, Object> body) {
        Map<String, Object> info = zhihuishuUserInfo(body);
        if (info == null) {
            return null;
        }
        return firstNonBlank(text(info.get("headPicUrl")), text(info.get("headPic")),
                text(info.get("avatar")));
    }

    /** 智慧树的 real_name 失败时返回 -1，需要过滤掉。 */
    private static String realName(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number && number.longValue() == -1) {
            return null;
        }
        String value = text(raw);
        return "-1".equals(value) ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
