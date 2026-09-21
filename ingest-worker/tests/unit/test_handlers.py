from __future__ import annotations

from datetime import UTC, datetime

import pytest

from knowledge_worker.basic_memory_client import BasicMemoryWriteResult
from knowledge_worker.handlers import (
    BasicMemoryHandler,
    HindsightHandler,
    LoggingHandler,
    MultiHandler,
    RecordingHandler,
    VaultHandler,
)
from knowledge_worker.hindsight_client import HindsightWriteResult
from knowledge_worker.messages import CapturedNote
from knowledge_worker.store import NullNoteStore
from knowledge_worker.vault import VaultWriteResult


def _note(**overrides: object) -> CapturedNote:
    base: dict[str, object] = {
        "id": "01HXYZ00000000000000000000",
        "type": "lesson",
        "scope": "personal",
        "source": "claude-code",
        "captured_at": datetime(2026, 5, 13, 12, 0, tzinfo=UTC),
        "confidence": 0.4,
        "title": "title",
        "body": "body",
        "vault_path": "personal/lesson/draft.md",
        "tags": [],
    }
    base.update(overrides)
    return CapturedNote.model_validate(base)


def test_logging_handler_returns_without_raising() -> None:
    LoggingHandler().handle("knowledge.lesson", _note())


class _StubWriter:
    def __init__(self) -> None:
        self.calls: list[CapturedNote] = []

    def write(self, note: CapturedNote) -> VaultWriteResult:
        self.calls.append(note)
        return VaultWriteResult(
            relative_path=f"notes/{note.scope}/{note.type}/{note.id}.md",
            commit_sha="a" * 40,
        )


class _RecordingStore:
    def __init__(self, affected: int = 1) -> None:
        self.updates: list[tuple[str, str, str]] = []
        self._affected = affected

    def update_vault_pointer(self, note_id: str, vault_path: str, vault_commit: str) -> int:
        self.updates.append((note_id, vault_path, vault_commit))
        return self._affected


def test_vault_handler_delegates_to_writer_and_updates_store() -> None:
    stub = _StubWriter()
    store = _RecordingStore()
    VaultHandler(stub, store).handle("knowledge.lesson", _note(id="01H"))
    assert [n.id for n in stub.calls] == ["01H"]
    assert store.updates == [("01H", "notes/personal/lesson/01H.md", "a" * 40)]


def test_vault_handler_logs_orphan_when_no_row_updated() -> None:
    # Zero-row updates fall through (logged as orphan) — no exception.
    VaultHandler(_StubWriter(), _RecordingStore(affected=0)).handle("knowledge.lesson", _note())


def test_vault_handler_propagates_writer_failure() -> None:
    class _Boom:
        def write(self, _note: CapturedNote) -> VaultWriteResult:
            raise RuntimeError("push failed")

    with pytest.raises(RuntimeError):
        VaultHandler(_Boom(), NullNoteStore()).handle("knowledge.lesson", _note())


def test_vault_handler_propagates_store_failure() -> None:
    class _DbDown:
        def update_vault_pointer(self, *_args: object) -> int:
            raise RuntimeError("db down")

    with pytest.raises(RuntimeError):
        VaultHandler(_StubWriter(), _DbDown()).handle("knowledge.lesson", _note())


def test_recording_handler_stores_deliveries() -> None:
    handler = RecordingHandler()
    handler.handle("knowledge.lesson", _note(id="01A"))
    handler.handle("knowledge.decision", _note(id="01B"))
    assert [d[0] for d in handler.deliveries] == ["knowledge.lesson", "knowledge.decision"]
    assert [d[1].id for d in handler.deliveries] == ["01A", "01B"]


class _StubHindsightClient:
    def __init__(self) -> None:
        self.retained: list[CapturedNote] = []

    def retain(self, note: CapturedNote) -> HindsightWriteResult:
        self.retained.append(note)
        return HindsightWriteResult(bank="personal", source_id=note.id, memory_id="mem-1")


def test_hindsight_handler_delegates_to_client() -> None:
    client = _StubHindsightClient()
    HindsightHandler(client).handle("knowledge.lesson", _note(id="01H"))
    assert [n.id for n in client.retained] == ["01H"]


def test_hindsight_handler_propagates_client_failure() -> None:
    class _Boom:
        def retain(self, _note: CapturedNote) -> HindsightWriteResult:
            raise RuntimeError("hindsight down")

    with pytest.raises(RuntimeError):
        HindsightHandler(_Boom()).handle("knowledge.lesson", _note())


class _StubBasicMemoryClient:
    def __init__(self) -> None:
        self.upserted: list[CapturedNote] = []

    def upsert(self, note: CapturedNote) -> BasicMemoryWriteResult:
        self.upserted.append(note)
        return BasicMemoryWriteResult(identifier="_inbox/x", created=True)


def test_basic_memory_handler_delegates_to_client() -> None:
    client = _StubBasicMemoryClient()
    BasicMemoryHandler(client).handle("knowledge.lesson", _note(id="01B"))
    assert [n.id for n in client.upserted] == ["01B"]


def test_basic_memory_handler_propagates_client_failure() -> None:
    class _Boom:
        def upsert(self, _note: CapturedNote) -> BasicMemoryWriteResult:
            raise RuntimeError("basic memory down")

    with pytest.raises(RuntimeError):
        BasicMemoryHandler(_Boom()).handle("knowledge.lesson", _note())


def test_multi_handler_requires_at_least_one_handler() -> None:
    with pytest.raises(ValueError):
        MultiHandler([])


def test_multi_handler_fans_out_to_every_handler() -> None:
    a, b = RecordingHandler(), RecordingHandler()
    MultiHandler([a, b]).handle("knowledge.lesson", _note(id="01M"))
    assert [d[1].id for d in a.deliveries] == ["01M"]
    assert [d[1].id for d in b.deliveries] == ["01M"]


def test_multi_handler_still_calls_every_handler_after_one_fails() -> None:
    """One unreachable downstream must not skip the others."""

    class _Boom:
        def handle(self, _routing_key: str, _note: CapturedNote) -> None:
            raise RuntimeError("boom")

    survivor = RecordingHandler()
    with pytest.raises(ExceptionGroup):
        MultiHandler([_Boom(), survivor]).handle("knowledge.lesson", _note(id="01M"))
    assert [d[1].id for d in survivor.deliveries] == ["01M"]


def test_multi_handler_raises_nothing_when_every_handler_succeeds() -> None:
    MultiHandler([RecordingHandler()]).handle("knowledge.lesson", _note())
