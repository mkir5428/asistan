# Çip Sunucu (sesli-asistan)

Flask tabanlı arka uç: telefondan gelen sesi Groq Whisper ile yazıya çevirir,
akıllı ampul komutlarını Tuya'ya iletir, gerisini Claude yanıtlar.

## Uç noktalar

- `GET /` → sağlık/teşhis sayfası (eksik env değişkenlerini gösterir)
- `GET /komut?text=merhaba` → metin komutu
- `POST /ses` → multipart `file` alanıyla ses dosyası (m4a/wav)

## Render > Environment'da olması gerekenler

| Değişken | Açıklama |
|---|---|
| `ANTHROPIC_API_KEY` | Claude API anahtarı |
| `GROQ_API_KEY` | Groq (whisper-large-v3) anahtarı |
| `TUYA_CLIENT_ID` / `TUYA_CLIENT_SECRET` | Tuya IoT Cloud uygulaması |
| `TUYA_DEVICE_ID` | Akıllı ampulün cihaz kimliği |
| `TUYA_ENDPOINT` | Örn. `https://api.tuya.com` (bölgeye göre değişir) |

İsteğe bağlı: `CLAUDE_MODEL` (varsayılan `claude-sonnet-5`), `PORT`.

## Lokal çalıştırma

```bash
pip install -r requirements.txt
python voice_server.py
```

## Render Start Command (önerilen)

```
gunicorn voice_server:app --bind 0.0.0.0:$PORT --timeout 120
```
