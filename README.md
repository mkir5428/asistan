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

## Render Start Command (önerilen)

```
gunicorn voice_server:app --bind 0.0.0.0:$PORT --timeout 120
```
