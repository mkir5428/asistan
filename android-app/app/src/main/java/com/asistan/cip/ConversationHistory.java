package com.asistan.cip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ConversationHistory {
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_API_MESSAGES = 40;
    private static final int MAX_API_MESSAGE_CHARACTERS = 2000;

    static final class Entry {
        final String sender;
        final String text;

        Entry(String sender, String text) {
            this.sender = sender;
            this.text = text;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    static ConversationHistory fromJson(String serialized) throws JSONException {
        ConversationHistory history = new ConversationHistory();
        if (serialized == null || serialized.isEmpty()) {
            return history;
        }
        JSONArray messages = new JSONArray(serialized);
        int first = Math.max(0, messages.length() - MAX_MESSAGES);
        for (int i = first; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            history.append(message.getString("sender"), message.getString("text"));
        }
        return history;
    }

    void append(String sender, String text) {
        entries.add(new Entry(sender, text));
        if (entries.size() > MAX_MESSAGES) {
            entries.remove(0);
        }
    }

    void clear() {
        entries.clear();
    }

    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    JSONArray apiMessages(String currentUserMessage) throws JSONException {
        List<Entry> conversation = new ArrayList<>();
        boolean skippedCurrentMessage = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (!skippedCurrentMessage && "Sen".equals(entry.sender)
                    && entry.text.equals(currentUserMessage)) {
                skippedCurrentMessage = true;
                continue;
            }
            conversation.add(entry);
        }
        Collections.reverse(conversation);

        List<Entry> turns = new ArrayList<>();
        Entry pendingUser = null;
        for (Entry entry : conversation) {
            String role = apiRole(entry.sender);
            if (role == null || entry.text.trim().isEmpty()) {
                continue;
            }
            if ("user".equals(role)) {
                pendingUser = new Entry("user", bounded(entry.text));
            } else if (pendingUser != null) {
                turns.add(pendingUser);
                turns.add(new Entry("assistant", bounded(entry.text)));
                pendingUser = null;
            }
        }

        int first = Math.max(0, turns.size() - MAX_API_MESSAGES);
        if (first % 2 != 0) {
            first++;
        }
        JSONArray messages = new JSONArray();
        for (int i = first; i + 1 < turns.size(); i += 2) {
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", turns.get(i).text);
            messages.put(user);
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", turns.get(i + 1).text);
            messages.put(assistant);
        }
        return messages;
    }

    static boolean isClearHistoryCommand(String text) {
        String normalized = WakeCommandParser.normalize(text);
        boolean clear = normalized.contains("temizle") || normalized.contains("sifirla")
                || normalized.contains("sil") || normalized.contains("unut")
                || normalized.contains("resetle");
        boolean history = normalized.contains("sohbet") || normalized.contains("gecmis")
                || normalized.contains("hafiza");
        return clear && history;
    }

    String toJson() throws JSONException {
        JSONArray messages = new JSONArray();
        for (Entry entry : entries) {
            JSONObject message = new JSONObject();
            message.put("sender", entry.sender);
            message.put("text", entry.text);
            messages.put(message);
        }
        return messages.toString();
    }

    private static String apiRole(String sender) {
        if ("Sen".equals(sender)) {
            return "user";
        }
        if ("Çip".equals(sender)) {
            return "assistant";
        }
        return null;
    }

    private static String bounded(String text) {
        return text.length() <= MAX_API_MESSAGE_CHARACTERS
                ? text : text.substring(0, MAX_API_MESSAGE_CHARACTERS);
    }
}
