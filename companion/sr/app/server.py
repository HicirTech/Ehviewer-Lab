"""HTTP front of the upscaler. Inference runs on one worker thread; nothing is written to disk."""
import hmac
import io
import json
import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import numpy as np
from PIL import Image, UnidentifiedImageError

from app.upscale import MODEL, SCALES, Upscaler, pick_gpu

PORT = 8790
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
MIN_PASS_LENGTH = 8
MAX_BODY_BYTES = 32 * 1024 * 1024
MAX_INPUT_PIXELS = 25_000_000
# Long strips would explode: an 800 x 12000 page at x4 is 154 million pixels.
MAX_OUTPUT_PIXELS = 40_000_000
# Requests decoding, waiting for or holding the device; more get 503 so memory stays bounded.
MAX_QUEUED = 4
JPEG_QUALITY = 95

log = logging.getLogger("sr")


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, upscaler, password, max_input_pixels=MAX_INPUT_PIXELS,
                 max_output_pixels=MAX_OUTPUT_PIXELS, max_queued=MAX_QUEUED):
        super().__init__(address, Handler)
        self.upscaler = upscaler
        self.expected_auth = f"Bearer {password}".encode()
        self.max_input_pixels = max_input_pixels
        self.max_output_pixels = max_output_pixels
        self.slots = threading.BoundedSemaphore(max_queued)
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="upscale")

    def server_close(self):
        super().server_close()
        self.worker.shutdown(cancel_futures=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "ehviewer-sr"

    def do_GET(self):
        if not self._authorised():
            return
        if urlsplit(self.path).path != "/v1/info":
            return self._fail(HTTPStatus.NOT_FOUND, "unknown path")
        info = {"model": MODEL, "scales": list(SCALES), "device": self.server.upscaler.device}
        self._send(HTTPStatus.OK, "application/json", json.dumps(info).encode())

    def do_POST(self):
        if not self._authorised():
            return
        url = urlsplit(self.path)
        if url.path != "/v1/upscale":
            return self._fail(HTTPStatus.NOT_FOUND, "unknown path")
        scale = _scale(url.query)
        if scale is None:
            return self._fail(HTTPStatus.BAD_REQUEST, "scale must be one of 2, 3, 4")
        body = self._read_body()
        if body is None:
            return
        try:
            image = Image.open(io.BytesIO(body))
        except Image.DecompressionBombError:
            return self._fail(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "image too large")
        except (UnidentifiedImageError, OSError):
            return self._fail(HTTPStatus.BAD_REQUEST, "not an image")
        w, h = image.size
        if w * h > self.server.max_input_pixels or w * h * scale * scale > self.server.max_output_pixels:
            return self._fail(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "image too large for this scale")
        if not self.server.slots.acquire(blocking=False):
            return self._fail(HTTPStatus.SERVICE_UNAVAILABLE, "busy", {"Retry-After": "1"})
        try:
            self._upscale(image, scale)
        finally:
            self.server.slots.release()

    def _upscale(self, image, scale):
        try:
            rgb = np.asarray(image.convert("RGB"))
        except OSError:
            return self._fail(HTTPStatus.BAD_REQUEST, "image cannot be decoded")
        started = time.monotonic()
        try:
            result = self.server.worker.submit(self.server.upscaler.upscale, rgb, scale).result()
        except Exception:
            log.exception("x%d upscale of %dx%d failed", scale, image.width, image.height)
            return self._fail(HTTPStatus.INTERNAL_SERVER_ERROR, "upscale failed")
        inferred = time.monotonic()
        jpeg = io.BytesIO()
        Image.fromarray(result).save(jpeg, "JPEG", quality=JPEG_QUALITY)
        log.info("x%d %dx%d in %d ms, jpeg %d ms, %d bytes", scale, image.width, image.height,
                 (inferred - started) * 1000, (time.monotonic() - inferred) * 1000, jpeg.tell())
        self._send(HTTPStatus.OK, "image/jpeg", jpeg.getvalue())

    def _authorised(self):
        given = (self.headers.get("Authorization") or "").encode()
        if hmac.compare_digest(given, self.server.expected_auth):
            return True
        self._fail(HTTPStatus.UNAUTHORIZED, "missing or wrong password", {"WWW-Authenticate": "Bearer"})
        return False

    def _read_body(self):
        length = self.headers.get("Content-Length", "")
        if not length.isdigit():
            self._fail(HTTPStatus.LENGTH_REQUIRED, "Content-Length required")
            return None
        if int(length) > MAX_BODY_BYTES:
            self._fail(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "body too large")
            return None
        return self.rfile.read(int(length))

    def _fail(self, status, reason, headers=None):
        self._send(status, "application/json", json.dumps({"error": reason}).encode(), headers)

    def _send(self, status, content_type, payload, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        log.info("%s %s", self.address_string(), format % args)


def _scale(query):
    values = parse_qs(query).get("scale", [])
    return {str(s): s for s in SCALES}.get(values[0]) if len(values) == 1 else None


def read_password(environ):
    password = environ.get("HOMELAB_PASS", "")
    if len(password) < MIN_PASS_LENGTH:
        raise SystemExit(f"HOMELAB_PASS must be at least {MIN_PASS_LENGTH} characters")
    return password


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    password = read_password(os.environ)
    upscaler = Upscaler(MODELS_DIR, pick_gpu())
    server = Server(("", PORT), upscaler, password)
    log.info("%s on %s, listening on %d", MODEL, upscaler.device, PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
