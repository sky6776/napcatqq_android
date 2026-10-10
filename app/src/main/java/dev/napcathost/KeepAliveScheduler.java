package dev.napcathost;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.TimeUnit;

final class KeepAliveScheduler {
    static final long INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    static final long WAKE_LOCK_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(20);
    private static final int REQUEST_CODE = 4402;

    private KeepAliveScheduler() {
    }

    static void schedule(Context context) {
        Context appContext = context.getApplicationContext();
        if (!HostConfig.enhancedKeepAlive(appContext)
                || !HostConfig.preferences(appContext)
                .getBoolean("desired_running", false)) {
            cancel(appContext);
            return;
        }
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(
                Context.ALARM_SERVICE
        );
        if (alarmManager == null) {
            HostLogger.log(appContext, "无法取得 AlarmManager，周期唤醒未启用");
            return;
        }
        long triggerAt = nextTriggerAt(System.currentTimeMillis());
        try {
            // Alarm-clock alarms are allowed to leave Doze and are not subject to the usual
            // allow-while-idle batching. The visible next-alarm indicator is intentional: the
            // enhanced keep-alive switch explicitly opts into this aggressive mode.
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAt, showIntent(appContext)),
                    pendingIntent(appContext)
            );
        } catch (SecurityException e) {
            // Keep a Doze-capable fallback for devices that reject alarm-clock scheduling.
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent(appContext)
            );
        }
    }

    static void cancel(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(
                Context.ALARM_SERVICE
        );
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(appContext));
        }
    }

    static long nextTriggerAt(long currentTimeMillis) {
        return currentTimeMillis + INTERVAL_MILLIS;
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class)
                .setAction(KeepAliveReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent showIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_CODE + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
