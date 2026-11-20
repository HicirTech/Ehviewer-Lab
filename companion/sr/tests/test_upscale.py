import unittest
from pathlib import Path

import numpy as np

from app.upscale import SCALES, SOFTWARE_DEVICE, Upscaler, first_hardware_device, pick_gpu

MODELS = Path(__file__).resolve().parent.parent / "models"


def page(height, width):
    """Synthetic page: noise over gradients, so every tile holds different content."""
    ys, xs = np.mgrid[0:height, 0:width]
    base = np.stack([ys * 255 // (height - 1), xs * 255 // (width - 1), (ys * 7 + xs * 3) % 256], axis=-1)
    noise = np.random.default_rng(7).integers(-40, 40, base.shape)
    return np.clip(base + noise, 0, 255).astype(np.uint8)


class FirstHardwareDeviceTest(unittest.TestCase):
    def test_skips_software_rasterisers(self):
        cases = [  # (device types in ncnn order, expected index)
            ([], None),
            ([SOFTWARE_DEVICE], None),
            ([SOFTWARE_DEVICE, 1], 1),
            ([0, SOFTWARE_DEVICE], 0),
        ]
        for types, expected in cases:
            with self.subTest(types=types):
                self.assertEqual(expected, first_hardware_device(types))


class UpscalerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upscaler = Upscaler(MODELS, pick_gpu())

    def test_output_is_the_input_times_the_scale(self):
        rgb = page(30, 50)

        for scale in SCALES:
            with self.subTest(scale=scale):
                out = self.upscaler.upscale(rgb, scale)

                self.assertEqual((30 * scale, 50 * scale, 3), out.shape)
                self.assertEqual(np.uint8, out.dtype)

    def test_tiles_stitch_into_the_whole_image_result(self):
        # 100 x 90 in 40-pixel tiles: inner seams both ways plus partial tiles on the right and bottom.
        rgb = page(100, 90)

        for scale in SCALES:
            with self.subTest(scale=scale):
                whole = self.upscaler.upscale(rgb, scale, tile=1000).astype(int)
                tiled = self.upscaler.upscale(rgb, scale, tile=40).astype(int)

                diff = np.abs(whole - tiled)
                self.assertLessEqual(diff.max(), 2)
                self.assertLess(diff.mean(), 0.05)

    def test_gpu_matches_the_cpu(self):
        if pick_gpu() is None:
            self.skipTest("no hardware GPU")
        # Wider than a tile, so in-tile coordinates pass 2048 in the x4 domain.
        rgb = page(160, 600)
        cpu = Upscaler(MODELS, None)

        for scale in SCALES:
            with self.subTest(scale=scale):
                diff = np.abs(self.upscaler.upscale(rgb, scale).astype(int) - cpu.upscale(rgb, scale).astype(int))

                self.assertLessEqual(diff.max(), 4)
                self.assertLess(diff.mean(), 0.5)


if __name__ == "__main__":
    unittest.main()
