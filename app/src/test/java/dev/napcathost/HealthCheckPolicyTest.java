package dev.napcathost;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HealthCheckPolicyTest {
    @Test
    public void restartsOnlyAfterThreeConsecutiveFailures() {
        assertFalse(HealthCheckPolicy.shouldRestart(1));
        assertFalse(HealthCheckPolicy.shouldRestart(2));
        assertTrue(HealthCheckPolicy.shouldRestart(3));
        assertTrue(HealthCheckPolicy.shouldRestart(4));
    }

    @Test
    public void confirmsLoggedOutOnlyOnSecondObservation() {
        assertFalse(HealthCheckPolicy.isLoggedOutConfirmed(1));
        assertTrue(HealthCheckPolicy.isLoggedOutConfirmed(2));
        assertTrue(HealthCheckPolicy.isLoggedOutConfirmed(3));
    }

    @Test
    public void restartsOnlyAfterAutomaticLoginHadTimeToRecover() {
        assertFalse(HealthCheckPolicy.shouldRestartAfterLoggedOut(2));
        assertTrue(HealthCheckPolicy.shouldRestartAfterLoggedOut(3));
    }

    @Test
    public void userVerificationSuppressesAutomaticRestart() {
        assertTrue(HealthCheckPolicy.requiresUserVerification(
                "QQ 登录需要验证码，请打开 WebUI"
        ));
        assertTrue(HealthCheckPolicy.requiresUserVerification(
                "QQ 登录需要新设备验证，请打开 WebUI"
        ));
        assertFalse(HealthCheckPolicy.requiresUserVerification(
                "QQ 已掉线，正在尝试安全自动登录"
        ));
    }

    @Test
    public void reconcilesOnlyStaleLoginProblemDetails() {
        assertTrue(HealthCheckPolicy.shouldReconcileOnlineState(
                "前台监管中；QQ 自动登录未完成，请查看日志"
        ));
        assertTrue(HealthCheckPolicy.shouldReconcileOnlineState(
                "QQ 已掉线，请打开 WebUI 重新登录"
        ));
        assertFalse(HealthCheckPolicy.shouldReconcileOnlineState(
                "QQ 已登录；WebUI 端口 6099"
        ));
    }
}
