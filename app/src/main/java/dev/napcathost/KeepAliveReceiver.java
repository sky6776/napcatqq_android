package dev.napcathost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class KeepAliveReceiver extends BroadcastReceiver {
    static final String ACTION_ALARM = "dev.napcathost.action.KEEP_ALIVE_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_ALARM.equals(intent.getAction())) {
            return;
        }
        boolean desiredRunning = HostConfig.preferences(context)
                .getBoolean("desired_running", false);
        if (!desiredRunning || !HostConfig.enhancedKeepAlive(context)) {
            KeepAliveScheduler.cancel(context);
            return;
        }

        HostConfig.recordKeepAlive(context, System.currentTimeMillis());
        // Reschedule first so a later service/process death cannot break the alarm chain.
        KeepAliveScheduler.schedule(context);
        try {
            Intent service = new Intent(context, NapCatService.class)
                    .setAction(NapCatService.ACTION_KEEP_ALIVE);
            context.startForegroundService(service);
        } catch (RuntimeException e) {
            HostLogger.log(context, "周期唤醒前台服务失败：" + e.getClass().getSimpleName());
        }
    }
}
