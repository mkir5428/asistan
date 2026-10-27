package com.asistan.cip;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TransferApi {
    static final class PairingResult {
        final String pairingId;
        final String deviceToken;
        final long expiresInSeconds;

        PairingResult(String pairingId, String deviceToken, long expiresInSeconds) {
            this.pairingId = pairingId;
            this.deviceToken = deviceToken;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final String baseUrl;

    TransferApi(String baseUrl) throws IOException {
        URL parsed = new URL(baseUrl);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getHost() == null
                || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getRef() != null) {
            throw new IOException("Aktarım sunucusu yalnızca HTTPS olabilir.");
        }
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    PairingResult claim(String pairingCode, String publicKeyPem) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pairing_code", pairingCode);
        body.put("public_key_pem", publicKeyPem);
        HttpURLConnection connection = open("/transfer/pairings/claim", "POST", null);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            writeBytes(connection, body.toString().getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw serverError(status, response);
            }
            JSONObject json = new JSONObject(response);
            String pairingId = requiredString(json, "pairing_id");
            String deviceToken = requiredString(json, "device_token");
            long expiresIn = json.getLong("pairing_expires_in");
            if (expiresIn <= 0) {
                throw new IOException("Sunucu geçersiz eşleşme süresi döndürdü.");
            }
            return new PairingResult(pairingId, deviceToken, expiresIn);
        } finally {
            connection.disconnect();
        }
    }

    List<TransferAction> listActions(TransferCredentials credentials) throws Exception {
        String query = URLEncoder.encode(credentials.pairingId, "UTF-8").replace("+", "%20");
        HttpURLConnection connection = open("/transfer/actions?pairing_id=" + query,
                "GET", credentials.deviceToken);
        try {
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status != HttpURLConnection.HTTP_OK) {
                throw serverError(status, response);
            }
            return parseActions(response, credentials.pairingId);
        } finally {
            connection.disconnect();
        }
    }

    static List<TransferAction> parseActions(String response, String pairingId) throws JSONException {
        JSONObject envelope = new JSONObject(response);
        JSONArray actions = envelope.getJSONArray("actions");
        List<TransferAction> result = new ArrayList<>();
        for (int i = 0; i < actions.length(); i++) {
            result.add(TransferAction.fromJson(actions.getJSONObject(i), pairingId));
        }
        return result;
    }

    TransferAction createPhoneUploadAction(TransferCredentials credentials, String filename,
                                           long size, String sha256) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pairing_id", credentials.pairingId);
        body.put("filename", filename);
        body.put("size", size);
        body.put("sha256", sha256);
        HttpURLConnection connection = open("/transfer/actions/phone", "POST", credentials.deviceToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            writeBytes(connection, body.toString().getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw serverError(status, response);
            }
            TransferAction action = TransferAction.fromJson(new JSONObject(response), credentials.pairingId);
            if (!"phone_to_pc".equals(action.direction) || !action.awaitsApproval()
                    || !filename.equals(action.filename) || size != action.size
                    || !sha256.equals(action.sha256)) {
                throw new IOException("Sunucunun oluşturduğu aktarım isteği seçilen dosyayla eşleşmiyor.");
            }
            return action;
        } finally {
            connection.disconnect();
        }
    }

    void approve(TransferCredentials credentials, TransferAction action, byte[] signature) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pairing_id", credentials.pairingId);
        body.put("signature", Base64.encodeToString(signature, Base64.NO_WRAP));
        HttpURLConnection connection = open(actionPath(action.actionId) + "/approve",
                "POST", credentials.deviceToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            writeBytes(connection, body.toString().getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status != HttpURLConnection.HTTP_NO_CONTENT) {
                throw serverError(status, response);
            }
        } finally {
            connection.disconnect();
        }
    }

    void cancel(TransferCredentials credentials, TransferAction action) throws Exception {
        JSONObject body = new JSONObject();
        body.put("pairing_id", credentials.pairingId);
        HttpURLConnection connection = open(actionPath(action.actionId) + "/cancel",
                "POST", credentials.deviceToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            writeBytes(connection, body.toString().getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw serverError(status, response);
            }
        } finally {
            connection.disconnect();
        }
    }

    void revoke(TransferCredentials credentials) throws Exception {
        HttpURLConnection connection = open("/transfer/pairings/"
                + encodePath(credentials.pairingId), "DELETE", credentials.deviceToken);
        try {
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return;
            }
            if (status < 200 || status >= 300) {
                throw serverError(status, response);
            }
        } finally {
            connection.disconnect();
        }
    }

    void upload(TransferCredentials credentials, TransferAction action, File source) throws Exception {
        if (source.length() != action.size || source.length() > TransferAction.MAX_FILE_SIZE
                || !sha256(source).equals(action.sha256)) {
            throw new IOException("Seçilen dosya aktarım isteğinin boyut veya SHA-256 bilgisiyle eşleşmiyor.");
        }
        HttpURLConnection connection = open(actionPath(action.actionId) + "/data",
                "PUT", credentials.deviceToken);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(source.length());
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-Cip-Pairing-Id", credentials.pairingId);
        try {
            try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                 OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                copyLimited(input, output, action.size);
            }
            int status = connection.getResponseCode();
            String response = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw serverError(status, response);
            }
        } finally {
            connection.disconnect();
        }
    }

    File download(TransferCredentials credentials, TransferAction action, File destination) throws Exception {
        String pairing = URLEncoder.encode(credentials.pairingId, "UTF-8").replace("+", "%20");
        HttpURLConnection connection = open(actionPath(action.actionId) + "/data?pairing_id=" + pairing,
                "GET", credentials.deviceToken);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw serverError(status, readBody(connection, status));
            }
            int contentLength = connection.getContentLength();
            if ((contentLength >= 0 && contentLength != action.size)
                    || action.size > TransferAction.MAX_FILE_SIZE) {
                throw new IOException("İndirilen dosyanın boyutu aktarım isteğiyle eşleşmiyor.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > action.size || copied > TransferAction.MAX_FILE_SIZE) {
                        throw new IOException("İndirilen dosya izin verilen boyutu aşıyor.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (copied != action.size || !hex(digest.digest()).equals(action.sha256)) {
                throw new IOException("İndirilen dosyanın boyut veya SHA-256 doğrulaması başarısız.");
            }
            return destination;
        } catch (Exception error) {
            if (destination.exists() && !destination.delete()) {
                error.addSuppressed(new IOException("Doğrulanamayan geçici dosya silinemedi."));
            }
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
                if (length > TransferAction.MAX_FILE_SIZE) {
                    throw new IOException("Dosyalar en fazla 20 MiB olabilir.");
                }
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private HttpURLConnection open(String path, String method, String deviceToken) throws Exception {
        URL url = new URL(baseUrl + path);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Aktarım sunucusu yalnızca HTTPS olabilir.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (deviceToken != null) {
            connection.setRequestProperty("Authorization", "Bearer " + deviceToken);
        }
        return connection;
    }

    private static String actionPath(String actionId) throws Exception {
        return "/transfer/actions/" + encodePath(actionId);
    }

    private static String encodePath(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static void writeBytes(HttpURLConnection connection, byte[] data) throws IOException {
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(data);
        }
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream source = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (source == null) {
            return "";
        }
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("Sunucunun yanıtı izin verilen boyutu aşıyor.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static IOException serverError(int status, String body) {
        String code = "";
        try {
            if (body != null && !body.isEmpty()) {
                code = new JSONObject(body).optString("error", "");
            }
        } catch (JSONException ignored) {
            code = "";
        }
        String detail = code.isEmpty() ? "" : " (" + code + ")";
        return new IOException(String.format(Locale.ROOT,
                "Aktarım sunucusu isteği reddetti (HTTP %d).%s", status, detail));
    }

    private static String requiredString(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new JSONException("Sunucu yanıtında " + key + " alanı eksik.");
        }
        return (String) value;
    }

    private static long copyLimited(InputStream input, OutputStream output, long expectedSize) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            copied += count;
            if (copied > expectedSize || copied > TransferAction.MAX_FILE_SIZE) {
                throw new IOException("Dosya boyutu aktarım isteğiyle eşleşmiyor.");
            }
            output.write(buffer, 0, count);
        }
        if (copied != expectedSize) {
            throw new IOException("Dosya aktarım sırasında kısaldı.");
        }
        return copied;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }
}
