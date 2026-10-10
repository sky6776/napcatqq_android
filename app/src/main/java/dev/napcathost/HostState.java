package dev.napcathost;

import android.content.Context;
import android.content.SharedPreferences;

final class HostState {
    enum Phase {
        IDLE,
        INSTALLING,
        READY,
        STARTING,
        RUNNING,
        BACKOFF,
        STOPPING,
        ERROR
    }

    static final class Snapshot {
        final Phase phase;
        final String detail;
        final long changedAt;

        Snapshot(Phase phase, String detail, long changedAt) {
            this.phase = phase;
            this.detail = detail;
            this.changedAt = changedAt;
        }
    }

    private HostState() {
    }

    static synchronized void set(Context context, Phase phase, String detail) {
        SharedPreferences.Editor editor = HostConfig.preferences(context).edit();
        editor.putString("state_phase", phase.name());
        editor.putString("state_detail", detail == null ? "" : detail);
        editor.putLong("state_changed_at", System.currentTimeMillis());
        editor.apply();
    }

    static Snapshot get(Context context) {
        SharedPreferences prefs = HostConfig.preferences(context);
        Phase phase;
        try {
            phase = Phase.valueOf(prefs.getString("state_phase", Phase.IDLE.name()));
        } catch (IllegalArgumentException ignored) {
            phase = Phase.IDLE;
        }
        return new Snapshot(
                phase,
                prefs.getString("state_detail", ""),
                prefs.getLong("state_changed_at", 0L)
        );
    }

    static String label(Phase phase) {
        switch (phase) {
            case INSTALLING:
                return "正在安装";
            case READY:
                return "运行时已就绪";
            case STARTING:
                return "正在启动";
            case RUNNING:
                return "NapCat 运行中";
            case BACKOFF:
                return "等待自动重启";
            case STOPPING:
                return "正在停止";
            case ERROR:
                return "发生错误";
            case IDLE:
            default:
                return "未运行";
        }
    }
}
