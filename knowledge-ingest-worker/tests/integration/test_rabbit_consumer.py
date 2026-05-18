"""End-to-end smoke test: publish a `CapturedNote` payload onto the
same topic exchange the knowledge-api side declares, then verify the
worker consumes + dispatches to its handler.

Spins up RabbitMQ via Testcontainers (Docker required). Declares the
`knowledge` topic exchange + `knowledge.ingest` queue on the test's
own connection rather than relying on knowledge-api to do it — keeps
the worker's start-up free of declaration assumptions, which is the
production posture too.
"""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Iterator

import pika
import pytest

try:  # pragma: no cover — Docker is required for integration runs
    from testcontainers.rabbitmq import RabbitMqContainer
except ImportError:  # pragma: no cover
    RabbitMqContainer = None  # type: ignore[assignment]

from knowledge_worker.consumer import Consumer
from knowledge_worker.handlers import RecordingHandler
from knowledge_worker.settings import Settings

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def rabbit() -> Iterator[dict[str, object]]:
    if RabbitMqContainer is None:  # pragma: no cover
        pytest.skip("testcontainers.rabbitmq not installed")
    with RabbitMqContainer("rabbitmq:3-management-alpine") as container:
        host = container.get_container_host_ip()
        port = int(container.get_exposed_port(container.port))
        yield {
            "host": host,
            "port": port,
            "user": container.username,
            "password": container.password,
        }


def _settings(rabbit: dict[str, object]) -> Settings:
    return Settings.from_env(
        env={
            "RABBITMQ_HOST": str(rabbit["host"]),
            "RABBITMQ_PORT": str(rabbit["port"]),
            "RABBITMQ_USER": str(rabbit["user"]),
            "RABBITMQ_PASSWORD": str(rabbit["password"]),
            "INGEST_QUEUE": "knowledge.ingest",
            "INGEST_PREFETCH": "1",
        }
    )


def _declare_topology(settings: Settings) -> pika.BlockingConnection:
    conn = pika.BlockingConnection(
        pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            credentials=pika.PlainCredentials(
                settings.rabbitmq_user, settings.rabbitmq_password
            ),
            heartbeat=30,
        )
    )
    ch = conn.channel()
    ch.exchange_declare(exchange="knowledge", exchange_type="topic", durable=True)
    ch.queue_declare(queue=settings.queue, durable=True)
    ch.queue_bind(queue=settings.queue, exchange="knowledge", routing_key="knowledge.*")
    return conn


def _publish(
    settings: Settings,
    routing_key: str,
    payload: dict[str, object],
) -> None:
    conn = pika.BlockingConnection(
        pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            credentials=pika.PlainCredentials(
                settings.rabbitmq_user, settings.rabbitmq_password
            ),
        )
    )
    ch = conn.channel()
    ch.basic_publish(
        exchange="knowledge",
        routing_key=routing_key,
        body=json.dumps(payload).encode(),
    )
    conn.close()


def _await_delivery(handler: RecordingHandler, expected: int, timeout_s: float = 10.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if len(handler.deliveries) >= expected:
            return
        time.sleep(0.05)
    raise AssertionError(
        f"expected {expected} deliveries within {timeout_s}s, got {len(handler.deliveries)}"
    )


def _payload(routing_id: str) -> dict[str, object]:
    return {
        "id": routing_id,
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


def test_consumes_published_message_and_invokes_handler(rabbit: dict[str, object]) -> None:
    settings = _settings(rabbit)
    setup_conn = _declare_topology(settings)
    setup_conn.close()

    handler = RecordingHandler()
    consumer = Consumer(settings, handler)
    consumer.start()
    runner = threading.Thread(target=consumer.run_forever, daemon=True)
    runner.start()

    try:
        _publish(settings, "knowledge.lesson", _payload("01HXYZ00000000000000000001"))
        _await_delivery(handler, expected=1)
        routing_key, note = handler.deliveries[0]
        assert routing_key == "knowledge.lesson"
        assert note.id == "01HXYZ00000000000000000001"
    finally:
        consumer.stop()
        runner.join(timeout=5)


def test_handles_three_messages_in_order(rabbit: dict[str, object]) -> None:
    settings = _settings(rabbit)
    _declare_topology(settings).close()

    handler = RecordingHandler()
    consumer = Consumer(settings, handler)
    consumer.start()
    runner = threading.Thread(target=consumer.run_forever, daemon=True)
    runner.start()

    try:
        for i in range(3):
            _publish(settings, "knowledge.ingest", _payload(f"01HXYZ0000000000000000000{i}"))
        _await_delivery(handler, expected=3)
        ids = [note.id for _, note in handler.deliveries]
        assert ids == [
            "01HXYZ00000000000000000000",
            "01HXYZ00000000000000000001",
            "01HXYZ00000000000000000002",
        ]
    finally:
        consumer.stop()
        runner.join(timeout=5)
