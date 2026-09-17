"""Issue #276 — the scraper's logs are JSON, in ECS field names, and every line of a request
carries the correlation id, uvicorn's access line included.

The `probe_run` tests drive a real `uvicorn` process because nothing inside the app can see
uvicorn's own logging config or its access line.
"""

import asyncio
import json
import logging
import re
import socket
import subprocess
import sys
import time
import types
import urllib.error
import urllib.request
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

import pytest
from starlette.datastructures import Headers

import main
from logging_config import (
    SERVICE_NAME,
    UNSET_CORRELATION_ID,
    EcsJsonFormatter,
    correlation_id,
)
from tests.logging_probe_app import HANDLER_LOG_MESSAGE

_SCRAPER_DIR = Path(__file__).resolve().parent.parent

# Sized for a cold CI runner importing Playwright; the poll returns as soon as the server answers.
_STARTUP_TIMEOUT_S = 60


def _format(record: logging.LogRecord) -> dict:
    return json.loads(EcsJsonFormatter().format(record))


def _record(msg: str = "hello", *, exc_info=None, args=()) -> logging.LogRecord:
    """Built through the live factory, which importing `main` has installed, so the record carries
    the correlation id the same way a real one does."""
    return logging.getLogRecordFactory()(
        "some.logger", logging.INFO, __file__, 10, msg, args, exc_info
    )


def test_formatter_emits_ecs_field_names():
    line = _format(_record())

    assert line["log.level"] == "INFO"
    assert line["log.logger"] == "some.logger"
    assert line["message"] == "hello"
    assert line["service.name"] == SERVICE_NAME
    assert datetime.fromisoformat(line["@timestamp"]).tzinfo is not None


def test_correlation_id_is_renamed_to_the_java_mdc_key():
    token = correlation_id.set("cid-from-contextvar")
    try:
        line = _format(_record())
    finally:
        correlation_id.reset(token)

    assert line["correlationId"] == "cid-from-contextvar"
    # A query written against one service's field name must not silently miss the other's.
    assert "correlation_id" not in line


def test_line_outside_a_request_still_carries_the_correlation_id_field():
    assert _format(_record())["correlationId"] == UNSET_CORRELATION_ID


def test_non_ascii_is_not_escaped():
    # Hebrew shop names are ordinary input here; `\u05e7` in `docker logs` is not.
    assert "קספ" in EcsJsonFormatter().format(_record("shop=%s", args=("קספ",)))


def test_newlines_in_a_logged_value_cannot_forge_a_second_line():
    # CWE-117, the guard `_log_safe` states at each call site, here unconditionally.
    formatted = EcsJsonFormatter().format(_record("url=%s", args=("http://x\nlog.level=FATAL",)))

    assert "\n" not in formatted
    assert json.loads(formatted)["message"] == "url=http://x\nlog.level=FATAL"


def test_exception_becomes_ecs_error_fields():
    try:
        raise ValueError("boom")
    except ValueError:
        line = _format(_record("ksp handler failed", exc_info=sys.exc_info()))

    assert line["error.type"] == "ValueError"
    assert line["error.message"] == "boom"
    assert "ValueError: boom" in line["error.stack_trace"]
    # python-json-logger's own key for the traceback, which an ECS consumer does not know.
    assert "exc_info" not in line


def test_main_is_wrapped_in_the_correlation_id_middleware():
    # What keeps the probe app a stand-in for main rather than a divergent second wiring.
    assert isinstance(main.app, main.CorrelationIdMiddleware)
    assert main.app.app is main.api


# The subprocess tests below prove the middleware end to end, but they run it in another process,
# where nothing in-process can observe it. These drive it directly instead.


def _http_scope(headers=()) -> dict:
    return {"type": "http", "headers": [(k.lower().encode(), v.encode()) for k, v in headers]}


async def _receive() -> dict:
    return {"type": "http.request"}


async def _drive(app, scope) -> list[dict]:
    """Runs one request through the middleware, returning the ASGI messages it sent."""
    sent = []

    async def send(message):
        sent.append(message)

    await main.CorrelationIdMiddleware(app)(scope, _receive, send)
    return sent


