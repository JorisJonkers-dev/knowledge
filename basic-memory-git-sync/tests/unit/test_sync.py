from __future__ import annotations

from pathlib import Path

import pytest
from git import Actor, GitCommandError, Repo

from basic_memory_git_sync.settings import Settings
from basic_memory_git_sync.sync import VaultGitBackstop, run_forever


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


def test_attach_adopts_existing_non_git_dir(tmp_path: Path, remote: Path) -> None:
    """First boot on a shared PVC root that is non-empty but not a git repo.

    Reproduces the production failure: another container (or the vault-agent
    secrets mount) has already written into the vault PVC root, so
    ``clone_from`` would refuse per "destination path exists and is not an
    empty directory". attach() must init + fetch into the adopted dir instead
    of failing, without discarding what is already on the volume.
    """
    v = tmp_path / "vault"
    v.mkdir(parents=True)
    (v / "secrets").mkdir()  # e.g. the vault-agent secrets overlay
    b = VaultGitBackstop(clone_url=str(remote), vault_dir=v, push=False)
    b.attach()
    assert (v / ".git").exists()
    assert (v / "secrets").exists()  # not discarded
    # The remote branch was fetched + checked out; the sidecar can now push writes.
    assert b._repo.active_branch.name == "main"
    assert b._repo.remotes.origin.url == str(remote)


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


def _settings(tmp_path: Path, remote: Path, **overrides: object) -> Settings:
    base: dict[str, object] = {
        "clone_url": str(remote),
        "vault_dir": str(tmp_path / "vault"),
        "branch": "main",
        "ssh_key_path": "",
        "author_name": "basic-memory-vault",
        "author_email": "basicmemory@test",
        "poll_seconds": 1,
        "push": False,
        "log_level": "INFO",
        "service_version": "test",
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def test_run_forever_loop_commits_then_hits_ceiling(
    tmp_path: Path, remote: Path, vault_dir: Path
) -> None:
    """A bounded run of the real production loop commits what Basic Memory
    writes in one cycle and returns once the iteration ceiling is reached, so
    the loop body (attach → poll → commit) is proven rather than assumed."""
    # The shared tree already exists (the same PVC clone from a prior boot);
    # attach() must reattach, not re-clone into a non-empty dir.
    Repo.clone_from(remote, vault_dir, branch="main")
    note = vault_dir / "topics" / "via-loop.md"
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text("# written by Basic Memory")

    settings = _settings(tmp_path, remote, push="true")
    run_forever(settings, max_iterations=1, sleep_fn=lambda _s: None)

    verify_dir = tmp_path / "verify"
    Repo.clone_from(remote, verify_dir, branch="main")
    head = next(Repo(verify_dir).iter_commits("main"))
    assert head.message.strip() == "memory: sync vault notes"
    assert (verify_dir / "topics" / "via-loop.md").read_text().startswith("# written by")


def test_run_forever_halts_on_conflict_surfaces_it(
    tmp_path: Path, remote: Path, vault_dir: Path, side: tuple[Repo, Path]
) -> None:
    """A remote change that conflicts with uncommitted local work halts the
    loop (logs loudly, returns) and retains the local edit — it does not
    auto-resolve the conflict or drop either side."""
    Repo.clone_from(remote, vault_dir, branch="main")
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

    settings = _settings(tmp_path, remote, push="true")
    # The loop halts (returns) rather than raising; nothing is dropped.
    run_forever(settings, max_iterations=3, sleep_fn=lambda _s: None)

    # Local edit survives on disk.
    assert (vault_dir / "topics" / "conflict.md").read_text() == "local version"
    # And origin still has the remote side, unchanged (no force-push).
    verify_dir = tmp_path / "verify"
    Repo.clone_from(remote, verify_dir, branch="main")
    assert (verify_dir / "topics" / "conflict.md").read_text() == "remote version"


def test_settings_from_env_defaults(tmp_path: Path) -> None:
    s = Settings.from_env({"VAULT_DIR": str(tmp_path)})
    assert s.vault_dir == str(tmp_path)
    assert s.branch == "main"
    assert s.push is False
    assert s.poll_seconds == 10
