package com.asistan.cip;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

final class TransferAction {
    static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    final String actionId;
    final String pairingId;
    final String nonce;
    final String direction;
    final String filename;
    final long size;
    final String sha256;
    final String createdAt;
    final String expiresAt;
    final String state;
    final String challenge;

    private TransferAction(String actionId, String pairingId, String nonce, String direction,
                           String filename, long size, String sha256, String createdAt,
                           String expiresAt, String state, String challenge) {
        this.actionId = actionId;
        this.pairingId = pairingId;
        this.nonce = nonce;
        this.direction = direction;
        this.filename = filename;
        this.size = size;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = state;
        this.challenge = challenge;
    }

    static TransferAction fromJson(JSONObject json, String expectedPairingId) throws JSONException {
        String actionId = requiredString(json, "action_id");
        String pairingId = requiredString(json, "pairing_id");
        String nonce = requiredString(json, "nonce");
        String direction = requiredString(json, "direction");
        String filename = requiredString(json, "filename");
        Object rawSize = json.get("size");
        if (!(rawSize instanceof Number)) {
            throw new JSONException("Aktarım boyutu tam sayı olmalı.");
        }
        double numericSize = ((Number) rawSize).doubleValue();
        long size = ((Number) rawSize).longValue();
        if (Double.isNaN(numericSize) || Double.isInfinite(numericSize)
                || numericSize != (double) size) {
            throw new JSONException("Aktarım boyutu tam sayı olmalı.");
        }
        String sha256 = requiredString(json, "sha256");
        String createdAt = requiredString(json, "created_at");
        String expiresAt = requiredString(json, "expires_at");
        String state = requiredString(json, "state");
        if (!state.equals("awaiting_approval") && !state.equals("approved")
                && !state.equals("receiving") && !state.equals("ready")
                && !state.equals("sending") && !state.equals("completed")
                && !state.equals("cancelled")) {
            throw new JSONException("Desteklenmeyen aktarım durumu.");
        }
        if (!json.has("challenge")) {
            throw new JSONException("Aktarım yanıtında imza challenge alanı eksik.");
        }
        Object rawChallenge = json.get("challenge");
        if (rawChallenge != JSONObject.NULL && !(rawChallenge instanceof String)) {
            throw new JSONException("Aktarım challenge alanı metin veya null olmalı.");
        }
        String challenge = rawChallenge == JSONObject.NULL ? null : (String) rawChallenge;

        if (!pairingId.equals(expectedPairingId)) {
            throw new JSONException("Aktarım eşleşme kimliği etkin eşleşmeyle uyuşmuyor.");
        }
        if (!direction.equals("phone_to_pc") && !direction.equals("pc_to_phone")) {
            throw new JSONException("Desteklenmeyen aktarım yönü.");
        }
        if (filename.trim().isEmpty() || filename.length() > 255 || filename.equals(".")
                || filename.equals("..") || containsPathSeparator(filename) || containsControl(filename)) {
            throw new JSONException("Aktarım dosya adı geçersiz.");
        }
        if (size < 1 || size > MAX_FILE_SIZE) {
            throw new JSONException("Dosya boyutu 1 bayt ile 20 MiB arasında olmalı.");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new JSONException("Aktarım SHA-256 özeti geçersiz.");
        }
        if (containsLineBreak(actionId) || containsLineBreak(pairingId)
                || containsLineBreak(nonce) || containsLineBreak(direction)
                || containsLineBreak(sha256) || containsLineBreak(createdAt)
                || containsLineBreak(expiresAt)) {
            throw new JSONException("Aktarım imza alanlarında satır sonu karakteri olamaz.");
        }
        if (state.equals("awaiting_approval")) {
            if (challenge == null || challenge.isEmpty()) {
                throw new JSONException("Onay bekleyen aktarımda challenge yok.");
            }
            String expectedChallenge = challenge(actionId, pairingId, nonce, direction,
                    filename, size, sha256, expiresAt);
            if (!expectedChallenge.equals(challenge)) {
                throw new JSONException("Sunucu challenge değeri aktarım alanlarıyla eşleşmiyor.");
            }
        } else if (challenge != null) {
            throw new JSONException("Onay beklemeyen aktarımda challenge bulunmamalı.");
        }
        return new TransferAction(actionId, pairingId, nonce, direction, filename,
                size, sha256, createdAt, expiresAt, state, challenge);
    }

    static String challenge(String actionId, String pairingId, String nonce, String direction,
                            String filename, long size, String sha256, String expiresAt) {
        return "cip-transfer-v1\n" + pairingId + "\n" + actionId + "\n" + nonce + "\n"
                + direction + "\n" + filename + "\n" + size + "\n"
                + sha256 + "\n" + expiresAt;
    }

    boolean awaitsApproval() {
        return "awaiting_approval".equals(state) && challenge != null;
    }

    private static String requiredString(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new JSONException("Aktarım yanıtında " + key + " alanı eksik.");
        }
        return (String) value;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
