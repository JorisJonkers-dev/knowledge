from __future__ import annotations

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from knowledge_worker.messages import CapturedNote


def _payload(**overrides: object) -> dict[str, object]:
    base: dict[str, object] = {
        "id": "01HXYZ00000000000000000000",
        "type": "lesson",
        "scope": "personal",
        "source": "claude-code",
        "captured_at": "2026-05-13T12:00:00Z",
        "confidence": 0.4,
        "title": "title",
        "body": "body",
        "vault_path": "personal/lesson/draft.md",
        "tags": ["kotlin", "mcp"],
    }
    base.update(overrides)
    return base


def test_parses_complete_payload() -> None:
    note = CapturedNote.model_validate(_payload())
    assert note.id == "01HXYZ00000000000000000000"
    assert note.captured_at == datetime(2026, 5, 13, 12, 0, tzinfo=UTC)
    assert note.tags == ["kotlin", "mcp"]
    assert note.session_id is None


def test_session_id_is_optional() -> None:
    note = CapturedNote.model_validate(_payload(session_id="abc-123"))
    assert note.session_id == "abc-123"


def test_extra_keys_are_preserved_in_extra_property() -> None:
    note = CapturedNote.model_validate(_payload(future_field="not modelled yet"))
    assert note.extra == {"future_field": "not modelled yet"}


def test_missing_required_field_raises() -> None:
    payload = _payload()
    del payload["id"]
    with pytest.raises(ValidationError):
        CapturedNote.model_validate(payload)


def test_wrong_type_for_confidence_raises() -> None:
    with pytest.raises(ValidationError):
        CapturedNote.model_validate(_payload(confidence="not-a-number"))
