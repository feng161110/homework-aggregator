//scheduled_tasks.task_data 的结构
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 任务内容（scheduled_tasks.task_data 的 JSON 结构）。
 *
 * <p>字段名沿用原型：{@code repeat_data} 保持下划线，这样库里已有的数据与文档里的示例都能原样读写。
 *
 * <p><b>没有占位符系统，也没有模板</b>：subject / content 就是最终发出去的内容，
 * 由用户在界面上自己写（从作业导入时会带出一份可编辑的建议文案）。
 *
 * @param to         收件人邮箱。<b>创建时由服务端强制写成当前登录用户的邮箱</b>，不采信前端传值
 * @param subject    邮件主题
 * @param content    邮件正文
 * @param format     text | html
 * @param repeat     none | daily | weekly | monthly | immediate
 * @param repeatData 重复规则参数（weekly 用 days，monthly 用 day）
 * @param category   homework | exam | report | daily | custom
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskData(String to, String subject, String content, String format, String repeat,
                       @JsonProperty("repeat_data") RepeatData repeatData, String category) {

    public static final String FORMAT_TEXT = "text";
    public static final String FORMAT_HTML = "html";

    /*
     * 下面几个是便捷方法，不是 JSON 字段。
     * 必须加 @JsonIgnore：否则 Jackson 会把 normalized() 当成属性序列化，
     * 而它返回的又是 TaskData —— 直接无限递归。
     */

    /** 兜底缺省值：前端漏传或库里的老数据按「纯文本 + 不重复 + 自定义」处理。 */
    @JsonIgnore
    public TaskData normalized() {
        return new TaskData(
                to,
                subject,
                content,
                (format == null || format.isBlank()) ? FORMAT_TEXT : format.toLowerCase(),
                (repeat == null || repeat.isBlank()) ? "none" : repeat.toLowerCase(),
                repeatData,
                (category == null || category.isBlank()) ? "custom" : category);
    }

    /** 覆盖收件人（服务端锁定为账号邮箱用）。 */
    @JsonIgnore
    public TaskData withTo(String newTo) {
        return new TaskData(newTo, subject, content, format, repeat, repeatData, category);
    }

    @JsonIgnore
    public boolean isHtml() {
        return FORMAT_HTML.equalsIgnoreCase(format);
    }

    @JsonIgnore
    public String repeatOrNone() {
        return repeat == null || repeat.isBlank() ? "none" : repeat.toLowerCase();
    }
}
