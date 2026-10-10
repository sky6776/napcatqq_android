package dev.napcathost;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class NapCatWebApi {
    private static final String API_BASE = "http://127.0.0.1:6099/api";
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    String authenticate(String webUiToken) throws IOException {
        JSONObject request = new JSONObject();
        try {
            request.put("hash", sha256Hex(webUiToken + ".napcat"));
            request.put("totpCode", "");
            JSONObject data = post("/auth/login", request, null);
            String credential = data.optString("Credential", "");
            if (credential.length() < 16) {
                throw new IOException("WebUI 未返回有效 Credential");
            }
            return credential;
        } catch (JSONException e) {
            throw new IOException("无法生成 WebUI 鉴权请求", e);
        }
    }

    JSONObject checkLoginStatus(String credential) throws IOException {
        return post("/QQLogin/CheckLoginStatus", new JSONObject(), credential);
    }

    JSONObject passwordLogin(String credential, String qqNumber, String passwordMd5)
            throws IOException {
        JSONObject request = new JSONObject();
        try {
            request.put("uin", qqNumber);
            request.put("passwordMd5", passwordMd5);
        } catch (JSONException e) {
            throw new IOException("无法生成 QQ 登录请求", e);
        }
        return post("/QQLogin/PasswordLogin", request, credential);
    }

    private JSONObject post(String path, JSONObject request, String credential) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Connection", "close");
            if (credential != null) {
                connection.setRequestProperty("Authorization", "Bearer " + credential);
            }
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int httpCode = connection.getResponseCode();
            InputStream stream = httpCode >= 200 && httpCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readLimited(stream);
            if (httpCode < 200 || httpCode >= 300) {
                throw new IOException("NapCat WebUI HTTP " + httpCode);
            }
            try {
                JSONObject envelope = new JSONObject(response);
                if (envelope.optInt("code", -1) != 0) {
                    String message = envelope.optString("message", "API 调用失败");
                    throw new IOException("NapCat WebUI：" + safeMessage(message));
                }
                JSONObject data = envelope.optJSONObject("data");
                return data == null ? new JSONObject() : data;
            } catch (JSONException e) {
                throw new IOException("NapCat WebUI 返回了无效 JSON", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String sha256Hex(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("设备不支持 SHA-256", e);
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (result.length() + read > MAX_RESPONSE_CHARS) {
                    throw new IOException("NapCat WebUI 响应过大");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private static String safeMessage(String value) {
        String singleLine = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 180);
    }
}
