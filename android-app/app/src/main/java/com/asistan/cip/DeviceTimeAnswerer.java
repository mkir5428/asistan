package com.asistan.cip;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class DeviceTimeAnswerer {
    private DeviceTimeAnswerer() {
    }

    static String answer(String question) {
        return answer(question, new Date(), TimeZone.getDefault(), Locale.getDefault());
    }

    static String answer(String question, Date now, TimeZone timeZone, Locale locale) {
        String normalized = WakeCommandParser.normalize(question);
        if (isTimeQuestion(normalized)) {
            DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, locale);
            timeFormat.setTimeZone(timeZone);
            return "Şu an saat " + timeFormat.format(now) + ".";
        }
        if (isWeekdayQuestion(normalized)) {
            SimpleDateFormat weekdayFormat = new SimpleDateFormat("EEEE", locale);
            weekdayFormat.setTimeZone(timeZone);
            return "Bugün " + capitalize(weekdayFormat.format(now), locale) + ".";
        }
        if (isDateQuestion(normalized)) {
            DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.LONG, locale);
            dateFormat.setTimeZone(timeZone);
            return "Bugün " + dateFormat.format(now) + ".";
        }
        return null;
    }

    private static boolean isTimeQuestion(String question) {
        return question.equals("saat kac")
                || question.equals("saat su an kac")
                || question.equals("su an saat kac")
                || question.equals("su an kac")
                || question.equals("bugun saat kac");
    }

    private static boolean isWeekdayQuestion(String question) {
        return question.equals("bugun gunlerden ne")
                || question.equals("bugun hangi gun")
                || question.equals("bugun haftanin hangi gunu")
                || question.equals("haftanin hangi gunu")
                || question.equals("hangi gun");
    }

    private static boolean isDateQuestion(String question) {
        return question.equals("bugun tarih ne")
                || question.equals("bugunun tarihi ne")
                || question.equals("bugunun tarihi")
                || question.equals("bugun ayin kaci")
                || question.equals("tarih kac");
    }

    private static String capitalize(String value, Locale locale) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(locale) + value.substring(1);
    }
}
