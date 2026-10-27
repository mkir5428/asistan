import base64
import hashlib
import unittest

import voice_server
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


class GuvenliAktarimTest(unittest.TestCase):
    def setUp(self):
        voice_server._transfer_pairings.clear()
        voice_server._transfer_rate_buckets.clear()
        self.client = voice_server.app.test_client()
        self.private_key = ec.generate_private_key(ec.SECP256R1())
        public_key_pem = self.private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("ascii")
        pairing = self.request("POST", "/transfer/pairings")
        self.assertEqual(pairing.status_code, 201)
        self.pairing = pairing.get_json()
        claim = self.request(
            "POST",
            "/transfer/pairings/claim",
            json={
                "pairing_code": self.pairing["pairing_code"],
                "public_key_pem": public_key_pem,
            },
        )
        self.assertEqual(claim.status_code, 201)
        self.device_token = claim.get_json()["device_token"]

    def tearDown(self):
        for pairing in voice_server._transfer_pairings.values():
            for action in pairing.actions.values():
                voice_server._transfer_wipe_action(action)
        voice_server._transfer_pairings.clear()
        voice_server._transfer_rate_buckets.clear()

    def request(self, method, path, **kwargs):
        return self.client.open(path, method=method, base_url="https://cip.test", **kwargs)

    @staticmethod
    def auth(token):
        return {"Authorization": f"Bearer {token}"}

    def create_action(self, direction, payload=b"transfer test"):
        response = self.request(
            "POST",
            "/transfer/actions",
            json={
                "pairing_id": self.pairing["pairing_id"],
                "direction": direction,
                "filename": "sample.txt",
                "size": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            },
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(response.status_code, 201)
        return response.get_json(), payload

    def create_phone_action(self, payload=b"phone-selected file"):
        response = self.request(
            "POST",
            "/transfer/actions/phone",
            json={
                "pairing_id": self.pairing["pairing_id"],
                "filename": "phone-file.txt",
                "size": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            },
            headers=self.auth(self.device_token),
        )
        return response, payload

    def approve(self, action):
        signature = self.private_key.sign(
            action["challenge"].encode("utf-8"),
            ec.ECDSA(hashes.SHA256()),
        )
        return self.request(
            "POST",
            f"/transfer/actions/{action['action_id']}/approve",
            json={
                "pairing_id": self.pairing["pairing_id"],
                "signature": base64.b64encode(signature).decode("ascii"),
            },
            headers=self.auth(self.device_token),
        )

    def transfer(self, direction, sender_token, receiver_token):
        action, payload = self.create_action(direction)
        self.assertEqual(self.approve(action).status_code, 204)
        upload = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(sender_token),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(upload.status_code, 201)
        download = self.request(
            "GET",
            f"/transfer/actions/{action['action_id']}/data",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(receiver_token),
        )
        self.assertEqual(download.status_code, 200)
        self.assertEqual(download.data, payload)
        stored = voice_server._transfer_pairings[self.pairing["pairing_id"]].actions[
            action["action_id"]
        ]
        self.assertEqual(stored.state, "completed")
        self.assertIsNone(stored.data)
        return action

    def test_https_is_required_and_existing_api_key_is_not_transfer_auth(self):
        insecure = self.client.post("/transfer/pairings")
        self.assertEqual(insecure.status_code, 426)
        response = self.request(
            "POST",
            "/transfer/actions",
            json={"pairing_id": self.pairing["pairing_id"]},
            headers={"Authorization": "Bearer shared-app-key"},
        )
        self.assertEqual(response.status_code, 401)

    def test_pairing_code_is_single_use_and_status_reports_paired(self):
        public_key_pem = self.private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("ascii")
        response = self.request(
            "POST",
            "/transfer/pairings/claim",
            json={
                "pairing_code": self.pairing["pairing_code"],
                "public_key_pem": public_key_pem,
            },
        )
        self.assertEqual(response.status_code, 409)
        status = self.request(
            "GET",
            f"/transfer/pairings/{self.pairing['pairing_id']}",
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(status.get_json()["state"], "paired")

    def test_biometric_signed_approval_allows_one_transfer_in_either_direction(self):
        action = self.transfer(
            "pc_to_phone",
            self.pairing["pc_token"],
            self.device_token,
        )
        replay = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=b"transfer test",
            content_type="application/octet-stream",
            headers={
                **self.auth(self.pairing["pc_token"]),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(replay.status_code, 409)
        self.transfer(
            "phone_to_pc",
            self.device_token,
            self.pairing["pc_token"],
        )

    def test_phone_creates_phone_to_pc_action_and_pc_downloads_only_after_approval(self):
        created, payload = self.create_phone_action()
        self.assertEqual(created.status_code, 201)
        action = created.get_json()
        self.assertEqual(action["direction"], "phone_to_pc")
        self.assertEqual(action["state"], "awaiting_approval")
        self.assertEqual(action["filename"], "phone-file.txt")
        self.assertEqual(action["size"], len(payload))
        self.assertEqual(action["sha256"], hashlib.sha256(payload).hexdigest())

        listing = self.request(
            "GET",
            "/transfer/actions",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.pairing["pc_token"]),
        )
        listed = next(
            item
            for item in listing.get_json()["actions"]
            if item["action_id"] == action["action_id"]
        )
        self.assertEqual(listed["challenge"], action["challenge"])
        early_download = self.request(
            "GET",
            f"/transfer/actions/{action['action_id']}/data",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(early_download.status_code, 409)

        self.assertEqual(self.approve(action).status_code, 204)
        upload = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(self.device_token),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(upload.status_code, 201)
        ready = self.request(
            "GET",
            "/transfer/actions",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.pairing["pc_token"]),
        )
        listed = next(
            item
            for item in ready.get_json()["actions"]
            if item["action_id"] == action["action_id"]
        )
        self.assertEqual(listed["state"], "ready")
        download = self.request(
            "GET",
            f"/transfer/actions/{action['action_id']}/data",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(download.status_code, 200)
        self.assertEqual(download.data, payload)

    def test_phone_action_route_is_device_only_and_for_phone_to_pc_scope(self):
        body = {
            "pairing_id": self.pairing["pairing_id"],
            "filename": "phone.txt",
            "size": 1,
            "sha256": hashlib.sha256(b"x").hexdigest(),
        }
        pc_attempt = self.request(
            "POST",
            "/transfer/actions/phone",
            json=body,
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(pc_attempt.status_code, 401)
        direction_attempt = self.request(
            "POST",
            "/transfer/actions/phone",
            json={**body, "direction": "pc_to_phone"},
            headers=self.auth(self.device_token),
        )
        self.assertEqual(direction_attempt.status_code, 400)
        other_route_attempt = self.request(
            "POST",
            "/transfer/actions",
            json={**body, "direction": "phone_to_pc"},
            headers=self.auth(self.device_token),
        )
        self.assertEqual(other_route_attempt.status_code, 401)

    def test_phone_action_uses_shared_active_action_limit(self):
        first, _payload = self.create_phone_action()
        self.assertEqual(first.status_code, 201)
        second = self.request(
            "POST",
            "/transfer/actions",
            json={
                "pairing_id": self.pairing["pairing_id"],
                "direction": "pc_to_phone",
                "filename": "pc.txt",
                "size": 1,
                "sha256": hashlib.sha256(b"x").hexdigest(),
            },
            headers=self.auth(self.pairing["pc_token"]),
        )
        self.assertEqual(second.status_code, 201)
        third, _payload = self.create_phone_action()
        self.assertEqual(third.status_code, 409)
        self.assertEqual(third.get_json()["error"], "too_many_active_actions")

    def test_invalid_signature_and_wrong_sender_are_rejected(self):
        action, payload = self.create_action("pc_to_phone")
        invalid = self.request(
            "POST",
            f"/transfer/actions/{action['action_id']}/approve",
            json={
                "pairing_id": self.pairing["pairing_id"],
                "signature": "bm90IGEgc2lnbmF0dXJl",
            },
            headers=self.auth(self.device_token),
        )
        self.assertEqual(invalid.status_code, 403)
        before_approval = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(self.pairing["pc_token"]),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(before_approval.status_code, 409)
        self.assertEqual(self.approve(action).status_code, 204)
        wrong_sender = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(self.device_token),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(wrong_sender.status_code, 403)

    def test_content_hash_mismatch_cancels_the_action_without_buffering(self):
        action, payload = self.create_action("pc_to_phone")
        self.assertEqual(self.approve(action).status_code, 204)
        mismatch = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=b"X" * len(payload),
            content_type="application/octet-stream",
            headers={
                **self.auth(self.pairing["pc_token"]),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(mismatch.status_code, 400)
        stored = voice_server._transfer_pairings[self.pairing["pairing_id"]].actions[
            action["action_id"]
        ]
        self.assertEqual(stored.state, "cancelled")
        self.assertIsNone(stored.data)

    def test_interrupted_download_keeps_data_until_a_complete_retry(self):
        action, payload = self.create_action("pc_to_phone")
        self.assertEqual(self.approve(action).status_code, 204)
        self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(self.pairing["pc_token"]),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        stored = voice_server._transfer_pairings[self.pairing["pairing_id"]].actions[
            action["action_id"]
        ]
        interrupted = self.request(
            "GET",
            f"/transfer/actions/{action['action_id']}/data",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.device_token),
        )
        self.assertEqual(next(iter(interrupted.response)), payload)
        interrupted.close()
        self.assertEqual(stored.state, "ready")
        self.assertIsNotNone(stored.data)

        completed = self.request(
            "GET",
            f"/transfer/actions/{action['action_id']}/data",
            query_string={"pairing_id": self.pairing["pairing_id"]},
            headers=self.auth(self.device_token),
        )
        self.assertEqual(completed.data, payload)
        self.assertEqual(stored.state, "completed")
        self.assertIsNone(stored.data)

    def test_invalid_size_and_path_are_rejected(self):
        for filename, size in (
            ("../secret.txt", 1),
            ("oversized.bin", voice_server.TRANSFER_MAX_BYTES + 1),
        ):
            response = self.request(
                "POST",
                "/transfer/actions",
                json={
                    "pairing_id": self.pairing["pairing_id"],
                    "direction": "pc_to_phone",
                    "filename": filename,
                    "size": size,
                    "sha256": "0" * 64,
                },
                headers=self.auth(self.pairing["pc_token"]),
            )
            self.assertEqual(response.status_code, 400)

    def test_pairing_creation_is_rate_limited(self):
        for _ in range(4):
            self.assertEqual(self.request("POST", "/transfer/pairings").status_code, 201)
        limited = self.request("POST", "/transfer/pairings")
        self.assertEqual(limited.status_code, 429)

    def test_pairing_revocation_removes_transfer_state(self):
        action, payload = self.create_action("pc_to_phone")
        self.assertEqual(self.approve(action).status_code, 204)
        uploaded = self.request(
            "PUT",
            f"/transfer/actions/{action['action_id']}/data",
            data=payload,
            content_type="application/octet-stream",
            headers={
                **self.auth(self.pairing["pc_token"]),
                "X-Cip-Pairing-Id": self.pairing["pairing_id"],
            },
        )
        self.assertEqual(uploaded.status_code, 201)
        stored = voice_server._transfer_pairings[self.pairing["pairing_id"]].actions[
            action["action_id"]
        ]
        self.assertIsNotNone(stored.data)
        response = self.request(
            "DELETE",
            f"/transfer/pairings/{self.pairing['pairing_id']}",
            headers=self.auth(self.device_token),
        )
        self.assertEqual(response.status_code, 204)
        self.assertNotIn(self.pairing["pairing_id"], voice_server._transfer_pairings)


if __name__ == "__main__":
    unittest.main()
