import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import voice_server


class WorkspacePlanApiTest(unittest.TestCase):
    def setUp(self):
        self.client = voice_server.app.test_client()
        self.headers = {
            "Authorization": "Bearer test-key",
            "Content-Type": "application/json",
        }

    def request(self, body, *, https=True, headers=None):
        return self.client.post(
            "/workspace/plan",
            data=json.dumps(body, ensure_ascii=False),
            headers=headers or self.headers,
            base_url="https://cip.test" if https else "http://cip.test",
        )

    @staticmethod
    def tool_response(summary="Updated the file.", changes=None):
        return SimpleNamespace(
            stop_reason="tool_use",
            content=[
                SimpleNamespace(
                    type="tool_use",
                    name="propose_file_changes",
                    input={
                        "summary": summary,
                        "changes": changes
                        or [
                            {
                                "relative_path": "src/main.py",
                                "replacement_content": "print('updated')\n",
                            }
                        ],
                    },
                )
            ]
        )

    def test_requires_existing_api_key_and_https(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}):
            unauthorized = self.request(
                {"instruction": "Update this", "files": [{"path": "src/main.py", "content": ""}]},
                headers={"Content-Type": "application/json"},
            )
            insecure = self.request(
                {"instruction": "Update this", "files": [{"path": "src/main.py", "content": ""}]},
                https=False,
            )
        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(insecure.status_code, 426)
        self.assertEqual(insecure.get_json(), {"error": "https_required"})

    def test_returns_replacement_for_the_selected_file(self):
        body = {
            "instruction": "Add a greeting.",
            "files": [{"path": "src/main.py", "content": "print('hello')\n"}],
        }
        response_model = self.tool_response()
        messages = SimpleNamespace(create=Mock(return_value=response_model))
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)),
        ):
            response = self.request(body)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.get_json(),
            {
                "summary": "Updated the file.",
                "changes": [
                    {
                        "relative_path": "src/main.py",
                        "replacement_content": "print('updated')\n",
                    }
                ],
            },
        )
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        call = messages.create.call_args.kwargs
        self.assertEqual(call["tool_choice"], {"type": "tool", "name": "propose_file_changes"})
        self.assertIn("untrusted data", call["system"])

    def test_rejects_traversal_and_duplicate_input_paths_before_provider_call(self):
        messages = SimpleNamespace(create=Mock())
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)),
        ):
            traversal = self.request({
                "instruction": "Edit",
                "files": [{"path": "../secret.txt", "content": "x"}],
            })
            duplicate = self.request({
                "instruction": "Edit",
                "files": [
                    {"path": "src/main.py", "content": "x"},
                    {"path": "src/main.py", "content": "y"},
                ],
            })

        self.assertEqual(traversal.status_code, 400)
        self.assertEqual(duplicate.status_code, 400)
        messages.create.assert_not_called()

    def test_rejects_oversized_input(self):
        body = {
            "instruction": "Edit",
            "files": [{
                "path": "src/main.py",
                "content": "x" * (voice_server.WORKSPACE_MAX_FILE_BYTES + 1),
            }],
        }
        with patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}):
            response = self.request(body)
        self.assertEqual(response.status_code, 413)
        self.assertEqual(response.get_json(), {"error": "file_too_large"})

    def test_rejects_malformed_or_unmatched_model_output(self):
        body = {
            "instruction": "Edit",
            "files": [{"path": "src/main.py", "content": "print('hello')"}],
        }
        wrong_path = self.tool_response(changes=[{
            "relative_path": "src/other.py",
            "replacement_content": "print('updated')",
        }])
        messages = SimpleNamespace(create=Mock(return_value=wrong_path))
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)),
        ):
            response = self.request(body)

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.get_json(), {"error": "invalid_model_response"})

    def test_provider_errors_are_reported_without_success_shaped_fallback(self):
        messages = SimpleNamespace(create=Mock(side_effect=RuntimeError("provider unavailable")))
        body = {
            "instruction": "Edit",
            "files": [{"path": "src/main.py", "content": "print('hello')"}],
        }
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)),
        ):
            response = self.request(body)

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.get_json(), {"error": "provider_error"})


if __name__ == "__main__":
    unittest.main()
