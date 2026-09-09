from __future__ import annotations

from pathlib import Path

import pytest
from git import Actor, GitCommandError, Repo

from basic_memory_git_sync.sync import VaultGitBackstop, reset_vault_dir


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
def vault_dir(tmp_path: Path) -> Path:
    return tmp_path / "vault"


@pytest.fixture()
def backstop(tmp_path: Path, remote: Path, vault_dir: Path) -> VaultGitBackstop:
    b = VaultGitBackstop(
        clone_url=str(remote),
        vault_dir=vault_dir,
        branch="main",
        author=Actor("basic-memory-vault", "basicmemory@test"),
        ssh_key_path=None,
        push=True,
    )
    b.attach()
    return b


@pytest.fixture()
def side(tmp_path: Path, remote: Path) -> tuple[Repo, Path]:
    """A second checkout of `remote` to simulate a remote-only writer."""
    d = tmp_path / "side"
    r = Repo.clone_from(remote, d, branch="main")
    r.config_writer().set_value("user", "email", "side@test").release()
    r.config_writer().set_value("user", "name", "side").release()
    return r, d


def test_attach_clones_when_missing(tmp_path: Path, remote: Path) -> None:
    fresh = tmp_path / "fresh"
    b = VaultGitBackstop(clone_url=str(remote), vault_dir=fresh, push=False)
    b.attach()
    assert (fresh / ".git").exists()


def test_attach_reuses_existing_checkout(tmp_path: Path, remote: Path) -> None:
    v = tmp_path / "v"
    b = VaultGitBackstop(clone_url=str(remote), vault_dir=v, push=False)
    b.attach()
    b.attach()  # no error


def test_poll_commits_nothing_when_clean(backstop: VaultGitBackstop) -> None:
    result = backstop.poll_once()
    assert result.committed is False
    assert result.commit_sha is None


def test_poll_commits_and_pushes_written_note(
    backstop: VaultGitBackstop, tmp_path: Path, vault_dir: Path, remote: Path
) -> None:
    note = vault_dir / "topics" / "example-topic.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text("---\nid: 01MEMORY\n---\n\n# A note written by Basic Memory\n")

    result = backstop.poll_once()

    assert result.committed is True
    assert result.commit_sha

    verify_dir = tmp_path / "verify"
    Repo.clone_from(remote, verify_dir, branch="main")
    assert (verify_dir / "topics" / "example-topic.md").read_text().startswith("---")
    head = next(Repo(verify_dir).iter_commits("main"))
    assert head.message.strip() == "memory: sync vault notes"
    assert head.author.name == "basic-memory-vault"


def test_poll_commits_multiple_changes_in_one_pass(
    backstop: VaultGitBackstop, vault_dir: Path
) -> None:
    (vault_dir / "a.md").write_text("a")
    (vault_dir / "b.md").write_text("b")
    result = backstop.poll_once()
    assert result.committed is True
    files = set(Repo(vault_dir).head.commit.stats.files.keys())
    assert "a.md" in files
    assert "b.md" in files


def test_poll_second_pass_is_clean_after_commit(
    backstop: VaultGitBackstop, vault_dir: Path
) -> None:
    (vault_dir / "a.md").write_text("a")
    assert backstop.poll_once().committed is True
    assert backstop.poll_once().committed is False


def test_pull_incorporates_remote_commits_before_local(
    backstop: VaultGitBackstop,
    tmp_path: Path,
    vault_dir: Path,
    remote: Path,
    side: tuple[Repo, Path],
) -> None:
    side_repo, side_dir = side
    (side_dir / "drive-by.md").write_text("hi")
    side_repo.index.add(["drive-by.md"])
    side_repo.index.commit("drive-by (remote edit)")
    side_repo.remotes.origin.push()

    (vault_dir / "local.md").write_text("local")

    backstop.poll_once()

    verify_dir = tmp_path / "verify"
    Repo.clone_from(remote, verify_dir, branch="main")
    assert (verify_dir / "drive-by.md").exists()
    assert (verify_dir / "local.md").exists()


def test_conflicting_remote_change_surfaces_never_auto_resolves(
    backstop: VaultGitBackstop,
    tmp_path: Path,
    vault_dir: Path,
    remote: Path,
    side: tuple[Repo, Path],
) -> None:
    side_repo, side_dir = side
    side_note = side_dir / "topics" / "conflict.md"
    side_note.parent.mkdir(parents=True, exist_ok=True)
    side_note.write_text("remote version")
    side_repo.index.add(["topics/conflict.md"])
    side_repo.index.commit("remote conflict edit")
    side_repo.remotes.origin.push()

    local_note = vault_dir / "topics" / "conflict.md"
    local_note.parent.mkdir(parents=True, exist_ok=True)
    local_note.write_text("local version")

    with pytest.raises(GitCommandError):
        backstop.poll_once()

    # Local content is retained; remote commit is untouched in origin.
    assert local_note.read_text() == "local version"
    verify_dir = tmp_path / "verify"
    Repo.clone_from(remote, verify_dir, branch="main")
    assert (verify_dir / "topics" / "conflict.md").read_text() == "remote version"


def test_poll_requires_attach(tmp_path: Path, remote: Path) -> None:
    b = VaultGitBackstop(clone_url=str(remote), vault_dir=tmp_path / "x", push=False)
    with pytest.raises(RuntimeError):
        b.poll_once()


def test_reset_vault_dir_recreates(tmp_path: Path) -> None:
    dir_path = tmp_path / "dir"
    dir_path.mkdir()
    (dir_path / "file.txt").write_text("x")
    reset_vault_dir(dir_path)
    assert dir_path.exists() and not (dir_path / "file.txt").exists()
