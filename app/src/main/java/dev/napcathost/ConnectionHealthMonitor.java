package dev.napcathost;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ConnectionHealthMonitor {
    private static final long PERIODIC_CHECK_MINUTES = 3L;
    private static final long NETWORK_SETTLE_SECONDS = 5L;
    private static final long FAILURE_RETRY_SECONDS = 20L;
    private static final long AUTO_LOGIN_RECHECK_SECONDS = 75L;

    private final Context context;
    private final ProcessSupervisor supervisor;
    private final ConnectivityManager connectivityManager;
    private final NapCatWebApi api = new NapCatWebApi();
    private final QqCredentialStore credentialStore;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "napcat-health-monitor");
                thread.setDaemon(true);
                return thread;
            }
    );
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);

    private volatile boolean networkAvailable = true;
    private volatile Network currentNetwork;
    private volatile int consecutiveWebUiFailures;
    private volatile int consecutiveLoggedOutChecks;
    private volatile Boolean lastLoginStatus;
    private volatile ScheduledFuture<?> periodicTask;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Network previousNetwork = currentNetwork;
                    boolean switched = previousNetwork != null
                            && !previousNetwork.equals(network);
                    boolean recovered = !networkAvailable || switched;
                    currentNetwork = network;
                    networkAvailable = true;
                    if (recovered) {
                        HostConfig.recordNetworkRecovery(context, System.currentTimeMillis());
                        HostLogger.log(context, switched
                                ? "默认网络已切换，准备检查 QQ 连接"
                                : "默认网络已恢复，准备检查 QQ 连接");
                    }
                    checkSoon("网络可用", NETWORK_SETTLE_SECONDS);
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network,
                        NetworkCapabilities capabilities
                ) {
                    boolean validated = capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    );
                    if (validated && !networkAvailable) {
                        networkAvailable = true;
                        HostConfig.recordNetworkRecovery(context, System.currentTimeMillis());
                        HostLogger.log(context, "网络已验证可用，准备检查 QQ 连接");
                        checkSoon("网络验证通过", NETWORK_SETTLE_SECONDS);
                    }
                }

                @Override
                public void onLost(Network network) {
                    Network activeNetwork = currentNetwork;
                    if (activeNetwork != null && !activeNetwork.equals(network)) {
                        // Android may report the old default network as lost after a new default
                        // network has already become available. Do not mark the new one offline.
                        return;
                    }
                    currentNetwork = null;
                    networkAvailable = false;
                    consecutiveLoggedOutChecks = 0;
                    HostLogger.log(context, "默认网络已断开，等待系统恢复连接");
                }
            };

    ConnectionHealthMonitor(Context context, ProcessSupervisor supervisor) {
        this.context = context.getApplicationContext();
        this.supervisor = supervisor;
        connectivityManager = (ConnectivityManager) this.context.getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
        credentialStore = new QqCredentialStore(this.context);
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (connectivityManager != null) {
            try {
                if (HostConfig.enhancedKeepAlive(context)) {
                    NetworkRequest.Builder request = new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
                    // Unlike a passive default-network callback, requestNetwork asks Android
                    // to keep one matching physical Internet network available for this FGS.
                    connectivityManager.requestNetwork(request.build(), networkCallback);
                    HostLogger.log(context, "已请求系统主动维持物理网络");
                } else {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback);
                }
            } catch (RuntimeException e) {
                HostLogger.log(context,
                        "注册或请求网络失败：" + e.getClass().getSimpleName());
            }
        }
        periodicTask = executor.scheduleWithFixedDelay(
                () -> check("定时检查"),
                PERIODIC_CHECK_MINUTES,
                PERIODIC_CHECK_MINUTES,
                TimeUnit.MINUTES
        );
        checkSoon("服务启动", 10L);
    }

    void pause() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // The callback may already have been removed when the process was stopping.
            }
        }
        ScheduledFuture<?> task = periodicTask;
        if (task != null) {
            task.cancel(false);
            periodicTask = null;
        }
        consecutiveWebUiFailures = 0;
        consecutiveLoggedOutChecks = 0;
        currentNetwork = null;
        networkAvailable = true;
    }

    void refreshNetworkPolicy() {
        if (!started.get()) {
            return;
        }
        pause();
        start();
    }

    void shutdown() {
        pause();
        executor.shutdownNow();
    }

    void checkSoon(String reason, long delaySeconds) {
        if (!started.get() || executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(() -> check(reason), delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Service shutdown won the race with a network callback.
        }
    }

    private void check(String reason) {
        if (!started.get()
                || !HostConfig.preferences(context).getBoolean("desired_running", false)
                || !checkInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!supervisor.isProcessAlive()) {
                HostLogger.log(context, "健康检查发现监管进程未运行，尝试恢复");
                supervisor.start();
                return;
            }
            if (!networkAvailable) {
                return;
            }
            String webUiToken = HostLogger.latestWebUiToken(context);
            if (webUiToken == null) {
                recordWebUiFailure(reason, "尚未发现 WebUI");
                return;
            }

            String credential = api.authenticate(webUiToken);
            JSONObject status = api.checkLoginStatus(credential);
            boolean loggedIn = status.optBoolean("isLogin", false);
            consecutiveWebUiFailures = 0;
            if (loggedIn) {
                consecutiveLoggedOutChecks = 0;
                HostConfig.recordHealthCheck(context, System.currentTimeMillis(), true);
                Boolean previous = lastLoginStatus;
                lastLoginStatus = true;
                HostState.Snapshot snapshot = HostState.get(context);
                boolean staleLoginState = HealthCheckPolicy.shouldReconcileOnlineState(
                        snapshot.detail
                );
                if (Boolean.FALSE.equals(previous) || staleLoginState) {
                    HostLogger.log(context, staleLoginState
                            ? "健康检查确认 QQ 在线，已清除旧的登录失败提示"
                            : "QQ 连接已经恢复");
                    HostState.set(context, HostState.Phase.RUNNING,
                            "QQ 连接已恢复；WebUI 端口 6099");
                }
                return;
            }

            int loggedOutChecks = ++consecutiveLoggedOutChecks;
            if (!HealthCheckPolicy.isLoggedOutConfirmed(loggedOutChecks)) {
                HostLogger.log(context, "健康检查暂未发现 QQ 登录，20 秒后复核");
                checkSoon("登录状态复核", FAILURE_RETRY_SECONDS);
                return;
            }
            HostConfig.recordHealthCheck(context, System.currentTimeMillis(), false);
            Boolean previous = lastLoginStatus;
            lastLoginStatus = false;
            HostState.Snapshot snapshot = HostState.get(context);
            boolean waitingForUser = HealthCheckPolicy.requiresUserVerification(snapshot.detail);

            if (!Boolean.FALSE.equals(previous)) {
                HostLogger.log(context, "健康检查发现 QQ 未登录");
            }
            if (waitingForUser) {
                if (loggedOutChecks == HealthCheckPolicy.LOGGED_OUT_CHECKS_BEFORE_RESTART) {
                    HostLogger.log(context, "QQ 登录正在等待人工验证，暂不自动重启");
                }
                return;
            }
            if (HealthCheckPolicy.shouldRestartAfterLoggedOut(loggedOutChecks)) {
                consecutiveLoggedOutChecks = 0;
                if (supervisor.requestRestart(
                        "QQ 持续离线且自动登录未恢复，重启 Linux QQ/NapCat")) {
                    HostConfig.recordHealthRestart(context, System.currentTimeMillis());
                }
                return;
            }
            if (credentialStore.hasSavedCredential()) {
                HostState.set(context, HostState.Phase.RUNNING,
                        "QQ 已掉线，正在尝试安全自动登录");
                supervisor.triggerAutoLogin();
                checkSoon("自动登录结果复核", AUTO_LOGIN_RECHECK_SECONDS);
            } else {
                HostState.set(context, HostState.Phase.RUNNING,
                        "QQ 已掉线，请打开 WebUI 重新登录");
            }
        } catch (IOException e) {
            recordWebUiFailure(reason, safeMessage(e.getMessage()));
        } finally {
            checkInFlight.set(false);
        }
    }

    private void recordWebUiFailure(String reason, String detail) {
        consecutiveLoggedOutChecks = 0;
        int failures = ++consecutiveWebUiFailures;
        HostLogger.log(context, "WebUI 健康检查失败（" + failures + "/"
                + HealthCheckPolicy.WEB_UI_FAILURES_BEFORE_RESTART + "，" + reason
                + "）：" + detail);
        if (HealthCheckPolicy.shouldRestart(failures)) {
            consecutiveWebUiFailures = 0;
            if (supervisor.requestRestart("WebUI 连续三次无响应，执行自愈重启")) {
                HostConfig.recordHealthRestart(context, System.currentTimeMillis());
            }
        } else {
            checkSoon("失败重试", FAILURE_RETRY_SECONDS);
        }
    }

    private static String safeMessage(String value) {
        if (value == null || value.isEmpty()) {
            return "未知错误";
        }
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 120 ? singleLine : singleLine.substring(0, 120);
    }
}
