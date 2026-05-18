from __future__ import annotations

from datetime import UTC, datetime

from knowledge_worker.handlers import LoggingHandler, RecordingHandler, VaultHandler
from knowledge_worker.messages import CapturedNote
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


def test_vault_handler_delegates_to_writer() -> None:
    stub = _StubWriter()
    VaultHandler(stub).handle("knowledge.lesson", _note(id="01H"))
    assert [n.id for n in stub.calls] == ["01H"]


def test_vault_handler_propagates_writer_failure() -> None:
    class _Boom:
        def write(self, _note: CapturedNote) -> VaultWriteResult:
            raise RuntimeError("push failed")

    import pytest

    with pytest.raises(RuntimeError):
        VaultHandler(_Boom()).handle("knowledge.lesson", _note())


def test_recording_handler_stores_deliveries() -> None:
    handler = RecordingHandler()
    handler.handle("knowledge.lesson", _note(id="01A"))
    handler.handle("knowledge.decision", _note(id="01B"))
    assert [d[0] for d in handler.deliveries] == ["knowledge.lesson", "knowledge.decision"]
    assert [d[1].id for d in handler.deliveries] == ["01A", "01B"]
