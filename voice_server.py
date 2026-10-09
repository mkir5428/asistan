"""
Çip sesli asistan sunucusu (Render) — v4.0.

Uç noktalar:
  GET  /                        -> sağlık/teşhis sayfası
  GET  /komut?text=...&gecmis=...&profil=...    -> sohbetli AI komutu
  POST /ses (multipart: file, + format=json)    -> ses (Groq) -> komut
  GET  /cevir?text=...&dil=...  -> çeviri (Claude)
  GET  /ozet?text=...&mod=...   -> özetleme (Claude)
  GET  /hava?sehir=...          -> hava durumu (open-meteo, anahtarsız)
  GET  /hesap?ifade=...         -> güvenli hesap makinesi
  GET  /seslendir?metin=...     -> doğal Türkçe ses (edge-tts, mp3)
  GET  /temizle?session=...     -> sunucu tarafı oturum hafızasını sıfırla

Tasarım ilkesi: AÇILIŞTA HİÇBİR ŞEY ÇÖKMEZ. Anahtarlar/bağlantılar tembel;
eksik servis istek anında anlaşılır Türkçe mesaj döner.
"""

import asyncio
import io
import ast
import json
import logging
import os
import re
import sys
import threading
from datetime import datetime, timedelta, timezone

import requests
from flask import Flask, Response, jsonify, request
from tuya_connector import TuyaOpenAPI
import anthropic

SURUM = "4.3"

