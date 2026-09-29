//平台课程列表响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 平台（学习通 / 雨课堂 / 智慧树）的课程列表。
 *
 * @param success 是否取到数据
 * @param message 提示文案
 * @param count   课程数量
 * @param courses 课程列表
 */
public record PlatformCoursesResponse(boolean success, String message, int count, List<Course> courses) {

    /**
     * 单门课程。
     *
     * @param courseName    课程名
     * @param courseId      课程 ID
     * @param clazzId       班级 ID
     * @param url           默认入口（点击卡片进入）
     * @param teacher       教师
     * @param description   说明（学校 / 开课时间等）
     * @param coverUrl      封面图地址
     * @param status        active / ended
     * @param statusText    状态文案（正常 / 已结束）
     * @param pages         该课程的各个入口（任务 / 章节 / 作业 / 考试 …）
     * @param teacherAvatar 教师头像（雨课堂）
     * @param schoolLogo    学校头像 / 校徽（雨课堂）
     * @param schoolName    学校名（雨课堂 / 智慧树）
     * @param term          学期（雨课堂，如「2024-2025学年 第1学期（秋）」；智慧树为学年学期）
     * @param studentsCount 学生人数（雨课堂）
     * @param progress      学习进度百分比 0-100（智慧树；其余平台为 null）
     */
    public record Course(String courseName, String courseId, String clazzId, String url,
                         String teacher, String description, String coverUrl,
                         String status, String statusText, List<PageLink> pages,
                         String teacherAvatar, String schoolLogo, String schoolName,
                         String term, Integer studentsCount, Integer progress) {
    }

    /** 课程内的一个入口。 */
    public record PageLink(String key, String title, String url) {
    }
}
