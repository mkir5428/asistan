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

## Doğrulamalar

Windows'ta istemci güvenlik/protokol kontrollerini çalıştırın:

```powershell
dotnet run --project .\windows-app\tests\Cip.Windows.Tests.csproj
```

Kontroller HTTPS zorunluluğunu, klasör sınırlarını, Windows dosya adlarını,
eşleştirme isteğinin kimlik bilgisi göndermemesini ve eylem isteğinin PC
token'ını kullanmasını kapsar.
