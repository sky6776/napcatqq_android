package dev.napcathost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RuntimeEnvironment {
    private static final String[] HOST_TOOL_FILES = {
            "proot",
            "loader",
            "libtalloc.so.2",
            "libandroid-shmem.so"
    };

    private final Context context;
    final File runtimeDir;
    final File rootfsDir;
    final File stagingRootfsDir;
    final File downloadsDir;
    final File hostToolsDir;
    final File tempDir;

    RuntimeEnvironment(Context context) {
        this.context = context.getApplicationContext();
        runtimeDir = new File(this.context.getFilesDir(), "runtime");
        rootfsDir = new File(runtimeDir, "rootfs");
        stagingRootfsDir = new File(runtimeDir, "rootfs.installing");
        downloadsDir = new File(runtimeDir, "downloads");
        hostToolsDir = new File(runtimeDir, "host-tools");
        tempDir = new File(runtimeDir, "tmp");
    }

    void ensureDirectories() throws IOException {
        mkdirs(runtimeDir);
        mkdirs(downloadsDir);
        mkdirs(hostToolsDir);
        mkdirs(tempDir);
    }

    void ensureHostTools() throws IOException {
        ensureDirectories();
        for (String fileName : HOST_TOOL_FILES) {
            File destination = new File(hostToolsDir, fileName);
            if (!destination.isFile() || destination.length() == 0L) {
                copyAsset("runtime-host/arm64-v8a/" + fileName, destination);
            }
            chmod(destination, fileName.equals("proot") || fileName.equals("loader") ? 0700 : 0600);
        }
    }

    boolean isReady() {
        return new File(rootfsDir, ".napcathost-ready").isFile()
                && new File(rootfsDir, "bin/bash").exists()
                && findQqBinary().exists();
    }

    File findQqBinary() {
        File primary = new File(rootfsDir, "root/Napcat/opt/QQ/qq");
        if (primary.exists()) {
            return primary;
        }
        return new File(rootfsDir, "opt/QQ/qq");
    }

    @SuppressLint("SdCardPath")
    List<String> prootCommand(String guestCommand, String qqNumber) {
        File proot = new File(hostToolsDir, "proot");
        List<String> command = new ArrayList<>();
        command.add(proot.getAbsolutePath());
        command.add("-L");
        command.add("--kill-on-exit");
        command.add("--link2symlink");
        command.add("--sysvipc");
        command.add("--kernel-release=6.17.0-PRoot-Distro");
        command.add("--change-id=0:0");
        // This PRoot build accepts the long option only in --rootfs=PATH form.
        command.add("--rootfs=" + rootfsDir.getAbsolutePath());

        addBind(command, new File("/dev"), "/dev");
        addBind(command, new File("/proc"), "/proc");
        addBind(command, new File("/sys"), "/sys");
        addBind(command, new File(rootfsDir, "sys/.empty"), "/sys/fs/selinux");
        addBind(command, new File("/system"), "/system");
        addBind(command, new File("/vendor"), "/vendor");
        addBind(command, new File("/product"), "/system/product");
        addBind(command, new File("/apex"), "/apex");
        addBind(command, new File("/odm"), "/odm");
        addBind(command, new File(rootfsDir, "tmp"), "/dev/shm");

        File external = context.getExternalFilesDir(null);
        if (external != null) {
            addBind(command, external, "/sdcard");
        }

        addBind(command,
                new File(rootfsDir, "proc/.sysctl_inotify_max_user_watches"),
                "/proc/sys/fs/inotify/max_user_watches");
        addBind(command,
                new File(rootfsDir, "proc/.sysctl_entry_cap_last_cap"),
                "/proc/sys/kernel/cap_last_cap");
        addBind(command, new File(rootfsDir, "proc/.vmstat"), "/proc/vmstat");
        addBind(command, new File(rootfsDir, "proc/.version"), "/proc/version");
        addBind(command, new File(rootfsDir, "proc/.uptime"), "/proc/uptime");
        addBind(command, new File(rootfsDir, "proc/.stat"), "/proc/stat");
        addBind(command, new File(rootfsDir, "proc/.loadavg"), "/proc/loadavg");

        command.add("--cwd=/root");
        command.add("/usr/bin/env");
        command.add("-i");
        command.add("HOME=/root");
        command.add("USER=root");
        command.add("LOGNAME=root");
        command.add("LANG=C.UTF-8");
        command.add("TERM=xterm-256color");
        command.add("TMPDIR=/tmp");
        command.add("ANDROID_DATA=/data");
        command.add("ANDROID_ROOT=/system");
        command.add("EXTERNAL_STORAGE=/sdcard");
        command.add("MOZ_FAKE_NO_SANDBOX=1");
        command.add("PULSE_SERVER=127.0.0.1");
        command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.add("NAPCAT_QQ=" + (qqNumber == null ? "" : qqNumber));
        command.add("/bin/bash");
        command.add("-lc");
        command.add(guestCommand);
        return command;
    }

    private static void addBind(List<String> command, File hostPath, String guestPath) {
        if (!hostPath.exists()) {
            return;
        }
        command.add("--bind=" + hostPath.getAbsolutePath() + ":" + guestPath);
    }

    ProcessBuilder processBuilder(String guestCommand, String qqNumber) {
        // Starting PRoot directly can leave Android's ProcessBuilder exec-status pipe open,
        // causing ProcessBuilder.start() to wait forever. Exec the system shell first so
        // Android observes a completed exec, then forward every PRoot argument unchanged.
        List<String> wrappedCommand = new ArrayList<>();
        wrappedCommand.add("/system/bin/sh");
        wrappedCommand.add("-c");
        wrappedCommand.add("exec \"$@\"");
        wrappedCommand.add("napcathost-proot");
        wrappedCommand.addAll(prootCommand(guestCommand, qqNumber));
        ProcessBuilder builder = new ProcessBuilder(wrappedCommand);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("LD_LIBRARY_PATH", hostToolsDir.getAbsolutePath());
        environment.put("PROOT_LOADER", new File(hostToolsDir, "loader").getAbsolutePath());
        environment.put("PROOT_TMP_DIR", tempDir.getAbsolutePath());
        environment.put("HOME", context.getFilesDir().getAbsolutePath());
        environment.put("TMPDIR", tempDir.getAbsolutePath());
        return builder;
    }

    static File safeResolve(File root, String archiveName) throws IOException {
        String normalizedName = archiveName.replace('\\', '/');
        while (normalizedName.startsWith("./")) {
            normalizedName = normalizedName.substring(2);
        }
        File destination = new File(root, normalizedName).getCanonicalFile();
        String rootPath = root.getCanonicalPath();
        if (!destination.getPath().equals(rootPath)
                && !destination.getPath().startsWith(rootPath + File.separator)) {
            throw new IOException("归档包含越界路径：" + archiveName);
        }
        return destination;
    }

    static void deleteInside(File allowedRoot, File target) throws IOException {
        File canonicalRoot = allowedRoot.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        if (canonicalTarget.equals(canonicalRoot)
                || !canonicalTarget.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("拒绝删除运行时目录之外的路径：" + canonicalTarget);
        }
        deleteRecursively(canonicalTarget);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return;
        }
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("无法删除：" + file);
        }
    }

    private void copyAsset(String assetName, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    static void mkdirs(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建目录：" + directory);
        }
    }

    static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            throw new IOException("chmod 失败：" + file, e);
        }
    }
}
