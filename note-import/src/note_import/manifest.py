"""Manifest ledger — the idempotency key.

A run appends one JSON line per imported record. Re-running the importer
reads this ledger back and skips any record whose ``(source_id,
content_hash)`` key is already present, so a re-run never produces duplicate
notes. The manifest is append-only; the ledger of a prior run is treated as
authoritative for what has *already* been seen.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from datetime import date, datetime
from pathlib import Path
from typing import cast

from note_import.model import Classification, ImportRecord, NoteMetadata


class Manifest:
    """A JSONL manifest at ``path`` backed by a set of seen keys."""

    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._seen: set[tuple[str, str]] = set()
        if self._path.exists():
            self._load()

    # -- reading -- #

    def keys(self) -> set[tuple[str, str]]:
        """All currently-seen ``(source_id, content_hash)`` keys."""
        return set(self._seen)

    def contains(self, source_id: str, content_hash: str) -> bool:
        """True when this record has already been imported."""
        return (source_id, content_hash) in self._seen

    def records(self) -> Iterator[ImportRecord]:
        """Stream every record stored in the manifest currently on disk."""
        if not self._path.exists():
            return
        with self._path.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                yield _record_from_json(json.loads(line))

    # -- writing -- #

    def append(self, record: ImportRecord) -> None:
        """Append one record and mark its key as seen (idempotency guard)."""
        self._path.parent.mkdir(parents=True, exist_ok=True)
        with self._path.open("a", encoding="utf-8") as fh:
            fh.write(_record_to_json(record) + "\n")
        self._seen.add(record.key)

    def reopen(self) -> None:
        """Reload seen keys from disk (e.g. after a second process wrote)."""
        self._seen.clear()
        for record in self.records():
            self._seen.add(record.key)

    def _load(self) -> None:
        for record in self.records():
            self._seen.add(record.key)


def _record_to_json(record: ImportRecord) -> str:
    note = record.note
    payload: dict[str, object] = {
        "schema_version": 1,
        "source_id": note.source_id,
        "content_hash": note.content_hash,
        "relative_path": note.relative_path,
        "directory": note.directory,
        "title": note.title,
        "type": note.type,
        "tags": list(note.tags),
        "frontmatter": note.frontmatter,
        "mapped": note.mapped,
        "git_commit": note.git_commit,
        "git_lineage": list(note.git_lineage),
        "source_path": note.source_path,
        "provenance_claimed": note.provenance_claimed,
        "classification": record.classification,
        "archive_path": record.archive_path,
    }
    return json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        default=_json_default,
    )


def _json_default(obj: object) -> object:
    """Make a JSON payload serializable even when a note's frontmatter holds
    non-primitive values (datetimes, dates, enums) lifted from upstream YAML.
    The manifest is a durable ledger and must never fail to write because a
    note carried a timestamp."""
    if isinstance(obj, datetime):
        return obj.isoformat()
    if isinstance(obj, date):
        return obj.isoformat()
    return str(obj)


def _record_from_json(payload: dict[str, object]) -> ImportRecord:
    tags_raw = payload.get("tags")
    tags: tuple[str, ...] = tuple(str(t) for t in tags_raw) if isinstance(tags_raw, list) else ()
    lineage_raw = payload.get("git_lineage")
    lineage: tuple[str, ...] = (
        tuple(str(s) for s in lineage_raw) if isinstance(lineage_raw, list) else ()
    )
    classification_raw = str(payload.get("classification", "legacy-derived"))
    classification = cast(
        Classification,
        classification_raw
        if classification_raw in ("evidence-backed", "legacy-derived")
        else "legacy-derived",
    )
    note = NoteMetadata(
        source_id=str(payload["source_id"]),
        content_hash=str(payload["content_hash"]),
        relative_path=str(payload["relative_path"]),
        directory=str(payload.get("directory", "")),
        title=str(payload.get("title", "")),
        type=str(payload.get("type", "note")),
        tags=tags,
        frontmatter=_as_dict(payload.get("frontmatter", {})),
        frontmatter_raw="",  # not persisted (recoverable from mapped/archive)
        mapped=_as_dict(payload.get("mapped", {})),
        git_commit=str(payload.get("git_commit", "")),
        git_lineage=lineage,
        source_path=str(payload.get("source_path", "")),
        provenance_claimed=bool(payload.get("provenance_claimed", False)),
    )
    return ImportRecord(
        note=note,
        classification=classification,
        archive_path=str(payload.get("archive_path", "")),
    )


def _as_dict(raw: object) -> dict[str, object]:
    if isinstance(raw, dict):
        return dict(raw)
    return {}


__all__ = ["Manifest"]
