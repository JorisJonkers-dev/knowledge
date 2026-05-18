from __future__ import annotations

from datetime import UTC, datetime

from knowledge_worker.handlers import LoggingHandler, RecordingHandler
from knowledge_worker.messages import CapturedNote


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


def test_recording_handler_stores_deliveries() -> None:
    handler = RecordingHandler()
    handler.handle("knowledge.lesson", _note(id="01A"))
    handler.handle("knowledge.decision", _note(id="01B"))
    assert [d[0] for d in handler.deliveries] == ["knowledge.lesson", "knowledge.decision"]
    assert [d[1].id for d in handler.deliveries] == ["01A", "01B"]
