package com.zealmutex.app.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RuleEngineTest {
    @Test
    public void usedDurationDisplaysOnlyCompleteMinutes() {
        assertEquals("1小时30分钟",
                RuleEngine.formatDuration(90L * 60_000L + 59_000L));
        assertEquals("0分钟", RuleEngine.formatDuration(59_000L));
    }

    @Test
    public void remainingDurationRoundsUpPartialMinute() {
        assertEquals("1分钟", RuleEngine.formatRemainingDuration(1L));
        assertEquals("1小时30分钟",
                RuleEngine.formatRemainingDuration(89L * 60_000L + 1L));
        assertEquals("0分钟", RuleEngine.formatRemainingDuration(0L));
    }
}
