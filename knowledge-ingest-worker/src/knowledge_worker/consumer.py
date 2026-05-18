"""Blocking pika consumer.

Acks on a clean `handler.handle` return. Reraises everything else so
pika's default behaviour (`requeue=True`) nacks the delivery and
returns it to the broker. Once Phase 5-3 lands the DLX is wired in
the knowledge-api side already, so terminal failures land on
`knowledge.ingest.dlq` after the broker's default delivery-count
trips.
"""

from __future__ import annotations

import contextlib
import json
import logging
from collections.abc import Callable
from dataclasses import dataclass

import pika
import structlog
from pika.adapters.blocking_connection import BlockingChannel
from pika.spec import Basic, BasicProperties
from pydantic import ValidationError

from knowledge_worker.handlers import Handler
from knowledge_worker.messages import CapturedNote
from knowledge_worker.settings import Settings

type ConnectionFactory = Callable[[pika.ConnectionParameters], pika.BlockingConnection]


@dataclass(frozen=True, slots=True)
class _Delivery:
    routing_key: str
    body: bytes


class Consumer:
    """Wraps a pika `BlockingConnection` + per-delivery dispatch."""

    def __init__(
        self,
        settings: Settings,
        handler: Handler,
        *,
        connection_factory: ConnectionFactory | None = None,
    ) -> None:
        self._settings = settings
        self._handler = handler
        self._connection_factory = connection_factory or pika.BlockingConnection
        self._log = structlog.get_logger(__name__)
        self._connection: pika.BlockingConnection | None = None
        self._channel: BlockingChannel | None = None

    # -- lifecycle --

    def start(self) -> None:
        params = pika.ConnectionParameters(
            host=self._settings.rabbitmq_host,
            port=self._settings.rabbitmq_port,
            virtual_host=self._settings.rabbitmq_vhost,
            credentials=pika.PlainCredentials(
                self._settings.rabbitmq_user,
                self._settings.rabbitmq_password,
            ),
            # The knowledge-api side declares the exchange + queue and
            # binds them on app start. We just consume.
            heartbeat=30,
            blocked_connection_timeout=300,
        )
        self._connection = self._connection_factory(params)
        self._channel = self._connection.channel()
        self._channel.basic_qos(prefetch_count=self._settings.prefetch_count)
        self._channel.basic_consume(
            queue=self._settings.queue,
            on_message_callback=self._on_message,
            auto_ack=False,
        )
        self._log.info(
            "consumer.started",
            queue=self._settings.queue,
            prefetch=self._settings.prefetch_count,
        )

    def run_forever(self) -> None:
        assert self._channel is not None, "call start() before run_forever()"
        self._channel.start_consuming()

    def stop(self) -> None:
        with contextlib.suppress(Exception):
            if self._channel and self._channel.is_open:
                self._channel.stop_consuming()
        with contextlib.suppress(Exception):
            if self._connection and self._connection.is_open:
                self._connection.close()

    # -- dispatch --

    def _on_message(
        self,
        channel: BlockingChannel,
        method: Basic.Deliver,
        _properties: BasicProperties,
        body: bytes,
    ) -> None:
        delivery = _Delivery(routing_key=method.routing_key or "", body=body)
        try:
            note = self._parse(delivery)
        except (ValidationError, json.JSONDecodeError, UnicodeDecodeError) as exc:
            # Bad payloads can't get fixed by retrying — ack so the
            # broker doesn't redeliver the same broken message
            # forever. The DLQ workflow in Phase 5-3 routes these to
            # a dead-letter queue instead, once it lands.
            self._log.error(
                "consumer.parse_failed",
                routing_key=delivery.routing_key,
                error=str(exc),
            )
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        try:
            self._handler.handle(delivery.routing_key, note)
        except Exception:
            self._log.exception(
                "consumer.handle_failed",
                routing_key=delivery.routing_key,
                id=note.id,
            )
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        channel.basic_ack(delivery_tag=method.delivery_tag)

    @staticmethod
    def _parse(delivery: _Delivery) -> CapturedNote:
        payload = json.loads(delivery.body.decode("utf-8"))
        return CapturedNote.model_validate(payload)


def silence_pika_warning_logs() -> None:
    """pika emits a noisy WARNING per `basic_qos` round-trip. Demote to INFO."""

    logging.getLogger("pika").setLevel(logging.INFO)
