"""Core data model for the import pipeline.

The pipeline produces three kinds of objects:

- :class:`SourceFile` — one markdown file discovered by walking the vault
  tree. It is the raw unit the walker yields; the relative path is kept
  verbatim so directory structure is never flattened.
- :class:`NoteMetadata` — the *normalized* note produced by mapping a
  source file's YAML frontmatter into the Basic Memory note format. It
  carries a stable ``source_id``, a ``content_hash``, the preserved
  frontmatter, and git lineage.
- :class:`ImportRecord` — the final record emitted to the manifest: the
  note plus its classification (evidence-backed / legacy-derived) and the
  content-addressed archive path of the original bytes.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

# A record is either backed by archived original evidence, or derived from
# a legacy source whose original evidence is gone. See classify.py for the
# rules. The strings are deliberately the literal values written to the
# manifest so downstream consumers can match on them directly.
Classification = Literal["evidence-backed", "legacy-derived"]


@dataclass(frozen=True, slots=True)
class SourceFile:
    """A markdown file discovered in the source vault.

    ``relative_path`` is the vault-relative POSIX path and is the single
    source of truth for structure — the top-level component
    (``personal`` / ``work`` / ``projects`` / ``topics`` / ``agents`` /
    ``public`` / ``_inbox`` / ``_index`` / ``_discarded`` / …) is derived
    from it, never from flattening.
    """

    relative_path: str
    """Vault-relative POSIX path, e.g. ``topics/python/notes.md``."""

    bytes: bytes
    """Raw file contents (the original bytes to archive)."""

    @property
    def directory(self) -> str:
        """Top-level category (first path component).

        Empty string for a file at the vault root (no ``/``).
        """
        if "/" in self.relative_path:
            return self.relative_path.split("/", 1)[0]
        return ""


@dataclass(frozen=True, slots=True)
class NoteMetadata:
    """A normalized note in the Basic Memory note format.

    ``frontmatter`` preserves the upstream-native key/value mapping and
    ``frontmatter_raw`` the exact YAML block text; ``mapped`` carries the
    canonical Basic Memory field set derived from it. Nothing here invents
    provenance — unprovable fields are simply ``None``.
    """

    source_id: str
    """Stable identifier (upstream `id` when present, else hash-derived)."""

    content_hash: str
    """SHA-256 hex digest of the original byte content. Dedup + archive key."""

    relative_path: str
    """Vault-relative path, preserved across the mapping."""

    directory: str
    """Top-level category derived from ``relative_path``."""

    title: str
    """Note title (from frontmatter ``title`` or the first ``# `` heading)."""

    type: str
    """Basic Memory type (``lesson``/``decision``/``note``/``fact``/…)."""

    tags: tuple[str, ...]
    """Sorted tags, normalized from the upstream native list."""

    frontmatter: dict[str, object]
    """Upstream-native frontmatter as parsed (preserved verbatim)."""

    frontmatter_raw: str
    """The original YAML frontmatter block text (native syntax preserved)."""

    mapped: dict[str, object]
    """Canonical Basic Memory mapping of the frontmatter."""

    git_commit: str
    """Most recent commit SHA that touched this file, or ``""`` if none."""

    git_lineage: tuple[str, ...]
    """Commit SHAs that touched this file, newest-first (history carried)."""

    source_path: str
    """Original source discovery path, or ``""`` when there is none."""

    provenance_claimed: bool
    """True only when provenance is *real* (see classifier). Never guessed."""


@dataclass(frozen=True, slots=True)
class ImportRecord:
    """Final output record: a normalized note + how to treat legacy.

    Written to the manifest as JSON lines. Re-keying on
    ``(source_id, content_hash)`` is what makes a re-run idempotent.
    """

    note: NoteMetadata
    classification: Classification
    archive_path: str
    """Content-addressed location of the archived original bytes."""

    @property
    def key(self) -> tuple[str, str]:
        """Idempotency key: ``(source_id, content_hash)``."""
        return (self.note.source_id, self.note.content_hash)


@dataclass(frozen=True, slots=True)
class Summary:
    """A human + machine readable account of one import run."""

    walked: int = 0
    new: int = 0
    skipped: int = 0
    evidence_backed: int = 0
    legacy_derived: int = 0
    archived: int = 0

    def as_dict(self) -> dict[str, int]:
        """Plain dict for logging/CI assertions."""
        return {
            "walked": self.walked,
            "new": self.new,
            "skipped": self.skipped,
            "evidence_backed": self.evidence_backed,
            "legacy_derived": self.legacy_derived,
            "archived": self.archived,
        }


@dataclass(slots=True)
class RunStats:
    """Mutable accumulator for a single run (not public API surface)."""

    new: int = 0
    skipped: int = 0
    evidence_backed: int = 0
    legacy_derived: int = 0
    archived: int = 0


__all__ = [
    "Classification",
    "ImportRecord",
    "NoteMetadata",
    "RunStats",
    "SourceFile",
    "Summary",
]
