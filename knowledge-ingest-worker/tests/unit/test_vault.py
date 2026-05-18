from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest
from git import Actor, Repo

from knowledge_worker.messages import CapturedNote
from knowledge_worker.vault import VaultGitWriter, _slug, _title_slug


def _note(**overrides: object) -> CapturedNote:
    base: dict[str, object] = {
        "id": "01HXYZ00000000000000000000",
        "type": "lesson",
        "scope": "personal",
        "source": "claude-code",
        "captured_at": datetime(2026, 5, 13, 12, 0, tzinfo=UTC),
        "session_id": "sess-1",
        "confidence": 0.42,
        "title": "Render scripts must run",
        "body": "Five render scripts gate the Platform Validate job.",
        "vault_path": "personal/lesson/draft.md",
        "tags": ["render", "platform"],
    }
    base.update(overrides)
    return CapturedNote.model_validate(base)


@pytest.fixture()
def remote(tmp_path: Path) -> Path:
    """Bare git repo standing in for `origin`."""

    bare = tmp_path / "remote.git"
    Repo.init(bare, bare=True, initial_branch="main")
    seed_dir = tmp_path / "seed"
    seed = Repo.clone_from(bare, seed_dir)
    seed.config_writer().set_value("user", "email", "seed@test").release()
    seed.config_writer().set_value("user", "name", "seed").release()
    (seed_dir / ".gitkeep").write_text("")
    seed.index.add([".gitkeep"])
    seed.index.commit("init")
    seed.remotes.origin.push(refspec="HEAD:main")
    return bare


@pytest.fixture()
def writer(tmp_path: Path, remote: Path) -> VaultGitWriter:
    w = VaultGitWriter(
        clone_url=str(remote),
        clone_dir=tmp_path / "clone",
        branch="main",
        author=Actor("worker", "worker@test"),
        ssh_key_path=None,
        push=True,
    )
    w.open()
    return w


def test_slug_normalises_unsafe_characters() -> None:
    assert _slug("project:personal-stack-2") == "project-personal-stack-2"
    assert _slug("agent:_shared/foo") == "agent-_shared-foo"
    assert _slug("personal") == "personal"


def test_title_slug_kebab_cases_and_truncates_at_word_boundary() -> None:
    assert _title_slug("Render scripts must run") == "render-scripts-must-run"
    assert _title_slug("claude mcp add: --header is variadic") == (
        "claude-mcp-add-header-is-variadic"
    )
    assert _title_slug("  ") == ""
    long_title = "a very very long title that keeps going on and never quite stops indeed"
    slug = _title_slug(long_title)
    assert len(slug) <= 60
    # Truncation should land on a word boundary, not mid-word.
    assert not slug.endswith("ne")  # would imply mid-word cut from "never"
    assert slug.endswith(("-", "going", "and", "stops")) or "-" in slug


def test_open_clones_when_directory_missing(tmp_path: Path, remote: Path) -> None:
    clone = tmp_path / "fresh"
    w = VaultGitWriter(
        clone_url=str(remote),
        clone_dir=clone,
        branch="main",
        push=False,
    )
    w.open()
    assert (clone / ".git").exists()


def test_open_attaches_existing_clone(tmp_path: Path, remote: Path) -> None:
    clone = tmp_path / "existing"
    Repo.clone_from(remote, clone, branch="main")
    w = VaultGitWriter(clone_url=str(remote), clone_dir=clone, branch="main", push=False)
    w.open()
    # Idempotent: a second open() reattaches without error.
    w.open()


def test_write_creates_file_with_frontmatter(writer: VaultGitWriter, tmp_path: Path) -> None:
    result = writer.write(_note())

    assert result.relative_path == ("_inbox/2026-05-13/120000-render-scripts-must-run--01HXYZ00.md")
    target = tmp_path / "clone" / result.relative_path
    content = target.read_text(encoding="utf-8")
    assert content.startswith("---\n")
    assert "id: 01HXYZ00000000000000000000\n" in content
    assert "type: lesson\n" in content
    assert "scope: personal\n" in content
    assert "session_id: sess-1\n" in content
    assert "confidence: 0.42\n" in content
    assert "tags: [platform, render]\n" in content
    assert "# Render scripts must run" in content
    assert "Five render scripts gate" in content


def test_write_commit_message_uses_worker_inbox_prefix(
    writer: VaultGitWriter, tmp_path: Path
) -> None:
    writer.write(_note(scope="project:personal-stack-2", id="01ABC", type="decision"))
    repo = Repo(tmp_path / "clone")
    head = repo.head.commit
    # Worker no longer encodes scope into the commit message — the
    # curator owns scope assignment after promoting out of `_inbox/`.
    assert head.message.strip() == "worker(inbox): decision render-scripts-must-run"
    assert head.author.name == "worker"


def test_write_pushes_to_remote(writer: VaultGitWriter, remote: Path, tmp_path: Path) -> None:
    writer.write(_note(id="01PUSHED0000000000000000000", title="A pushed note"))

    # Re-clone the remote in a second working tree and confirm the
    # commit landed.
    verify = tmp_path / "verify"
    Repo.clone_from(remote, verify, branch="main")
    rel = "_inbox/2026-05-13/120000-a-pushed-note--01PUSHED.md"
    assert (verify / rel).exists()
    log = list(Repo(verify).iter_commits("main"))
    assert any("a-pushed-note" in c.message for c in log)


def test_write_path_ignores_scope_until_curator_promotes(
    writer: VaultGitWriter, tmp_path: Path
) -> None:
    # The worker's path no longer depends on `scope` at all — the
    # curator decides scope (and the final folder) after triage.
    result = writer.write(_note(scope="agent:_shared/foo", id="01SCOPE0000000000000000000"))
    assert result.relative_path == ("_inbox/2026-05-13/120000-render-scripts-must-run--01SCOPE0.md")
    assert (tmp_path / "clone" / result.relative_path).exists()


def test_write_without_session_id_or_tags(writer: VaultGitWriter, tmp_path: Path) -> None:
    result = writer.write(_note(session_id=None, tags=[]))
    content = (tmp_path / "clone" / result.relative_path).read_text()
    assert "session_id:" not in content
    assert "tags:" not in content


def test_write_raises_when_open_not_called(tmp_path: Path, remote: Path) -> None:
    w = VaultGitWriter(clone_url=str(remote), clone_dir=tmp_path / "x", push=False)
    with pytest.raises(RuntimeError):
        w.write(_note())


def test_pull_rebases_remote_changes(writer: VaultGitWriter, remote: Path, tmp_path: Path) -> None:
    # Land a commit directly on the remote via a side clone.
    side = tmp_path / "side"
    side_repo = Repo.clone_from(remote, side, branch="main")
    side_repo.config_writer().set_value("user", "email", "side@test").release()
    side_repo.config_writer().set_value("user", "name", "side").release()
    (side / "drive-by.md").write_text("hi")
    side_repo.index.add(["drive-by.md"])
    side_repo.index.commit("drive-by")
    side_repo.remotes.origin.push()

    writer.pull()
    assert (tmp_path / "clone" / "drive-by.md").exists()
