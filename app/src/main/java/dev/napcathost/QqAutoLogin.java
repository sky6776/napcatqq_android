package dev.napcathost;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class QqAutoLogin {
    private final Context context;
    private final QqCredentialStore credentialStore;
    private final NapCatWebApi api = new NapCatWebApi();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qq-auto-login");
        thread.setDaemon(true);
        return thread;
    });

    QqAutoLogin(Context context) {
        this.context = context.getApplicationContext();
        credentialStore = new QqCredentialStore(this.context);
    }

    void trigger() {
        if (shutdown.get()) {
            return;
        }
        String qqNumber = HostConfig.qqNumber(context);
        if (qqNumber.isEmpty() || !credentialStore.hasSavedCredential()) {
            return;
        }
        rerunRequested.set(true);
        scheduleIfIdle();
    }

    void cancel() {
        generation.incrementAndGet();
        rerunRequested.set(false);
    }

    private void scheduleIfIdle() {
        if (inFlight.compareAndSet(false, true)) {
            try {
                executor.execute(this::runPendingAttempts);
            } catch (RejectedExecutionException e) {
                inFlight.set(false);
            }
        }
    }

    void shutdown() {
        shutdown.set(true);
        cancel();
        executor.shutdownNow();
    }

    private void runPendingAttempts() {
        try {
            while (!shutdown.get() && rerunRequested.getAndSet(false)) {
                String qqNumber = HostConfig.qqNumber(context);
                if (!qqNumber.isEmpty() && credentialStore.hasSavedCredential()) {
                    attemptWhenReady(qqNumber, generation.get());
                }
            }
        } finally {
            inFlight.set(false);
            // A WebUI-ready line may arrive after the loop condition but before inFlight is reset.
            if (!shutdown.get() && rerunRequested.get()) {
                scheduleIfIdle();
            }
        }
    }

    private void attemptWhenReady(String qqNumber, long attemptGeneration) {
        IOException lastConnectionError = null;
        for (int attempt = 0; attempt < 30
                && isCurrent(attemptGeneration)
                && !Thread.currentThread().isInterrupted(); attempt++) {
            String webUiToken = HostLogger.latestWebUiToken(context);
            if (webUiToken != null) {
                try {
                    performLogin(webUiToken, qqNumber, attemptGeneration);
                    return;
                } catch (java.net.ConnectException e) {
                    lastConnectionError = e;
                } catch (java.net.SocketTimeoutException e) {
                    lastConnectionError = e;
                } catch (IOException | GeneralSecurityException e) {
                    HostLogger.log(context, "QQ 自动登录未完成：" + safeMessage(e.getMessage()));
                    HostState.set(context, HostState.Phase.RUNNING,
                            "前台监管中；QQ 自动登录未完成，请查看日志");
                    return;
                }
            }
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (lastConnectionError != null && isCurrent(attemptGeneration)) {
            HostLogger.log(context, "QQ 自动登录未完成：WebUI 尚未就绪");
        }
    }

    private void performLogin(String webUiToken, String qqNumber, long attemptGeneration)
            throws IOException, GeneralSecurityException {
        if (!isCurrent(attemptGeneration)) {
            return;
        }
        String passwordMd5 = credentialStore.loadPasswordMd5();
        if (passwordMd5 == null) {
            return;
        }
        String credential = api.authenticate(webUiToken);
        // Triggers raised while WebUI was starting are now redundant: this attempt owns the
        // complete quick-login-then-password sequence. Discard them so captcha/new-device
        // responses cannot cause the same password request to be submitted twice.
        rerunRequested.set(false);
        if (waitForQuickLogin(credential, attemptGeneration)) {
            return;
        }
        if (!isCurrent(attemptGeneration)) {
            return;
        }

        JSONObject result;
        try {
            result = api.passwordLogin(credential, qqNumber, passwordMd5);
        } catch (IOException passwordLoginError) {
            // QQ may finish its own quick login between the status check above and this
            // password request. NapCat then reports "QQ Is Logined" as an API error even
            // though the desired outcome has already been reached.
            try {
                JSONObject currentStatus = api.checkLoginStatus(credential);
                if (currentStatus.optBoolean("isLogin", false)) {
                    HostLogger.log(context, "QQ 已在密码请求前完成登录，按成功处理");
                    HostState.set(context, HostState.Phase.RUNNING,
                            "QQ 已登录；WebUI 端口 6099");
                    return;
                }
            } catch (IOException ignored) {
                // Preserve the original password-login error when the follow-up also fails.
            }
            throw passwordLoginError;
        }
        if (result.optBoolean("needCaptcha", false)) {
            HostLogger.log(context, "QQ 密码已提交，需要在 WebUI 完成验证码");
            HostState.set(context, HostState.Phase.RUNNING,
                    "QQ 登录需要验证码，请打开 WebUI");
            return;
        }
        if (result.optBoolean("needNewDevice", false)) {
            HostLogger.log(context, "QQ 密码已提交，需要在 WebUI 完成新设备验证");
            HostState.set(context, HostState.Phase.RUNNING,
                    "QQ 登录需要新设备验证，请打开 WebUI");
            return;
        }

        HostLogger.log(context, "QQ 密码登录请求已安全提交");
        pollForLogin(credential, attemptGeneration);
    }

    private boolean waitForQuickLogin(String credential, long attemptGeneration)
            throws IOException {
        HostLogger.log(context, "先等待 Linux QQ 快速登录（最长 20 秒）");
        for (int observation = 1;
                observation <= QuickLoginPolicy.MAX_OBSERVATIONS
                        && isCurrent(attemptGeneration)
                        && !Thread.currentThread().isInterrupted(); observation++) {
            JSONObject status = api.checkLoginStatus(credential);
            String loginError = status.optString("loginError", "");
            QuickLoginPolicy.Decision decision = QuickLoginPolicy.evaluate(
                    status.optBoolean("isLogin", false),
                    loginError,
                    observation
            );
            if (decision == QuickLoginPolicy.Decision.LOGGED_IN) {
                HostLogger.log(context, "Linux QQ 快速登录成功，跳过密码登录");
                HostState.set(context, HostState.Phase.RUNNING,
                        "QQ 已登录；WebUI 端口 6099");
                return true;
            }
            if (decision == QuickLoginPolicy.Decision.USE_PASSWORD) {
                HostLogger.log(context, loginError.isEmpty()
                        ? "Linux QQ 快速登录等待超时且仍未登录，改用已保存密码"
                        : "Linux QQ 快速登录未成功，改用已保存密码");
                return false;
            }
            try {
                TimeUnit.SECONDS.sleep(QuickLoginPolicy.OBSERVATION_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pollForLogin(String credential, long attemptGeneration) throws IOException {
        for (int attempt = 0; attempt < 15
                && isCurrent(attemptGeneration)
                && !Thread.currentThread().isInterrupted(); attempt++) {
            JSONObject status = api.checkLoginStatus(credential);
            if (status.optBoolean("isLogin", false)) {
                HostLogger.log(context, "QQ 密码自动登录成功");
                HostState.set(context, HostState.Phase.RUNNING,
                        "QQ 已自动登录；WebUI 端口 6099");
                return;
            }
            String loginError = status.optString("loginError", "");
            if (!loginError.isEmpty()) {
                throw new IOException("QQ 登录失败");
            }
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!isCurrent(attemptGeneration)) {
            return;
        }
        HostLogger.log(context, "QQ 密码登录请求已提交，仍在等待登录结果");
        HostState.set(context, HostState.Phase.RUNNING,
                "QQ 正在登录；可打开 WebUI 查看进度");
    }

    private boolean isCurrent(long attemptGeneration) {
        return !shutdown.get() && generation.get() == attemptGeneration;
    }

    private static String safeMessage(String value) {
        if (value == null || value.isEmpty()) {
            return "未知错误";
        }
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160);
    }
}
