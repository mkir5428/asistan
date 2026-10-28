package com.asistan.cip;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceActionErrorMapperTest {
    @Test
    public void reportsContactSpecificPickerAndGrantFailures() {
        String pickerFailure = DeviceActionErrorMapper.message(
                DeviceCommandRouter.Action.PICK_PHONE_TO_CALL, new IllegalStateException("raw"));
        String grantFailure = DeviceActionErrorMapper.message(
                DeviceCommandRouter.Action.PICK_PHONE_FOR_SMS, new SecurityException("raw"));

        assertTrue(pickerFailure.contains("Kişi seçici"));
        assertTrue(grantFailure.contains("seçilen kişinin telefon bilgisini"));
        assertFalse(grantFailure.contains("raw"));
    }

    @Test
    public void doesNotMisreportCalendarAndFileFailuresAsContactPermissionProblems() {
        String calendarFailure = DeviceActionErrorMapper.message(
                DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT, new SecurityException());
        String fileFailure = DeviceActionErrorMapper.message(
                DeviceCommandRouter.Action.OPEN_FILE, new SecurityException());

        assertTrue(calendarFailure.contains("Takvim etkinliği"));
        assertTrue(fileFailure.contains("Dosya seçici"));
        assertFalse(calendarFailure.contains("rehber"));
        assertFalse(fileFailure.contains("rehber"));
    }
}
