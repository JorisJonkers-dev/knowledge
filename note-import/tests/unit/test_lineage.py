"""History preservation: git lineage carried across, never collapsed."""

from __future__ import annotations

from pathlib import Path

from git import Repo

from note_import.lineage import LineageResolver, NotAGitRepo


def test_lineage_returns_full_history_newest_first(
    vault: tuple[Path, object], tmp_path: Path
) -> None:
    root, _ = vault
    resolver = LineageResolver.open(root)
    lineage = resolver.lineage_for("personal/notes.md")
    # The personal note was touched by two commits.
    assert len(lineage) == 2
    # Newest-first ordering (git log default), so the most recent change is
    # the one the import should attribute the current content to.
    assert resolver.resolve("personal/notes.md")[0] == lineage[0]
    assert resolver.head_commit()


def test_lineage_single_edit_file(vault: tuple[Path, object]) -> None:
    root, _ = vault
    resolver = LineageResolver.open(root)
    lineage = resolver.lineage_for("topics/python/notes.md")
    assert len(lineage) == 1


def test_lineage_unrenamed_file_history_carried(tmp_path: Path) -> None:
    """History across edits is carried, not collapsed to the latest commit."""
    root = tmp_path / "v"
    root.mkdir()
    repo = Repo.init(root, initial_branch="main")
    repo.config_writer().set_value("user", "email", "a@b").release()
    repo.config_writer().set_value("user", "name", "t").release()
    f = root / "topics" / "new.md"
    f.parent.mkdir(parents=True)
    f.write_text("# v1\n", encoding="utf-8")
    repo.index.add(["topics/new.md"])
    repo.index.commit("create")
    f.write_text("# v2\n", encoding="utf-8")
    repo.index.add(["topics/new.md"])
    repo.index.commit("edit")

    resolver = LineageResolver.open(root)
    # Two separate edits -> two commits, newest first; not collapsed to one.
    lineage = resolver.lineage_for("topics/new.md")
    assert len(lineage) == 2
    assert resolver.resolve("topics/new.md")[0] == lineage[0]


def test_not_a_git_repo_raises(tmp_path: Path) -> None:
    plain = tmp_path / "plain"
    plain.mkdir()
    try:
        LineageResolver.open(plain)
        raise AssertionError("expected NotAGitRepo")
    except NotAGitRepo:
        pass
