# Çip Sunucu (sesli-asistan)

Flask tabanlı arka uç: telefondan gelen sesi Groq Whisper ile yazıya çevirir,
akıllı ampul komutlarını Tuya'ya iletir, gerisini Claude yanıtlar.

## Uç noktalar

`/` ve ayrı eşleştirme kimlik bilgileri kullanan `/transfer/` dışında tüm uç
noktalar API anahtarı ister. Metin parametreleri GET sorgusuyla veya POST form
alanlarıyla gönderilebilir.

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
| `ASISTAN_API_KEY` | Mevcut `/transfer/` dışındaki API uç noktaları için zorunlu anahtar |
| `ANTHROPIC_API_KEY` | Claude API anahtarı |
| `GROQ_API_KEY` | Groq (whisper-large-v3) anahtarı |
| `TUYA_CLIENT_ID` / `TUYA_CLIENT_SECRET` | Tuya IoT Cloud uygulaması |
| `TUYA_DEVICE_ID` | Akıllı ampulün cihaz kimliği |
| `TUYA_ENDPOINT` | Örn. `https://api.tuya.com` (bölgeye göre değişir) |

İsteğe bağlı: `CLAUDE_MODEL` (varsayılan `claude-sonnet-5-5`), `EDGE_TTS_SES`, `PORT`.

Mevcut API isteklerinde (`/` sağlık sayfası hariç) şu başlığı gönder:

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

`android-app/` klasörü Çip için yerel Android istemcisidir. Metin ve mikrofonla
`/komut` ile `/ses` uç noktalarını kullanır; yanıtları Türkçe seslendirir.
İlk açılışta Render sunucu adresini ve `ASISTAN_API_KEY` değerini Ayarlar'a gir.
Anahtar Android Keystore ile cihazda şifreli saklanır; APK'nın içine gömülmez.
Uygulamayı başkalarıyla paylaşmak yine anahtarı korumaz: bu kişisel kullanım içindir.

Android Studio'da `android-app/` klasörünü açıp Gradle eşitlemesini tamamla ve
**Build > Build APK(s)** seç. Komut satırından `android-app/` klasöründe
`.\gradlew.bat assembleDebug` (Windows) veya `./gradlew assembleDebug` çalıştır.
Test APK'sı `android-app/app/build/outputs/apk/debug/app-debug.apk` konumuna çıkar.
Build için Android SDK 36 ve JDK 17 veya üstü gerekir.

## Bilgisayar ↔ telefon dosya aktarımı API'si

Transfer API'si `/transfer/` altında ayrı kimlik bilgileri kullanır; `ASISTAN_API_KEY`
bu uç noktalara gönderilmez ve dosya aktarım yetkisi değildir. Tüm istekler aynı
HTTPS sunucu adresine yapılmalıdır. Web CORS erişimi açılmaz. Dosya gövdesi TLS
üzerinden şifrelenir; Render TLS'yi sonlandırdığı için relay dosya baytlarını aktarım
sırasında bellekte açık biçimde görebilir. Dosya diske, uygulama loglarına veya hata
yanıtlarına yazılmaz. Bu protokol uçtan uca dosya şifrelemesi sağlamaz.

### Eşleştirme ve iptal

Bilgisayar `POST /transfer/pairings` çağırır; API anahtarı gerekmez:

```http
POST /transfer/pairings
```

```json
{
  "pairing_id": "yanitta-verilen-kimlik",
  "pairing_code": "10-haneli-kod",
  "pc_token": "tekil-bilgisayar-tokeni",
  "pairing_code_expires_in": 300,
  "pairing_expires_in": 2592000
}
```

10 haneli eşleştirme kodu beş dakika geçerlidir ve bir kez kullanılabilir. Bilgisayar kodu
kullanıcıya yerel olarak gösterir; `pc_token` işletim sisteminin güvenli kimlik
bilgisi kasasında tutulmalı, loglanmamalıdır. Android bir P-256 EC açık anahtarını
PEM biçiminde göndererek `POST /transfer/pairings/claim` ile kodu talep eder:

```json
{"pairing_code":"1234567890","public_key_pem":"-----BEGIN PUBLIC KEY-----\\n..."}
```

Başarılı yanıt `pairing_id`, 30 gün geçerli `device_token` ve
`pairing_expires_in` verir.
Eşleştirme durumu bilgisayardan
`GET /transfer/pairings/{pairing_id}` (`pc_token`) ile `waiting` veya `paired`
olarak sorgulanır. Eşleştirme iki taraftan da
`DELETE /transfer/pairings/{pairing_id}` ile iptal edilebilir; iptal bekleyen
aktarımları ve bellekteki dosya baytlarını siler. Süre dolunca yeniden eşleştirme
gerekir.

### Tek eylem onayı ve aktarım

Bilgisayar her dosya işlemi için yeni bir işlem oluşturur; sunucu keyfi yol, dosya
okuma/yazma veya komut çalıştırma API'si sunmaz. İstemci yalnızca kullanıcının
Windows'ta seçtiği/paylaştığı dosya veya klasör kapsamında işlem yapmalıdır.

```http
POST /transfer/actions
Authorization: Bearer <pc_token>
Content-Type: application/json
```

```json
{
  "pairing_id":"...",
  "direction":"pc_to_phone",
  "filename":"rapor.pdf",
  "size":12345,
  "sha256":"64-kucuk-harfli-hex-karakter"
}
```

