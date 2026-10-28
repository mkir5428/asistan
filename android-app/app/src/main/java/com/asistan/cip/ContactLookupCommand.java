package com.asistan.cip;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContactLookupCommand {
    private static final Pattern DIRECTORY_SEARCH =
            Pattern.compile("^(?:rehberde|rehberimde|kisilerde|kisilerimde) (.+?) (?:bul|ara|numarasini bul|telefonunu bul)$");
    private static final Pattern CONTACT_NUMBER =
            Pattern.compile("^(.+?) (?:nin|nın|nun|nün|in|ın|un|ün) (?:numarasi|telefonu) ne$");

    private ContactLookupCommand() {
    }

    static String extractName(String input) {
        String normalized = WakeCommandParser.normalize(input);
        Matcher directorySearch = DIRECTORY_SEARCH.matcher(normalized);
        if (directorySearch.matches()) {
            return cleanName(directorySearch.group(1));
        }
        Matcher contactNumber = CONTACT_NUMBER.matcher(normalized);
        if (contactNumber.matches()) {
            return cleanName(contactNumber.group(1));
        }
        return null;
    }

    private static String cleanName(String name) {
        String cleaned = name.replaceAll("\\b(?:yi|yı|yu|yü|nin|nın|nun|nün)$", "").trim();
        return cleaned.length() >= 2 ? cleaned : null;
    }
}
