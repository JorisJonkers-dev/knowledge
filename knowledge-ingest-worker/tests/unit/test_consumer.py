"""Unit-level tests for `Consumer._on_message`.

We bypass the real BlockingConnection / channel by passing a fake
channel object that records `basic_ack` / `basic_nack` calls. This
keeps the test focused on the dispatch + error-handling logic
rather than the AMQP transport (the transport is exercised by the
Testcontainers integration test).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

import pytest

from knowledge_worker.consumer import Consumer
from knowledge_worker.handlers import Handler, RecordingHandler
from knowledge_worker.messages import CapturedNote
from knowledge_worker.settings import Settings


@dataclass
class _FakeMethod:
    delivery_tag: int = 1
    routing_key: str = "knowledge.lesson"


@dataclass
class _FakeChannel:
    acks: list[int] = field(default_factory=list)
    nacks: list[tuple[int, bool]] = field(default_factory=list)

    def basic_ack(self, delivery_tag: int) -> None:
        self.acks.append(delivery_tag)

    def basic_nack(self, delivery_tag: int, requeue: bool) -> None:
        self.nacks.append((delivery_tag, requeue))


def _settings() -> Settings:
    return Settings.from_env(env={})


def _valid_body() -> bytes:
    return json.dumps(
        {
            "id": "01HXYZ00000000000000000000",
            "type": "lesson",
            "scope": "personal",
            "source": "claude-code",
            "captured_at": "2026-05-13T12:00:00Z",
            "confidence": 0.4,
            "title": "title",
            "body": "body",
            "vault_path": "personal/lesson/draft.md",
            "tags": [],
        }
    ).encode()


def test_valid_message_is_acked_and_handed_to_handler() -> None:
    handler = RecordingHandler()
    consumer = Consumer(_settings(), handler)
    channel = _FakeChannel()

    consumer._on_message(channel, _FakeMethod(), object(), _valid_body())  # type: ignore[arg-type]

    assert channel.acks == [1]
    assert channel.nacks == []
    assert len(handler.deliveries) == 1
    routing_key, note = handler.deliveries[0]
    assert routing_key == "knowledge.lesson"
    assert isinstance(note, CapturedNote)


def test_invalid_payload_is_acked_to_avoid_redelivery_loops() -> None:
    handler = RecordingHandler()
    consumer = Consumer(_settings(), handler)
    channel = _FakeChannel()

    consumer._on_message(channel, _FakeMethod(), object(), b"{not valid json")  # type: ignore[arg-type]

    assert channel.acks == [1]
    assert channel.nacks == []
    assert handler.deliveries == []


def test_payload_missing_required_field_is_acked_and_skipped() -> None:
    handler = RecordingHandler()
    consumer = Consumer(_settings(), handler)
    channel = _FakeChannel()

    payload = json.loads(_valid_body())
    del payload["id"]
    body = json.dumps(payload).encode()
    consumer._on_message(channel, _FakeMethod(), object(), body)  # type: ignore[arg-type]

    assert channel.acks == [1]
    assert handler.deliveries == []


def test_handler_exception_nacks_without_requeue() -> None:
    class _Boom:
        def handle(self, routing_key: str, note: CapturedNote) -> None:
            raise RuntimeError("downstream is on fire")

    consumer = Consumer(_settings(), _Boom())
    channel = _FakeChannel()
    consumer._on_message(channel, _FakeMethod(), object(), _valid_body())  # type: ignore[arg-type]

    assert channel.acks == []
    assert channel.nacks == [(1, False)]


def test_routing_key_is_threaded_through_to_handler() -> None:
    handler = RecordingHandler()
    consumer = Consumer(_settings(), handler)
    channel = _FakeChannel()

    consumer._on_message(
        channel,
        _FakeMethod(routing_key="knowledge.decision", delivery_tag=42),
        object(),  # type: ignore[arg-type]
        _valid_body(),
    )

    assert channel.acks == [42]
    assert handler.deliveries[0][0] == "knowledge.decision"


def test_start_uses_injected_connection_factory(monkeypatch: pytest.MonkeyPatch) -> None:
    class _FakeChannelObj:
        is_open = True

        def basic_qos(self, prefetch_count: int) -> None: ...
        def basic_consume(self, **_: Any) -> None: ...
        def start_consuming(self) -> None: ...
        def stop_consuming(self) -> None: ...

    class _FakeConnection:
        is_open = True

        def channel(self) -> _FakeChannelObj:
            return _FakeChannelObj()

        def close(self) -> None: ...

    calls: list[Any] = []

    def factory(params: Any) -> _FakeConnection:
        calls.append(params)
        return _FakeConnection()

    handler: Handler = RecordingHandler()
    consumer = Consumer(_settings(), handler, connection_factory=factory)  # type: ignore[arg-type]
    consumer.start()

    assert len(calls) == 1
    assert calls[0].host.endswith("data-system.svc.cluster.local")
    consumer.stop()
