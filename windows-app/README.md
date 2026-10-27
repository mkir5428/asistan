# Çip Windows dosya aktarımı

Bu Windows masaüstü istemcisi, Android uygulamasıyla HTTPS üzerinden dosya
aktarımını yönetir. Arayüz Türkçedir. Kaynak dosya seçimi ve alınan dosyanın
hedefi yalnızca kullanıcının açıkça izin verdiği klasörlerle sınırlandırılır.
Her işlem Android'de ayrı biyometrik onay bekler; istemci onaydan önce dosya
yüklemez veya indirmeye hazır olmayan veriyi kabul etmez.

## Gereksinimler ve çalıştırma

- Windows 10 veya 11
- .NET 8 SDK

PowerShell'de depo kökünden:

```powershell
dotnet run --project .\windows-app\Cip.Windows.csproj
```

Yayımlanabilir Windows x64 uygulaması oluşturmak için:

```powershell
dotnet publish .\windows-app\Cip.Windows.csproj -c Release -r win-x64 --self-contained true
```

Self-contained yayımlama .NET çalışma zamanını pakete dahil eder.

İlk açılışta HTTPS sunucu adresini girip **Eşleştir** düğmesine basın. Üretilen
10 haneli, tek kullanımlık kodu 5 dakika içinde Android uygulamasına girin.
Eşleşme token'ı yalnızca mevcut Windows hesabının okuyabileceği DPAPI korumalı
uygulama durumunda tutulur. `ASISTAN_API_KEY` bu uygulamada istenmez veya
`/transfer/` uç noktalarına gönderilmez.

**İzin verilen klasörler** bölümüne yalnızca aktarım için paylaşmak istediğiniz
klasörleri ekleyin. Bilgisayardan telefona gönderirken kaynak dosya seçicisi,
dosyanın bu klasörlerden birinde olmasını zorunlu kılar. Telefondan gelen dosya
seçtiğiniz izinli klasöre kaydedilir; var olan dosyanın üzerine yazılmaz.
Her dosya en fazla 20 MiB olabilir.

## Çalışma alanı değişiklik önerileri API'si

Backend'in `POST /workspace/plan` endpoint'i kullanıcının yazdığı talimatı ve
yalnızca seçtiği mevcut dosyaların UTF-8 metinlerini alır. HTTPS ve mevcut
`ASISTAN_API_KEY` Bearer kimlik doğrulaması zorunludur. Çalışma alanı kökü
sunucuya gönderilmez; istemci gerçek dosya yolunun yapılandırılmış kökün içinde
olduğunu ve dosyanın sembolik bağlantı/reparse noktası olmadığını doğrulamalıdır.

```http
POST /workspace/plan
Authorization: Bearer <ASISTAN_API_KEY>
Content-Type: application/json
```

```json
{
  "instruction": "Bu dosyada hata mesajını düzelt.",
  "files": [
    {"path": "src/main.py", "content": "mevcut UTF-8 dosya içeriği"}
  ]
}
```

Başarılı yanıtın biçimi:

```json
{
  "summary": "Hata mesajı ve ilgili doğrulama güncellendi.",
  "changes": [
    {"relative_path": "src/main.py", "replacement_content": "önerilen tam dosya içeriği"}
  ]
}
```

İstek 512 KiB, talimat 4.000 karakter, yol 240 karakter, dosya sayısı 8,
dosya başına girdi/çıktı 64 KiB, toplam içerik 256 KiB ve özet 2.000 karakterle
sınırlıdır. Yalnızca göreli `/` yolları kabul edilir. Sunucu mutlak yolları,
ebeveyn/geçersiz segmentleri, ters eğik çizgileri, yinelenen yolları, NUL ve
Windows aygıt adlarını reddeder. Yanıt yalnızca istenen yolların her birini bir
kez içerebilir; sunucu dosya oluşturamaz, silemez, yazamaz veya kod çalıştıramaz.
İstemci unified diff'i göstermeli ve dosyaları yalnızca kullanıcının açık
**Uygula** onayıyla yazmalıdır. Hata yanıtları `{ "error": "..." }` biçimindedir;
özellikle `provider_error` ve `invalid_model_response` durumlarında hiçbir
değişiklik uygulanmamalıdır.

## Aktarım akışı

- Bilgisayardan telefona gönderme, dosyanın boyutunu ve SHA-256 özetini yerelde
  hesaplar ve PC token ile `/transfer/actions` üzerinden istek oluşturur.
- Telefondan bilgisayara gönderme Android uygulamasında başlatılır: kullanıcı
  dosya seçicisinden dosyayı seçer, Android `/transfer/actions/phone` ile
  metadata'yı kaydeder ve biyometrik onaydan sonra yükler. Windows kuyruğunda
  işlem `ready` olduğunda bir izinli hedef klasör seçip indirebilirsiniz.
