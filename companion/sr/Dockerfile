FROM python:3.13-slim-trixie

# Vulkan loader and Mesa's drivers (RADV for AMD, ANV for Intel); without a GPU ncnn runs on the CPU.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libvulkan1 mesa-vulkan-drivers \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY models/ models/
COPY app/ app/
COPY tests/ tests/

RUN useradd --system --uid 10001 sr
USER sr
# The root filesystem is read-only at run time; Mesa keeps its shader cache in the tmpfs.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 XDG_CACHE_HOME=/tmp
EXPOSE 8790
CMD ["python", "-m", "app.server"]
