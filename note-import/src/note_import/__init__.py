"""knowledge-note-import.

Standalone CLI + library that imports curated notes from a source git vault
into the Basic Memory note format. This is *preparation* tooling: it walks,
maps, classifies, dedupes and archives. It never writes to a live Basic
Memory server, Garage or Postgres store — the manifest it emits is the
consumer contract for a later (live) sync step.
"""

from note_import.archive import ContentAddressedArchive
from note_import.classify import classify
from note_import.importer import ImportRunner
from note_import.manifest import Manifest
from note_import.model import ImportRecord, NoteMetadata, SourceFile
from note_import.walk import walk_vault

__all__ = [
    "ContentAddressedArchive",
    "ImportRecord",
    "ImportRunner",
    "Manifest",
    "NoteMetadata",
    "SourceFile",
    "classify",
    "walk_vault",
]
