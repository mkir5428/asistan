# Çip Sunucu (sesli-asistan)

Flask tabanlı arka uç: telefondan gelen sesi Groq Whisper ile yazıya çevirir,
akıllı ampul komutlarını Tuya'ya iletir, gerisini Claude yanıtlar.

## Uç noktalar

`/` dışındaki tüm uç noktalar API anahtarı ister. Metin parametreleri GET
sorgusuyla veya POST form alanlarıyla gönderilebilir.

- `GET /` → sağlık/teşhis sayfası
- `GET|POST /komut` → metin komutu (`text`, isteğe bağlı `gecmis`, `profil`, `session`)
- `POST /ses` → `file` multipart alanı ya da ham ses gövdesi; `?format=json` JSON yanıt verir
- `GET|POST /cevir` → çeviri (`text`, isteğe bağlı `dil`)
- `GET|POST /ozet` → özet (`text`, isteğe bağlı `mod=kisa|orta|madde`)
- `GET /hava?sehir=istanbul` → hava durumu
- `GET /hesap?ifade=12%20carpi%204` → güvenli hesap makinesi
- `GET /seslendir?metin=Merhaba` → MP3 ses
- `GET /temizle?session=...` → oturum hafızasını temizler

## Render > Environment'da olması gerekenler

| Değişken | Açıklama |
|---|---|
| `ASISTAN_API_KEY` | `/` dışındaki uç noktalar için zorunlu API anahtarı |
| `ANTHROPIC_API_KEY` | Claude API anahtarı |
| `GROQ_API_KEY` | Groq (whisper-large-v3) anahtarı |
| `TUYA_CLIENT_ID` / `TUYA_CLIENT_SECRET` | Tuya IoT Cloud uygulaması |
| `TUYA_DEVICE_ID` | Akıllı ampulün cihaz kimliği |
| `TUYA_ENDPOINT` | Örn. `https://api.tuya.com` (bölgeye göre değişir) |

İsteğe bağlı: `CLAUDE_MODEL` (varsayılan `claude-sonnet-5-5`), `EDGE_TTS_SES`, `PORT`.

`/` sağlık sayfası dışında tüm isteklerde şu başlığı gönder:

```
Authorization: Bearer <ASISTAN_API_KEY>
```

Ses yüklemeleri en fazla 25 MB olabilir.

API anahtarı için en az 32 rastgele bayt kullan; örneğin Python'da
`python -c "import secrets; print(secrets.token_urlsafe(32))"` komutuyla üret.

## Lokal çalıştırma

```bash
pip install -r requirements.txt
python voice_server.py
```

## Android uygulaması

`android-app/` klasörü Çip için yerel Android istemcisidir. Metinle `/komut`,
isteğe bağlı kısa kayıtla `/ses` uç noktasını kullanır; yanıtları Türkçe
seslendirir. Uygulama ayrıca Android'in cihaz-üstü konuşma tanımasını kullanarak
isteğe bağlı “Hey Çip” uyandırma sözcüğünü dinleyebilir. Dinleme yalnızca
kullanıcı uygulama içindeki düğmeyle başlattığında, açık mikrofon izniyle ve
durdurma düğmesi bulunan kalıcı bildirimi göstererek çalışır.

Uyandırma sözcüğü yalnızca Android 12+ cihazlarda ve cihazın Türkçe çevrimdışı
konuşma tanıma hizmeti mevcutsa çalışır. Android, her üreticide Türkçe çevrimdışı
model bulunduğunu veya tanımanın her zaman doğru olacağını garanti etmez.
Dinleme servisi ses tanımasını `createOnDeviceSpeechRecognizer` ile cihazda
gerçekleştirir; kullanılabilir değilse uzaktaki tanımaya sessizce geçmez ve
uygulamada hata gösterir. Bu sürümde konuşmacı profili/eşleşmesi uygulanmıyor;
uyandırma sözcüğü ses kimliğini doğrulamaz ve hiçbir ses eşleşmesi ses taklidi
veya kayıtla kandırılmaya karşı güvenlik garantisi olarak kullanılmamalıdır.

Dosyalar Android sistem belge seçicisiyle, kişiler telefon numarası seçicisiyle,
takvim etkinlikleri takvim uygulamasının kendi onay ekranıyla açılır. Arama
yalnızca Android arama ekranını, SMS yalnızca hazır mesaj ekranını açar; uygulama
sessizce arama yapmaz, SMS göndermez veya tüm kişiler/dosyalara arka planda erişmez.
Yerel komutlar dar bir listeden eşleştirilir; diğer komutlar mevcut sunucu
uç noktalarına gider.

İlk açılışta Render sunucu adresini ve `ASISTAN_API_KEY` değerini Ayarlar'a gir.
Anahtar Android Keystore ile cihazda şifreli saklanır; APK'nın içine gömülmez.
Uygulamayı başkalarıyla paylaşmak yine anahtarı korumaz: bu kişisel kullanım içindir.

Windows dosya işlemleri için bu uygulamada henüz sunucu uç noktası/sözleşmesi
bulunmuyor. `WindowsActionApproval` arayüzü, gelecekteki sunucu adaptörünün her
işlemden önce Android biyometrik onayı istemesi için test edilebilir bir sınır
sağlar; mevcut `/komut` veya `/ses` protokolü değiştirilmez ve Windows işlemi
uydurulmuş bir API'ye gönderilmez.

Android Studio'da `android-app/` klasörünü açıp Gradle eşitlemesini tamamla ve
**Build > Build APK(s)** seç. Komut satırından `android-app/` klasöründe
`.\gradlew.bat assembleDebug` (Windows) veya `./gradlew assembleDebug` çalıştır.
Test APK'sı `android-app/app/build/outputs/apk/debug/app-debug.apk` konumuna çıkar.
Build için Android SDK 36 ve JDK 17 veya üstü gerekir.

Android birim testleri için `android-app/` klasöründe
`.\gradlew.bat testDebugUnitTest` çalıştır.

## Render Start Command (önerilen)

```
gunicorn voice_server:app --bind 0.0.0.0:$PORT --timeout 120
```
