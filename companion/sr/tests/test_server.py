import http.client
import io
import json
import threading
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from app.server import MAX_BODY_BYTES, MIN_PASS_LENGTH, Server, read_password
from app.upscale import MODEL, Upscaler, pick_gpu

MODELS = Path(__file__).resolve().parent.parent / "models"
PASSWORD = "test-pass-0123"


def png(width, height):
    buf = io.BytesIO()
    Image.new("RGB", (width, height), (200, 120, 40)).save(buf, "PNG")
    return buf.getvalue()


class GatedUpscaler:
    """Holds the device until released, so a test can fill the queue on purpose."""

    device = "gated"

    def __init__(self):
        self.entered = threading.Event()
        self.release = threading.Event()

    def upscale(self, rgb, scale):
        self.entered.set()
        self.release.wait(timeout=30)
        h, w, _ = rgb.shape
        return np.zeros((h * scale, w * scale, 3), np.uint8)


class ReadPasswordTest(unittest.TestCase):
    def test_the_password_needs_the_minimum_length(self):
        shortest = "p" * MIN_PASS_LENGTH

        self.assertEqual(shortest, read_password({"HOMELAB_PASS": shortest}))
        for environ in ({}, {"HOMELAB_PASS": shortest[1:]}):
            with self.subTest(environ=environ), self.assertRaises(SystemExit):
                read_password(environ)


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upscaler = Upscaler(MODELS, pick_gpu())

    def start(self, upscaler=None, **limits):
        server = Server(("127.0.0.1", 0), upscaler or self.upscaler, PASSWORD, **limits)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server.server_address[1]

    @staticmethod
    def call(port, method, path, body=None, password=PASSWORD):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        headers = {"Authorization": f"Bearer {password}"} if password else {}
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        return response.status, response.getheader("Content-Type"), response.read()

    @staticmethod
    def call_with_headers_only(port, headers):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        conn.putrequest("POST", "/v1/upscale?scale=2")
        conn.putheader("Authorization", f"Bearer {PASSWORD}")
        for name, value in headers.items():
            conn.putheader(name, value)
        conn.endheaders()
        return conn.getresponse().status

    def test_info_names_the_model_scales_and_device(self):
        port = self.start()

        status, content_type, body = self.call(port, "GET", "/v1/info")

        self.assertEqual(200, status)
        self.assertEqual("application/json", content_type)
        self.assertEqual({"model": MODEL, "scales": [2, 3, 4], "device": self.upscaler.device}, json.loads(body))

    def test_requests_without_the_right_password_are_refused(self):
        port = self.start()

        for password in (None, "wrong-pass-0123"):
            with self.subTest(password=password):
                self.assertEqual(401, self.call(port, "GET", "/v1/info", password=password)[0])
                self.assertEqual(401, self.call(port, "POST", "/v1/upscale?scale=2", png(4, 4), password=password)[0])

    def test_upscale_returns_a_jpeg_scaled_by_the_factor(self):
        port = self.start()

        status, content_type, body = self.call(port, "POST", "/v1/upscale?scale=3", png(20, 30))

        self.assertEqual(200, status)
        self.assertEqual("image/jpeg", content_type)
        self.assertEqual((60, 90), Image.open(io.BytesIO(body)).size)

    def test_scale_must_be_2_3_or_4(self):
        port = self.start()

        for query in ("", "?scale=1", "?scale=5", "?scale=x", "?scale=2&scale=3"):
            with self.subTest(query=query):
                self.assertEqual(400, self.call(port, "POST", f"/v1/upscale{query}", png(4, 4))[0])

    def test_a_body_that_is_not_an_image_is_refused(self):
        port = self.start()

        self.assertEqual(400, self.call(port, "POST", "/v1/upscale?scale=2", b"not an image")[0])

    def test_size_limits_count_the_scale(self):
        port = self.start(max_input_pixels=600, max_output_pixels=2400)

        cases = [  # (width, height, scale, expected status)
            (20, 30, 2, 200),  # 600 in, 2400 out: both at the limit
            (20, 31, 2, 413),  # 620 in
            (20, 30, 3, 413),  # 5400 out
        ]
        for width, height, scale, expected in cases:
            with self.subTest(size=(width, height), scale=scale):
                status = self.call(port, "POST", f"/v1/upscale?scale={scale}", png(width, height))[0]
                self.assertEqual(expected, status)

    def test_body_needs_a_length_within_the_limit(self):
        port = self.start()

        self.assertEqual(411, self.call_with_headers_only(port, {}))
        self.assertEqual(413, self.call_with_headers_only(port, {"Content-Length": str(MAX_BODY_BYTES + 1)}))

    def test_busy_when_the_queue_is_full(self):
        gated = GatedUpscaler()
        port = self.start(upscaler=gated, max_queued=1)
        first = {}
        holder = threading.Thread(
            target=lambda: first.update(status=self.call(port, "POST", "/v1/upscale?scale=2", png(4, 4))[0]))
        holder.start()
        self.assertTrue(gated.entered.wait(timeout=30))

        second = self.call(port, "POST", "/v1/upscale?scale=2", png(4, 4))[0]
        gated.release.set()
        holder.join(timeout=30)

        self.assertEqual(503, second)
        self.assertEqual(200, first["status"])


if __name__ == "__main__":
    unittest.main()
