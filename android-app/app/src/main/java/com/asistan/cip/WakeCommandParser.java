package com.asistan.cip;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WakeCommandParser {
    private static final Pattern WAKE_PHRASE = Pattern.compile("\\bhey\\s+(?:çip|cip|chip)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private WakeCommandParser() {
    }

    static boolean containsWakePhrase(String transcript) {
        return WAKE_PHRASE.matcher(transcript).find();
    }

    static String commandAfterWakePhrase(String transcript) {
        Matcher matcher = WAKE_PHRASE.matcher(transcript);
        if (!matcher.find()) {
            return "";
        }
        return transcript.substring(Math.min(matcher.end(), transcript.length()))
                .replaceFirst("^[\\s,.:;!?-]+", "")
                .trim();
    }

    static String normalize(String transcript) {
        return transcript.toLowerCase(new Locale("tr", "TR"))
                .replace('ç', 'c')
                .replace('ı', 'i')
                .replace('ş', 's')
                .replace('ğ', 'g')
                .replace('ü', 'u')
                .replace('ö', 'o')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