async def _respond_ok(scope, receive, send):
    # Both messages, as a real app sends them: only the first carries headers to stamp.
    await send({"type": "http.response.start", "status": 200, "headers": []})
    await send({"type": "http.response.body", "body": b"{}"})


async def test_middleware_binds_the_callers_id_and_echoes_it_back():
    bound = {}

    async def app(scope, receive, send):
        bound["during_request"] = correlation_id.get()
        await _respond_ok(scope, receive, send)

    sent = await _drive(app, _http_scope([("X-Correlation-ID", "from-the-caller")]))

    assert bound["during_request"] == "from-the-caller"
    assert Headers(scope=sent[0])["X-Correlation-ID"] == "from-the-caller"


async def test_middleware_generates_an_id_when_the_caller_sends_none():
    sent = await _drive(_respond_ok, _http_scope())

    assert re.fullmatch(r"[0-9a-f-]{36}", Headers(scope=sent[0])["X-Correlation-ID"])


async def test_middleware_passes_non_http_scopes_straight_through():
    seen = {}

    async def app(scope, receive, send):
        seen["type"] = scope["type"]
        seen["bound"] = correlation_id.get()

    assert await _drive(app, {"type": "lifespan"}) == []
    assert seen == {"type": "lifespan", "bound": UNSET_CORRELATION_ID}


async def test_the_id_outlives_an_exception_but_not_the_request():
    async def app(scope, receive, send):
        raise RuntimeError("boom")

    bound = {}

    async def one_request_cycle():
        with pytest.raises(RuntimeError):
            await _drive(app, _http_scope([("X-Correlation-ID", "id-of-the-failure")]))
        # Where uvicorn logs "Exception in ASGI application": after the exception left the
        # middleware, so the id has to still be bound.
        bound["after_the_exception"] = correlation_id.get()

    # A task of its own, as uvicorn gives each request cycle — which is what bounds the leak.
    await asyncio.create_task(one_request_cycle())

    assert bound["after_the_exception"] == "id-of-the-failure"
    # ...and the value that was never reset stayed inside that task.
    assert correlation_id.get() == UNSET_CORRELATION_ID


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _get(url: str, correlation_id_header: str | None = None):
    request = urllib.request.Request(url)
    if correlation_id_header is not None:
        request.add_header("X-Correlation-ID", correlation_id_header)
    return urllib.request.urlopen(request, timeout=10)


def _wait_until_serving(port: int, process: subprocess.Popen, log_path: Path) -> None:
    deadline = time.monotonic() + _STARTUP_TIMEOUT_S
    while time.monotonic() < deadline:
        if process.poll() is not None:
            pytest.fail(f"uvicorn exited early ({process.returncode}):\n{log_path.read_text()}")
        try:
            with _get(f"http://127.0.0.1:{port}/ready") as response:
                response.read()
            return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            time.sleep(0.1)
    pytest.fail(f"uvicorn did not start in {_STARTUP_TIMEOUT_S}s:\n{log_path.read_text()}")


