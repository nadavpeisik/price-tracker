"""JSON logging for the scraper, in ECS field names, so Loki can query it next to the Spring
apps' output (issue #276).

Two facts about uvicorn that this file is shaped around and the code cannot state:

  1. uvicorn applies `dictConfig(uvicorn.config.LOGGING_CONFIG)` while building its `Config`,
     *before* it imports the app module. `configure_logging()` runs at `main` import, so it is the
     last writer. Move it later and uvicorn's config wins.
  2. That config gives `uvicorn` and `uvicorn.access` their own handlers with `propagate=False`
     (`uvicorn.error` has no handler and propagates to `uvicorn`). A root handler alone therefore
     never formats an access line.

Neither is documented API, so `tests/test_logging_config.py` pins both against a real `uvicorn`.
"""

import contextvars
import logging
import logging.config

from pythonjsonlogger.core import RESERVED_ATTRS
from pythonjsonlogger.json import JsonFormatter

# The Spring apps get theirs from `spring.application.name` (`pricehunt-backend` / `pricehunt-bff`).
SERVICE_NAME = "pricehunt-scraper"

# "-" rather than None or an absent key: one shape for a Loki query to handle, not three.
UNSET_CORRELATION_ID = "-"

correlation_id: contextvars.ContextVar[str] = contextvars.ContextVar(
    "correlation_id", default=UNSET_CORRELATION_ID
)

# The JSON spelling is the Java MDC key; the attribute keeps the Python one. Renaming here rather
# than in Alloy (#277) keeps one spelling in the raw file, `docker logs` and Loki alike.
_CORRELATION_ID_ATTR = "correlation_id"
_CORRELATION_ID_FIELD = "correlationId"

# python-json-logger writes this one itself, from `record.created`, when passed as `timestamp=`.
_TIMESTAMP_FIELD = "@timestamp"

_RECORD_ATTRS = ["levelname", "name", "message", _CORRELATION_ID_ATTR]

_FIELD_RENAMES = {
    "levelname": "log.level",
    "name": "log.logger",
    _CORRELATION_ID_ATTR: _CORRELATION_ID_FIELD,
    "exc_info": "error.stack_trace",
}

# uvicorn's ANSI-decorated copy of `message`, passed as an `extra` on its startup lines. Not
# reserved, so without this it rides along as a field and doubles every one of those lines.
_RESERVED_ATTRS = (*RESERVED_ATTRS, "color_message")

_original_log_record_factory = logging.getLogRecordFactory()


def _log_record_factory(*args, **kwargs):
    record = _original_log_record_factory(*args, **kwargs)
    setattr(record, _CORRELATION_ID_ATTR, correlation_id.get())
    return record


class EcsJsonFormatter(JsonFormatter):
    """JSON in ECS field names. Referenced by dotted path from `_LOGGING_CONFIG`."""

    def __init__(self, *args, **kwargs):
        super().__init__(
            *args,
            fmt=_RECORD_ATTRS,
            rename_fields=_FIELD_RENAMES,
            static_fields={"service.name": SERVICE_NAME},
            reserved_attrs=_RESERVED_ATTRS,
            timestamp=_TIMESTAMP_FIELD,
            # Hebrew shop names are ordinary input here; `\uXXXX` in `docker logs` is not.
            json_ensure_ascii=False,
            **kwargs,
        )

    def add_fields(self, log_data, record, message_dict):
        # The traceback is a rename of `exc_info`, but the type and message are on no attribute —
        # without deriving them, a failure is only findable by searching inside the stack trace.
        if record.exc_info and record.exc_info[0] is not None:
            exc_type, exc_value = record.exc_info[0], record.exc_info[1]
            log_data["error.type"] = exc_type.__name__
            log_data["error.message"] = str(exc_value)
        super().add_fields(log_data, record, message_dict)


_LOGGING_CONFIG = {
    "version": 1,
    # `main` and `sites.ksp` already exist by the time this runs; disabling would silence them.
    "disable_existing_loggers": False,
    "formatters": {"ecs_json": {"()": f"{__name__}.EcsJsonFormatter"}},
    # One stream, including what uvicorn sends to stderr: a local `| jq` then sees every line.
    "handlers": {
        "stdout": {
            "class": "logging.StreamHandler",
            "formatter": "ecs_json",
            "stream": "ext://sys.stdout",
        }
    },
    # Named explicitly — fact 2 in the module docstring.
    "loggers": {
        name: {"level": "INFO", "handlers": ["stdout"], "propagate": False}
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access")
    },
    "root": {"level": "INFO", "handlers": ["stdout"]},
}


def configure_logging() -> None:
    """Installs the record factory and the JSON config. Idempotent; must run at `main` import
    (fact 1 in the module docstring)."""
    logging.setLogRecordFactory(_log_record_factory)
    logging.config.dictConfig(_LOGGING_CONFIG)
