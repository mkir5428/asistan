package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONArray;

public class ConversationHistoryTest {
    @Test
    public void preservesMessagesAndSpeakerAcrossSerialization() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.append("Sen", "Merhaba");
        history.append("Çip", "Merhaba, nasıl yardımcı olayım?");

        ConversationHistory restored = ConversationHistory.fromJson(history.toJson());

        assertEquals(2, restored.entries().size());
        assertEquals("Sen", restored.entries().get(0).sender);
        assertEquals("Merhaba", restored.entries().get(0).text);
        assertEquals("Çip", restored.entries().get(1).sender);
    }

    @Test
    public void keepsOnlyMostRecentMessages() throws Exception {
        ConversationHistory history = new ConversationHistory();
        for (int i = 0; i < 55; i++) {
            history.append("Sen", "Mesaj " + i);
        }

        ConversationHistory restored = ConversationHistory.fromJson(history.toJson());

        assertEquals(50, restored.entries().size());
        assertEquals("Mesaj 5", restored.entries().get(0).text);
        assertEquals("Mesaj 54", restored.entries().get(49).text);
    }

    @Test
    public void exportsOnlyCompleteConversationTurnsAndSkipsCurrentMessageAndErrors() throws Exception {
        ConversationHistory history = new ConversationHistory();
        history.append("Çip", "Hoş geldin");
        history.append("Sen", "Önceki soru");
        history.append("Çip", "Önceki yanıt");
        history.append("Hata", "Sunucu reddetti");
        history.append("Sen", "Tamamlanmamış istek");
        history.append("Sen", "Şimdiki soru");

        JSONArray messages = history.apiMessages("Şimdiki soru");

        assertEquals(2, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals("Önceki soru", messages.getJSONObject(0).getString("content"));
        assertEquals("assistant", messages.getJSONObject(1).getString("role"));
        assertEquals("Önceki yanıt", messages.getJSONObject(1).getString("content"));
        assertTrue(history.apiMessages("not present").length() <= 2);
    }

    @Test
    public void recognizesClearHistoryCommandsAndCanClearLocalHistory() {
        assertTrue(ConversationHistory.isClearHistoryCommand("Sohbet geçmişini temizle"));
        ConversationHistory history = new ConversationHistory();
        history.append("Sen", "Merhaba");
        history.clear();
        assertEquals(0, history.entries().size());
    }

    @Test
    public void respectsServerHistoryCountAndContentLimits() throws Exception {
        ConversationHistory history = new ConversationHistory();
        for (int i = 0; i < 25; i++) {
            history.append("Sen", "Soru " + i);
            history.append("Çip", "Yanıt " + i);
        }
        history.append("Sen", "x".repeat(2500));
        history.append("Çip", "Yanıt");

        JSONArray messages = history.apiMessages("not present");

        assertEquals(40, messages.length());
        assertEquals("Soru 6", messages.getJSONObject(0).getString("content"));
        assertEquals(2000, messages.getJSONObject(38).getString("content").length());
    }
}
