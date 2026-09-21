from __future__ import annotations

from datetime import UTC, datetime

from knowledge_worker.basic_memory_client import (
    BasicMemoryClient,
    note_identifier,
    note_title,
)
from knowledge_worker.mcp_transport import McpNotFoundError
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
        "body": "the body",
        "vault_path": "personal/lesson/draft.md",
        "tags": ["k8s"],
    }
    base.update(overrides)
    return CapturedNote.model_validate(base)


def test_note_title_is_deterministic_from_type_and_id_prefix() -> None:
    note = _note(id="01HXYZ00000000000000000000")
    assert note_title(note) == "lesson-01HXYZ000000"
    # Same id always produces the same title (stable permalink across replays).
    assert note_title(note) == note_title(_note(id="01HXYZ00000000000000000000"))


def test_note_identifier_combines_folder_and_slugged_title() -> None:
    note = _note(id="01HXYZ00000000000000000000")
    assert note_identifier(note, folder="_inbox") == "_inbox/lesson-01hxyz000000"


class _RecordingTransport:
    def __init__(self, *, not_found_on: set[str] | None = None) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []
        self._not_found_on = not_found_on or set()

    def call_tool(self, name: str, arguments: dict[str, object]) -> dict[str, object]:
        self.calls.append((name, arguments))
        if name in self._not_found_on:
            raise McpNotFoundError("not found")
        return {"content": []}


def test_upsert_edits_in_place_when_the_note_already_exists() -> None:
    transport = _RecordingTransport()
    client = BasicMemoryClient(transport, project="main", folder="_inbox")

    result = client.upsert(_note())

    assert [c[0] for c in transport.calls] == ["edit_note"]
    args = transport.calls[0][1]
    assert args["operation"] == "replace"
    assert args["identifier"] == result.identifier
    assert result.created is False


def test_upsert_falls_back_to_write_note_when_edit_reports_not_found() -> None:
    transport = _RecordingTransport(not_found_on={"edit_note"})
    client = BasicMemoryClient(transport, project="main", folder="_inbox")

    result = client.upsert(_note(tags=["k8s", "vault"]))

    assert [c[0] for c in transport.calls] == ["edit_note", "write_note"]
    write_args = transport.calls[1][1]
    assert write_args["folder"] == "_inbox"
    assert write_args["tags"] == ["k8s", "vault"]
    assert result.created is True


def test_upsert_of_an_existing_note_never_calls_write_note() -> None:
    """Regression guard for the last-writer-wins finding: a revision of an
    existing note must go through edit_note only, never write_note
    (which --overwrite would make last-writer-wins)."""
    transport = _RecordingTransport()
    BasicMemoryClient(transport, project="main", folder="_inbox").upsert(_note())
    assert "write_note" not in [name for name, _ in transport.calls]


def test_delete_is_a_no_op_when_the_note_is_already_gone() -> None:
    transport = _RecordingTransport(not_found_on={"delete_note"})
    # A replayed tombstone against an already-deleted note must not raise.
    BasicMemoryClient(transport, project="main", folder="_inbox").delete(_note())
    identifier = note_identifier(_note(), folder="_inbox")
    assert transport.calls == [("delete_note", {"project": "main", "identifier": identifier})]


def test_render_body_includes_frontmatter_and_original_body() -> None:
    from knowledge_worker.basic_memory_client import render_body

    rendered = render_body(_note(session_id="sess-1"))
    assert "id: 01HXYZ00000000000000000000" in rendered
    assert "session_id: sess-1" in rendered
    assert rendered.endswith("the body")
