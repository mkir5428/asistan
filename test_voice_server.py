import os
import unittest
from unittest.mock import Mock, patch

import voice_server


class GuvenliHesapTest(unittest.TestCase):
    def test_turkce_islem_kelimelerini_hesaplar(self):
        temiz, sonuc = voice_server.guvenli_hesap("12 çarpı 4")
        self.assertEqual(temiz, "12 * 4")
        self.assertEqual(sonuc, 48)

    def test_islem_onceligini_korur(self):
        _, sonuc = voice_server.guvenli_hesap("(5 artı 3) bölü 2")
        self.assertEqual(sonuc, 4)

    def test_buyuk_usleri_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("2**101")

    def test_uzun_ifadeyi_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("1" * 201)

    def test_karmasik_ifadeyi_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("2**(3**100)")


class KimlikDogrulamaTest(unittest.TestCase):
    def setUp(self):
        self.client = voice_server.app.test_client()

    def test_saglik_sayfasi_api_anahtarsiz_acik(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": ""}):
            response = self.client.get("/")
        self.assertEqual(response.status_code, 200)

    def test_anahtar_yapilandirilmadiysa_api_503_doner(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": ""}):
            response = self.client.get("/komut?text=merhaba")
        self.assertEqual(response.status_code, 503)

    def test_anahtar_olmadan_api_401_doner(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}):
            response = self.client.get("/komut?text=merhaba")
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")

    def test_gecerli_anahtarla_komut_calisir(self):
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.isle_komut", return_value=("Tamam.", 200, {})),
        ):
            response = self.client.get(
                "/komut?text=merhaba",
                headers={"Authorization": "Bearer test-key"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_data(as_text=True), "Tamam.")

    def test_komut_hata_yanitinda_saglayici_detaylarini_gizler_ve_loglar(self):
        hata_detayi = "provider API error code=provider-secret-123"
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_cevap", side_effect=RuntimeError(hata_detayi)),
            self.assertLogs("cip-sunucu", level="ERROR") as kayitlar,
        ):
            response = self.client.get(
                "/komut?text=merhaba",
                headers={"Authorization": "Bearer test-key"},
            )

        yanit = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.mimetype, "text/plain")
        self.assertEqual(
            yanit,
            "Cevap üretirken hata oldu. Lütfen daha sonra tekrar deneyin.",
        )
        self.assertNotIn(hata_detayi, yanit)
        self.assertIn(hata_detayi, "\n".join(kayitlar.output))

    def test_ceviri_form_postunu_destekler(self):
        metin = "Merhaba " * 499 + "Merhaba"
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_sistem_gorevi", return_value="Hello") as gorev,
        ):
            response = self.client.post(
                "/cevir",
                data={"text": metin, "dil": "ingilizce"},
                headers={"Authorization": "Bearer test-key"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_data(as_text=True), "Hello")
        self.assertTrue(gorev.call_args.args[1].endswith(metin))

    def test_istek_boyutu_sinirlidir(self):
        self.assertEqual(voice_server.app.config["MAX_CONTENT_LENGTH"], 25_000_000)


class GroqTest(unittest.TestCase):
    def test_transkripsiyon_bearer_anahtari_gonderir(self):
        yanit = Mock()
        yanit.json.return_value = {"text": "merhaba"}
        with (
            patch.dict(os.environ, {"GROQ_API_KEY": "groq-test"}),
            patch("voice_server.requests.post", return_value=yanit) as post,
        ):
            sonuc = voice_server.groq_transkript(b"ses", "ses.m4a")

        self.assertEqual(sonuc, "merhaba")
        self.assertEqual(
            post.call_args.kwargs["headers"]["Authorization"],
            "Bearer groq-test",
        )


if __name__ == "__main__":
    unittest.main()
