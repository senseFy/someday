#!/usr/bin/env python3
"""Expose only System V3 recovery reads while an isolated restore is verified."""

import argparse
import http.client
import json
import pathlib
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


READ_POST = re.compile(
    r"^/sync/v3/workspaces/[^/]+/entities/(checkpoint/fetch|pull|frontiers)$"
)
READ_GET = re.compile(
    r"^(/health|/sync/v3/(capabilities|workspaces/[^/]+/entities/(epoch|status)|"
    r"workspaces/[^/]+/media/[^/]+))$"
)
MEDIA = re.compile(r"^/sync/v3/workspaces/[^/]+/media/[^/]+$")
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
}


class RecoveryProxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    backend_host: str
    backend_port: int
    audit_file: pathlib.Path
    audit_lock = threading.Lock()

    def do_GET(self) -> None:  # noqa: N802
        self._read_or_reject(READ_GET.fullmatch(self.path.split("?", 1)[0]) is not None)

    def do_HEAD(self) -> None:  # noqa: N802
        self._read_or_reject(MEDIA.fullmatch(self.path.split("?", 1)[0]) is not None)

    def do_POST(self) -> None:  # noqa: N802
        self._read_or_reject(READ_POST.fullmatch(self.path.split("?", 1)[0]) is not None)

    def do_PUT(self) -> None:  # noqa: N802
        self._reject_write()

    def do_PATCH(self) -> None:  # noqa: N802
        self._reject_write()

    def do_DELETE(self) -> None:  # noqa: N802
        self._reject_write()

    def _read_or_reject(self, allowed: bool) -> None:
        if not allowed:
            self._reject_write()
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else None
        headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower() not in HOP_BY_HOP and name.lower() != "host"
        }
        headers["Host"] = f"{self.backend_host}:{self.backend_port}"
        connection = http.client.HTTPConnection(self.backend_host, self.backend_port, timeout=30)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in HOP_BY_HOP and name.lower() != "content-length":
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(payload)
        finally:
            connection.close()

    def _reject_write(self) -> None:
        path = self.path.split("?", 1)[0]
        with self.audit_lock:
            with self.audit_file.open("a", encoding="utf-8") as audit:
                audit.write(f"{self.command}\t{path}\n")
                audit.flush()
        payload = json.dumps({"error": "recovery_read_only"}).encode()
        self.send_response(503)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def log_message(self, message: str, *args: object) -> None:
        print(f"[recovery-read-only] {message % args}", flush=True)


class RecoveryHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--backend-port", type=int, required=True)
    parser.add_argument("--audit-file", type=pathlib.Path, required=True)
    args = parser.parse_args()
    RecoveryProxy.backend_host = "127.0.0.1"
    RecoveryProxy.backend_port = args.backend_port
    args.audit_file.parent.mkdir(parents=True, exist_ok=True)
    args.audit_file.write_text("", encoding="utf-8")
    RecoveryProxy.audit_file = args.audit_file
    RecoveryHTTPServer(("127.0.0.1", args.listen_port), RecoveryProxy).serve_forever()


if __name__ == "__main__":
    main()
