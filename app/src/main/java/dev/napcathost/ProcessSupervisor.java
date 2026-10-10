package dev.napcathost;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProcessSupervisor {
    private final Context context;
    private final RuntimeEnvironment environment;
    private final QqAutoLogin autoLogin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "napcat-supervisor");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean desiredRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthRestartRequested = new AtomicBoolean(false);
    private volatile Process activeProcess;

    ProcessSupervisor(Context context, RuntimeEnvironment environment) {
        this.context = context.getApplicationContext();
        this.environment = environment;
        autoLogin = new QqAutoLogin(this.context);
    }

    synchronized boolean start() {
        if (desiredRunning.get()) {
            autoLogin.trigger();
            return true;
        }
        if (!environment.isReady()) {
            HostState.set(context, HostState.Phase.ERROR, "请先安装运行时");
            return false;
        }
        try {
            environment.ensureHostTools();
        } catch (IOException e) {
            HostState.set(context, HostState.Phase.ERROR, e.getMessage());
            HostLogger.log(context, "准备宿主工具失败：" + e);
            return false;
        }

        desiredRunning.set(true);
        HostConfig.preferences(context).edit().putBoolean("desired_running", true).apply();
        executor.execute(this::superviseLoop);
        autoLogin.trigger();
        return true;
    }

    synchronized void stop() {
        desiredRunning.set(false);
        healthRestartRequested.set(false);
        autoLogin.cancel();
        HostConfig.preferences(context).edit().putBoolean("desired_running", false).apply();
        HostState.set(context, HostState.Phase.STOPPING, "正在停止 Linux QQ");
        Process process = activeProcess;
        if (process != null) {
            process.destroy();
            Thread stopper = new Thread(() -> forceStopAfterDelay(process), "napcat-force-stop");
            stopper.setDaemon(true);
            stopper.start();
        } else {
            HostState.set(context, HostState.Phase.READY,
                    environment.isReady() ? "运行时已就绪" : "未安装运行时");
        }
    }

    boolean isDesiredRunning() {
        return desiredRunning.get();
    }

    boolean isProcessAlive() {
        Process process = activeProcess;
        return process != null && process.isAlive();
    }

    void shutdown() {
        desiredRunning.set(false);
        healthRestartRequested.set(false);
        Process process = activeProcess;
        if (process != null) {
            process.destroy();
        }
        executor.shutdownNow();
        autoLogin.shutdown();
    }

    void triggerAutoLogin() {
        autoLogin.trigger();
    }

    synchronized boolean requestRestart(String reason) {
        if (!desiredRunning.get() || !healthRestartRequested.compareAndSet(false, true)) {
            return false;
        }
        Process process = activeProcess;
        if (process == null || !process.isAlive()) {
            healthRestartRequested.set(false);
            return false;
        }
        HostLogger.log(context, reason);
        HostState.set(context, HostState.Phase.STARTING, reason);
        process.destroy();
        Thread stopper = new Thread(() -> forceStopAfterDelay(process),
                "napcat-health-restart");
        stopper.setDaemon(true);
        stopper.start();
        return true;
    }

    private void superviseLoop() {
        int failures = 0;
        while (desiredRunning.get()) {
            long startedAt = System.currentTimeMillis();
            int exitCode = -1;
            try {
                HostState.set(context, HostState.Phase.STARTING, "正在启动 Xvfb、Linux QQ 与 NapCat");
                HostLogger.log(context, "启动 Linux QQ/NapCat");
                ProcessBuilder builder = environment.processBuilder(
                        "/opt/napcathost/start.sh",
                        HostConfig.qqNumber(context)
                );
                activeProcess = builder.start();
                HostState.set(context, HostState.Phase.RUNNING,
                        "前台监管中；WebUI 端口 6099");

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(activeProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        HostLogger.log(context, "[NapCat] " + line);
                        if (line.contains("WebUi User Panel Url:")) {
                            autoLogin.trigger();
                        }
                    }
                }
                exitCode = activeProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                HostLogger.log(context, "启动或读取进程失败：" + e);
            } finally {
                activeProcess = null;
            }

            if (!desiredRunning.get()) {
                break;
            }

            if (healthRestartRequested.getAndSet(false)) {
                HostLogger.log(context, "健康检查自愈重启 Linux QQ/NapCat");
                try {
                    TimeUnit.SECONDS.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            long lifetime = System.currentTimeMillis() - startedAt;
            if (lifetime >= TimeUnit.MINUTES.toMillis(5)) {
                failures = 0;
            } else {
                failures++;
            }
            long delaySeconds = Math.min(60L, 2L << Math.min(failures, 5));
            String detail = "进程退出（" + exitCode + "），" + delaySeconds + " 秒后重启";
            HostState.set(context, HostState.Phase.BACKOFF, detail);
            HostLogger.log(context, detail);
            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!desiredRunning.get()) {
            HostState.set(context, HostState.Phase.READY,
                    environment.isReady() ? "运行时已就绪" : "未安装运行时");
        }
    }

    private void forceStopAfterDelay(Process process) {
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                HostLogger.log(context, "进程未在 8 秒内退出，执行强制停止");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
