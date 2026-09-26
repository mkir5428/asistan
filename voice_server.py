import os
import requests
from flask import Flask, request
from tuya_connector import TuyaOpenAPI
import anthropic

ANTHROPIC_API_KEY = os.environ["ANTHROPIC_API_KEY"]
GROQ_API_KEY = os.environ["GROQ_API_KEY"]
TUYA_CLIENT_ID = os.environ["TUYA_CLIENT_ID"]
TUYA_CLIENT_SECRET = os.environ["TUYA_CLIENT_SECRET"]
TUYA_DEVICE_ID = os.environ["TUYA_DEVICE_ID"]
TUYA_ENDPOINT = os.environ["TUYA_ENDPOINT"]

claude = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)

tuya = TuyaOpenAPI(TUYA_ENDPOINT, TUYA_CLIENT_ID, TUYA_CLIENT_SECRET)
tuya.connect()

app = Flask(__name__)


def duz_metin(mesaj):
    return mesaj, 200, {"Content-Type": "text/plain; charset=utf-8"}


def groq_transkript(ses_bytes, dosya_adi):
    resp = requests.post(
        "https://api.groq.com/openai/v1/audio/transcriptions",
        headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
        files={"file": (dosya_adi, ses_bytes)},
        data={"model": "whisper-large-v3", "language": "tr"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["text"]


def claude_cevap(metin):
    yanit = claude.messages.create(
        model="claude-sonnet-5",
        max_tokens=1024,
        messages=[{"role": "user", "content": metin}],
    )
    for blok in yanit.content:
        if blok.type == "text":
            return blok.text
    return "Bir cevap üretemedim."


def isle_komut(metin):
    if not metin:
        return duz_metin("Bir şey duymadım.")

    kucuk_metin = metin.lower()

    if "ampul" in kucuk_metin and ("aç" in kucuk_metin or "ac" in kucuk_metin):
        tuya.post(f"/v1.0/iot-03/devices/{TUYA_DEVICE_ID}/commands", {
            "commands": [{"code": "switch_led", "value": True}]
        })
        return duz_metin("Ampulü açtım.")

    if "ampul" in kucuk_metin and "kapat" in kucuk_metin:
        tuya.post(f"/v1.0/iot-03/devices/{TUYA_DEVICE_ID}/commands", {
            "commands": [{"code": "switch_led", "value": False}]
        })
        return duz_metin("Ampulü kapattım.")

    try:
        return duz_metin(claude_cevap(metin))
    except Exception as e:
        return duz_metin(f"Cevap üretirken hata oldu: {e}")


@app.route("/komut", methods=["GET"])
def komut():
    # Eski metin tabanlı giriş (test/geri uyumluluk için duruyor)
    metin = request.args.get("text", "")
    return isle_komut(metin)


@app.route("/ses", methods=["POST"])
def ses():
    # Yeni: ham ses dosyası, Groq ile yazıya çevrilip işleniyor
    if "file" not in request.files:
        return duz_metin("Ses dosyası gelmedi.")

    audio_file = request.files["file"]
    try:
        metin = groq_transkript(audio_file.read(), audio_file.filename or "audio.wav")
    except Exception as e:
        return duz_metin(f"Sesi anlayamadım: {e}")

    return isle_komut(metin)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 10000))
    app.run(host="0.0.0.0", port=port)
