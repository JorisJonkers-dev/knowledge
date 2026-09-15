"""Unit coverage for the handler/store factory in `__main__`.

The full `main()` requires a live RabbitMQ + OTel exporter, so the
test exercises the dependency-injection seams directly: when no
downstream target is enabled the worker falls back to
`LoggingHandler`, and when kb persistence is disabled it falls back
to `NullNoteStore`. `HindsightClient` and `SseMcpTransport` are
monkeypatched to fakes — the real `SseMcpTransport` opens a network
connection in its constructor, which a unit test must not do.
"""

from __future__ import annotations

import pytest
import structlog

import knowledge_worker.__main__ as main_mod
from knowledge_worker.__main__ import _build_handler, _build_store
from knowledge_worker.handlers import (
    BasicMemoryHandler,
    HindsightHandler,
    LoggingHandler,
    MultiHandler,
)
from knowledge_worker.settings import Settings
from knowledge_worker.store import NullNoteStore


def _settings(**overrides: str) -> Settings:
    env: dict[str, str] = {
        "VAULT_ENABLED": "false",
        "KB_PERSIST_ENABLED": "false",
        "HINDSIGHT_ENABLED": "false",
        "BASIC_MEMORY_ENABLED": "false",
    }
    env.update(overrides)
    return Settings.from_env(env=env)


class _FakeHindsightClient:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs


class _FakeSseMcpTransport:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs


@pytest.fixture(autouse=True)
def _fake_network_clients(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main_mod, "HindsightClient", _FakeHindsightClient)
    monkeypatch.setattr(main_mod, "SseMcpTransport", _FakeSseMcpTransport)


def test_build_handler_disabled_returns_logging_handler() -> None:
    log = structlog.get_logger("test")
    handler = _build_handler(_settings(), log)
    assert isinstance(handler, LoggingHandler)


def test_build_store_disabled_returns_null_store() -> None:
    log = structlog.get_logger("test")
    store = _build_store(_settings(), log)
    assert isinstance(store, NullNoteStore)


def test_build_handler_returns_hindsight_handler_alone_when_only_it_is_enabled() -> None:
    log = structlog.get_logger("test")
    handler = _build_handler(_settings(HINDSIGHT_ENABLED="true"), log)
    assert isinstance(handler, HindsightHandler)


def test_build_handler_returns_basic_memory_handler_alone_when_only_it_is_enabled() -> None:
    log = structlog.get_logger("test")
    handler = _build_handler(_settings(BASIC_MEMORY_ENABLED="true"), log)
    assert isinstance(handler, BasicMemoryHandler)


def test_build_handler_composes_multi_handler_for_the_new_platform() -> None:
    """The knowledge-platform-system Deployment turns both targets on."""
    log = structlog.get_logger("test")
    handler = _build_handler(
        _settings(HINDSIGHT_ENABLED="true", BASIC_MEMORY_ENABLED="true"), log
    )
    assert isinstance(handler, MultiHandler)
    assert len(handler._handlers) == 2
    assert isinstance(handler._handlers[0], HindsightHandler)
    assert isinstance(handler._handlers[1], BasicMemoryHandler)