# ==================== LOG ====================
logging.basicConfig(
    level=logging.INFO,
    stream=sys.stdout,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("cip-sunucu")

app = Flask(__name__)

# ==================== ENV (tembel) ====================

GEREKLILER = [
    "ANTHROPIC_API_KEY",
    "GROQ_API_KEY",
    "TUYA_CLIENT_ID",
    "TUYA_CLIENT_SECRET",
    "TUYA_DEVICE_ID",
    "TUYA_ENDPOINT",
]


def env_aldi(ad: str) -> bool:
    return bool(os.environ.get(ad, "").strip())


def eksik_env_listesi() -> list:
    return [ad for ad in GEREKLILER if not env_aldi(ad)]


# ==================== CLAUDE (tembel) ====================

_claude = None


def claude_istemci():
    global _claude
    if _claude is None:
        anahtar = os.environ.get("ANTHROPIC_API_KEY", "").strip()
        if not anahtar:
            raise RuntimeError("ANTHROPIC_API_KEY Render'da ayarlanmamış.")
        _claude = anthropic.Anthropic(
            api_key=anahtar,
            timeout=60.0,
            max_retries=1,
        )
    return _claude


KISILIK = (
    "Sen Çip'sin: kullanıcının telefonunda yaşayan kişisel sesli asistan. "
    "Yapay zeka modeli ya da başka bir şirketin ürünü olduğunu asla söyleme; sen Çip'sin. "
    "Kullanıcıya 'sen' diye, samimi ve rahat hitap et; ona 'Şef' de (her cümlede değil, doğal yerlerde). "
    "KURALLAR: Cevapların sesli okunacak. En fazla 2-3 kısa cümle yaz, gereksiz giriş, "
    "özür, tekrar ve 'size nasıl yardımcı olabilirim' gibi kalıplar kullanma. "
    "Emoji ve Markdown kullanma. Doğrudan sonuca git. "
    "Sorunun cevabını biliyorsan net söyle, bilmiyorsan uydurma, tek cümleyle söyle. "
    "HAFIZA: Bu konuşmadaki önceki mesajları ve kullanıcı bilgilerini (adı vb.) hatırla ve kullan; "
    "'bilmiyorum, söylemediniz' deme, önce geçmişe bak. "
    "Matematik, mantık ve bilgi sorularında önce doğru cevabı düşün, sonra kısa söyle."
)


def simdi_metni() -> str:
    gunler = ["Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"]
    t = datetime.now(timezone(timedelta(hours=3)))  # Türkiye saati
    return f"{t.day}.{t.month}.{t.year} {gunler[t.weekday()]}, saat {t.hour:02d}:{t.minute:02d} (Türkiye)"


def claude_cevap(metin: str, gecmis: list = None, profil: str = "") -> str:
    sistem = KISILIK + "\n\nŞu an: " + simdi_metni()
    if profil:
        sistem += "\n\nKullanıcı hakkında bildiklerin (bu bilgiye göre kişiselleştir):\n" + profil[:1000]
    mesajlar = list(gecmis or [])
    mesajlar.append({"role": "user", "content": metin})
    yanit = claude_istemci().messages.create(
        model=os.environ.get("CLAUDE_MODEL", "claude-sonnet-5-5"),
        max_tokens=350,
        system=sistem,
        messages=mesajlar,
    )
    for blok in yanit.content:
        if blok.type == "text":
            return blok.text
    return "Bir cevap üretemedim."


def claude_sistem_gorevi(gorev: str, metin: str) -> str:
    """Çeviri/özet gibi tek seferlik görevler: kişilik yok, sadece görev."""
    yanit = claude_istemci().messages.create(
        model=os.environ.get("CLAUDE_MODEL", "claude-sonnet-5-5"),
        max_tokens=1024,
        system=gorev,
        messages=[{"role": "user", "content": metin}],
    )
    for blok in yanit.content:
        if blok.type == "text":
            return blok.text
    return "Bir cevap üretemedim."


# ==================== GROQ (tembel) ====================


def groq_transkript(ses_bytes: bytes, dosya_adi: str) -> str:
    anahtar = os.environ.get("GROQ_API_KEY", "").strip()
    if not anahtar:
        raise RuntimeError("GROQ_API_KEY Render'da ayarlanmamış.")
    resp = requests.post(
        "https://api.groq.com/openai/v1/audio/transcriptions",
        headers={"Authorization": f"Bearer {anahtar}"},
        files={"file": (dosya_adi, ses_bytes)},
        data={"model": "whisper-large-v3", "language": "tr"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["text"]


# ==================== TUYA (tembel bağlantı) ====================

_tuya = None
_tuya_bagli = False


def tuya_istemci():
    global _tuya, _tuya_bagli
    if _tuya is None:
        cid = os.environ.get("TUYA_CLIENT_ID", "").strip()
        csecret = os.environ.get("TUYA_CLIENT_SECRET", "").strip()
        endpoint = os.environ.get("TUYA_ENDPOINT", "").strip()
        if not cid or not csecret or not endpoint:
            raise RuntimeError("Tuya env değişkenleri eksik.")
        _tuya = TuyaOpenAPI(endpoint, cid, csecret)
    if not _tuya_bagli:
        _tuya.connect()
        _tuya_bagli = True
    return _tuya


def tuya_ampul(acilsin: bool) -> str:
    try:
        cihaz = os.environ.get("TUYA_DEVICE_ID", "").strip()
        if not cihaz:
            return "Ampul ayarları eksik, Render'da TUYA_DEVICE_ID yok."
        yanit = tuya_istemci().post(
            f"/v1.0/iot-03/devices/{cihaz}/commands",
            {"commands": [{"code": "switch_led", "value": acilsin}]},
        )
        if isinstance(yanit, dict) and yanit.get("success"):
            return "Ampulü açtım." if acilsin else "Ampulü kapattım."
        return f"Ampule komut gitti ama kabul edilmedi: {yanit}"
    except Exception as e:
        log.exception("Tuya hatası")
        return f"Ampule ulaşamadım: {e}"


# ==================== HAVA DURUMU (open-meteo, anahtarsız) ====================

HAVA_KODLARI = {
    0: "açık ve güneşli", 1: "az bulutlu", 2: "parçalı bulutlu", 3: "kapalı",
    45: "sisli", 48: "kırağılı sisli",
    51: "hafif çisentili", 53: "çisentili", 55: "yoğun çisentili",
    56: "dondurucu çisentili", 57: "yoğun dondurucu çisentili",
    61: "hafif yağmurlu", 63: "yağmurlu", 65: "şiddetli yağmurlu",
    66: "dondurucu yağmurlu", 67: "şiddetli dondurucu yağmurlu",
    71: "hafif kar yağışlı", 73: "kar yağışlı", 75: "yoğun kar yağışlı",
    77: "kar taneli",
    80: "hafif sağanaklı", 81: "sağanak yağışlı", 82: "şiddetli sağanaklı",
    85: "kar sağanaklı", 86: "yoğun kar sağanaklı",
    95: "gök gürültülü fırtınalı", 96: "dolulu fırtınalı", 99: "şiddetli dolulu fırtınalı",
}


def hava_cek(sehir: str):
    geo = requests.get(
        "https://geocoding-api.open-meteo.com/v1/search",
        params={"name": sehir, "count": 1, "language": "tr", "format": "json"},
        timeout=10,
    ).json()
    sonuc = geo.get("results") or []
    if not sonuc:
        return None
    yer = sonuc[0]
    w = requests.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": yer["latitude"],
            "longitude": yer["longitude"],
            "current": "temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m",
            "daily": "temperature_2m_max,temperature_2m_min",
            "forecast_days": 1,
            "timezone": "auto",
        },
        timeout=10,
    ).json()
    anlik = w.get("current") or {}
    gunluk = w.get("daily") or {}
    kod = int(anlik.get("weather_code", -1))
    aciklama = HAVA_KODLARI.get(kod, "değişken")
    return (
        f"{yer['name']} şu an {round(anlik.get('temperature_2m', 0))} derece, {aciklama}. "
        f"Bugün en düşük {round((gunluk.get('temperature_2m_min') or [0])[0])}, "
        f"en yüksek {round((gunluk.get('temperature_2m_max') or [0])[0])} derece. "
        f"Nem yüzde {round(anlik.get('relative_humidity_2m', 0))}, "
        f"rüzgâr {round(anlik.get('wind_speed_10m', 0))} kilometre saat."
    )


# ==================== GÜVENLİ HESAP MAKİNESİ ====================

KELIME_ISLEMLER = [
    (re.compile(r"\b(üssü|ussu|us su|ussune|kuvvet)\b"), "**"),
    (re.compile(r"\b(artı|arti|artir|plus)\b"), "+"),
    (re.compile(r"\b(eksi|minus)\b"), "-"),
    (re.compile(r"\b(çarpı|carpi|kere)\b"), "*"),
    (re.compile(r"\b(bölü|bolu|böl|over)\b"), "/"),
]

IZINLI_DUGUMLER = (
    ast.Expression, ast.BinOp, ast.UnaryOp, ast.Constant,
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Pow, ast.Mod, ast.FloorDiv,
    ast.USub, ast.UAdd,
)


def guvenli_hesap(ifade: str):
    """Sadece aritmetik: harf/fonksiyon/şüpheli şey varsa reddeder."""
    temiz = ifade.strip()
    for desen, isaret in KELIME_ISLEMLER:
        temiz = desen.sub(isaret, temiz)
    temiz = temiz.replace("x", "*").replace("X", "*").replace(",", ".")
    temiz = re.sub(r"[^0-9+\-*/().%\s\x00]", "", temiz.replace("**", "\x00")).replace("\x00", "**").strip()
    if not temiz or not re.search(r"\d", temiz):
        raise ValueError("sayı yok")
    agac = ast.parse(temiz, mode="eval")
    for dugum in ast.walk(agac):
        if not isinstance(dugum, IZINLI_DUGUMLER):
            raise ValueError("yasaklı ifade")
        if isinstance(dugum, ast.Constant) and not isinstance(dugum.value, (int, float)):
            raise ValueError("sayı olmayan sabit")
    sonuc = eval(  # noqa: S307 — AST beyaz listesiyle sınırlı
        compile(agac, "<hesap>", "eval"),
        {"__builtins__": {}},
        {},
    )
    if isinstance(sonuc, float) and sonuc.is_integer():
        sonuc = int(sonuc)
    return temiz, sonuc


# ==================== DOĞAL SES (edge-tts) ====================


def edge_ses_uret(metin: str) -> bytes:
    import edge_tts

    ses = os.environ.get("EDGE_TTS_SES", "tr-TR-EmelNeural")

    async def olustur() -> bytes:
        buf = io.BytesIO()
        akis = edge_tts.Communicate(metin, ses)
        async for parca in akis.stream():
            if parca["type"] == "audio":
                buf.write(parca["data"])
        return buf.getvalue()

    return asyncio.run(olustur())


# ==================== OTURUM HAFIZASI ====================

MAX_SOHBET_TURU = 20
_sohbetler = {}
_sohbet_kilit = threading.Lock()


def sohbet_al(session: str) -> list:
    with _sohbet_kilit:
        return list(_sohbetler.get(session, []))


def sohbet_ekle(session: str, soru: str, cevap: str):
    if not session:
        return
    with _sohbet_kilit:
        gecmis = _sohbetler.setdefault(session, [])
        gecmis.append({"role": "user", "content": soru})
        gecmis.append({"role": "assistant", "content": cevap})
        while len(gecmis) > MAX_SOHBET_TURU * 2:
            del gecmis[0:2]


def sohbet_temizle(session: str):
    with _sohbet_kilit:
        _sohbetler.pop(session, None)


def gecmisten_mesajlar(session: str) -> list:
    """İstemcinin gönderdiği gecmis JSON'u geçerliyse onu kullan (telefon kalıcı hafızası)."""
    ham = request.values.get("gecmis", "")
    if ham:
        try:
            dizi = json.loads(ham)
            temiz = []
            for m in dizi if isinstance(dizi, list) else []:
                if (
                    isinstance(m, dict)
                    and m.get("role") in ("user", "assistant")
                    and isinstance(m.get("content"), str)
                ):
                    temiz.append({"role": m["role"], "content": m["content"][:2000]})
            if temiz:
                return temiz[-MAX_SOHBET_TURU * 2:]
        except Exception:
            pass
    return sohbet_al(session)


# ==================== KOMUT ÇÖZÜMLEME ====================


def tr_den_ascii(metin: str) -> str:
    return (
        metin.lower()
        .replace("ç", "c").replace("ğ", "g").replace("ı", "i")
        .replace("ö", "o").replace("ş", "s").replace("ü", "u")
        .replace("â", "a").replace("î", "i").replace("û", "u")
    )


LAMBA_KELIMELER = re.compile(r"(ampul|lamba|isik|isigi|light)")
ACMA_KELIMELER = re.compile(r"\b(ac|acar|acik|acacak|yak|on)\b")
KAPATMA_KELIMELER = re.compile(r"\b(kapat|kapatir|kapa|kapali|sondur|off)\b")
TEMIZLE_KELIMELER = re.compile(r"\b(temizle|sifirla|sil|unut|resetle)\b")
HAFIZA_KELIMELER = re.compile(r"\b(hafiza\w*|sohbet\w*|gecmis\w*)\b")


def bellek_temizleme_istegi(metin: str) -> bool:
    a = tr_den_ascii(metin)
    return bool(TEMIZLE_KELIMELER.search(a) and HAFIZA_KELIMELER.search(a))


def isle_komut(metin: str, gecmis: list = None, profil: str = ""):
    metin = (metin or "").strip()
    if not metin:
        return duz_metin("Bir şey duymadım.")

    ascii_metin = tr_den_ascii(metin)

    if LAMBA_KELIMELER.search(ascii_metin):
        if ACMA_KELIMELER.search(ascii_metin):
            log.info("Ampul komutu: AÇ")
            return duz_metin(tuya_ampul(True))
        if KAPATMA_KELIMELER.search(ascii_metin):
            log.info("Ampul komutu: KAPAT")
            return duz_metin(tuya_ampul(False))

    try:
        return duz_metin(claude_cevap(metin, gecmis, profil))
    except Exception as e:
        log.exception("Claude hatası")
        return duz_metin(f"Cevap üretirken hata oldu: {e}")


# ==================== UÇ NOKTALAR ====================


def duz_metin(mesaj: str):
    return mesaj, 200, {"Content-Type": "text/plain; charset=utf-8"}


def oturum_id() -> str:
    return (request.values.get("session", "") or "varsayilan").strip()[:80]


def profil_metni() -> str:
    return (request.values.get("profil", "") or "").strip()[:1000]


@app.route("/")
def saglik():
    durum = "\n".join(
        f"{'✓' if env_aldi(ad) else '✗ EKSİK'}  {ad}" for ad in GEREKLILER
    )
    return duz_metin(
        f"Çip sunucusu v{SURUM} ayakta.\n"
        f"Ortam değişkenleri (Render > Environment):\n{durum}\n"
        f"Aktif oturum sayısı: {len(_sohbetler)}\n\n"
        "AI: /komut?text=...  /cevir  /ozet  /temizle\n"
        "Araçlar: /hava?sehir=...  /hesap?ifade=...  /seslendir?metin=..."
    )


@app.route("/komut", methods=["GET", "POST"])
def komut():
    metin = request.values.get("text", "")
    log.info("Komut: %r (gecmis=%d mesaj, profil=%d harf)", metin, len(gecmisten_mesajlar(oturum_id())), len(profil_metni()))
    if bellek_temizleme_istegi(metin):
        sohbet_temizle(oturum_id())
        return duz_metin("Sohbet hafızasını temizledim, yeni bir sayfa açtık.")
    cevap = isle_komut(metin, gecmisten_mesajlar(oturum_id()), profil_metni())[0]
    if not cevap.startswith("Cevap üretirken hata"):
        sohbet_ekle(oturum_id(), metin, cevap)
    return duz_metin(cevap)


@app.route("/ses", methods=["POST"])
def ses():
    if "file" in request.files:
        dosya = request.files["file"]
        veri = dosya.read()
        ad = dosya.filename or "audio.m4a"
    elif request.data:
        veri = request.data
        ad = "audio.m4a"
    else:
        return duz_metin("Ses dosyası gelmedi.")

    if len(veri) < 1000:
        return duz_metin("Kayıt çok kısa veya boş geldi.")

    try:
        metin = groq_transkript(veri, ad)
    except Exception as e:
        log.exception("Groq hatası")
        if request.args.get("format") == "json":
            return jsonify({"transkript": "", "cevap": f"Sesi anlayamadım: {e}", "temizlendi": False})
        return duz_metin(f"Sesi anlayamadım: {e}")

    log.info("Transkript: %r", metin)
    temizlik = bellek_temizleme_istegi(metin)
    if temizlik:
        sohbet_temizle(oturum_id())
        cevap = "Sohbet hafızasını temizledim, yeni bir sayfa açtık."
    else:
        cevap = isle_komut(metin, gecmisten_mesajlar(oturum_id()), profil_metni())[0]
        if not cevap.startswith("Cevap üretirken hata"):
            sohbet_ekle(oturum_id(), metin, cevap)

    if request.args.get("format") == "json":
        return jsonify({"transkript": metin, "cevap": cevap, "temizlendi": temizlik})
    return duz_metin(cevap)


CEVIRI_GOREVI = (
    "Sen profesyonel bir çevirmensin. Sana verilen metni istenen dile çevir. "
    "SADECE çeviriyi döndür: açıklama, tırnak işareti, 'İşte çeviri:' gibi "
    "eklemeler yapma. Doğal ve akıcı çevir."
)


@app.route("/cevir", methods=["GET"])
def cevir():
    metin = (request.args.get("text", "") or "").strip()
    if not metin:
        return duz_metin("Çevrilecek metin boş. Örnek: /cevir?text=merhaba&dil=ingilizce")

    dil = (request.args.get("dil", "") or "").strip().lower()
    dil_adi = dil.capitalize() if dil else "İngilizce"

    try:
        sonuc = claude_sistem_gorevi(CEVIRI_GOREVI, f"{dil_adi} diline çevir:\n\n{metin}")
        return duz_metin(sonuc)
    except Exception as e:
        log.exception("Çeviri hatası")
        return duz_metin(f"Çeviri başarısız: {e}")


OZET_GOREVI = (
    "Sen metin özetleme uzmanısın. Verilen metni istenen modda özetle. "
    "Sadece özeti döndür. Türkçe yaz."
)

OZET_MODLARI = {
    "kisa": "Tek cümlelik çok kısa özet yap.",
    "orta": "2-4 cümlelik özet yap.",
    "madde": "Maddeler halinde özetle (her madde ayrı satırda, kısa ve net).",
}


@app.route("/ozet", methods=["GET"])
def ozet():
    metin = (request.args.get("text", "") or "").strip()
    if len(metin) < 80:
        return duz_metin("Özetlenecek metin çok kısa. /ozet?text=<uzun metin>&mod=kisa|orta|madde")

    mod = tr_den_ascii(request.args.get("mod", "orta"))
    talimat = OZET_MODLARI.get(mod, OZET_MODLARI["orta"])

    try:
        sonuc = claude_sistem_gorevi(OZET_GOREVI, f"{talimat}\n\nMetin:\n{metin[:15000]}")
        return duz_metin(sonuc)
    except Exception as e:
        log.exception("Özet hatası")
        return duz_metin(f"Özet başarısız: {e}")


@app.route("/hava", methods=["GET"])
def hava():
    sehir = (request.args.get("sehir", "") or "").strip()
    if not sehir:
        return duz_metin("Hangi şehrin havasına bakayım? Örnek: /hava?sehir=istanbul")
    try:
        bilgi = hava_cek(sehir)
        if not bilgi:
            return duz_metin(f"'{sehir}' diye bir yer bulamadım, şehir adını kontrol eder misin?")
        return duz_metin(bilgi)
    except Exception as e:
        log.exception("Hava hatası")
        return duz_metin(f"Hava bilgisine ulaşamadım: {e}")


@app.route("/hesap", methods=["GET"])
def hesap():
    ifade = (request.args.get("ifade", "") or "").strip()
    if not ifade:
        return duz_metin("Ne hesaplayayım? Örnek: /hesap?ifade=12.5 çarpı 4")
    try:
        temiz, sonuc = guvenli_hesap(ifade)
        return duz_metin(f"{temiz} = {sonuc}")
    except Exception:
        return duz_metin("Bunu hesaplayamadım. Örnek: '12 çarpı 4', '(5 artı 3) bölü 2'")


@app.route("/seslendir", methods=["GET"])
def seslendir_rute():
    metin = (request.args.get("metin", "") or "").strip()[:500]
    if not metin:
        return duz_metin("Seslendirilecek metin boş.")
    try:
        veri = edge_ses_uret(metin)
        return Response(veri, mimetype="audio/mpeg")
    except Exception as e:
        log.exception("edge-tts hatası")
        return duz_metin(f"Ses üretilemedi: {e}")


@app.route("/temizle", methods=["GET"])
def temizle():
    session = oturum_id()
    sohbet_temizle(session)
    log.info("Oturum temizlendi: %s", session)
    return duz_metin("Sohbet hafızası temizlendi, yeni bir sayfa açtık.")


if __name__ == "__main__":
    log.info("Çip sunucusu v%s başlıyor; eksik env: %s", SURUM, eksik_env_listesi() or "yok")
    port = int(os.environ.get("PORT", 10000))
    app.run(host="0.0.0.0", port=port, threaded=True)