@contextmanager
def _probe_server(log_path: Path):
    """The probe app under a real uvicorn, torn down on exit so the caller reads a complete file.

    stdout goes to a file, not a pipe, so a chatty startup cannot deadlock on a full pipe buffer;
    `-u` stops the child block-buffering its lines.
    """
    port = _free_port()
    with log_path.open("w") as log_file:
        process = subprocess.Popen(
            [
                sys.executable,
                "-u",
                "-m",
                "uvicorn",
                "tests.logging_probe_app:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            cwd=_SCRAPER_DIR,
            stdout=log_file,
            stderr=subprocess.STDOUT,
        )
        try:
            _wait_until_serving(port, process, log_path)
            yield port
        finally:
            # SIGTERM first so uvicorn exits gracefully and Python flushes the log file; kill
            # only as the fallback, or a hung shutdown holds the port for the rest of the run.
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


@pytest.fixture(scope="module")
def probe_run(tmp_path_factory):
    """One uvicorn process, three requests, and every line it printed, parsed as JSON. Module
    scoped so the three independent requests pay for one server start between them."""
    log_path = tmp_path_factory.mktemp("uvicorn") / "uvicorn.log"
    run = types.SimpleNamespace()

    with _probe_server(log_path) as port:
        with _get(f"http://127.0.0.1:{port}/probe", "cid-under-test") as response:
            response.read()
            run.echoed_correlation_id = response.headers.get("X-Correlation-ID")

        with _get(f"http://127.0.0.1:{port}/probe") as response:
            response.read()
            run.generated_correlation_id = response.headers.get("X-Correlation-ID")

        with pytest.raises(urllib.error.HTTPError) as failure:
            _get(f"http://127.0.0.1:{port}/boom", "cid-of-the-500")
        run.failure_status = failure.value.code
        run.failure_correlation_id = failure.value.headers.get("X-Correlation-ID")
        failure.value.close()

    # json.loads raising here is itself the assertion for "every line is JSON" — and not only our
    # lines: uvicorn's startup and access handlers have to be ours too, or a log consumer meets a
    # line it cannot parse.
    run.lines = [json.loads(line) for line in log_path.read_text().splitlines() if line.strip()]
    return run


def _request_lines_by_logger(run, request_correlation_id: str) -> dict:
    """The request's lines, keyed by logger — one line per logger is all these requests emit."""
    return {
        line["log.logger"]: line
        for line in run.lines
        if line["correlationId"] == request_correlation_id
    }


def test_uvicorn_access_line_carries_the_callers_correlation_id(probe_run):
    assert probe_run.lines, "uvicorn printed nothing"
    assert {line["service.name"] for line in probe_run.lines} == {SERVICE_NAME}
    assert probe_run.echoed_correlation_id == "cid-under-test"

    by_logger = _request_lines_by_logger(probe_run, "cid-under-test")
    assert by_logger["probe"]["message"] == HANDLER_LOG_MESSAGE
    # The point of the issue: uvicorn's access line, written from the ASGI send callback after the
    # handler has returned, carries the same id as the handler's own line.
    assert "GET /probe" in by_logger["uvicorn.access"]["message"]
    assert by_logger["uvicorn.access"]["log.level"] == "INFO"


def test_a_caller_without_a_correlation_id_header_gets_one_generated(probe_run):
    # The backend always sends the header; anything else reaching the scraper (curl, a future
    # caller) still has to be followable, which is what the generated id is for.
    assert re.fullmatch(r"[0-9a-f-]{36}", probe_run.generated_correlation_id)

    by_logger = _request_lines_by_logger(probe_run, probe_run.generated_correlation_id)
    assert by_logger["probe"]["message"] == HANDLER_LOG_MESSAGE
    assert "GET /probe" in by_logger["uvicorn.access"]["message"]


def test_the_500_response_is_correlated_too(probe_run):
    # An unhandled exception is answered by ServerErrorMiddleware, which sits outside any
    # `add_middleware` — so this is what pins the middleware as the outermost ASGI layer.
    assert probe_run.failure_status == 500
    assert probe_run.failure_correlation_id == "cid-of-the-500"

    by_logger = _request_lines_by_logger(probe_run, "cid-of-the-500")
    assert "GET /boom" in by_logger["uvicorn.access"]["message"]
    assert " 500" in by_logger["uvicorn.access"]["message"]
    # The traceback uvicorn logs for the failed request is correlated and ECS-shaped as well.
    assert by_logger["uvicorn.error"]["error.type"] == "RuntimeError"
    assert by_logger["uvicorn.error"]["error.message"] == "probe blew up"


def test_lines_outside_any_request_carry_the_unset_marker(probe_run):
    startup = [
        line
        for line in probe_run.lines
        if line["log.logger"].startswith("uvicorn") and "Started server process" in line["message"]
    ]
    assert startup and all(line["correlationId"] == UNSET_CORRELATION_ID for line in startup)
