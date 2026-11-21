# Super-resolution companion

A small HTTP service for the homelab that upscales pages while EhViewer@Lab reads them (#206, part of #173).
The app sends the page it is showing, gets the upscaled page back as a JPEG and shows it. Nothing is kept on
either side.

- Model: realesr-animevideov3 from Real-ESRGAN v0.2.5.0 (BSD-3-Clause, see `sr/models/LICENSE`), run with
  ncnn 1.0.20261020.
- Runs on a GPU through Vulkan (Mesa: RADV for AMD, ANV for Intel). Without one it falls back to the CPU.

## Requirements

- Docker Engine with the Compose plugin.
- A Vulkan GPU exposed as `/dev/dri`. Tested with the Radeon 780M of a Ryzen 7 8845HS; NVIDIA cards are not
  covered.
- Without a GPU, delete `devices` and `group_add` from `docker-compose.yml`. A page then takes about 7 s instead
  of under 2 s.

## Run

```sh
cp .env.example .env
# HOMELAB_PASS: the password of every homelab service, at least 8 characters
# RENDER_GID: the output of `stat -c %g /dev/dri/renderD128`
docker compose up -d --build
docker compose logs sr   # "... on <your GPU>, listening on 8790"
```

In the app's Homelab settings, enter the super-resolution URL and the same password (app side: #207). The app
uses plain http only with a LAN address (10.x, 172.16–31.x, 192.168.x), for example `http://192.168.1.10:8790`.
A domain name needs https: put the service behind an https reverse proxy and enter `https://<domain>`. Keep
port 8790 on the LAN or behind WireGuard; never expose it to the internet.

## API

Every request needs `Authorization: Bearer <HOMELAB_PASS>`.

| Request | Response |
|---|---|
| `GET /v1/info` | `{"model": "realesr-animevideov3", "scales": [2, 3, 4], "device": "<GPU name or cpu>"}` |
| `POST /v1/upscale?scale=N`, body: the page (JPEG, PNG, WebP; first frame of a GIF) | the page upscaled N times, as a JPEG of quality 95 |

The app picks N from its target resolution setting. With "Device resolution" it skips pages whose short side
already reaches the device's long side and otherwise takes the smallest of 2, 3 and 4 that gets there; 2x, 3x
and 4x upscale every page by that factor.

Failures return `{"error": "..."}`:

| Status | When |
|---|---|
| 400 | scale is not 2, 3 or 4, or the body is not an image |
| 401 | missing or wrong password |
| 404 | unknown path |
| 411 | no `Content-Length` |
| 413 | body over 32 MiB, input over 25 million pixels or output over 40 million pixels |
| 500 | the upscale itself failed (the cause is in the log) |
| 503 | four requests already in flight; retry later |

## Nothing is stored

Requests are handled in memory. The container has a read-only root, no volumes and a 64 MB tmpfs that only
holds Mesa's shader cache. The logs carry sizes and timings, never images or the password.

## Measured on the Radeon 780M (2026-11-20)

| Pages | Inference | JPEG | Response |
|---|---|---|---|
| Seven pages 1075–1280 px wide, x3 | 1.39–1.76 s | 58–87 ms | 2.6–7.5 MB |
| One 1280 x 960 page, x4 | 1.29 s | 56 ms | 1.7 MB |

The same pages take about 7 s on the 8845HS CPU. ncnn's fp16 arithmetic left pixels up to 250 levels off on
RADV, so the server computes in fp32: x2 and x3 ran as fast as with fp16, x4 about 0.5 s slower per page.

## Tests

```sh
docker compose run --rm sr python -m unittest discover -s tests -v
```
