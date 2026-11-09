#!/usr/bin/env python3
"""Narrow reverse proxy for Kimi Code's experimental API.

Security properties:
- binds to loopback ONLY (TLS is provided by an existing trusted HTTPS ingress);
- separate randomly generated mobile access token;
- only permits routes needed by Android client;
- rejects unsafe download paths, redirects and unbounded uploads;
- does not forward client auth credentials to third parties.
Never expose this HTTP listener directly to the internet.
"""
from __future__ import annotations

import hmac
import http.server
import os
import re
import urllib.error
import urllib.parse
import urllib.request

BIND = "127.0.0.1"
PORT = int(os.environ.get("KIMI_GATEWAY_PORT", "8765"))
UPSTREAM = "http://127.0.0.1:" + str(int(os.environ.get("KIMI_LOCAL_PORT", "58627")))
APP_TOKEN = os.environ.get("KIMI_MOBILE_TOKEN", "")
KIMI_TOKEN = os.environ.get("KIMI_SERVER_TOKEN", "")
MAX_REQUEST = 32 * 1024 * 1024
MAX_RESPONSE = 100 * 1024 * 1024

SESSION = r"[A-Za-z0-9_-]{4,160}"
APPROVAL = r"[A-Za-z0-9_-]{1,160}"
SESSION_PREFIX = rf"/api/v1/sessions/{SESSION}"
ALLOWED = {
    ("GET", "/api/v1/meta"),
    ("GET", "/api/v1/sessions"),
    ("POST", "/api/v1/sessions"),
    ("POST", "/api/v1/files"),
}


def allowed(method: str, path: str) -> bool:
    parsed = urllib.parse.urlsplit(path)
    raw = parsed.path
    if parsed.fragment or "//" in raw or "\\\\" in raw:
        return False
    if method == "GET" and (raw.endswith("/approvals") or raw.endswith("/questions")) and parsed.query != "status=pending":
        return False
    if (method, raw) in ALLOWED:
        return True
    if method == "GET" and re.fullmatch(SESSION_PREFIX + r"/(messages|snapshot|prompts|approvals|questions)", raw):
        return True
    if method == "POST" and re.fullmatch(SESSION_PREFIX + r"/prompts", raw):
        return True
    if method == "POST" and re.fullmatch(SESSION_PREFIX + rf"/prompts/{APPROVAL}:abort", raw):
        return True
    if method == "POST" and re.fullmatch(SESSION_PREFIX + rf"/questions/{APPROVAL}", raw):
        return True
    if method == "POST" and re.fullmatch(SESSION_PREFIX + rf"/approvals/{APPROVAL}", raw):
        return True
    if method == "GET" and re.fullmatch(SESSION_PREFIX + r"/fs/.+:download", raw):
        tail = raw.split("/fs/", 1)[1][:-len(":download")]
        # Reject percent-encoded slash, dot-dot, or noncanonical paths
        try:
            decoded = urllib.parse.unquote(tail, errors="strict")
        except UnicodeDecodeError:
            return False
        segments = decoded.split("/")
        return bool(decoded) and not decoded.startswith("/") and all(
            segment not in ("", ".", "..") and "\x00" not in segment for segment in segments
        )
    return False


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


LOCAL_OPENER = urllib.request.build_opener(NoRedirect)


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "KimiControlGateway/1.0"

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_OPTIONS(self):
        self.send_error(405, "Not available")

    def dispatch(self, method):
        submitted = self.headers.get("Authorization", "")
        if not hmac.compare_digest(submitted, "Bearer " + APP_TOKEN):
            self.send_error(401, "Unauthorized")
            return
        if not allowed(method, self.path):
            self.send_error(403, "Endpoint not allowed")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "Invalid length")
            return
        if length < 0 or length > MAX_REQUEST:
            self.send_error(413, "Payload too large")
            return
        if method == "POST" and length == 0:
            self.send_error(400, "Missing content length")
            return
        body = self.rfile.read(length) if length else None
        headers = {
            "Authorization": "Bearer " + KIMI_TOKEN,
            "Accept": self.headers.get("Accept", "application/json"),
        }
        if method == "POST":
            headers["Content-Type"] = self.headers.get("Content-Type", "application/json")
        request = urllib.request.Request(UPSTREAM + self.path, data=body, headers=headers, method=method)
        try:
            with LOCAL_OPENER.open(request, timeout=120) as response:
                content = response.read(MAX_RESPONSE + 1)
                if len(content) > MAX_RESPONSE:
                    self.send_error(413, "Response too large")
                    return
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/octet-stream"))
                self.send_header("Content-Length", str(len(content)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                self.wfile.write(content)
        except urllib.error.HTTPError as exc:
            if 300 <= exc.code < 400:
                self.send_error(502, "Unexpected redirect from Kimi backend")
                return
            self.send_response(exc.code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(exc.read(8192))
        except (OSError, urllib.error.URLError):
            self.send_error(502, "Kimi Code backend unavailable")

    def log_message(self, fmt, *args):
        # Do not log sensitive URLs, tokens, paths, or query strings.
        if os.environ.get("KIMI_GATEWAY_DEBUG") == "1":
            super().log_message("request completed")


if __name__ == "__main__":
    if len(APP_TOKEN) < 32 or len(KIMI_TOKEN) < 16:
        raise SystemExit("Set KIMI_MOBILE_TOKEN (>=32 chars) and KIMI_SERVER_TOKEN (>=16 chars)")
    print(f"Kimi gateway listening on {BIND}:{PORT} (private loopback only)")
    http.server.ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
