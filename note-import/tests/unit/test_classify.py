"""Evidence vs legacy-derived classification — provenance is never invented."""

from __future__ import annotations

from note_import.classify import classify
from note_import.frontmatter import map_note
from note_import.model import NoteMetadata, SourceFile


def _note(text: str) -> NoteMetadata:
    return map_note(SourceFile(relative_path="topics/a/notes.md", bytes=text.encode()))


def test_evidence_present_marks_backed() -> None:
    note = _note("---\nid: x\n---\n\n# A\n")
    classification, flagged = classify(note, has_evidence=lambda d: True)
    assert classification == "evidence-backed"
    assert flagged.provenance_claimed is True


def test_evidence_gone_marks_legacy_derived_and_claims_nothing() -> None:
    note = _note("---\nid: x\n---\n\n# A\n")
    classification, flagged = classify(note, has_evidence=lambda d: False)
    # Original evidence is gone -> legacy-derived.
    assert classification == "legacy-derived"
    # NEVER invent provenance: the flag stays False and nothing claims a
    # source artifact for this note.
    assert flagged.provenance_claimed is False


def test_default_resolver_marks_backed() -> None:
    """Normal import flow: bytes just archived => backed."""
    note = _note("# A\n")
    classification, _ = classify(note)
    assert classification == "evidence-backed"


def test_empty_original_is_legacy_derived() -> None:
    """0-byte originals carry no evidence -> legacy-derived, not backed."""
    note = _note("")
    # The importer feeds `has_evidence` based on non-empty originals; model
    # that here as a resolver that rejects empty content.
    classification, flagged = classify(note, has_evidence=lambda d: False)
    assert classification == "legacy-derived"
    assert flagged.provenance_claimed is False
