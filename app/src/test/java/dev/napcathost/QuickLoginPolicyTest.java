package dev.napcathost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class QuickLoginPolicyTest {
    @Test
    public void loggedInAlwaysSkipsPassword() {
        assertEquals(QuickLoginPolicy.Decision.LOGGED_IN,
                QuickLoginPolicy.evaluate(true, "", 1));
        assertEquals(QuickLoginPolicy.Decision.LOGGED_IN,
                QuickLoginPolicy.evaluate(true, "旧错误", QuickLoginPolicy.MAX_OBSERVATIONS));
    }

    @Test
    public void waitsWhileQuickLoginIsStillPending() {
        assertEquals(QuickLoginPolicy.Decision.WAIT,
                QuickLoginPolicy.evaluate(false, "", 1));
        assertEquals(QuickLoginPolicy.Decision.WAIT,
                QuickLoginPolicy.evaluate(false, null,
                        QuickLoginPolicy.MAX_OBSERVATIONS - 1));
    }

    @Test
    public void usesPasswordOnlyAfterFailureOrTimeout() {
        assertEquals(QuickLoginPolicy.Decision.USE_PASSWORD,
                QuickLoginPolicy.evaluate(false, "快速登录失败", 1));
        assertEquals(QuickLoginPolicy.Decision.USE_PASSWORD,
                QuickLoginPolicy.evaluate(false, "", QuickLoginPolicy.MAX_OBSERVATIONS));
    }
}
