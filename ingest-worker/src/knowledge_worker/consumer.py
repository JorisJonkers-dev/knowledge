"""Blocking pika consumer.

Acks on a clean ``handler.handle`` return.

Parse failures (malformed JSON, schema violations, bad encoding) are
immediately dead-lettered: ``basic_nack(requeue=False)`` routes the
delivery through the DLX that knowledge-api declares on the ingest
queue so the poison payload lands on ``knowledge.ingest.dlq`` and
stays recoverable.

Handler failures (vault write errors, DB errors) are also nacked
without requeue so they flow to the same DLQ rather than vanishing.
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
from knowledge_worker.jobs import IngestionJobService, JobState
from knowledge_worker.messages import CapturedNote
from knowledge_worker.settings import Settings

type ConnectionFactory = Callable[[pika.ConnectionParameters], pika.BlockingConnection]


@dataclass(frozen=True, slots=True)
class _Delivery:
    routing_key: str
    body: bytes


class Consumer:
    """Wraps a pika ``BlockingConnection`` + per-delivery dispatch."""

    def __init__(
        self,
        settings: Settings,
        handler: Handler,
        *,
        connection_factory: ConnectionFactory | None = None,
        job_service: IngestionJobService | None = None,
    ) -> None:
        self._settings = settings
        self._handler = handler
        self._connection_factory = connection_factory or pika.BlockingConnection
        self._job_service = job_service
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
            # Poison payloads cannot be fixed by retrying. Dead-letter
            # them immediately so they land on knowledge.ingest.dlq via
            # the DLX declared by knowledge-api on this queue.
            self._log.error(
                "consumer.parse_failed",
                routing_key=delivery.routing_key,
                error=str(exc),
            )
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        try:
            self._handle(delivery.routing_key, note)
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

    def _handle(self, routing_key: str, note: CapturedNote) -> None:
        """Dispatch one parsed note, optionally through the job store.

        Without a job store this is the legacy write-through path. With
        one, the note is idempotently claimed (dedup'd), each pending
        outbox record is handed to the handler, and the outcome is
        persisted:

        * success  -> ``succeed`` (advances the checkpoint / finalises).
        * failure  -> ``fail`` on the attempt counter; retryable failures
          re-raise so the broker redelivers (driving attempt 2..n), the
          final exhausted attempt dead-letters and is ACK'd here (the
          deadletter row *is* the DLQ — no endless redelivery).
        """
        if self._job_service is None:
            self._handler.handle(routing_key, note)
            return
        batch = self._job_service.begin([note])
        if batch.is_replay:
            self._log.info("consumer.replay_skipped", id=note.id)
            return
        for record in batch.pending_records():
            materialized = CapturedNote.model_validate(record.payload)
            try:
                self._handler.handle(routing_key, materialized)
            except Exception as exc:
                state = self._job_service.fail(batch, record, str(exc))
                if state is JobState.DEADLETTER:
                    self._log.error(
                        "consumer.deadletter",
                        id=note.id,
                        source_id=batch.job.source_id,
                        error=str(exc),
                    )
                    return  # durable deadletter row; ack, do not redeliver
                raise
            self._job_service.succeed(batch, record)


def silence_pika_warning_logs() -> None:
    """pika emits a noisy WARNING per ``basic_qos`` round-trip. Demote to INFO."""

    logging.getLogger("pika").setLevel(logging.INFO)
