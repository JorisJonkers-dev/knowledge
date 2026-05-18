"""Unit coverage for the handler/store factory in `__main__`.

The full `main()` requires a live RabbitMQ + OTel exporter, so the
test exercises the dependency-injection seams directly: when the
vault writer is disabled the worker falls back to `LoggingHandler`,
and when kb persistence is disabled it falls back to `NullNoteStore`.
"""

from __future__ import annotations

import structlog

from knowledge_worker.__main__ import _build_handler, _build_store
from knowledge_worker.handlers import LoggingHandler
from knowledge_worker.settings import Settings
from knowledge_worker.store import NullNoteStore


def _settings(**overrides: str) -> Settings:
    env: dict[str, str] = {"VAULT_ENABLED": "false", "KB_PERSIST_ENABLED": "false"}
    env.update(overrides)
    return Settings.from_env(env=env)


def test_build_handler_disabled_returns_logging_handler() -> None:
    log = structlog.get_logger("test")
    handler = _build_handler(_settings(), log)
    assert isinstance(handler, LoggingHandler)


def test_build_store_disabled_returns_null_store() -> None:
    log = structlog.get_logger("test")
    store = _build_store(_settings(), log)
    assert isinstance(store, NullNoteStore)
