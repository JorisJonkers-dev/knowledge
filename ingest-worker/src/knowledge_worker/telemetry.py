"""structlog configuration matching the JVM services' JSON log shape.

Keys (`@timestamp`, `message`, `level_value`, `service.name`,
`service.version`, `deployment.environment`) mirror the Spring
logstash-encoder output so Loki dashboards work without per-service
templates. trace_id / span_id are pulled from the OTel context when
opentelemetry-distro is loaded.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

import structlog
from structlog.types import EventDict

_LEVEL_VALUES = {
    "debug": 10000,
    "info": 20000,
    "warning": 30000,
    "warn": 30000,
    "error": 40000,
    "critical": 50000,
    "fatal": 50000,
}


def _add_timestamp(_logger: object, _name: str, event_dict: EventDict) -> EventDict:
    event_dict["@timestamp"] = datetime.now(UTC).isoformat().replace("+00:00", "Z")
    return event_dict


def _add_level_value(_logger: object, name: str, event_dict: EventDict) -> EventDict:
    event_dict["level_value"] = _LEVEL_VALUES.get(name.lower(), 0)
    return event_dict


def _rename_event_to_message(_logger: object, _name: str, event_dict: EventDict) -> EventDict:
    if "event" in event_dict and "message" not in event_dict:
        event_dict["message"] = event_dict.pop("event")
    return event_dict


def _add_trace_context(_logger: object, _name: str, event_dict: EventDict) -> EventDict:
    """Pull trace_id / span_id from the OTel context if available.

    Import lazily so unit tests without the SDK on the path still pass.
    """

    try:
        from opentelemetry import trace
    except ModuleNotFoundError:  # pragma: no cover — distro always present in prod
        return event_dict

    span = trace.get_current_span()
    ctx = span.get_span_context() if span else None
    if ctx is None or not ctx.is_valid:
        return event_dict
    event_dict["trace_id"] = format(ctx.trace_id, "032x")
    event_dict["span_id"] = format(ctx.span_id, "016x")
    return event_dict


def configure(
    *,
    level: str,
    service_version: str,
    service_name: str = "knowledge-ingest-worker",
    deployment_environment: str = "production",
) -> None:
    """Wire structlog + stdlib logging into a single JSON formatter.

    Re-callable: `basicConfig` is a no-op once a handler is attached,
    so the root logger's level is set explicitly to keep repeated
    `configure(...)` calls (notably the unit tests) consistent.
    """

    logging.basicConfig(level=level.upper(), format="%(message)s")
    logging.getLogger().setLevel(level.upper())

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            _add_level_value,
            _add_timestamp,
            _rename_event_to_message,
            _add_trace_context,
            structlog.processors.format_exc_info,
            _service_context(
                service_name=service_name,
                service_version=service_version,
                deployment_environment=deployment_environment,
            ),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelName(level.upper())
        ),
        cache_logger_on_first_use=True,
    )


def _service_context(
    *,
    service_name: str,
    service_version: str,
    deployment_environment: str,
) -> Any:
    def add(_logger: object, _name: str, event_dict: EventDict) -> EventDict:
        event_dict.setdefault("service", service_name)
        event_dict.setdefault("service.name", service_name)
        event_dict.setdefault("service.version", service_version)
        event_dict.setdefault("deployment.environment", deployment_environment)
        return event_dict

    return add
