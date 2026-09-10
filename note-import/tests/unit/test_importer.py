"""End-to-end importer tests: count reconciliation, structure, idempotency,
history preservation, and legacy-derived marking, all against temp dirs."""

from __future__ import annotations

from pathlib import Path

from note_import.archive import ContentAddressedArchive
from note_import.importer import ImportRunner
from note_import.lineage import LineageResolver
from note_import.manifest import Manifest


def _runner(tmp_path: Path, vault_root: Path) -> ImportRunner:
    archive = ContentAddressedArchive(tmp_path / "store")
    archive.open()
    manifest = Manifest(tmp_path / "manifest.jsonl")
    return ImportRunner(
        vault_root=vault_root,
        archive=archive,
        manifest=manifest,
        lineage=LineageResolver.open(vault_root),
    )


def test_count_reconciliation(vault: tuple[Path, object], tmp_path: Path) -> None:
    """walked == new on first run, and walked == new + skipped thereafter."""
    root, _ = vault
    runner = _runner(tmp_path, root)
    first = runner.run()
    assert first.walked == 9
    assert first.new == 9
    assert first.skipped == 0

    second = runner.run()  # idempotent re-run
    assert second.walked == 9
    assert second.new == 0
    assert second.skipped == 9

    archive = ContentAddressedArchive(tmp_path / "store")
    stored = len(list(archive._root.rglob("*.md")))
    assert stored == 9  # no duplicate objects on re-run


def test_no_duplicate_notes_on_rerun(vault: tuple[Path, object], tmp_path: Path) -> None:
    root, _ = vault
    runner = _runner(tmp_path, root)
    runner.run()
    manifest = Manifest(tmp_path / "manifest.jsonl")
    keys = manifest.keys()
    assert len(keys) == 9
    # Re-running with a fresh runner against the same manifest emits nothing new.
    fresh = ImportRunner(
        vault_root=root,
        archive=ContentAddressedArchive(tmp_path / "store"),
        manifest=manifest,
    )
    summary = fresh.run()
    assert summary.new == 0
    assert summary.skipped == 9


def test_structure_preserved_in_records(
    vault: tuple[Path, object], tmp_path: Path
) -> None:
    root, _ = vault
    runner = _runner(tmp_path, root)
    runner.run()
    manifest = Manifest(tmp_path / "manifest.jsonl")
    records = {r.note.relative_path: r for r in manifest.records()}
    # Every top-level category survives as its own directory — never flattened.
    assert set(records) == {
        "personal/notes.md",
        "work/notes.md",
        "projects/acme/notes.md",
        "topics/python/notes.md",
        "agents/_shared/notes.md",
        "public/notes.md",
        "_inbox/2026-06-18/capture.md",
        "_index/topics.md",
        "stub.md",
    }
    assert records["personal/notes.md"].note.directory == "personal"
    assert records["_inbox/2026-06-18/capture.md"].note.directory == "_inbox"
    assert records["topics/python/notes.md"].note.directory == "topics"


def test_history_preserved_in_records(vault: tuple[Path, object], tmp_path: Path) -> None:
    root, _ = vault
    runner = _runner(tmp_path, root)
    runner.run()
    manifest = Manifest(tmp_path / "manifest.jsonl")
    records = {r.note.relative_path: r for r in manifest.records()}
    # personal/notes.md was edited twice -> 2 commits of lineage carried over.
    personal = records["personal/notes.md"].note
    assert len(personal.git_lineage) == 2
    assert personal.git_commit == personal.git_lineage[0]  # newest-first
    # single-edit file -> 1 commit.
    assert len(records["topics/python/notes.md"].note.git_lineage) == 1


def test_legacy_derived_marking_for_empty_original(
    vault: tuple[Path, object], tmp_path: Path
) -> None:
    root, _ = vault
    runner = _runner(tmp_path, root)
    summary = runner.run()
    # The 0-byte stub has no original evidence -> legacy-derived.
    assert summary.legacy_derived == 1
    assert summary.evidence_backed == 8

    manifest = Manifest(tmp_path / "manifest.jsonl")
    records = {r.note.relative_path: r for r in manifest.records()}
    stub = records["stub.md"]
    assert stub.classification == "legacy-derived"
    assert stub.note.provenance_claimed is False  # never invent provenance
    # A real note is evidence-backed.
    assert records["personal/notes.md"].classification == "evidence-backed"
    assert records["personal/notes.md"].note.provenance_claimed is True


def test_non_git_vault_still_imports(tmp_path: Path) -> None:
    """Works pointed at a plain directory (lineage simply empty)."""
    root = tmp_path / "plain"
    (root / "topics").mkdir(parents=True)
    (root / "topics" / "a.md").write_text("# A\n", encoding="utf-8")
    runner = ImportRunner(
        vault_root=root,
        archive=ContentAddressedArchive(tmp_path / "store"),
        manifest=Manifest(tmp_path / "manifest.jsonl"),
    )
    summary = runner.run()
    assert summary.new == 1
    manifest = Manifest(tmp_path / "manifest.jsonl")
    rec = next(iter(manifest.records()))
    assert rec.note.git_lineage == ()
    assert rec.note.git_commit == ""
