package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.Test;

public class DeviceTimeAnswererTest {
    private static final Locale TURKISH = new Locale("tr", "TR");
    private static final TimeZone ISTANBUL = TimeZone.getTimeZone("Europe/Istanbul");

    @Test
    public void recognizesTimeQuestions() {
        Date now = date(2026, 8, 28, 16, 5);

        assertEquals("Şu an saat 16:05.", answer("Saat kaç?", now));
        assertEquals("Şu an saat 16:05.", answer("Şu an saat kaç?", now));
        assertEquals("Şu an saat 16:05.", answer("Bugün saat kaç", now));
    }

    @Test
    public void recognizesDateAndWeekdayQuestions() {
        Date now = date(2026, 8, 28, 16, 5);

        assertEquals("Bugün 28 Eylül 2026.", answer("Bugünün tarihi ne?", now));
        assertEquals("Bugün 28 Eylül 2026.", answer("Bugün ayın kaçı?", now));
        assertEquals("Bugün Pazartesi.", answer("Bugün günlerden ne?", now));
        assertEquals("Bugün Pazartesi.", answer("Haftanın hangi günü?", now));
    }

    @Test
    public void usesRequestedLocaleAndDeviceTimeZone() {
        Date utcTime = date(TimeZone.getTimeZone("UTC"), 2026, 0, 1, 21, 30);

        String answer = DeviceTimeAnswerer.answer("Bugünün tarihi", utcTime, ISTANBUL, TURKISH);

        assertEquals("Bugün 2 Ocak 2026.", answer);
    }

    @Test
    public void usesLocaleSpecificTimeFormatting() {
        Date now = date(2026, 8, 28, 16, 5);

        String answer = DeviceTimeAnswerer.answer("saat kaç", now, ISTANBUL, Locale.US);

        assertTrue(answer.startsWith("Şu an saat "));
        assertTrue(answer.endsWith("."));
        assertTrue(answer.contains("4:05"));
        assertTrue(answer.contains("PM"));
    }

    @Test
    public void returnsNullForUnrecognizedQuestions() {
        Date now = date(2026, 8, 28, 16, 5);

        assertNull(answer("Bugün hava nasıl?", now));
        assertNull(answer("Saat kaç ve hava nasıl?", now));
        assertNull(answer("Bana tarihi anlatır mısın?", now));
    }

    private static String answer(String question, Date now) {
        return DeviceTimeAnswerer.answer(question, now, ISTANBUL, TURKISH);
    }

    private static Date date(int year, int month, int day, int hour, int minute) {
        return date(ISTANBUL, year, month, day, hour, minute);
    }

    private static Date date(TimeZone timeZone, int year, int month, int day, int hour, int minute) {
        GregorianCalendar calendar = new GregorianCalendar(timeZone, TURKISH);
        calendar.clear();
        calendar.set(year, month, day, hour, minute);
        return calendar.getTime();
    }
}
