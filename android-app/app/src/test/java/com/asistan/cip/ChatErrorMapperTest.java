package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.Test;

public class ChatErrorMapperTest {
    @Test
    public void mapsServerStatusesWithoutExposingResponseDetails() {
        String denied = ChatErrorMapper.message(new ChatErrorMapper.ServerResponseException(403));

        assertEquals("Sunucu bu isteğe izin vermedi. Sunucu ayarlarını veya API erişim yetkisini kontrol et.",
                denied);
        assertFalse(denied.contains("403"));
        assertFalse(denied.contains("Exception"));
    }

    @Test
    public void mapsAuthenticationAndServerFailuresToActionableMessages() {
        assertEquals("API anahtarı doğrulanamadı. Ayarlar'dan kontrol et.",
                ChatErrorMapper.message(new ChatErrorMapper.ServerResponseException(401)));
        assertEquals("Sunucuda geçici bir sorun oluştu. Biraz sonra tekrar dene.",
                ChatErrorMapper.message(new ChatErrorMapper.ServerResponseException(503)));
    }

    @Test
    public void mapsNetworkFailuresWithoutLeakingExceptionText() {
        String networkMessage = ChatErrorMapper.message(new IOException("secret server body 734"));
        String timeoutMessage = ChatErrorMapper.message(new SocketTimeoutException("raw failure 900"));

        assertEquals("Sunucuya bağlanılamadı. İnternet bağlantını kontrol edip tekrar dene.", networkMessage);
        assertEquals("Sunucu zamanında yanıt vermedi. Tekrar deneyebilirsin.", timeoutMessage);
        assertFalse(networkMessage.contains("734"));
        assertFalse(timeoutMessage.contains("900"));
    }
}
