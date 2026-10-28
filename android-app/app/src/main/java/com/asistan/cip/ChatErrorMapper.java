package com.asistan.cip;

import java.io.IOException;
import java.net.SocketTimeoutException;

final class ChatErrorMapper {
    static final class ServerResponseException extends IOException {
        final int statusCode;

        ServerResponseException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private ChatErrorMapper() {
    }

    static String message(Exception error) {
        if (error instanceof ServerResponseException) {
            int status = ((ServerResponseException) error).statusCode;
            if (status == 401) {
                return "API anahtarı doğrulanamadı. Ayarlar'dan kontrol et.";
            }
            if (status == 403) {
                return "Sunucu bu isteğe izin vermedi. Sunucu ayarlarını veya API erişim yetkisini kontrol et.";
            }
            if (status == 404) {
                return "Sunucu adresi veya sohbet hizmeti bulunamadı. Ayarlar'daki adresi kontrol et.";
            }
            if (status == 429) {
                return "Çok fazla istek gönderildi. Biraz bekleyip tekrar dene.";
            }
            if (status >= 500) {
                return "Sunucuda geçici bir sorun oluştu. Biraz sonra tekrar dene.";
            }
            return "Sunucu isteği tamamlayamadı. Sunucu ayarlarını kontrol edip tekrar dene.";
        }
        if (error instanceof SocketTimeoutException) {
            return "Sunucu zamanında yanıt vermedi. Tekrar deneyebilirsin.";
        }
        if (error instanceof IOException) {
            return "Sunucuya bağlanılamadı. İnternet bağlantını kontrol edip tekrar dene.";
        }
        return "İstek tamamlanamadı. Lütfen tekrar dene.";
    }
}
