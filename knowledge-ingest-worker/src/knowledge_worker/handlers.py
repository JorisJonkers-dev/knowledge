"""Message handlers.

Phase 5-1 ships a `LoggingHandler` that just records each captured
note + ACKs. Phase 5-2 swaps in a `VaultGitHandler` that writes the
markdown file under the cloned `knowledge-vault` repo, then commits +
pushes. Phase 5-3 wraps that with the LightRAG chunking + embedding
pipeline.

The `Handler` protocol keeps `Consumer` ignorant of which stage we're
in — a test can construct the consumer with a `RecordingHandler` and
assert on the captured notes without spinning up the production
backends.
"""

from __future__ import annotations

from typing import Protocol

import structlog

from knowledge_worker.messages import CapturedNote


class Handler(Protocol):
    def handle(self, routing_key: str, note: CapturedNote) -> None: ...


class LoggingHandler:
    """Default Phase 5-1 handler: emits one structured log per delivery."""

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


class RecordingHandler:
    """Test double — keeps every delivery for later assertion."""

    def __init__(self) -> None:
        self.deliveries: list[tuple[str, CapturedNote]] = []

    def handle(self, routing_key: str, note: CapturedNote) -> None:
        self.deliveries.append((routing_key, note))
