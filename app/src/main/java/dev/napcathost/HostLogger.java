package dev.napcathost;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HostLogger {
    private static final long MAX_LOG_SIZE = 2L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final Pattern WEB_UI_TOKEN_URL = Pattern.compile(
            "http://(?:127\\.0\\.0\\.1|0\\.0\\.0\\.0|localhost|\\[::\\]):6099/webui\\?token=([A-Za-z0-9_-]{4,128})"
    );

    private HostLogger() {
    }

    static File logFile(Context context) {
        File directory = new File(context.getFilesDir(), "logs");
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        return new File(directory, "host.log");
    }

    static void log(Context context, String message) {
        String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOCK) {
            File file = logFile(context);
            rotateIfNeeded(file);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(timestamp);
                writer.write(" ");
                writer.write(message == null ? "" : message);
                writer.write('\n');
            } catch (IOException ignored) {
                // Logging must never terminate the foreground service.
            }
        }
    }

    static String tail(Context context, int maxLines) {
        synchronized (LOCK) {
            File file = logFile(context);
            if (!file.isFile()) {
                return "暂无日志";
            }
            Deque<String> lines = new ArrayDeque<>(maxLines);
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lines.size() == maxLines) {
                        lines.removeFirst();
                    }
                    lines.addLast(line);
                }
            } catch (IOException e) {
                return "读取日志失败：" + e.getMessage();
            }
            return String.join("\n", lines);
        }
    }

    static String latestWebUiUrl(Context context) {
        return latestWebUiValue(context, false);
    }

    static String latestWebUiToken(Context context) {
        return latestWebUiValue(context, true);
    }

    private static String latestWebUiValue(Context context, boolean tokenOnly) {
        synchronized (LOCK) {
            File file = logFile(context);
            if (!file.isFile()) {
                return null;
            }
            String latest = null;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String candidate = tokenOnly
                            ? latestWebUiTokenFromText(line)
                            : latestWebUiUrlFromText(line);
                    if (candidate != null) {
                        latest = candidate;
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
            return latest;
        }
    }

    static String latestWebUiUrlFromText(String text) {
        String token = latestWebUiTokenFromText(text);
        return token == null ? null : "http://127.0.0.1:6099/webui?token=" + token;
    }

    static String latestWebUiTokenFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = WEB_UI_TOKEN_URL.matcher(text);
        String latest = null;
        while (matcher.find()) {
            latest = matcher.group(1);
        }
        return latest;
    }

    private static void rotateIfNeeded(File file) {
        if (!file.isFile() || file.length() < MAX_LOG_SIZE) {
            return;
        }
        File old = new File(file.getParentFile(), "host.log.1");
        if (old.exists()) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(old);
    }
}
