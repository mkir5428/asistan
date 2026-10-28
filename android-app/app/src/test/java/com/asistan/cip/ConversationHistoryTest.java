package com.asistan.cip;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
