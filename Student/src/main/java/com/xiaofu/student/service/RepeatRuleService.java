//重复规则算法（作业提醒的核心）
package com.xiaofu.student.service;

import com.xiaofu.student.dto.RepeatData;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 重复规则算法，对应原项目 tasks.php 的 computeNextScheduledTime，逐条对齐：
 *
 * <ul>
 *   <li>none / immediate —— 不推进（由调用方保证时间合法）</li>
 *   <li>daily —— 每次 +1 天</li>
 *   <li>weekly —— 跳到下一个落在 days 集合里的日子（1 = 周一 … 7 = 周日）</li>
 *   <li>monthly —— 跳到次月的 min(day, 当月最后一天)，保留时分秒</li>
 * </ul>
 *
 * <p>{@code DayOfWeek.getValue()} 与 PHP {@code date('N')} 完全一致（周一=1 … 周日=7），无需换算。
 */
@Service
public class RepeatRuleService {

    public static final String REPEAT_NONE = "none";
    public static final String REPEAT_DAILY = "daily";
    public static final String REPEAT_WEEKLY = "weekly";
    public static final String REPEAT_MONTHLY = "monthly";
    public static final String REPEAT_IMMEDIATE = "immediate";

    /** 安全上限，防死循环。 */
    private static final int MAX_LOOP = 3660;

    /**
     * 计算下一次执行时间：返回严格晚于当前时间的第一个符合规则的时间点。
     *
     * @param base   基准时间（表单里选的那个时间）
     * @param repeat none / daily / weekly / monthly / immediate
     * @param data   weekly 的星期集合、monthly 的日期
     * @return 推进后的时间；无法推进时原样返回 base
     */
    public LocalDateTime computeNext(LocalDateTime base, String repeat, RepeatData data) {
        return computeNext(base, repeat, data, LocalDateTime.now());
    }

    /**
     * 带基准时间的重载：逻辑与上面完全一致，只是把「现在」参数化，
     * 便于单测稳定覆盖跨月、闰年这类依赖当前日期的分支。
     */
    public LocalDateTime computeNext(LocalDateTime base, String repeat, RepeatData data, LocalDateTime now) {
        if (base == null) {
            return null;
        }
        String rule = repeat == null ? REPEAT_NONE : repeat;
        LocalDateTime t = base;
        List<Integer> days = data == null ? List.of() : data.daysOrEmpty();
        int monthlyDay = (data == null || data.day() == null) ? 1 : Math.max(1, Math.min(31, data.day()));

        for (int i = 0; i < MAX_LOOP; i++) {
            boolean valid = switch (rule) {
                case REPEAT_WEEKLY -> days.isEmpty() || days.contains(t.getDayOfWeek().getValue());
                case REPEAT_MONTHLY -> t.getDayOfMonth() == Math.min(monthlyDay, t.toLocalDate().lengthOfMonth());
                default -> true;   // none / daily / immediate
            };
            if (valid && t.isAfter(now)) {
                return t;
            }
            LocalDateTime next = advance(t, rule, days, monthlyDay);
            if (next == null) {
                break;             // none / immediate：不推进
            }
            t = next;
        }
        return base;               // 兜底：返回原值
    }

    private LocalDateTime advance(LocalDateTime t, String repeat, List<Integer> days, int monthlyDay) {
        switch (repeat == null ? REPEAT_NONE : repeat) {
            case REPEAT_DAILY:
                return t.plusDays(1);
            case REPEAT_WEEKLY: {
                if (days.isEmpty()) {
                    return t.plusDays(1);
                }
                for (int off = 1; off <= 7; off++) {
                    LocalDateTime candidate = t.plusDays(off);
                    if (days.contains(candidate.getDayOfWeek().getValue())) {
                        return candidate;
                    }
                }
                return t.plusDays(1);
            }
            case REPEAT_MONTHLY: {
                YearMonth next = YearMonth.from(t).plusMonths(1);
                int targetDay = Math.min(monthlyDay, next.lengthOfMonth());
                return LocalDateTime.of(next.getYear(), next.getMonthValue(), targetDay,
                        t.getHour(), t.getMinute(), t.getSecond());
            }
            default:
                return null;       // none / immediate 不推进
        }
    }

    /** 是否是需要「发完往后排」的重复任务。 */
    public boolean isRepeat(String repeat) {
        return repeat != null
                && !REPEAT_NONE.equals(repeat)
                && !REPEAT_IMMEDIATE.equals(repeat);
    }
}
