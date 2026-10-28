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
- `POST /workspace/plan` → seçilmiş mevcut dosyalar için geçici değişiklik önerir
- `POST /workspace/agents/analyze` → açıkça seçilip onaylanan ajan tanımlarından geçici analiz alır

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

## Windows çalışma alanı değişiklik planı

`POST /workspace/plan` mevcut API anahtarıyla kimlik doğrular ve HTTPS gerektirir.
İstemci yalnızca kullanıcının açıkça seçtiği, yapılandırılmış çalışma alanındaki
mevcut dosyaların göreli yollarını ve UTF-8 metin içeriklerini gönderir; çalışma
alanı kökü sunucuya gönderilmez.

```http
POST /workspace/plan
Authorization: Bearer <ASISTAN_API_KEY>
Content-Type: application/json
```

```json
{
  "instruction": "Bu dosyada hata mesajını düzelt.",
  "files": [
    {"path": "src/main.py", "content": "mevcut dosya içeriği"}
  ]
}
```

Başarılı yanıt, yalnızca gönderilen yollar için eksiksiz içerik değişiklik
önerileri ve kısa bir özettir:

```json
{
  "summary": "Hata mesajı ve ilgili doğrulama güncellendi.",
  "changes": [
    {"relative_path": "src/main.py", "replacement_content": "önerilen tam dosya içeriği"}
  ]
}
```

İstek 512 KiB'ı, talimat 4.000 karakteri, yol 240 karakteri, dosya sayısı 8'i
aşamaz. Her dosyanın girdi/çıktı metni en fazla 64 KiB, toplam içerik en fazla
256 KiB'dır; özet 2.000 karakterle sınırlıdır. Yalnızca göreli `/` yolları
kabul edilir; mutlak yollar, `.`/`..`/boş segmentler, ters eğik çizgi, NUL ve
Windows aygıt adları reddedilir. Girdi ve model çıktısı sıkı biçimde doğrulanır;
çıktı ekleme/silme/yeniden adlandırma yapamaz ve gönderilen her yolu tam bir kez
geri döndürmelidir.

Bu API yalnızca plan üretir: sunucu dosya sistemine erişmez, kaynakları veya
sonuçları saklamaz, önerilen kodu çalıştırmaz. Windows istemcisi gerçek dosya
yollarının seçilmiş çalışma alanı içinde olduğunu ve sembolik bağlantı/reparse
noktası olmadığını ayrıca doğrulamalı, her dosya için unified diff göstermeli
ve yazma işlemini yalnızca kullanıcının açık **Uygula** onayıyla yapmalıdır.
`provider_error` veya `invalid_model_response` hataları planın üretilemediğini
belirtir; istemci değişiklik uygulamamalıdır.

## Seçilmiş uzman ajanlarla analiz

