"""Security regression tests for allowlisted Kimi Control gateway."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).with_name("kimi_gateway.py")
spec = importlib.util.spec_from_file_location("kimi_gateway", SCRIPT)
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class GatewayRoutes(unittest.TestCase):
    def test_expected_android_routes(self):
        allowed = [
            ("GET", "/api/v1/meta"),
            ("GET", "/api/v1/sessions?page_size=40"),
            ("POST", "/api/v1/sessions"),
            ("GET", "/api/v1/sessions/session_1234/messages?page_size=80"),
            ("GET", "/api/v1/sessions/session_1234/snapshot"),
            ("GET", "/api/v1/sessions/session_1234/approvals?status=pending"),
            ("GET", "/api/v1/sessions/session_1234/questions?status=pending"),
            ("POST", "/api/v1/sessions/session_1234/questions/question_123"),
            ("GET", "/api/v1/sessions/session_1234/prompts"),
            ("POST", "/api/v1/sessions/session_1234/prompts/pr_123:abort"),
            ("POST", "/api/v1/sessions/session_1234/prompts"),
            ("POST", "/api/v1/sessions/session_1234/approvals/appr_5"),
            ("POST", "/api/v1/files"),
            ("GET", "/api/v1/sessions/session_1234/fs/reports/result.md:download"),
        ]
        for method, path in allowed:
            with self.subTest(path=path):
                self.assertTrue(gateway.allowed(method, path))

    def test_sensitive_routes_blocked(self):
        blocked = [
            ("GET", "/api/v1/fs:content?path=/etc/passwd"),
            ("GET", "/api/v1/providers"),
            ("GET", "/api/v1/sessions/session_1234/approvals"),
            ("GET", "/api/v1/sessions/session_1234/approvals?status=all"),
            ("GET", "/api/v1/sessions/session_1234/questions"),
            ("GET", "/api/v1/sessions/session_1234/questions?status=all"),
            ("GET", "//api/v1/meta"),
            ("POST", "/api/v1/shutdown"),
            ("POST", "/api/v1/fs:mkdir"),
            ("GET", "/api/v1/sessions/session_1234/fs/../etc/passwd:download"),
            ("GET", "/api/v1/sessions/session_1234/fs/%2e%2e%2fetc%2fpasswd:download"),
            ("GET", "/api/v1/sessions/session_1234/fs/%2Fetc/passwd:download"),
            ("GET", "/api/v1/sessions/session_1234/fs/a//b:download"),
            ("GET", "/api/v1/sessions/abc/../../../fs:content"),
            ("POST", "/api/v1/files/secret"),
            ("DELETE", "/api/v1/sessions/session_1234"),
        ]
        for method, path in blocked:
            with self.subTest(path=path):
                self.assertFalse(gateway.allowed(method, path))


if __name__ == "__main__":
    unittest.main()
