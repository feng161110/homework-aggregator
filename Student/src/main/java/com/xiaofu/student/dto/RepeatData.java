//重复规则参数
package com.xiaofu.student.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 重复规则参数。
 *
 * @param days weekly 用：生效的星期，1 = 周一 … 7 = 周日（与 PHP date('N') 一致）
 * @param day  monthly 用：每月几号，1~31；当月不足该日时取当月最后一天
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepeatData(List<Integer> days, Integer day) {

    /** 便捷方法，不是 JSON 字段（见 TaskData 里的说明）。 */
    @JsonIgnore
    public List<Integer> daysOrEmpty() {
        return days == null ? List.of() : days;
    }
}
