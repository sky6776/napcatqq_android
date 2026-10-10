package dev.napcathost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HostLoggerTest {
    @Test
    public void latestWebUiUrlUsesMostRecentLocalToken() {
        String log = "first http://127.0.0.1:6099/webui?token=old_token\n"
                + "second http://[::]:6099/webui?token=new-token_2";

        assertEquals(
                "http://127.0.0.1:6099/webui?token=new-token_2",
                HostLogger.latestWebUiUrlFromText(log)
        );
    }

    @Test
    public void latestWebUiUrlRejectsExternalOrMalformedUrls() {
        assertNull(HostLogger.latestWebUiUrlFromText(
                "http://example.com:6099/webui?token=secret"
        ));
        assertNull(HostLogger.latestWebUiUrlFromText(
                "http://127.0.0.1:6099/webui?token=x"
        ));
    }

    @Test
    public void latestWebUiTokenReturnsTokenWithoutUrl() {
        assertEquals(
                "private_token-2",
                HostLogger.latestWebUiTokenFromText(
                        "WebUi User Panel Url: http://127.0.0.1:6099/webui?token=private_token-2"
                )
        );
    }

    @Test
    public void latestWebUiTokenAcceptsAnyLocalListenHost() {
        // NapCat prints the Panel Url with whatever host webui.host is set to;
        // 0.0.0.0 and localhost must yield their token like 127.0.0.1 does.
        assertEquals(
                "wildcard_token",
                HostLogger.latestWebUiTokenFromText(
                        "WebUi User Panel Url: http://0.0.0.0:6099/webui?token=wildcard_token"
                )
        );
        assertEquals(
                "localhost_token",
                HostLogger.latestWebUiTokenFromText(
                        "WebUi User Panel Url: http://localhost:6099/webui?token=localhost_token"
                )
        );
    }
}
