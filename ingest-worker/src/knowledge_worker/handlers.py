"""Message handlers.

The `Handler` protocol keeps `Consumer` ignorant of which storage
backend(s) are wired in. `VaultHandler` is the legacy write path (clone
knowledge-vault, write one markdown file per delivery, commit + push);
`HindsightHandler` and `BasicMemoryHandler` are the new-platform downstream
targets (fleet-infra#246 placement), and `MultiHandler` fans one delivery
out to however many of these are configured.

`LoggingHandler` stays as a no-side-effects default for smoke runs
where no real downstream is reachable; `RecordingHandler` keeps
deliveries in memory for tests.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

import structlog

from knowledge_worker.basic_memory_client import BasicMemoryClient
from knowledge_worker.hindsight_client import HindsightClient
from knowledge_worker.messages import CapturedNote
from knowledge_worker.store import NoteStore
from knowledge_worker.vault import VaultWriter


class Handler(Protocol):
    def handle(self, routing_key: str, note: CapturedNote) -> None: ...


class LoggingHandler:
    """No-side-effects handler — one structured log line per delivery."""

    def __init__(self) -> None:
        self._log = structlog.get_logger(__name__)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        self._log.info(
            "knowledge.captured",
            routing_key=routing_key,
            id=note.id,
            type=note.type,
            scope=note.scope,
            source=note.source,
            tag_count=len(note.tags),
        )


class VaultHandler:
    """Persists each captured note to the knowledge-vault git repo and
    writes the resulting (vault_path, vault_commit) back to `kb_notes`.

    Holds a single open `VaultWriter` for the worker's lifetime and
    delegates per-delivery work to it. The writer raises on push
    failures; the exception propagates so `Consumer` nacks the
    delivery and the broker handles redelivery.

    The `kb_notes` UPDATE is best-effort: a missing row (the api side
    never inserted) logs a warning and ACKs the delivery — re-driving
    won't conjure the row back. DB-level errors do propagate so the
    delivery is nacked and retried.
    """

    def __init__(self, writer: VaultWriter, store: NoteStore) -> None:
        self._writer = writer
        self._store = store
        self._log = structlog.get_logger(__name__)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        result = self._writer.write(note)
        affected = self._store.update_vault_pointer(
            note.id, result.relative_path, result.commit_sha
        )
        if affected == 0:
            # api-side row missing — recall won't surface this note
            # until knowledge-api re-inserts it. Log loud, ACK soft.
            self._log.warning(
                "knowledge.pointer_orphan",
                routing_key=routing_key,
                id=note.id,
                rel=result.relative_path,
            )
        self._log.info(
            "knowledge.persisted",
            routing_key=routing_key,
            id=note.id,
            rel=result.relative_path,
            commit=result.commit_sha[:12],
            rows_updated=affected,
        )


class HindsightHandler:
    """Retains each captured note into Hindsight via its HTTP retain API."""

    def __init__(self, client: HindsightClient) -> None:
        self._client = client
        self._log = structlog.get_logger(__name__)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        result = self._client.retain(note)
        self._log.info(
            "knowledge.hindsight_retained",
            routing_key=routing_key,
            id=note.id,
            bank=result.bank,
            memory_id=result.memory_id,
        )


class BasicMemoryHandler:
    """Upserts each captured note into Basic Memory via its MCP tools."""

    def __init__(self, client: BasicMemoryClient) -> None:
        self._client = client
        self._log = structlog.get_logger(__name__)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        result = self._client.upsert(note)
        self._log.info(
            "knowledge.basic_memory_upserted",
            routing_key=routing_key,
            id=note.id,
            identifier=result.identifier,
            created=result.created,
        )


class MultiHandler:
    """Fans one delivery out to every configured downstream handler.

    Every handler is attempted, even after an earlier one raises, so one
    unreachable downstream doesn't silently skip the others. Any failures
    are re-raised together (as an `ExceptionGroup`) so `Consumer` still
    nacks the whole delivery — a note is never "half retained" across
    Hindsight/Basic Memory without the broker knowing to retry it.
    """

    def __init__(self, handlers: Sequence[Handler]) -> None:
        if not handlers:
            raise ValueError("MultiHandler requires at least one handler")
        self._handlers = list(handlers)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        errors: list[Exception] = []
        for handler in self._handlers:
            try:
                handler.handle(routing_key, note)
            except Exception as exc:  # collected, not swallowed
                errors.append(exc)
        if errors:
            raise ExceptionGroup("multi_handler_failures", errors)


class RecordingHandler:
    """Test double — keeps every delivery for later assertion."""

    def __init__(self) -> None:
        self.deliveries: list[tuple[str, CapturedNote]] = []

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        self.deliveries.append((routing_key, note))
