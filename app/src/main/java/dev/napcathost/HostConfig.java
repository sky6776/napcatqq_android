package dev.napcathost;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

final class HostConfig {
    static final String PREFS = "napcat_host";

    private static final Pattern QQ_PATTERN = Pattern.compile("[0-9]{5,12}");

    private HostConfig() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String qqNumber(Context context) {
        String value = preferences(context).getString("qq_number", "");
        return value == null ? "" : value.trim();
    }

    static boolean setQqNumber(Context context, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !QQ_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        preferences(context).edit().putString("qq_number", normalized).apply();
        return true;
    }

    static boolean autoStart(Context context) {
        return preferences(context).getBoolean("auto_start", false);
    }

    static void setAutoStart(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("auto_start", enabled).apply();
    }

    static boolean enhancedKeepAlive(Context context) {
        return preferences(context).getBoolean("enhanced_keep_alive", true);
    }

    static void setEnhancedKeepAlive(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enhanced_keep_alive", enabled).apply();
    }

    static long lastKeepAliveAt(Context context) {
        return preferences(context).getLong("last_keep_alive_at", 0L);
    }

    static void recordKeepAlive(Context context, long timestamp) {
        preferences(context).edit().putLong("last_keep_alive_at", timestamp).apply();
    }

    static long lastWakeLockRenewalAt(Context context) {
        return preferences(context).getLong("last_wake_lock_renewal_at", 0L);
    }

    static boolean lastWakeLockRenewalSucceeded(Context context) {
        return preferences(context).getBoolean("last_wake_lock_renewal_succeeded", false);
    }

    static void recordWakeLockRenewal(Context context, long timestamp, boolean succeeded) {
        preferences(context).edit()
                .putLong("last_wake_lock_renewal_at", timestamp)
                .putBoolean("last_wake_lock_renewal_succeeded", succeeded)
                .apply();
    }

    static long lastHealthCheckAt(Context context) {
        return preferences(context).getLong("last_health_check_at", 0L);
    }

    static boolean lastHealthCheckLoggedIn(Context context) {
        return preferences(context).getBoolean("last_health_check_logged_in", false);
    }

    static void recordHealthCheck(Context context, long timestamp, boolean loggedIn) {
        preferences(context).edit()
                .putLong("last_health_check_at", timestamp)
                .putBoolean("last_health_check_logged_in", loggedIn)
                .apply();
    }

    static long lastNetworkRecoveryAt(Context context) {
        return preferences(context).getLong("last_network_recovery_at", 0L);
    }

    static void recordNetworkRecovery(Context context, long timestamp) {
        preferences(context).edit().putLong("last_network_recovery_at", timestamp).apply();
    }

    static long lastHealthRestartAt(Context context) {
        return preferences(context).getLong("last_health_restart_at", 0L);
    }

    static void recordHealthRestart(Context context, long timestamp) {
        preferences(context).edit().putLong("last_health_restart_at", timestamp).apply();
    }

}
