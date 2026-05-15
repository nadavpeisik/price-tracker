FROM python:3.12-slim

WORKDIR /app

# Stage 1: install deps from pyproject.toml. A placeholder main.py is needed
# because hatchling's only-include = ["main.py"] requires the file to exist at
# wheel-build time; uvicorn imports main:app from /app at runtime (not from the
# installed wheel), so the placeholder is harmless and gets replaced below.
COPY pyproject.toml ./
RUN touch main.py && \
    pip install --no-cache-dir . && \
    rm main.py && \
    playwright install --with-deps chromium

# Stage 2: real source — only this layer invalidates on code change, so the
# heavy chromium-download layer above stays cached.
COPY main.py ./

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
