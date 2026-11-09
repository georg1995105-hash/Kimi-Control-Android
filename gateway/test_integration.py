"""In-process gateway integration tests; do not require Kimi or network access."""
import importlib.util
import json
import pathlib
import threading
import urllib.error
import urllib.request
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = pathlib.Path(__file__).with_name("kimi_gateway.py")
spec = importlib.util.spec_from_file_location("kimi_gateway_integration", SCRIPT)
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class FakeKimi(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer local-kimi-secret":
            self.send_error(401)
            return
        payload = json.dumps({"code": 0, "msg": "success", "data": {"server_id": "test"}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


class GatewayIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), FakeKimi)
        cls.gateway = ThreadingHTTPServer(("127.0.0.1", 0), gateway.Handler)
        gateway.UPSTREAM = "http://127.0.0.1:" + str(cls.upstream.server_port)
        gateway.APP_TOKEN = "mobile-secret-with-more-than-32-characters"
        gateway.KIMI_TOKEN = "local-kimi-secret"
        cls.t1 = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.t2 = threading.Thread(target=cls.gateway.serve_forever, daemon=True)
        cls.t1.start()
        cls.t2.start()
        cls.url = "http://127.0.0.1:" + str(cls.gateway.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.gateway.shutdown()
        cls.upstream.shutdown()
        cls.gateway.server_close()
        cls.upstream.server_close()

    def call(self, path, bearer):
        req = urllib.request.Request(self.url + path, headers={"Authorization": "Bearer " + bearer})
        return urllib.request.urlopen(req, timeout=5)

    def test_valid_token_reaches_kimi_with_distinct_secret(self):
        with self.call("/api/v1/meta", gateway.APP_TOKEN) as resp:
            self.assertEqual(json.load(resp)["data"]["server_id"], "test")
            self.assertEqual(resp.headers["Cache-Control"], "no-store")

    def test_bad_token_is_denied(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.call("/api/v1/meta", "invalid")
        self.assertEqual(cm.exception.code, 401)

    def test_sensitive_route_denied_even_with_token(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.call("/api/v1/fs:content?path=/etc/passwd", gateway.APP_TOKEN)
        self.assertEqual(cm.exception.code, 403)


if __name__ == "__main__":
    unittest.main()
