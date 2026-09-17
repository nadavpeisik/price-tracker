"""The smallest app that inherits `main`'s logging, for `test_logging_config.py`.

It stands in for `main:app`, whose lifespan would launch Chromium for a test about log formatting.
Two things keep the stand-in honest, and both are deliberate: it never calls `configure_logging()`
itself (importing `main` is what configures logging, so the test fails if `main` stops doing it at
import), and the middleware is `main`'s own.
"""

import logging

from fastapi import FastAPI

from main import CorrelationIdMiddleware

api = FastAPI()
app = CorrelationIdMiddleware(api)

logger = logging.getLogger("probe")

# What the test greps its own line by — distinct from anything uvicorn writes.
HANDLER_LOG_MESSAGE = "probe handler reached"


@api.get("/ready")
async def ready():
    """Readiness polling target. Separate from /probe so the startup poll's access lines, which
    carry generated correlation ids, cannot be mistaken for the ones under test."""
    return {"ready": True}


@api.get("/probe")
async def probe():
    logger.info(HANDLER_LOG_MESSAGE)
    return {"ok": True}


@api.get("/boom")
async def boom():
    raise RuntimeError("probe blew up")
