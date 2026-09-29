//重复规则算法单测
package com.xiaofu.student.service;

import com.xiaofu.student.dto.RepeatData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重复规则算法单测：覆盖 daily / weekly / monthly / 跨月 / 闰年 / 不推进。
 *
 * <p>断言尽量写成「相对关系」而不是硬编码日期 —— 算法内部用当前时间判断，
 * 硬编码某个具体日期会让测试在别的日子失效。
 */
class RepeatRuleServiceTest {

    private final RepeatRuleService service = new RepeatRuleService();

    @Test
    @DisplayName("none：不推进，原样返回")
    void noneKeepsBase() {
        LocalDateTime base = LocalDateTime.now().minusDays(3).withNano(0);
        assertThat(service.computeNext(base, RepeatRuleService.REPEAT_NONE, null)).isEqualTo(base);
    }

    @Test
    @DisplayName("immediate：不推进，原样返回")
    void immediateKeepsBase() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(5).withNano(0);
        assertThat(service.computeNext(base, RepeatRuleService.REPEAT_IMMEDIATE, null)).isEqualTo(base);
    }

    @Test
    @DisplayName("daily：过去的时间向前推进一天，时分秒不变")
    void dailyAdvancesToNextDay() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_DAILY, null);

        assertThat(next).isEqualTo(base.plusDays(1));
        assertThat(next).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("weekly：落到 days 集合里的下一个星期，时分秒不变")
    void weeklyJumpsToSelectedWeekday() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(1).withNano(0);
        List<Integer> days = List.of(1, 3, 5);   // 周一 / 周三 / 周五
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_WEEKLY, new RepeatData(days, null));

        assertThat(next).isAfter(LocalDateTime.now());
        assertThat(days).contains(next.getDayOfWeek().getValue());
        assertThat(next.toLocalTime()).isEqualTo(base.toLocalTime());
        // 最多往后跳 7 天就能命中一个选中的星期
        assertThat(ChronoUnit.DAYS.between(base, next)).isBetween(0L, 7L);
    }

    @Test
    @DisplayName("weekly：不选星期时按每天推进（不会卡死）")
    void weeklyWithoutDaysFallsBackToDaily() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_WEEKLY, new RepeatData(List.of(), null));

        assertThat(next).isAfter(LocalDateTime.now());
        assertThat(ChronoUnit.DAYS.between(base, next)).isBetween(0L, 7L);
    }

    @Test
    @DisplayName("weekly：恰好在将来（未过期）的时间不动")
    void weeklyKeepsFutureTime() {
        LocalDateTime base = LocalDateTime.now().plusDays(7).withNano(0);
        // 选中的星期与 base 当天一致，且 base 在未来 -> 应原样返回
        int weekday = base.getDayOfWeek().getValue();
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_WEEKLY, new RepeatData(List.of(weekday), null));

        assertThat(next).isEqualTo(base);
    }

    @Test
    @DisplayName("monthly：跳到次月，日期是 min(day, 当月最后一天)，时分秒不变")
    void monthlyClampsToLastDayOfMonth() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_MONTHLY, new RepeatData(null, 31));

        assertThat(next).isAfter(LocalDateTime.now());
        assertThat(next.getDayOfMonth()).isEqualTo(Math.min(31, next.toLocalDate().lengthOfMonth()));
        assertThat(next.toLocalTime()).isEqualTo(base.toLocalTime());
    }

    @Test
    @DisplayName("monthly：2 月只有 29 天时（闰年）落到 2 月 29 日")
    void monthlyHandlesLeapFebruary() {
        LocalDateTime base = LocalDateTime.of(2028, 1, 31, 9, 30, 0);       // 2028 是闰年
        LocalDateTime now = LocalDateTime.of(2028, 1, 31, 10, 0, 0);        // 基准时间已过
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_MONTHLY,
                new RepeatData(null, 31), now);

        assertThat(next).isEqualTo(LocalDateTime.of(2028, 2, 29, 9, 30, 0));
    }

    @Test
    @DisplayName("monthly：2 月只有 28 天时（平年）落到 2 月 28 日")
    void monthlyHandlesPlainFebruary() {
        LocalDateTime base = LocalDateTime.of(2027, 1, 31, 9, 30, 0);       // 2027 是平年
        LocalDateTime now = LocalDateTime.of(2027, 1, 31, 10, 0, 0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_MONTHLY,
                new RepeatData(null, 31), now);

        assertThat(next).isEqualTo(LocalDateTime.of(2027, 2, 28, 9, 30, 0));
    }

    @Test
    @DisplayName("monthly：跨年（12 月 -> 次年 1 月）")
    void monthlyCrossesYearBoundary() {
        LocalDateTime base = LocalDateTime.of(2026, 12, 15, 20, 0, 0);
        LocalDateTime now = LocalDateTime.of(2026, 12, 15, 21, 0, 0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_MONTHLY,
                new RepeatData(null, 15), now);

        assertThat(next).isEqualTo(LocalDateTime.of(2027, 1, 15, 20, 0, 0));
    }

    @Test
    @DisplayName("weekly：基准时间已过一周时，推进到选中的下一个星期")
    void weeklyFromFixedBase() {
        // 2026-09-28 是周一，选周三 / 周五 -> 下一次应为 2026-09-30（周三）
        LocalDateTime base = LocalDateTime.of(2026, 9, 28, 8, 0, 0);
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 23, 0, 0);
        LocalDateTime next = service.computeNext(base, RepeatRuleService.REPEAT_WEEKLY,
                new RepeatData(List.of(3, 5), null), now);

        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 30, 8, 0, 0));
    }

    @Test
    @DisplayName("isRepeat：只有 daily/weekly/monthly 算重复任务")
    void isRepeatOnlyForRealRepeats() {
        assertThat(service.isRepeat(RepeatRuleService.REPEAT_DAILY)).isTrue();
        assertThat(service.isRepeat(RepeatRuleService.REPEAT_WEEKLY)).isTrue();
        assertThat(service.isRepeat(RepeatRuleService.REPEAT_MONTHLY)).isTrue();
        assertThat(service.isRepeat(RepeatRuleService.REPEAT_NONE)).isFalse();
        assertThat(service.isRepeat(RepeatRuleService.REPEAT_IMMEDIATE)).isFalse();
        assertThat(service.isRepeat(null)).isFalse();
    }
}
