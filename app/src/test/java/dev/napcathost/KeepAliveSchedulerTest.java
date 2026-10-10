package dev.napcathost;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public final class KeepAliveSchedulerTest {
    @Test
    public void schedulesTenMinutesFromCurrentTime() {
        long now = 12_345L;
        assertEquals(
                now + TimeUnit.MINUTES.toMillis(10),
                KeepAliveScheduler.nextTriggerAt(now)
        );
    }

    @Test
    public void wakeLockLeaseCoversTwoAlarmIntervals() {
        assertEquals(
                KeepAliveScheduler.INTERVAL_MILLIS * 2,
                KeepAliveScheduler.WAKE_LOCK_LEASE_MILLIS
        );
    }
}
