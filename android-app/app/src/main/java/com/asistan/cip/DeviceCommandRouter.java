package com.asistan.cip;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceCommandRouter {
    enum Action {
        OPEN_FILE,
        CREATE_CALENDAR_EVENT,
        PICK_PHONE_TO_CALL,
        PICK_PHONE_FOR_SMS,
        DIAL_NUMBER,
        COMPOSE_SMS
    }

    static final class Result {
        final Action action;
        final String phoneNumber;

        private Result(Action action, String phoneNumber) {
            this.action = action;
            this.phoneNumber = phoneNumber;
        }
    }

    private static final Pattern DIAL_COMMAND =
            Pattern.compile("^(?:ara|numarayi ara|numarayı ara)\\s+(\\+?[0-9][0-9\\s()-]{4,})$");
    private static final Pattern SMS_COMMAND =
            Pattern.compile("^sms\\s+(\\+?[0-9][0-9\\s()-]{4,})$");

    private DeviceCommandRouter() {
    }

    static Result route(String input) {
        String command = WakeCommandParser.normalize(input);
        if (command.equals("dosya sec") || command.equals("dosyalarimi ac")) {
            return new Result(Action.OPEN_FILE, null);
        }
        if (command.equals("takvim etkinligi olustur") || command.equals("takvime etkinlik ekle")) {
            return new Result(Action.CREATE_CALENDAR_EVENT, null);
        }
        if (command.equals("kisiyi ara") || command.equals("birini ara")) {
            return new Result(Action.PICK_PHONE_TO_CALL, null);
        }
        if (command.equals("sms gonder") || command.equals("mesaj gonder")) {
            return new Result(Action.PICK_PHONE_FOR_SMS, null);
        }

        String normalizedInput = input.toLowerCase(Locale.ROOT);
        Matcher dial = DIAL_COMMAND.matcher(normalizedInput);
        if (dial.matches()) {
            return new Result(Action.DIAL_NUMBER, dial.group(1).replaceAll("[^+0-9]", ""));
        }
        Matcher sms = SMS_COMMAND.matcher(normalizedInput);
        if (sms.matches()) {
            return new Result(Action.COMPOSE_SMS, sms.group(1).replaceAll("[^+0-9]", ""));
        }
        return null;
    }
}
