"""Telemetry config tests.

The full structlog pipeline writes to stdout via the JSONRenderer;
the asserts here verify the configurator can be called repeatedly
without exploding (idempotency matters because pytest re-imports
modules between tests).
"""

from __future__ import annotations

import json
import logging

import structlog

from knowledge_worker.telemetry import configure


def test_configure_emits_json_lines_with_service_fields(capfd) -> None:
    configure(level="INFO", service_version="abc1234", service_name="knowledge-ingest-worker")
    structlog.get_logger("test").info("hello", note_id="01HXY")
    # Pytest's capfd captures the configured stdlib root logger output.
    captured = capfd.readouterr().out.strip().splitlines()
    assert captured, "expected at least one log line"
    payload = json.loads(captured[-1])
    assert payload["message"] == "hello"
    assert payload["note_id"] == "01HXY"
    assert payload["service.name"] == "knowledge-ingest-worker"
    assert payload["service.version"] == "abc1234"
    assert payload["deployment.environment"] == "production"
    assert payload["level_value"] in {20000}
    assert payload["@timestamp"].endswith("Z")


def test_configure_can_be_called_twice() -> None:
    configure(level="INFO", service_version="v1")
    configure(level="DEBUG", service_version="v2")
    # No assertion needed — the call simply must not raise.
    assert logging.getLogger().level in {logging.INFO, logging.DEBUG}
