package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DeviceCommandRouterTest {
    @Test
    public void routesOnlyExplicitLocalCommands() {
        assertEquals(DeviceCommandRouter.Action.OPEN_FILE,
                DeviceCommandRouter.route("dosya seç").action);
        assertEquals(DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT,
                DeviceCommandRouter.route("takvime etkinlik ekle").action);
        assertEquals(DeviceCommandRouter.Action.PICK_PHONE_TO_CALL,
                DeviceCommandRouter.route("kişiyi ara").action);
        assertEquals(DeviceCommandRouter.Action.PICK_PHONE_FOR_SMS,
                DeviceCommandRouter.route("sms gönder").action);
        assertEquals(DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT,
                DeviceCommandRouter.route("takvim etkinliği oluştur").action);
        assertNull(DeviceCommandRouter.route("telefonumdaki her şeyi aç"));
    }

    @Test
    public void numberCommandsProduceOnlyNativeDialOrSmsActions() {
        DeviceCommandRouter.Result dial = DeviceCommandRouter.route("ara +90 555 123 4567");
        assertEquals(DeviceCommandRouter.Action.DIAL_NUMBER, dial.action);
        assertEquals("+905551234567", dial.phoneNumber);
        assertEquals(DeviceCommandRouter.Action.DIAL_NUMBER,
                DeviceCommandRouter.route("numarayı ara 05551234567").action);

        DeviceCommandRouter.Result sms = DeviceCommandRouter.route("sms 05551234567");
        assertEquals(DeviceCommandRouter.Action.COMPOSE_SMS, sms.action);
        assertEquals("05551234567", sms.phoneNumber);
    }

    @Test
    public void rejectsShortOrUnrecognizedPhoneInput() {
        assertNull(DeviceCommandRouter.route("ara 123"));
        assertNull(DeviceCommandRouter.route("sms 123"));
    }
}
