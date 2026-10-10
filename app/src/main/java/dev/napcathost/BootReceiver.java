package dev.napcathost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        boolean resumeAfterUpdate = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && HostConfig.preferences(context).getBoolean("desired_running", false);
        if (!HostConfig.autoStart(context) && !resumeAfterUpdate) {
            return;
        }
        Intent service = new Intent(context, NapCatService.class)
                .setAction(NapCatService.ACTION_START);
        context.startForegroundService(service);
        HostLogger.log(context, "收到系统启动广播，恢复 NapCat 前台服务");
    }
}
