"""Basic Memory writer via its MCP tool surface (fleet-infra#246 placement)."""

from __future__ import annotations

import contextlib
import re
from dataclasses import dataclass

from knowledge_worker.mcp_transport import McpNotFoundError, McpTransport
from knowledge_worker.messages import CapturedNote

_ID_PREFIX = 12  # enough of the ULID that two captures never collide here
_NON_SLUG_RE = re.compile(r"[^a-z0-9]+")


def _slug(text: str) -> str:
    return _NON_SLUG_RE.sub("-", text.lower()).strip("-")


def note_title(note: CapturedNote) -> str:
    """Deterministic title: same note id always produces the same title,
    so its Basic Memory permalink (and therefore `note_identifier`) is
    stable across replays and revisions."""
    return f"{note.type}-{note.id[:_ID_PREFIX]}"


def note_identifier(note: CapturedNote, *, folder: str) -> str:
    """The permalink `edit_note`/`delete_note` address this note by."""
    return f"{folder}/{_slug(note_title(note))}"


def render_body(note: CapturedNote) -> str:
    """Frontmatter + body, mirroring the vault writer's shape so a promoted
    note carries the same provenance fields regardless of which downstream
    target produced it."""
    lines = [
        f"id: {note.id}",
        f"type: {note.type}",
        f"scope: {note.scope}",
        f"source: {note.source}",
        f"captured_at: {note.captured_at.isoformat()}",
    ]
    if note.session_id:
        lines.append(f"session_id: {note.session_id}")
    lines.append(f"confidence: {note.confidence}")
    return "\n".join(lines) + "\n\n" + note.body


@dataclass(frozen=True, slots=True)
class BasicMemoryWriteResult:
    identifier: str
    created: bool  # True: write_note (new); False: edit_note (revision)


class BasicMemoryClient:
    """Upserts/deletes captured notes via a Basic Memory `McpTransport`."""

    def __init__(self, transport: McpTransport, *, project: str, folder: str) -> None:
        self._transport = transport
        self._project = project
        self._folder = folder

    def upsert(self, note: CapturedNote) -> BasicMemoryWriteResult:
        identifier = note_identifier(note, folder=self._folder)
        content = render_body(note)
        try:
            self._transport.call_tool(
                "edit_note",
                {
                    "project": self._project,
                    "identifier": identifier,
                    "operation": "replace",
                    "content": content,
                },
            )
            return BasicMemoryWriteResult(identifier=identifier, created=False)
        except McpNotFoundError:
            self._transport.call_tool(
                "write_note",
                {
                    "project": self._project,
                    "title": note_title(note),
                    "folder": self._folder,
                    "content": content,
                    "tags": list(note.tags),
                },
            )
            return BasicMemoryWriteResult(identifier=identifier, created=True)

    def delete(self, note: CapturedNote) -> None:
        # Not yet dispatched by Consumer — no deletion message on the queue (fleet-infra#246).
        identifier = note_identifier(note, folder=self._folder)
        # Already gone — a replayed tombstone is a no-op, not a failure.
        with contextlib.suppress(McpNotFoundError):
            self._transport.call_tool(
                "delete_note", {"project": self._project, "identifier": identifier}
            )


__all__ = ["BasicMemoryClient", "BasicMemoryWriteResult", "note_identifier", "note_title"]
