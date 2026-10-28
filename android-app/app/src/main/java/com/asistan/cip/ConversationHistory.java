package com.asistan.cip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ConversationHistory {
    private static final int MAX_MESSAGES = 50;

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

    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
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
}
