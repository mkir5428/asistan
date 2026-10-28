package com.asistan.cip;

final class DeviceActionErrorMapper {
    private DeviceActionErrorMapper() {
    }

    static String message(DeviceCommandRouter.Action action, Exception error) {
        if (action == DeviceCommandRouter.Action.PICK_PHONE_TO_CALL
                || action == DeviceCommandRouter.Action.PICK_PHONE_FOR_SMS) {
            if (error instanceof SecurityException) {
                return "Android seçilen kişinin telefon bilgisini uygulamayla paylaşmadı. Kişiyi yeniden seç.";
            }
            return "Kişi seçici açılamadı. Cihazındaki kişiler uygulamasının kullanılabilir olduğunu kontrol et.";
        }
        if (action == DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT) {
            return "Takvim etkinliği açılamadı. Cihazındaki takvim uygulamasının kullanılabilir olduğunu kontrol et.";
        }
        if (action == DeviceCommandRouter.Action.OPEN_FILE) {
            return "Dosya seçici açılamadı. Android dosya seçicisinin kullanılabilir olduğunu kontrol et.";
        }
        if (action == DeviceCommandRouter.Action.DIAL_NUMBER) {
            return "Arama uygulaması açılamadı. Cihazındaki arama uygulamasını kontrol et.";
        }
        return "Mesaj uygulaması açılamadı. Cihazındaki SMS uygulamasını kontrol et.";
    }
}
