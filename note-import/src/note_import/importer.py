"""High-level import orchestration.

:class:`ImportRunner` ties the pipeline together for one vault root:

1. Walk the tree (structure preserved, never flattened).
2. For each not-yet-imported markdown file: map frontmatter to the Basic
   Memory note format, attach git lineage, archive the original bytes, and
   classify evidence-backed vs legacy-derived.
3. Append the record to the manifest (idempotency). Re-running yields zero
   new records because the manifest is keyed on ``(source_id, content_hash)``.

No live Basic Memory / Garage / Postgres store is touched — the runner only
reads the source vault and writes to the local archive + manifest.
"""

from __future__ import annotations

from pathlib import Path

import structlog

from note_import.archive import ContentAddressedArchive
from note_import.classify import classify
from note_import.frontmatter import map_note
from note_import.lineage import LineageResolver
from note_import.manifest import Manifest
from note_import.model import ImportRecord, NoteMetadata, RunStats, Summary
from note_import.walk import walk_vault

_log = structlog.get_logger(__name__)


class ImportRunner:
    """One-shot importer for a single source vault path."""

    def __init__(
        self,
        *,
        vault_root: Path,
        archive: ContentAddressedArchive,
        manifest: Manifest,
        lineage: LineageResolver | None = None,
    ) -> None:
        self._vault_root = Path(vault_root)
        self._archive = archive
        self._manifest = manifest
        self._lineage = lineage

    def run(self) -> Summary:
        """Import any not-yet-seen notes and return a run :class:`Summary`.

        Idempotent: records already present in the manifest are skipped, so
        calling ``run()`` twice yields the same archive + manifest state and
        a ``new == 0`` second-run summary.
        """
        stats = RunStats()
        sources = walk_vault(self._vault_root)
        for source in sources:
            note = map_note(source)
            key = (note.source_id, note.content_hash)
            if self._manifest.contains(*key):
                stats.skipped += 1
                continue

            git_commit, git_lineage = self._resolve_lineage(note)
            note = NoteMetadata(
                # Rebuild with lineage attached; map_note's fields unchanged.
                source_id=note.source_id,
                content_hash=note.content_hash,
                relative_path=note.relative_path,
                directory=note.directory,
                title=note.title,
                type=note.type,
                tags=note.tags,
                frontmatter=note.frontmatter,
                frontmatter_raw=note.frontmatter_raw,
                mapped=note.mapped,
                git_commit=git_commit,
                git_lineage=git_lineage,
                source_path=note.source_path,
                provenance_claimed=note.provenance_claimed,
            )

            # Archive original bytes first so classification can check whether
            # real evidence is present.
            archive_path = self._archive.put(source.bytes)
            digest = note.content_hash

            # Evidence is real only when the original content is non-empty AND
            # its hash matches what we just archived. Empty originals (0-byte
            # stubs) have no evidence -> legacy-derived. digest/bytes_len are
            # bound as default args, not closed over (ruff B023 flags late
            # binding of loop variables).
            def _has_evidence(
                d: str,
                *,
                _digest: str = digest,
                _bytes_len: int = len(source.bytes),
            ) -> bool:
                return d == _digest and _bytes_len > 0

            classification, note = classify(note, has_evidence=_has_evidence)
            record = ImportRecord(
                note=note,
                classification=classification,
                archive_path=archive_path,
            )
            self._manifest.append(record)

            stats.new += 1
            stats.archived += 1
            if classification == "evidence-backed":
                stats.evidence_backed += 1
            else:
                stats.legacy_derived += 1
            _log.info(
                "note.imported",
                path=note.relative_path,
                source_id=note.source_id,
                classification=classification,
            )

        return Summary(
            walked=len(sources),
            new=stats.new,
            skipped=stats.skipped,
            evidence_backed=stats.evidence_backed,
            legacy_derived=stats.legacy_derived,
            archived=stats.archived,
        )

    def _resolve_lineage(self, note: NoteMetadata) -> tuple[str, tuple[str, ...]]:
        """Best-effort git lineage; never blocks the import on git absence."""
        if self._lineage is None:
            return "", ()
        return self._lineage.resolve(note.relative_path)


__all__ = ["ImportRunner"]
