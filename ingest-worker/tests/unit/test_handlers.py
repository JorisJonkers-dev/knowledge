from __future__ import annotations

from datetime import UTC, datetime

import pytest

from knowledge_worker.handlers import LoggingHandler, RecordingHandler, VaultHandler
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
