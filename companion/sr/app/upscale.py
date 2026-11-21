"""realesr-animevideov3 on ncnn: one long-lived net per scale, run tile by tile to bound memory."""
from pathlib import Path

import ncnn
import numpy as np

MODEL = "realesr-animevideov3"
SCALES = (2, 3, 4)
TILE = 512
# 18 stacked 3x3 convolutions: each output pixel depends on 18 input pixels on every side.
TILE_PAD = 20
# ncnn GpuInfo.type() of a software rasteriser such as llvmpipe, slower than ncnn's own CPU path.
SOFTWARE_DEVICE = 3


def first_hardware_device(device_types):
    """Index of the first Vulkan device that is real hardware, or None."""
    return next((i for i, kind in enumerate(device_types) if kind != SOFTWARE_DEVICE), None)


def pick_gpu():
    return first_hardware_device([ncnn.get_gpu_info(i).type() for i in range(ncnn.get_gpu_count())])


class Upscaler:
    """Owns the loaded nets; call upscale() from one thread only."""

    def __init__(self, models_dir: Path, gpu_index):
        self.device = "cpu" if gpu_index is None else ncnn.get_gpu_info(gpu_index).device_name()
        self._nets = {scale: _load(models_dir, scale, gpu_index) for scale in SCALES}

    def upscale(self, rgb, scale, tile=TILE):
        """H x W x 3 uint8 in, (H * scale) x (W * scale) x 3 uint8 out."""
        net = self._nets[scale]
        h, w, _ = rgb.shape
        out = np.empty((h * scale, w * scale, 3), np.uint8)
        for y in range(0, h, tile):
            for x in range(0, w, tile):
                y0, x0 = max(y - TILE_PAD, 0), max(x - TILE_PAD, 0)
                y1, x1 = min(y + tile + TILE_PAD, h), min(x + tile + TILE_PAD, w)
                result = _infer(net, rgb[y0:y1, x0:x1])
                th, tw = min(tile, h - y) * scale, min(tile, w - x) * scale
                ry, rx = (y - y0) * scale, (x - x0) * scale
                out[y * scale:y * scale + th, x * scale:x * scale + tw] = result[ry:ry + th, rx:rx + tw]
        return out


def _load(models_dir, scale, gpu_index):
    net = ncnn.Net()
    net.opt.use_vulkan_compute = gpu_index is not None
    if gpu_index is not None:
        net.set_vulkan_device(gpu_index)
        # fp16 arithmetic left pixels up to 250 levels off on RADV.
        net.opt.use_fp16_arithmetic = False
    # x2 and x3 are the x4 network plus a resize, so the three graphs share one weights file.
    if (net.load_param(str(models_dir / f"{MODEL}-x{scale}.param")) != 0
            or net.load_model(str(models_dir / f"{MODEL}.bin")) != 0):
        raise RuntimeError(f"cannot load {MODEL} x{scale} from {models_dir}")
    return net


def _infer(net, rgb):
    h, w, _ = rgb.shape
    mat = ncnn.Mat.from_pixels(np.ascontiguousarray(rgb), ncnn.Mat.PixelType.PIXEL_RGB, w, h)
    mat.substract_mean_normalize([], [1 / 255.0] * 3)
    extractor = net.create_extractor()
    extractor.input("data", mat)
    ret, result = extractor.extract("output")
    if ret != 0:
        raise RuntimeError(f"ncnn extract failed with {ret}")
    return (np.clip(np.array(result), 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8).transpose(1, 2, 0)
