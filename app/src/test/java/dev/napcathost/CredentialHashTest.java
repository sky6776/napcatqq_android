package dev.napcathost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CredentialHashTest {
    @Test
    public void qqPasswordMd5MatchesWebUiCryptoJsBehavior() throws Exception {
        assertEquals(
                "e10adc3949ba59abbe56e057f20f883e",
                QqCredentialStore.passwordMd5("123456")
        );
    }

    @Test
    public void webUiTokenHashUsesNapCatSuffix() throws Exception {
        assertEquals(
                "277dea791a1ee2de7fc58b9bb7efb5a11df741b864e0c09958ea07a7e5366749",
                NapCatWebApi.sha256Hex("test-token.napcat")
        );
    }
}