`direction` değeri `pc_to_phone` veya `phone_to_pc` olur. `size` 1–20 MiB
aralığındadır; `filename` yalnızca dosya adı olabilir. Yanıt `action_id`, `nonce`,
`expires_at`, `state:"awaiting_approval"` ve imzalanacak `challenge` döndürür.
Android `GET /transfer/actions?pairing_id=...` ile işlemi alır, kullanıcıya yönü,
dosya adını ve boyutu gösterir ve her işlem için ayrı BiometricPrompt ister.
Onay anahtarı Android Keystore'da biyometriyle her kullanımda yetkilendirilen P-256
anahtar olmalı; `BiometricPrompt.CryptoObject(Signature)` ile `challenge` UTF-8
baytları ECDSA/SHA-256 kullanılarak imzalanmalı ve DER imza Base64 gönderilmelidir:

```http
POST /transfer/actions/{action_id}/approve
Authorization: Bearer <device_token>
Content-Type: application/json
```

```json
{"pairing_id":"...","signature":"Base64-DER-ECDSA-imzasi"}
```

Challenge'ın imzalı metni tam olarak şu satırların LF (`\\n`) ile birleştirilmiş
UTF-8 halidir:
`cip-transfer-v1`, `pairing_id`, `action_id`, `nonce`, `direction`, `filename`,
`size`, `sha256`, `expires_at`. Onay iki dakika içinde gelmelidir. API imzayı eşleşmiş
Android açık anahtarıyla doğrular; onaydan sonra gönderim/alım için on dakika vardır.
PC ve telefon işlem durumunu aynı `GET /transfer/actions?pairing_id=...` uç
noktasından kendi token'larıyla sorgular.

Onaylanan işlemde yalnız belirlenen gönderen ham gövdeyi gönderir:

```http
PUT /transfer/actions/{action_id}/data
X-Cip-Pairing-Id: <pairing_id>
Authorization: Bearer <gönderenin-tokeni>
Content-Type: application/octet-stream
Content-Length: <imzalı-size>

<dosya baytları>
```

`pc_to_phone` için gönderen PC, `phone_to_pc` için Android'dir. Alıcı aynı
`/data` yoluna `GET` yapar; `pairing_id` query parametresi ve alıcının Bearer token'ı
kullanılır. Gövde boyutu ve SHA-256 imzalı metadata ile eşleşince `ready` olur.
Alıcı başarılı indirmeyi tamamladığında relay bellekteki baytları hemen siler ve
eylemi `completed` yapar. Taraflardan biri
`POST /transfer/actions/{action_id}/cancel` gövdesinde `pairing_id` göndererek
işlemi iptal edebilir.

### Sınırlar ve hata kodları

JSON gövdesi en fazla 8 KiB, tek dosya 20 MiB, süreçte tamponlanan toplam dosya
verisi 100 MiB, eşzamanlı eylem eşleştirme başına iki adettir. Eşleştirme oluşturma,
kod denemeleri, eylem oluşturma/onaylama ve veri uç noktaları süreç içi oran
sınırlamasına tabidir. Hata yanıtı biçimi `{"error":"kod"}`. Kodlar ve durumları: `400 invalid_request`,
`invalid_json`, `invalid_public_key`, `size_mismatch`, `content_mismatch`;
`401 unauthorized`; `403 invalid_signature`, `wrong_transfer_party`;
`404 not_found`; `409 already_paired`, `not_paired`, `too_many_active_actions`,
`action_capacity_exceeded`, `action_not_awaiting_approval`,
`action_not_approved_or_already_used`, `transfer_not_ready_or_already_used`;
`410 pairing_code_invalid_or_expired`; `413 request_too_large`;
`415 content_type_must_be_json`, `content_type_must_be_octet_stream`;
`426 https_required`; `429 rate_limited`; `503 capacity_exceeded`,
`relay_capacity_exceeded`; `unsupported_public_key` 400'dür. İstek IP sınırı
`request.remote_addr` kullanır (X-Forwarded-For güvenilir proxy yapılandırması
olmadığı için okunmaz); proxy tüm istemciler için aynı adresi gösterirse sınır
ortak uygulanır.

Sunucu biyometrinin gerçekten gösterildiğini tek başına doğrulayamaz; yalnızca
eşleşme sırasında verilen açık anahtarın imzasını doğrular. Bu nedenle Android
istemcisi biyometri zorunlu Android Keystore anahtarını doğru yapılandırmalı ve
her dosya eyleminde BiometricPrompt göstermelidir. Sunucu tarafında Android Key
Attestation doğrulaması bu API kapsamına dahil değildir.

Aktarım/eşleştirme durumu ve rate limitleri yalnızca tek Python sürecinin RAM'inde
tutulur; Flask uygulaması dosya içeriğini diske yazmaz ve süreçler arası paylaşım
yoktur. Render ingress/proxy katmanının gövde tamponlama veya altyapı günlük
politikaları bu uygulama tarafından denetlenmez; mutlak disk-saklamama garantisi
deployment katmanında da doğrulanmalıdır. Render'da tek servis
örneği ve tek Gunicorn worker kullanın; yatay ölçekleme veya birden fazla worker
istekleri farklı RAM durumlarına yönlendirip eşleştirme/aktarımı bozabilir. Yeniden
başlatma, çökme veya deploy bekleyen transferleri ve eşleşmeleri kaybettirir; istemci
yeniden eşleşip aktarımı başlatmalıdır. 100 MiB sınırı tamponlanmış dosyaları kapsar;
eşzamanlı isteklerin geçici RAM kullanımı buna eklenir.

## Render Start Command (önerilen)

```
gunicorn voice_server:app --bind 0.0.0.0:$PORT --timeout 120 --workers 1
```
