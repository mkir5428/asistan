package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeCommandParserTest {
    @Test
    public void recognizesTurkishWakePhraseAndExtractsFollowingCommand() {
        assertTrue(WakeCommandParser.containsWakePhrase("Hey Çip, hava nasıl?"));
        assertEquals("hava nasıl?", WakeCommandParser.commandAfterWakePhrase("Hey Çip, hava nasıl?"));
    }

    @Test
    public void supportsSpeechRecognizerSpellingVariant() {
        assertTrue(WakeCommandParser.containsWakePhrase("Hey chip"));
        assertEquals("", WakeCommandParser.commandAfterWakePhrase("Hey chip"));
    }

    @Test
    public void doesNotMatchWakePhraseInsideAnotherWord() {
        assertFalse(WakeCommandParser.containsWakePhrase("heyçip"));
        assertEquals("", WakeCommandParser.commandAfterWakePhrase("hava nasıl"));
    }
}
