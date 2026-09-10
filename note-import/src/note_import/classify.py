"""Classify records as evidence-backed vs legacy-derived.

A record is **evidence-backed** when we actually hold the original evidence
— the archived original bytes — so its provenance is real. A record whose
original evidence is gone is **legacy-derived**: we import the curated
content but do NOT claim provenance for it. The rule is deliberately
conservative: we never invent provenance, so ``provenance_claimed`` is only
ever set to ``True`` when the original content that backs the note exists in
the archive.

The evidence test is an injected callable so tests can simulate "evidence
gone" (e.g. a content hash with no archived original) without a live store.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace
from typing import Protocol

from note_import.model import Classification, ImportRecord, NoteMetadata

_PROVENANCE: Classification = "evidence-backed"
_LEGACY: Classification = "legacy-derived"

#: Given a note's content hash, return True when original evidence is
#: archived (present in the content-addressed store).
HasEvidence = Callable[[str], bool]

_ALWAYS_HAS_EVIDENCE: HasEvidence = lambda digest: True  # noqa: E731 - tiny default


class EvidenceResolver(Protocol):
    """Returns whether original evidence exists for a content hash."""

    def has_evidence(self, content_hash: str) -> bool: ...


class ArchiveEvidenceResolver:
    """Evidence = presence of the content hash in the archive."""

    def __init__(self, has: HasEvidence) -> None:
        self._has = has

    def has_evidence(self, digest: str) -> bool:
        return self._has(digest)


def classify(
    note: NoteMetadata,
    *,
    has_evidence: HasEvidence = _ALWAYS_HAS_EVIDENCE,
) -> tuple[Classification, NoteMetadata]:
    """Return ``(classification, note_with_provenance_flag_set)``.

    ``has_evidence`` defaults to *present*, which matches the normal import
    flow where the pipeline has just archived every original. Callers that
    want to model legacy records pass a resolver that returns False for the
    affected content hashes.
    """
    backed = has_evidence(note.content_hash)
    classification: Classification = _PROVENANCE if backed else _LEGACY
    flagged = replace(note, provenance_claimed=backed)
    return classification, flagged


def classify_record(record: ImportRecord) -> ImportRecord:
    """Re-derive a record's classification from its own provenance flag.

    Used to reconcile a loaded manifest so a hand-constructed record cannot
    claim evidence it does not carry.
    """
    classification: Classification = (
        _PROVENANCE if record.note.provenance_claimed else _LEGACY
    )
    return ImportRecord(
        note=record.note,
        classification=classification,
        archive_path=record.archive_path,
    )


__all__ = [
    "ArchiveEvidenceResolver",
    "EvidenceResolver",
    "classify",
]
