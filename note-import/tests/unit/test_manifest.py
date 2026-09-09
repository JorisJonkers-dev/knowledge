"""Manifest ledger: the idempotency key (source_id, content_hash)."""

from __future__ import annotations

from pathlib import Path

from note_import.classify import classify, classify_record
from note_import.frontmatter import map_note
from note_import.manifest import Manifest
from note_import.model import ImportRecord, SourceFile


def _record(text: str = "# Note body\n") -> ImportRecord:
    """Build an evidence-backed record via the real classifier (consistent)."""
    note = map_note(SourceFile(relative_path="topics/x.md", bytes=text.encode()))
    classification, flagged = classify(note)
    return ImportRecord(
        note=flagged, classification=classification, archive_path="objects/ab/cd/x.md"
    )


def _record_with_id(source_id: str, body: str) -> ImportRecord:
    src = SourceFile(
        relative_path="topics/x.md",
        bytes=f"---\nid: {source_id}\n---\n\n{body}\n".encode(),
    )
    note = map_note(src)
    classification, flagged = classify(note)
    return ImportRecord(
        note=flagged, classification=classification, archive_path="objects/ab/cd/x.md"
    )


def test_manifest_append_and_contains(tmp_path: Path) -> None:
    m = Manifest(tmp_path / "manifest.jsonl")
    rec = _record()
    assert m.contains(*rec.key) is False
    m.append(rec)
    assert m.contains(*rec.key) is True


def test_manifest_reload_persists_keys(tmp_path: Path) -> None:
    path = tmp_path / "manifest.jsonl"
    rec = _record()
    Manifest(path).append(rec)
    reloaded = Manifest(path)  # fresh instance reads the ledger back
    assert reloaded.contains(*rec.key) is True


def test_manifest_roundtrip_preserves_fields(tmp_path: Path) -> None:
    path = tmp_path / "manifest.jsonl"
    rec = _record()
    Manifest(path).append(rec)
    loaded = list(Manifest(path).records())
    assert len(loaded) == 1
    got = loaded[0]
    assert got.note.source_id == rec.note.source_id
    assert got.note.content_hash == rec.note.content_hash
    assert got.note.relative_path == "topics/x.md"
    assert got.classification == "evidence-backed"
    # The provenance flag is preserved through the ledger, so re-deriving
    # the classification agrees.
    assert classify_record(got).classification == "evidence-backed"


def test_different_content_same_id_is_distinct_key(tmp_path: Path) -> None:
    """Two versions of the same source id are distinct manifest entries."""
    m = Manifest(tmp_path / "manifest.jsonl")
    a = _record_with_id("SAME_ID_001", "# v1")
    b = _record_with_id("SAME_ID_001", "# v2")
    assert a.note.source_id == b.note.source_id == "SAME_ID_001"  # same upstream id
    assert a.key != b.key  # different content hash -> distinct
    m.append(a)
    m.append(b)
    assert len(m.keys()) == 2


def test_manifest_append_is_idempotent_by_key(tmp_path: Path) -> None:
    """Appending the same key twice does not grow the seen-set."""
    m = Manifest(tmp_path / "manifest.jsonl")
    rec = _record()
    m.append(rec)
    m.append(rec)
    assert len(m.keys()) == 1
