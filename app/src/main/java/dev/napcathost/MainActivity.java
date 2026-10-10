package dev.napcathost;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.security.GeneralSecurityException;
import java.util.Date;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int REQUEST_RUNTIME_ARCHIVE = 7001;
    private static final int REQUEST_NOTIFICATIONS = 7002;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView stateText;
    private TextView detailText;
    private TextView environmentText;
    private TextView logText;
    private EditText qqInput;
    private EditText qqPasswordInput;
    private CheckBox autoStartCheck;
    private CheckBox enhancedKeepAliveCheck;
    private CheckBox passwordAutoLoginCheck;
    private TextView passwordStatusText;
    private Button bundledRuntimeButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1_500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(buildContentView());
        requestNotificationPermissionIfNeeded();
        resumeDesiredHostIfNeeded();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_RUNTIME_ARCHIVE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The one-shot read grant is enough for the service started below.
        }
        Intent service = new Intent(this, NapCatService.class)
                .setAction(NapCatService.ACTION_IMPORT)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(service);
    }

    private View buildContentView() {
        int padding = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(244, 245, 247));

        TextView title = text("NapCatHost", 28, Color.rgb(49, 46, 129));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                installedVersionLabel() + " · Android 10 · ARM64 · 独立前台宿主",
                14,
                Color.DKGRAY
        );
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        LinearLayout statusCard = card();
        stateText = text("未运行", 21, Color.rgb(55, 65, 81));
        stateText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusCard.addView(stateText);
        detailText = text("", 14, Color.DKGRAY);
        detailText.setPadding(0, dp(5), 0, 0);
        statusCard.addView(detailText);
        environmentText = text("", 12, Color.GRAY);
        environmentText.setPadding(0, dp(8), 0, 0);
        statusCard.addView(environmentText);
        root.addView(statusCard);

        TextView configTitle = sectionTitle("账号与恢复");
        root.addView(configTitle);

        qqInput = new EditText(this);
        qqInput.setHint("QQ号（留空则启动后扫码）");
        qqInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        qqInput.setSingleLine(true);
        qqInput.setText(HostConfig.qqNumber(this));
        root.addView(qqInput, matchWrap());

        QqCredentialStore credentialStore = new QqCredentialStore(this);
        passwordAutoLoginCheck = new CheckBox(this);
        passwordAutoLoginCheck.setText("安全保存 QQ 密码并自动登录");
        passwordAutoLoginCheck.setChecked(credentialStore.hasSavedCredential());
        root.addView(passwordAutoLoginCheck, matchWrap());

        qqPasswordInput = new EditText(this);
        qqPasswordInput.setHint(credentialStore.hasSavedCredential()
                ? "密码已加密保存；留空可继续使用"
                : "QQ 密码");
        qqPasswordInput.setSingleLine(true);
        qqPasswordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        qqPasswordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        qqPasswordInput.setSaveEnabled(false);
        qqPasswordInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(qqPasswordInput, matchWrap());

        passwordStatusText = text("", 12, Color.GRAY);
        passwordStatusText.setPadding(0, dp(2), 0, dp(4));
        root.addView(passwordStatusText, matchWrap());
        passwordAutoLoginCheck.setOnCheckedChangeListener((button, checked) ->
                updatePasswordControls());
        updatePasswordControls();

        autoStartCheck = new CheckBox(this);
        autoStartCheck.setText("开机后自动恢复前台服务");
        autoStartCheck.setChecked(HostConfig.autoStart(this));
        autoStartCheck.setOnCheckedChangeListener((button, checked) ->
                HostConfig.setAutoStart(this, checked));
        root.addView(autoStartCheck, matchWrap());

        enhancedKeepAliveCheck = new CheckBox(this);
        enhancedKeepAliveCheck.setText("锁屏增强保活（每 10 分钟唤醒，增加耗电）");
        enhancedKeepAliveCheck.setChecked(HostConfig.enhancedKeepAlive(this));
        root.addView(enhancedKeepAliveCheck, matchWrap());

        Button save = button("保存配置");
        save.setOnClickListener(view -> saveConfiguration());
        root.addView(save, matchWrapWithTop(6));

        TextView runtimeTitle = sectionTitle("运行时");
        root.addView(runtimeTitle);
        LinearLayout runtimeRow = horizontalRow();
        bundledRuntimeButton = button("初始化内置运行时");
        bundledRuntimeButton.setOnClickListener(view -> confirmOnlineInstall());
        runtimeRow.addView(bundledRuntimeButton, weightedButton());
        Button importRuntime = button("导入 rootfs");
        importRuntime.setOnClickListener(view -> chooseRuntimeArchive());
        runtimeRow.addView(importRuntime, weightedButtonWithLeft());
        root.addView(runtimeRow, matchWrap());

        Button repair = button("修复离线运行时启动文件");
        repair.setOnClickListener(view -> confirmRepair());
        root.addView(repair, matchWrapWithTop(8));

        TextView serviceTitle = sectionTitle("服务控制");
        root.addView(serviceTitle);
        LinearLayout serviceRow = horizontalRow();
        Button start = button("启动");
        start.setOnClickListener(view -> {
            if (saveConfiguration()) {
                startServiceAction(NapCatService.ACTION_START);
            }
        });
        serviceRow.addView(start, weightedButton());
        Button stop = button("停止");
        stop.setOnClickListener(view -> startServiceAction(NapCatService.ACTION_STOP));
        serviceRow.addView(stop, weightedButtonWithLeft());
        root.addView(serviceRow, matchWrap());

        LinearLayout toolsRow = horizontalRow();
        Button webUi = button("打开 WebUI");
        webUi.setOnClickListener(view -> openWebUi());
        toolsRow.addView(webUi, weightedButton());
        Button battery = button("电池白名单");
        battery.setOnClickListener(view -> requestBatteryWhitelist());
        toolsRow.addView(battery, weightedButtonWithLeft());
        root.addView(toolsRow, matchWrapWithTop(8));

        TextView logTitle = sectionTitle("最近日志");
        root.addView(logTitle);
        logText = text("暂无日志", 11, Color.rgb(229, 231, 235));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logText.setBackgroundColor(Color.rgb(17, 24, 39));
        root.addView(logText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));

        TextView note = text(
                "说明：APK 已内置 Debian、系统依赖、Xvfb、Linux QQ 与 NapCat。" +
                        "首次启动只会解包本地运行时，不会下载组件。" +
                        "非 Root 系统在用户“强行停止”应用后无法自行恢复。",
                12,
                Color.GRAY
        );
        note.setPadding(0, dp(14), 0, dp(20));
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private String installedVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "版本 v" + info.versionName + "（构建 " + info.versionCode + "）";
        } catch (PackageManager.NameNotFoundException e) {
            return "版本未知";
        }
    }

    private void refreshStatus() {
        HostState.Snapshot snapshot = HostState.get(this);
        stateText.setText(HostState.label(snapshot.phase));
        detailText.setText(snapshot.detail);
        int stateColor = snapshot.phase == HostState.Phase.RUNNING
                ? Color.rgb(5, 150, 105)
                : snapshot.phase == HostState.Phase.ERROR
                ? Color.rgb(220, 38, 38)
                : Color.rgb(55, 65, 81);
        stateText.setTextColor(stateColor);

        RuntimeEnvironment environment = new RuntimeEnvironment(this);
        boolean runtimeReady = environment.isReady();
        bundledRuntimeButton.setEnabled(!runtimeReady);
        bundledRuntimeButton.setAlpha(runtimeReady ? 0.42f : 1f);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        boolean whitelisted = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        String changed = snapshot.changedAt == 0L
                ? ""
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(snapshot.changedAt));
        long lastKeepAliveAt = HostConfig.lastKeepAliveAt(this);
        String keepAliveStatus = !HostConfig.enhancedKeepAlive(this)
                ? "已关闭"
                : lastKeepAliveAt == 0L
                ? "闹钟级已开启，等待首次触发"
                : "闹钟级已开启，上次 " + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                .format(new Date(lastKeepAliveAt));
        long lastWakeLockRenewalAt = HostConfig.lastWakeLockRenewalAt(this);
        String wakeLockStatus = lastWakeLockRenewalAt == 0L
                ? "等待获取"
                : (HostConfig.lastWakeLockRenewalSucceeded(this) ? "已续租 " : "获取失败 ")
                + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                .format(new Date(lastWakeLockRenewalAt));
        long lastHealthCheckAt = HostConfig.lastHealthCheckAt(this);
        String healthStatus = lastHealthCheckAt == 0L
                ? "等待首次检查"
                : DateFormat.getTimeInstance(DateFormat.MEDIUM)
                .format(new Date(lastHealthCheckAt))
                + (HostConfig.lastHealthCheckLoggedIn(this) ? "，QQ 在线" : "，QQ 未登录");
        long lastHealthRestartAt = HostConfig.lastHealthRestartAt(this);
        if (lastHealthRestartAt != 0L) {
            healthStatus += "；上次自愈 " + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                    .format(new Date(lastHealthRestartAt));
        }
        environmentText.setText(
                "运行时：" + (runtimeReady ? "已安装" : "未安装")
                        + "  ·  电池限制：" + (whitelisted ? "已豁免" : "未豁免")
                        + "  ·  周期唤醒：" + keepAliveStatus
                        + "  ·  CPU 保活锁：" + wakeLockStatus
                        + "  ·  健康检查：" + healthStatus
                        + (changed.isEmpty() ? "" : "  ·  " + changed)
        );
        updatePasswordStatus();
        logText.setText(HostLogger.tail(this, 120));
    }

    private boolean saveConfiguration() {
        if (!HostConfig.setQqNumber(this, qqInput.getText().toString())) {
            Toast.makeText(this, "QQ号应为 5 到 12 位数字", Toast.LENGTH_LONG).show();
            return false;
        }

        QqCredentialStore credentialStore = new QqCredentialStore(this);
        String qqNumber = HostConfig.qqNumber(this);
        String password = qqPasswordInput.getText().toString();
        if (passwordAutoLoginCheck.isChecked()) {
            if (qqNumber.isEmpty()) {
                Toast.makeText(this, "启用密码自动登录时必须填写 QQ 号", Toast.LENGTH_LONG).show();
                return false;
            }
            if (password.length() > 128) {
                Toast.makeText(this, "QQ 密码过长", Toast.LENGTH_LONG).show();
                return false;
            }
            if (!password.isEmpty()) {
                try {
                    credentialStore.savePassword(password);
                } catch (GeneralSecurityException e) {
                    Toast.makeText(this,
                            "Android Keystore 保存密码失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    return false;
                }
            } else if (!credentialStore.hasSavedCredential()) {
                Toast.makeText(this, "请输入要安全保存的 QQ 密码", Toast.LENGTH_LONG).show();
                return false;
            }
        } else {
            credentialStore.clear();
        }

        HostConfig.setAutoStart(this, autoStartCheck.isChecked());
        HostConfig.setEnhancedKeepAlive(this, enhancedKeepAliveCheck.isChecked());
        boolean desiredRunning = HostConfig.preferences(this)
                .getBoolean("desired_running", false);
        if (desiredRunning) {
            startServiceAction(NapCatService.ACTION_REFRESH_KEEP_ALIVE);
        } else if (!enhancedKeepAliveCheck.isChecked()) {
            KeepAliveScheduler.cancel(this);
        }
        qqPasswordInput.setText("");
        qqPasswordInput.setHint(credentialStore.hasSavedCredential()
                ? "密码已加密保存；留空可继续使用"
                : "QQ 密码");
        updatePasswordStatus();
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void updatePasswordControls() {
        if (qqPasswordInput == null) {
            return;
        }
        boolean enabled = passwordAutoLoginCheck.isChecked();
        qqPasswordInput.setEnabled(enabled);
        qqPasswordInput.setAlpha(enabled ? 1f : 0.55f);
        updatePasswordStatus();
    }

    private void updatePasswordStatus() {
        if (passwordStatusText == null) {
            return;
        }
        boolean saved = new QqCredentialStore(this).hasSavedCredential();
        if (saved) {
            passwordStatusText.setText(
                    "密码登录凭据已由 Android Keystore 加密；不会传入命令行或日志"
            );
        } else if (passwordAutoLoginCheck != null && passwordAutoLoginCheck.isChecked()) {
            passwordStatusText.setText("输入密码并保存后，启动时会尝试自动登录");
        } else {
            passwordStatusText.setText("未启用密码自动登录；首次登录可继续使用二维码");
        }
    }

    private void confirmOnlineInstall() {
        new AlertDialog.Builder(this)
                .setTitle("初始化内置运行时")
                .setMessage("将从 APK 解包完整 ARM64 运行时到应用私有目录，全程无需下载。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("安装", (dialog, which) ->
                        startServiceAction(NapCatService.ACTION_INSTALL))
                .show();
    }

    private void confirmRepair() {
        new AlertDialog.Builder(this)
                .setTitle("修复启动文件")
                .setMessage("将保留现有账号数据，并重新生成离线运行时的启动配置。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) ->
                        startServiceAction(NapCatService.ACTION_REPAIR))
                .show();
    }

    private void chooseRuntimeArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_RUNTIME_ARCHIVE);
    }

    private void startServiceAction(String action) {
        Intent intent = new Intent(this, NapCatService.class).setAction(action);
        startForegroundService(intent);
    }

    private void resumeDesiredHostIfNeeded() {
        if (HostConfig.preferences(this).getBoolean("desired_running", false)) {
            startServiceAction(NapCatService.ACTION_START);
        }
    }

    private void openWebUi() {
        String latestUrl = HostLogger.latestWebUiUrl(this);
        if (latestUrl == null) {
            latestUrl = "http://127.0.0.1:6099/webui";
            Toast.makeText(this, "WebUI 令牌尚未生成，先打开基础页面", Toast.LENGTH_SHORT).show();
        }
        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse(latestUrl));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryWhitelist() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "已经位于电池优化白名单", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        layout.setBackgroundColor(Color.WHITE);
        layout.setElevation(dp(2));
        return layout;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 16, Color.rgb(55, 65, 81));
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedButtonWithLeft() {
        LinearLayout.LayoutParams params = weightedButton();
        params.leftMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
