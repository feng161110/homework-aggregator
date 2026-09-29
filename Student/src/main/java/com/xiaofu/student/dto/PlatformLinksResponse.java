//平台作业/考试链接响应
package com.xiaofu.student.dto;

import java.util.List;

/**
 * 平台（学习通 / 智慧树）的作业 / 考试链接。
 *
 * @param success 是否取到数据
 * @param message 提示文案
 * @param courses 按课程分组的链接
 */
public record PlatformLinksResponse(boolean success, String message, List<CourseGroup> courses) {

    /**
     * 一门课程下的作业 / 考试。
     *
     * @param courseId   课程 ID（学习通为「课程ID_班级ID」）
     * @param courseName 课程名（智慧树提供，学习通暂为 null）
     * @param items      该课程下的作业 / 考试
     */
    public record CourseGroup(String courseId, String courseName, List<LinkItem> items) {
    }

    /**
     * 单条作业 / 考试。
     *
     * @param id          条目 ID（如 work_1234 / exam_5678）
     * @param title       标题
     * @param type        work / exam
     * @param url         该条的直达地址
     * @param mobileUrl   手机版地址
     * @param expired     是否已过期（作业 / 考试都会返回）
     * @param status      状态文案（如「未提交」「已完成」）
     * @param statusClass 状态语义（如 pending / done），供前端上色
     * @param remainText  剩余时间文案（如「剩余3天」，可能为空）
     * @param startTime   开始时间（毫秒时间戳，可能为 null）
     * @param endTime     截止时间（毫秒时间戳，可能为 null；前端据此做实时倒计时与排序）
     */
    public record LinkItem(String id, String title, String type, String url, String mobileUrl,
                           Boolean expired, String status, String statusClass, String remainText,
                           Long startTime, Long endTime) {
    }
}
