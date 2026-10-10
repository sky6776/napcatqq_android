package dev.napcathost;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NapCatService extends Service {
    static final String ACTION_START = "dev.napcathost.action.START";
    static final String ACTION_STOP = "dev.napcathost.action.STOP";
    static final String ACTION_INSTALL = "dev.napcathost.action.INSTALL";
    static final String ACTION_IMPORT = "dev.napcathost.action.IMPORT";
    static final String ACTION_REPAIR = "dev.napcathost.action.REPAIR";
    static final String ACTION_KEEP_ALIVE = "dev.napcathost.action.KEEP_ALIVE";
    static final String ACTION_REFRESH_KEEP_ALIVE =
            "dev.napcathost.action.REFRESH_KEEP_ALIVE";

    private static final String CHANNEL_ID = "napcat_host_service";
    private static final int NOTIFICATION_ID = 4401;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService installerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "runtime-installer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean installing = new AtomicBoolean(false);

    private RuntimeEnvironment environment;
    private RuntimeInstaller installer;
    private ProcessSupervisor supervisor;
    private ConnectionHealthMonitor healthMonitor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean wakeLockUsesLease;

    private final Runnable notificationUpdater = new Runnable() {
        @Override
        public void run() {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
            handler.postDelayed(this, 2_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        environment = new RuntimeEnvironment(this);
        installer = new RuntimeInstaller(this, environment);
        supervisor = new ProcessSupervisor(this, environment);
        healthMonitor = new ConnectionHealthMonitor(this, supervisor);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.post(notificationUpdater);
        HostLogger.log(this, "前台服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopHost();
        } else if (ACTION_KEEP_ALIVE.equals(action)) {
            if (HostConfig.preferences(this).getBoolean("desired_running", false)) {
                renewWakeLock();
                startHost();
                healthMonitor.checkSoon("周期唤醒", 0L);
            } else {
                KeepAliveScheduler.cancel(this);
                stopSelf(startId);
            }
        } else if (ACTION_REFRESH_KEEP_ALIVE.equals(action)) {
            if (HostConfig.preferences(this).getBoolean("desired_running", false)) {
                startHost();
                healthMonitor.refreshNetworkPolicy();
            } else {
                refreshKeepAlivePolicy();
            }
        } else if (ACTION_INSTALL.equals(action)) {
            beginInstall(null, false);
        } else if (ACTION_IMPORT.equals(action)) {
            beginInstall(intent.getData(), false);
        } else if (ACTION_REPAIR.equals(action)) {
            beginInstall(null, true);
        } else if (ACTION_START.equals(action)) {
            startHost();
        } else if (HostConfig.preferences(this).getBoolean("desired_running", false)
                || HostConfig.autoStart(this)) {
            startHost();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(notificationUpdater);
        installer.cancel();
        healthMonitor.shutdown();
        supervisor.shutdown();
        installerExecutor.shutdownNow();
        releaseWakeLock();
        releaseWifiLock();
        if (HostConfig.preferences(this).getBoolean("desired_running", false)
                && HostConfig.enhancedKeepAlive(this)) {
            KeepAliveScheduler.schedule(this);
        } else {
            KeepAliveScheduler.cancel(this);
        }
        HostLogger.log(this, "前台服务已销毁");
        super.onDestroy();
    }

    private void startHost() {
        acquireWakeLock();
        if (!environment.isReady()) {
            HostConfig.preferences(this).edit().putBoolean("desired_running", true).apply();
            refreshKeepAlivePolicy();
            HostLogger.log(this, "首次启动：先解包 APK 内置离线运行时");
            beginInstall(null, false);
            return;
        }
        if (!supervisor.start()) {
            HostLogger.log(this, "运行时未就绪，无法启动");
        } else {
            refreshKeepAlivePolicy();
            healthMonitor.start();
        }
    }

    private void stopHost() {
        installer.cancel();
        healthMonitor.pause();
        supervisor.stop();
        KeepAliveScheduler.cancel(this);
        releaseWifiLock();
        releaseWakeLock();
        HostLogger.log(this, "用户请求停止 NapCat");
        handler.postDelayed(this::stopSelf, 1_000L);
    }

    private void beginInstall(Uri uri, boolean repairOnly) {
        if (!installing.compareAndSet(false, true)) {
            HostLogger.log(this, "安装任务已在执行，忽略重复请求");
            return;
        }
        boolean resumeAfterInstall = supervisor.isDesiredRunning()
                || HostConfig.preferences(this).getBoolean("desired_running", false);
        healthMonitor.pause();
        supervisor.stop();
        if (resumeAfterInstall) {
            HostConfig.preferences(this).edit().putBoolean("desired_running", true).apply();
        }
        acquireWakeLock();
        installerExecutor.execute(() -> {
            try {
                HostState.set(this, HostState.Phase.INSTALLING, "准备运行时安装");
                if (repairOnly) {
                    installer.repairOrUpdate();
                } else if (uri != null) {
                    installer.installFromUri(uri);
                } else {
                    installer.installBundled();
                }
                if (HostConfig.autoStart(this)
                        || HostConfig.preferences(this).getBoolean("desired_running", false)) {
                    supervisor.start();
                    refreshKeepAlivePolicy();
                    healthMonitor.start();
                }
            } catch (IOException | RuntimeException e) {
                HostState.set(this, HostState.Phase.ERROR,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                HostLogger.log(this, "运行时安装失败：" + e);
            } finally {
                installing.set(false);
                if (!supervisor.isDesiredRunning()) {
                    releaseWakeLock();
                }
            }
        });
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        updateWakeLock(false);
    }

    @SuppressLint("WakelockTimeout")
    private void renewWakeLock() {
        updateWakeLock(true);
    }

    @SuppressLint("WakelockTimeout")
    private void updateWakeLock(boolean forceRenewal) {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "napcathost:service-wakelock");
            wakeLock.setReferenceCounted(false);
        }
        boolean useLease = HostConfig.enhancedKeepAlive(this);
        boolean modeChanged = wakeLock.isHeld() && wakeLockUsesLease != useLease;
        try {
            // Some vendor power managers drop a long-held kernel wake lock without updating
            // WakeLock.isHeld() in the client process. Release/reacquire on every alarm so the
            // framework and kernel state are synchronized again.
            if ((forceRenewal || modeChanged) && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (!wakeLock.isHeld()) {
                if (useLease) {
                    wakeLock.acquire(KeepAliveScheduler.WAKE_LOCK_LEASE_MILLIS);
                } else {
                    wakeLock.acquire();
                }
                wakeLockUsesLease = useLease;
                HostConfig.recordWakeLockRenewal(this, System.currentTimeMillis(), true);
                if (forceRenewal) {
                    HostLogger.log(this, useLease
                            ? "CPU 保活锁已重新获取，租约 20 分钟"
                            : "CPU 保活锁已重新获取");
                }
            }
        } catch (RuntimeException e) {
            HostConfig.recordWakeLockRenewal(this, System.currentTimeMillis(), false);
            HostLogger.log(this, "CPU 保活锁获取失败：" + e.getClass().getSimpleName());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException ignored) {
                // The vendor power manager may already have revoked the underlying lock.
            }
        }
    }

    private void refreshKeepAlivePolicy() {
        boolean enabled = HostConfig.enhancedKeepAlive(this)
                && HostConfig.preferences(this).getBoolean("desired_running", false);
        if (enabled) {
            acquireWifiLock();
            KeepAliveScheduler.schedule(this);
        } else {
            releaseWifiLock();
            KeepAliveScheduler.cancel(this);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        if (wifiLock == null) {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (manager == null) {
                return;
            }
            wifiLock = manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "napcathost:wifi-keepalive"
            );
            wifiLock.setReferenceCounted(false);
        }
        try {
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (RuntimeException e) {
            HostLogger.log(this, "Wi-Fi 保活锁获取失败：" + e.getClass().getSimpleName());
        }
    }

    private void releaseWifiLock() {
        if (wifiLock == null) {
            return;
        }
        try {
            if (wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (RuntimeException ignored) {
            // Releasing a best-effort network lock must not terminate the service.
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        HostState.Snapshot snapshot = HostState.get(this);
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent openActivity = PendingIntent.getActivity(
                this,
                1,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent startIntent = new Intent(this, NapCatService.class).setAction(ACTION_START);
        PendingIntent startAction = PendingIntent.getService(
                this,
                2,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, NapCatService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this,
                3,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cat)
                .setContentTitle(HostState.label(snapshot.phase))
                .setContentText(snapshot.detail)
                .setContentIntent(openActivity)
                .setOngoing(snapshot.phase == HostState.Phase.RUNNING
                        || snapshot.phase == HostState.Phase.INSTALLING
                        || snapshot.phase == HostState.Phase.STARTING)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        if (supervisor != null && supervisor.isDesiredRunning()) {
            builder.addAction(new Notification.Action.Builder(
                    null, "停止", stopAction).build());
        } else {
            builder.addAction(new Notification.Action.Builder(
                    null, "启动", startAction).build());
        }
        return builder.build();
    }
}