- İstemci yalnızca durum `approved` olduktan sonra dosya baytlarını TLS korumalı
  `/data` uç noktasına yükler. İndirmede yalnızca durum `ready` olduğunda veri
  alınır ve boyut/SHA-256 yeniden doğrulanır.
- Kuyruk her birkaç saniyede yenilenir. İstek/aktarımı kuyruktan iptal
  edebilirsiniz. Onay bekleyen bir işlem 2 dakika, onaylanan aktarım penceresi
  10 dakika içinde tamamlanmalıdır. Eşleştirme başına en fazla iki etkin işlem
  vardır.
- Eşleşmeyi iptal etmek Android bağlantısını ve bekleyen sunucu verisini
  geçersiz kılar. Yeni cihaz için yeni kodla eşleştirme gerekir.

## Şifreleme ve çalışma sınırları

Dosyalar ağda HTTPS/TLS ile şifrelenir. Bu, uçtan uca dosya şifrelemesi değildir:
relay sunucusu dosya baytlarını aktarım boyunca bellekte açık biçimde görebilir.
Uygulama tarafında dosyalar diske yazılmaz; alınan son dosya yalnızca açıkça
seçtiğiniz hedef klasöre yazılır. Render ingress/proxy tamponlaması ve
altyapı günlükleri istemci tarafından denetlenemez; mutlak disk-saklamama
garantisi deployment katmanında da doğrulanmalıdır.

Pairing, token, action durumu ve bekleyen dosya baytları tek sunucu sürecinin
RAM'indedir. Render'da tek servis örneği ve tek Gunicorn worker kullanın.
Restart/deploy eşleşmeleri ve bekleyen transferleri siler; uygulama yeniden
eşleştirilmelidir. Bilgisayar uygulaması açık ve ağa bağlı değilken aktarım
yapılamaz. Sunucudaki rate limit ve kapasite hataları kullanıcıya gösterilir.

## v4.6 çalışma alanı ve yerel sunucu

**Çalışma alanı ve yerel sunucu** penceresinde bir proje klasörünü açıkça seçin.
Planlama isteği yalnızca ayrıca seçtiğiniz 1-8 mevcut dosyanın UTF-8 içeriğini
ve yazdığınız talimatı HTTPS `POST /workspace/plan` uç noktasına gönderir.
Talimat 4.000 karakter; yol 240 karakter; her dosya 64 KiB, toplam içerik
256 KiB ve JSON isteği 512 KiB ile sınırlıdır. Yanıtta dosya yolları tam olarak
seçilen dosyalarla eşleşmelidir.
`ASISTAN_API_KEY`, Windows hesabına bağlı DPAPI ile korunan ayarlarda tutulur ve
yalnızca bu planlama uç noktasına gönderilir; transfer API'sinde kullanılmaz.

Sunucudan dönen dosya yolları ve içerikler güvenilir kabul edilmez: istemci
yalnızca gönderilen dosyalar için eksiksiz yanıtı kabul eder, yolları tekrar
doğrular ve her dosyanın farkını gösterir. Dosyalar, **Apply** metnini yazarak
onay vermeden yazılmaz. Uygulama öncesi dosya içeriğinin
plan oluşturulduğundakiyle aynı olduğu doğrulanır; her dosya aynı klasördeki
geçici dosyaya yazılıp atomik olarak değiştirilir. Planlanan kod veya komut
çalıştırılmaz. Dosya seçim sınırları nedeniyle büyük/ikili dosyalar
planlamaya gönderilemez.

Yerel sunucu profilleri de elle oluşturulur ve seçilir. Profil sabit bir `.exe`,
seçili çalışma alanı içindeki çalışma klasörü ve sabit argümanlardan oluşur;
komut kabukları profil olarak reddedilir. Başlatma, durdurma ve yeniden
başlatma işlemleri yalnızca bu pencerede başlatılmış süreç için görünür
durum/çıktı günlüğüyle yapılır. Uygulama kapanırken başlattığı süreç ağacını
durdurur; profil otomatik başlatılmaz. Bu özellik Render veya başka bir bulut
sunucusunu yönetmez.

## Doğrulamalar

Windows'ta istemci güvenlik/protokol kontrollerini çalıştırın:

```powershell
dotnet run --project .\windows-app\tests\Cip.Windows.Tests.csproj
```

Kontroller HTTPS zorunluluğunu, klasör sınırlarını, Windows dosya adlarını,
eşleştirme isteğinin kimlik bilgisi göndermemesini ve eylem isteğinin PC
token'ını kullanmasını kapsar.
