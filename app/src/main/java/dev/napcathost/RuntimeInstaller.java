package dev.napcathost;

import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

final class RuntimeInstaller {
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final String BUNDLED_RUNTIME_ASSET =
            "napcathost-runtime-arm64-v1.tar.zst";
    private static final String BUNDLED_RUNTIME_SHA256 =
            "e65ed5f2ac027518973581b90e84d2446246acb5b1a6156c8ca18ae4b8c0dba4";

    private final Context context;
    private final RuntimeEnvironment environment;
    private volatile boolean cancelled;

    RuntimeInstaller(Context context, RuntimeEnvironment environment) {
        this.context = context.getApplicationContext();
        this.environment = environment;
    }

    void cancel() {
        cancelled = true;
    }

    void installBundled() throws IOException {
        cancelled = false;
        environment.ensureHostTools();
        HostState.set(context, HostState.Phase.INSTALLING, "正在解包 APK 内置运行时");
        try (InputStream input = context.getAssets().open(BUNDLED_RUNTIME_ASSET)) {
            installArchive(input, BUNDLED_RUNTIME_SHA256);
        }
    }

    void installFromUri(Uri uri) throws IOException {
        cancelled = false;
        environment.ensureHostTools();
        HostState.set(context, HostState.Phase.INSTALLING, "正在导入离线运行时");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("无法打开所选文件");
            }
            installArchive(input, null);
        }
    }

    void repairOrUpdate() throws IOException {
        cancelled = false;
        environment.ensureHostTools();
        if (!environment.rootfsDir.isDirectory()) {
            installBundled();
            return;
        }
        prepareGuestFiles(environment.rootfsDir);
        finalizeRuntime();
    }

    private void installArchive(InputStream archive, String expectedSha256) throws IOException {
        if (environment.isReady()) {
            HostLogger.log(context, "运行时已存在，保留账号数据并刷新启动文件");
            prepareGuestFiles(environment.rootfsDir);
            finalizeRuntime();
            return;
        }

        HostState.set(context, HostState.Phase.INSTALLING, "正在解包 Debian rootfs");
        HostLogger.log(context, "开始解包 APK 内置的完整离线运行时");
        environment.ensureDirectories();
        RuntimeEnvironment.deleteInside(environment.runtimeDir, environment.stagingRootfsDir);
        RuntimeEnvironment.mkdirs(environment.stagingRootfsDir);
        extractTarArchive(archive, environment.stagingRootfsDir, expectedSha256);
        ensureRootfsShape(environment.stagingRootfsDir);

        if (environment.rootfsDir.exists()) {
            RuntimeEnvironment.deleteInside(environment.runtimeDir, environment.rootfsDir);
        }
        try {
            Files.move(
                    environment.stagingRootfsDir.toPath(),
                    environment.rootfsDir.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveError) {
            Files.move(environment.stagingRootfsDir.toPath(), environment.rootfsDir.toPath());
        }

        prepareGuestFiles(environment.rootfsDir);
        finalizeRuntime();
    }

    private void finalizeRuntime() throws IOException {
        if (!environment.findQqBinary().isFile()) {
            throw new IOException("离线运行时中未找到 Linux QQ：" + environment.findQqBinary());
        }

        File marker = new File(environment.rootfsDir, ".napcathost-ready");
        writeText(marker, "ready\n");
        HostState.set(context, HostState.Phase.READY, "完整离线运行时已就绪");
        HostLogger.log(context, "离线运行时安装完成，无需下载其他组件");
    }

    private void extractTarArchive(InputStream archive, File destination, String expectedSha256)
            throws IOException {
        int entries = 0;
        List<PendingHardLink> hardLinks = new ArrayList<>();
        MessageDigest digest = sha256Digest();
        try (DigestInputStream digestInput = new DigestInputStream(archive, digest);
             InputStream fileInput = new BufferedInputStream(digestInput, BUFFER_SIZE);
             CompressorInputStream compressorInput = new CompressorStreamFactory()
                     .createCompressorInputStream(fileInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(compressorInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                checkCancelled();
                if (!tarInput.canReadEntryData(entry)) {
                    continue;
                }
                File target = RuntimeEnvironment.safeResolve(destination, entry.getName());
                File parent = target.getParentFile();
                if (parent != null) {
                    RuntimeEnvironment.mkdirs(parent);
                }

                if (entry.isDirectory()) {
                    RuntimeEnvironment.mkdirs(target);
                    chmodQuietly(target, entry.getMode());
                } else if (entry.isSymbolicLink()) {
                    createSymlink(entry.getLinkName(), target);
                } else if (entry.isLink()) {
                    hardLinks.add(new PendingHardLink(target, entry.getLinkName(), entry.getMode()));
                } else if (entry.isFile()) {
                    try (BufferedOutputStream output = new BufferedOutputStream(
                            new FileOutputStream(target), BUFFER_SIZE)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = tarInput.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                    chmodQuietly(target, entry.getMode());
                    if (entry.getLastModifiedDate() != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setLastModified(entry.getLastModifiedDate().getTime());
                    }
                }

                entries++;
                if (entries % 500 == 0) {
                    HostState.set(context, HostState.Phase.INSTALLING,
                            "正在解包 rootfs：" + entries + " 项");
                }
            }
            byte[] drain = new byte[BUFFER_SIZE];
            while (compressorInput.read(drain) >= 0) {
                checkCancelled();
            }
        } catch (CompressorException e) {
            throw new IOException("不支持的 rootfs 压缩格式", e);
        }

        String actualSha256 = toHex(digest.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new IOException("内置运行时校验失败：" + actualSha256);
        }
        HostLogger.log(context, "运行时 SHA-256：" + actualSha256);

        for (PendingHardLink link : hardLinks) {
            File source = RuntimeEnvironment.safeResolve(destination, link.sourceName);
            if (!source.isFile()) {
                throw new IOException("归档硬链接目标不存在：" + link.sourceName);
            }
            Files.copy(source.toPath(), link.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            chmodQuietly(link.target, link.mode);
        }
        HostLogger.log(context, "rootfs 解包完成，共 " + entries + " 项");
    }

    private void ensureRootfsShape(File root) throws IOException {
        if (new File(root, "bin").exists() && new File(root, "usr").isDirectory()) {
            return;
        }
        File[] children = root.listFiles(File::isDirectory);
        if (children != null && children.length == 1
                && new File(children[0], "usr").isDirectory()) {
            File nested = children[0];
            File[] nestedChildren = nested.listFiles();
            if (nestedChildren != null) {
                for (File child : nestedChildren) {
                    Files.move(child.toPath(), new File(root, child.getName()).toPath());
                }
            }
            RuntimeEnvironment.deleteInside(root, nested);
        }
        if (!new File(root, "bin").exists() || !new File(root, "usr").isDirectory()) {
            throw new IOException("所选文件不是有效的 Linux rootfs");
        }
    }

    private void prepareGuestFiles(File rootfs) throws IOException {
        for (String directory : new String[]{
                "apex", "data", "dev", "odm", "sdcard", "storage", "system", "sys/.empty", "vendor",
                "tmp", "var/tmp", "root/.config", "root/.config/QQ"
        }) {
            RuntimeEnvironment.mkdirs(new File(rootfs, directory));
        }
        RuntimeEnvironment.chmod(new File(rootfs, "tmp"), 01777);
        RuntimeEnvironment.chmod(new File(rootfs, "var/tmp"), 01777);

        File hostDirectory = new File(rootfs, "opt/napcathost");
        RuntimeEnvironment.mkdirs(hostDirectory);

        File start = new File(hostDirectory, "start.sh");
        writeText(start,
                "#!/bin/bash\n" +
                        "set -euo pipefail\n" +
                        "export HOME=/root\n" +
                        "export XDG_RUNTIME_DIR=/tmp/napcathost-runtime\n" +
                        "mkdir -p \"$XDG_RUNTIME_DIR\"\n" +
                        "chmod 700 \"$XDG_RUNTIME_DIR\"\n" +
                        "QQ_BIN=/root/Napcat/opt/QQ/qq\n" +
                        "if [ ! -x \"$QQ_BIN\" ] && [ -x /opt/QQ/qq ]; then QQ_BIN=/opt/QQ/qq; fi\n" +
                        "if [ ! -x \"$QQ_BIN\" ]; then echo 'Linux QQ executable not found' >&2; exit 127; fi\n" +
                        "if [[ \"${NAPCAT_QQ:-}\" =~ ^[0-9]{5,12}$ ]]; then\n" +
                        "  exec xvfb-run -a -s '-screen 0 1280x720x24 -nolisten tcp' " +
                        "\"$QQ_BIN\" --no-sandbox -q \"$NAPCAT_QQ\"\n" +
                        "else\n" +
                        "  exec xvfb-run -a -s '-screen 0 1280x720x24 -nolisten tcp' " +
                        "\"$QQ_BIN\" --no-sandbox\n" +
                        "fi\n");
        RuntimeEnvironment.chmod(start, 0755);

        writeText(new File(rootfs, "etc/resolv.conf"),
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\n");
        writeText(new File(rootfs, "etc/hosts"),
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
    }

    private void createSymlink(String linkTarget, File target) throws IOException {
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        try {
            Os.symlink(linkTarget, target.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("创建符号链接失败：" + target, e);
        }
    }

    private static void chmodQuietly(File file, int archiveMode) {
        int mode = archiveMode & 0777;
        if (mode == 0) {
            mode = file.isDirectory() ? 0755 : 0644;
        }
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException ignored) {
            // Some pseudo entries do not permit chmod; extraction can continue.
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new IOException("操作已取消");
        }
    }

    private static void writeText(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            RuntimeEnvironment.mkdirs(parent);
        }
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(value);
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("设备不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format("%02x", item & 0xff));
        }
        return builder.toString();
    }

    private static final class PendingHardLink {
        final File target;
        final String sourceName;
        final int mode;

        PendingHardLink(File target, String sourceName, int mode) {
            this.target = target;
            this.sourceName = sourceName;
            this.mode = mode;
        }
    }
}
