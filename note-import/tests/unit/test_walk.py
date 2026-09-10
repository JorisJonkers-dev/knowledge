"""Structure preservation: walker keeps the directory tree, never flattens."""

from __future__ import annotations

from pathlib import Path

import pytest

from note_import.model import SourceFile
from note_import.walk import walk_vault


def test_walker_preserves_directory_structure(vault: tuple[Path, object]) -> None:
    root, _ = vault
    files = walk_vault(root)
    rels = {s.relative_path for s in files}
    # Top-level categories all survive verbatim — a flattened importer would
    # collapse these into a single flat bucket.
    assert "personal/notes.md" in rels
    assert "work/notes.md" in rels
    assert "projects/acme/notes.md" in rels
    assert "topics/python/notes.md" in rels
    assert "agents/_shared/notes.md" in rels
    assert "public/notes.md" in rels
    assert "_inbox/2026-06-18/capture.md" in rels
    assert "_index/topics.md" in rels
    assert "stub.md" in rels


def test_walker_yields_source_files_with_bytes_and_directory(vault: tuple[Path, object]) -> None:
    root, _ = vault
    files = walk_vault(root)
    by_path = {s.relative_path: s for s in files}
    note = by_path["personal/notes.md"]
    assert isinstance(note, SourceFile)
    assert note.directory == "personal"
    assert note.bytes  # non-empty for the normal notes
    assert by_path["projects/acme/notes.md"].directory == "projects"
    assert by_path["_inbox/2026-06-18/capture.md"].directory == "_inbox"


def test_walker_count_matches_ml_files_on_disk(
    vault: tuple[Path, object], tmp_path: Path
) -> None:
    root, _ = vault
    expected = 0
    for p in root.rglob("*.md"):
        if p.is_file():
            expected += 1
    assert len(walk_vault(root)) == expected


def test_walker_skips_hidden_and_tool_dirs(tmp_path: Path) -> None:
    root = tmp_path / "v"
    root.mkdir()
    (root / ".obsidian").mkdir(parents=True)
    (root / ".git").mkdir()
    (root / "topics").mkdir()
    (root / "topics" / "real.md").write_text("# real", encoding="utf-8")
    (root / ".obsidian" / "hidden.md").write_text("# hidden", encoding="utf-8")
    (root / ".git" / "config.md").write_text("# not a note", encoding="utf-8")
    rels = {s.relative_path for s in walk_vault(root)}
    assert rels == {"topics/real.md"}


def test_walker_raises_when_root_missing(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        walk_vault(tmp_path / "does-not-exist")