`POST /workspace/agents/analyze` API anahtarı ve HTTPS gerektirir. İstemci,
kullanıcının seçtiği `.md` ajan tanımlarının kimlik, ad ve tam metnini isteğe
koymalıdır. İstekten önce arayüz, görevle birlikte gönderilecek **tüm** ajan
metinlerini (Claude'un seçim yapabilmesi için gönderilenler dahil) kullanıcıya
göstermeli ve açık onay almalıdır. `consent: true` bu istemci onay sözleşmesinin
zorunlu işaretidir; tek başına arayüz onayının kanıtı değildir.

```json
{
  "instruction": "Bu tasarımı güvenlik ve sürdürülebilirlik açısından değerlendir.",
  "consent": true,
  "agents": [
    {"id": "security-reviewer", "name": "Güvenlik inceleyicisi", "prompt": "Seçilmiş .md dosyasının tam metni"}
  ]
}
```

Sunucu Claude'a önce yalnızca istekteki adayları seçtiren bir planlama çağrısı
yapar; Claude en fazla üç ajan seçer. Her seçilen ajanın Markdown metni, yalnızca
kendi analiz çağrısına verilir; analizler birbirinden bağımsızdır ve son bir
çağrı bunları sentezler. Hiçbir çağrıda araç, şema, dosya sistemi, kabuk veya
eylem yürütme erişimi yoktur. Ajan Markdown'ı ve diğer istek içerikleri güvenilmez
veri sayılır; çıktı yalnızca analiz/öneridir, kod veya dosya değişikliği uygulamaz.
Bu, ayrı çalışan süreçler veya özerk/uygulanabilir ajanlar değildir.

En fazla 8 ajan gönderilebilir; her prompt 16 KiB, toplam prompt metni 48 KiB,
görev 4.000 karakter ve tüm istek 384 KiB ile sınırlıdır. Yanıtta seçilen ajanlar,
ayrı analizleri ve sentez yer alır. Prompt'lar, görev ve sonuçlar sunucu
tarafında saklanmaz; yalnızca istek işlenirken bellekte kullanılır. İstemci bu
verileri Claude'a göndermeden önce göstermeli, kullanıcıdan izin almalı ve
`provider_error` / `invalid_model_response` durumlarında sonuç üretildiğini
varsaymamalıdır.

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

Dosyalar Android sistem belge seçicisiyle, takvim etkinlikleri takvim
uygulamasının kendi onay ekranıyla açılır. Kişiyi arama/SMS için seçme akışı
Android'in telefon numarası seçicisini kullanır. `Rehberde Ayşe'yi ara` veya
`Ayşe'nin numarası ne?` gibi açık kişi arama komutlarında uygulama önce neden
gerektiğini açıklar, ardından Android `READ_CONTACTS` iznini ister. Arama ve
sonuçlar cihazda kalır; bu akış rehber bilgisini sunucuya göndermez veya sohbet
geçmişine kaydetmez. İzin başlangıçta istenmez. Arama yalnızca Android arama
ekranını, SMS yalnızca hazır mesaj ekranını açar; uygulama sessizce arama yapmaz,
SMS göndermez veya tüm kişiler/dosyalara arka planda erişmez.

Metin sohbeti, en fazla 20 tamamlanmış önceki kullanıcı/asistan turunu `/komut`
isteğine bağlam olarak ekler; geçerli kullanıcı mesajı ikinci kez eklenmez ve hata
bildirimleri konuşma bağlamına girmez. “Sohbet geçmişini temizle” önce cihazdaki
geçmişi siler ve sunucuya boş bağlam göndererek eski sunucu oturum belleğinin geri
gelmesini önler. Çip, Android'de sunulmayan cihaz erişimi veya işlemleri yaptığını
iddia etmemesi için yönlendirilir.

Android'deki **Uzman analizi** düğmesi, kullanıcının seçtiği 1-8 `.md` uzman
tanımını tam metinleriyle gösterir. Talimat ve seçili dosyalar yalnızca
HTTPS üzerinden, açık gönderim onayından sonra `/workspace/agents/analyze`
uç noktasına iletilir. Analiz yalnızca öneri üretir; dosya değiştirmez,
komut çalıştırmaz veya araç çağırmaz. Dosyalar ve sonuçlar yerel geçmişe
kaydedilmez; hazır ajan tanımları kendiliğinden eklenmez.

Yerel komutlar dar bir listeden eşleştirilir; diğer komutlar mevcut sunucu
uç noktalarına gider.
“Saat kaç?” ve “bugün hangi gün?” gibi tanınan saat/tarih soruları, cihazın
yerel saat dilimi ve dil ayarları kullanılarak internet veya API anahtarı
gerektirmeden yanıtlanır; yazılı komutlarda ve “Hey Çip” sonrasındaki komutlarda
aynı şekilde çalışır.

İlk açılışta Render sunucu adresini ve `ASISTAN_API_KEY` değerini Ayarlar'a gir.
Anahtar Android Keystore ile cihazda şifreli saklanır; APK'nın içine gömülmez.
Uygulamayı başkalarıyla paylaşmak yine anahtarı korumaz: bu kişisel kullanım içindir.

Android Studio'da `android-app/` klasörünü açıp Gradle eşitlemesini tamamla ve
**Build > Build APK(s)** seç. Komut satırından `android-app/` klasöründe
`.\gradlew.bat assembleDebug` (Windows) veya `./gradlew assembleDebug` çalıştır.
Test APK'sı `android-app/app/build/outputs/apk/debug/app-debug.apk` konumuna çıkar.
Build için Android SDK 36 ve JDK 17 veya üstü gerekir.

## Windows dosya aktarımı uygulaması

`windows-app/` klasörü Android cihazla HTTPS üzerinden dosya aktarımı için
Türkçe Windows masaüstü istemcisini içerir. Gereksinimler, eşleştirme ve
güvenlik sınırları için [`windows-app/README.md`](windows-app/README.md)
belgesine bakın.

Çalışma alanı penceresindeki **Uzman analizi** sekmesi, kullanıcının seçtiği
1-8 adet `.md` ajan tanımını tam metinleriyle önizler. Talimat ve tüm seçili
prompt'lar ancak açık onaydan sonra mevcut HTTPS sunucusuna ve Claude'a
gönderilir; bu prompt'lar ve analiz sonuçları uygulama durumuna kaydedilmez.
Sekme yalnızca uzman seçimi, analiz ve öneri gösterir; dosya düzenlemez,
komut çalıştırmaz veya araç çağırmaz.

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

Her dosya işlemi, kullanıcının ilgili istemcide seçtiği/paylaştığı dosya için yeni
bir action olarak oluşturulur; sunucu keyfi yol, dosya okuma/yazma veya komut
çalıştırma API'si sunmaz. Windows istemcisi yalnızca kullanıcının seçtiği/paylaştığı
dosya veya klasör kapsamında işlem yapmalıdır.

PC'den telefona aktarımda PC aşağıdaki `/transfer/actions` endpoint'ini kullanır.
Telefonun dosya seçicisinde kullanıcı tarafından seçilen dosya için Android,
başka yöne kapsam veremeyen `POST /transfer/actions/phone` endpoint'ini çağırır:

```http
POST /transfer/actions/phone
Authorization: Bearer <device_token>
Content-Type: application/json
```

```json
{
  "pairing_id":"...",
  "filename":"telefon-secimi.pdf",
  "size":12345,
  "sha256":"64-kucuk-harfli-hex-karakter"
}
```

Telefon endpoint'i `direction` alanını kabul etmez ve yalnızca `phone_to_pc`
action'ı oluşturur. Android dosya seçicisinden gelen metadata ile boyut ve
SHA-256'yı hesaplar; sunucu aynı filename, size ve SHA-256 doğrulamasını uygular.
Bu endpoint de aynı `201` action yanıtını verir: `action_id`, sabit `direction`,
`filename`, `size`, `sha256`, `nonce`, `expires_at`,
`state:"awaiting_approval"` ve imzalanacak `challenge`. Her iki endpoint aynı
pairing başına 30 action/saat, 20 action ömrü ve iki etkin action sınırlarını
kullanır.

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

`/transfer/actions` üzerindeki `direction` değeri `pc_to_phone` veya `phone_to_pc`
olur. Her iki endpoint'te `size` 1–20 MiB aralığındadır ve `filename` yalnızca
dosya adı olabilir. Android, kendi başlattığı `phone_to_pc` action'ı da dahil,
her action için kullanıcıya yönü, dosya adını ve boyutu gösterip ayrı bir
BiometricPrompt ister. Onay anahtarı Android Keystore'da biyometriyle her
kullanımda yetkilendirilen P-256 anahtar olmalı; `BiometricPrompt.CryptoObject(Signature)`
ile `challenge` UTF-8 baytları ECDSA/SHA-256 kullanılarak imzalanmalı ve DER
imza Base64 gönderilmelidir:

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
noktasından kendi token'larıyla sorgular. `phone_to_pc` için PC kendi token'ıyla
action'ı görür ve yalnızca state `ready` olduğunda dosyayı indirebilir.

Onaylanan işlemde yalnız belirlenen gönderen ham gövdeyi gönderir:

```http
PUT /transfer/actions/{action_id}/data
X-Cip-Pairing-Id: <pairing_id>
Authorization: Bearer <gönderenin-tokeni>
Content-Type: application/octet-stream
Content-Length: <imzalı-size>

<dosya baytları>
```

`pc_to_phone` için gönderen PC, `phone_to_pc` için Android'dir. Her iki yönde de
upload yalnızca cihazın imzası doğrulandıktan sonra kabul edilir. Alıcı aynı
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

Android birim testleri için `android-app/` klasöründe
`.\gradlew.bat testDebugUnitTest` çalıştır. Transfer testleri challenge alan
sırasını, null challenge durumlarını, katı yanıt zarfını ve dosya boyutu
sınırını denetler.

## Render Start Command (önerilen)

```
gunicorn voice_server:app --bind 0.0.0.0:$PORT --timeout 120 --workers 1
```
