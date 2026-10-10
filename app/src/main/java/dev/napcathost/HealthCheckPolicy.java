package dev.napcathost;

final class HealthCheckPolicy {
    static final int WEB_UI_FAILURES_BEFORE_RESTART = 3;
    static final int LOGGED_OUT_CHECKS_BEFORE_ACTION = 2;
    static final int LOGGED_OUT_CHECKS_BEFORE_RESTART = 3;

    private HealthCheckPolicy() {
    }

    static boolean shouldRestart(int consecutiveFailures) {
        return consecutiveFailures >= WEB_UI_FAILURES_BEFORE_RESTART;
    }

    static boolean isLoggedOutConfirmed(int consecutiveChecks) {
        return consecutiveChecks >= LOGGED_OUT_CHECKS_BEFORE_ACTION;
    }

    static boolean shouldRestartAfterLoggedOut(int consecutiveChecks) {
        return consecutiveChecks >= LOGGED_OUT_CHECKS_BEFORE_RESTART;
    }

    static boolean requiresUserVerification(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        return detail.contains("验证码") || detail.contains("新设备验证");
    }

    static boolean shouldReconcileOnlineState(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        return detail.contains("自动登录未完成")
                || detail.contains("已掉线")
                || detail.contains("未登录")
                || detail.contains("正在登录");
    }
}
