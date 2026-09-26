"""
Çip sesli asistan sunucusu (Render) — v3.0.

Uç noktalar:
  GET  /                       -> sağlık/teşhis sayfası (hangi env var/yok)
  GET  /komut?text=...&session=...&gecmis=[...] -> sohbetli metin komutu
  POST /ses (multipart: file, session)         -> ses dosyası (Groq) -> komut
  GET  /cevir?text=...&dil=...  -> çeviri (Claude)
  GET  /ozet?text=...&mod=...   -> özetleme (Claude)
  GET  /temizle?session=...     -> oturum hafızasını sıfırla

Tasarım ilkesi: AÇILIŞTA HİÇBİR ŞEY ÇÖKMEZ. API anahtarları ve Tuya
bağlantısı tembel (lazy) yüklenir; eksikse istek anında anlaşılır
Türkçe mesaj döner, servis ayakta kalır.
"""

import json
import logging
import os
import re
import sys
import threading

import requests
from flask import Flask, request
from tuya_connector import TuyaOpenAPI
import anthropic

SURUM = "3.0"

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
    "Sen Çip adlı Türkçe sesli asistansın. Cevapların sesli okunacağı için "
    "kısa, net, günlük konuşma diliyle yaz (1-4 cümle). Emoji kullanma. "
    "Markdown kullanma. Bilmediğin şeyi uydurma, kısaca söyle. "
    "Kullanıcıya 'kanka' gibi samimi ama saygılı hitap edebilirsin."
)

DIL_ADLARI = {
    "ingilizce": "İngilizce", "english": "İngilizce",
    "turkce": "Türkçe", "türkçe": "Türkçe",
    "almanca": "Almanca", "deutsch": "Almanca",
    "fransizca": "Fransızca", "ispanyolca": "İspanyolca",
    "italyanca": "İtalyanca", "rusca": "Rusça",
    "arapca": "Arapça", "japonca": "Japonca",
    "korece": "Korece", "cince": "Çince",
}


def claude_cevap(metin: str, gecmis: list = None) -> str:
    mesajlar = list(gecmis or [])
    mesajlar.append({"role": "user", "content": metin})
    yanit = claude_istemci().messages.create(
        model=os.environ.get("CLAUDE_MODEL", "claude-sonnet-5"),
        max_tokens=512,
        system=KISILIK,
        messages=mesajlar,
    )
    for blok in yanit.content:
        if blok.type == "text":
            return blok.text
    return "Bir cevap üretemedim."


def claude_sistem_gorevi(gorev: str, metin: str) -> str:
    """Çeviri/özet gibi tek seferlik görevler: kişilik yok, sadece görev."""
    yanit = claude_istemci().messages.create(
        model=os.environ.get("CLAUDE_MODEL", "claude-sonnet-5"),
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


# ==================== OTURUM HAFIZASI ====================

MAX_SOHBET_TURU = 20  # tur = soru + cevap
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
        # Limiti aşarsa en eski turu at (liste çift elemanlı kalır)
        while len(gecmis) > MAX_SOHBET_TURU * 2:
            del gecmis[0:2]


def sohbet_temizle(session: str):
    with _sohbet_kilit:
        _sohbetler.pop(session, None)


def gecmisten_mesajlar(session: str) -> list:
    """İstemcinin gönderdiği gecmis JSON'u geçerliyse onu kullan, yoksa sunucu hafızası."""
    ham = request.args.get("gecmis", "")
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
    """Türkçe harfleri ASCII muadiline çevirir (kelime eşleştirme için)."""
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
    """'sohbeti unut', 'hafızayı temizle' gibi istekleri yakalar."""
    a = tr_den_ascii(metin)
    return bool(TEMIZLE_KELIMELER.search(a) and HAFIZA_KELIMELER.search(a))


def isle_komut(metin: str, gecmis: list = None):
    metin = (metin or "").strip()
    if not metin:
        return duz_metin("Bir şey duymadım.")

    ascii_metin = tr_den_ascii(metin)

    # Akıllı ampul: "ışığı aç", "ampulü kapat", "lamba yak" gibi.
    if LAMBA_KELIMELER.search(ascii_metin):
        if ACMA_KELIMELER.search(ascii_metin):
            log.info("Ampul komutu: AÇ")
            return duz_metin(tuya_ampul(True))
        if KAPATMA_KELIMELER.search(ascii_metin):
            log.info("Ampul komutu: KAPAT")
            return duz_metin(tuya_ampul(False))

    try:
        return duz_metin(claude_cevap(metin, gecmis))
    except Exception as e:
        log.exception("Claude hatası")
        return duz_metin(f"Cevap üretirken hata oldu: {e}")


# ==================== UÇ NOKTALAR ====================


def duz_metin(mesaj: str):
    return mesaj, 200, {"Content-Type": "text/plain; charset=utf-8"}


def oturum_id() -> str:
    return (request.args.get("session", "") or "varsayilan").strip()[:80]


@app.route("/")
def saglik():
    durum = "\n".join(
        f"{'✓' if env_aldi(ad) else '✗ EKSİK'}  {ad}" for ad in GEREKLILER
    )
    aktif = len(_sohbetler)
    return duz_metin(
        f"Çip sunucusu v{SURUM} ayakta.\n"
        f"Ortam değişkenleri (Render > Environment):\n{durum}\n"
        f"Aktif oturum sayısı: {aktif}\n\n"
        "Test: /komut?text=merhaba  veya  POST /ses (file alanı)\n"
        "AI: /cevir?text=...&dil=ingilizce   /ozet?text=...&mod=kisa   /temizle?session=..."
    )


@app.route("/komut", methods=["GET"])
def komut():
    metin = request.args.get("text", "")
    log.info("Komut: %r", metin)
    if bellek_temizleme_istegi(metin):
        sohbet_temizle(oturum_id())
        return duz_metin("Sohbet hafızasını temizledim, yeni bir sayfa açtık.")
    cevap = isle_komut(metin, gecmisten_mesajlar(oturum_id()))[0]
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
        # Ham gövde de kabul edilir (farklı istemciler için)
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
        return duz_metin(f"Sesi anlayamadım: {e}")

    log.info("Transkript: %r", metin)
    if bellek_temizleme_istegi(metin):
        sohbet_temizle(oturum_id())
        return duz_metin("Sohbet hafızasını temizledim, yeni bir sayfa açtık.")
    cevap = isle_komut(metin, gecmisten_mesajlar(oturum_id()))[0]
    if not cevap.startswith("Cevap üretirken hata"):
        sohbet_ekle(oturum_id(), metin, cevap)
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
    dil_adi = DIL_ADLARI.get(tr_den_ascii(dil))
    if not dil_adi:
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
