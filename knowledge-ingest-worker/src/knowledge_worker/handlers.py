"""Message handlers.

The `Handler` protocol keeps `Consumer` ignorant of which storage
backend is wired in. Production runs `VaultHandler` — clone the
knowledge-vault repo, write one markdown file per delivery, commit
+ push. The LightRAG chunking + embedding pipeline will wrap that
handler when it lands.

`LoggingHandler` stays as a no-side-effects default for smoke runs
where no real vault is reachable; `RecordingHandler` keeps
deliveries in memory for tests.
"""

from __future__ import annotations

from typing import Protocol

import structlog

from knowledge_worker.messages import CapturedNote
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
    """Persists each captured note to the knowledge-vault git repo.

    Holds a single open `VaultWriter` for the worker's lifetime and
    delegates per-delivery work to it. The writer raises on push
    failures; we re-raise so `Consumer` nacks the delivery and the
    broker handles redelivery.
    """

    def __init__(self, writer: VaultWriter) -> None:
        self._writer = writer
        self._log = structlog.get_logger(__name__)

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        result = self._writer.write(note)
        self._log.info(
            "knowledge.persisted",
            routing_key=routing_key,
            id=note.id,
            rel=result.relative_path,
            commit=result.commit_sha[:12],
        )


class RecordingHandler:
    """Test double — keeps every delivery for later assertion."""

    def __init__(self) -> None:
        self.deliveries: list[tuple[str, CapturedNote]] = []

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        self.deliveries.append((routing_key, note))
