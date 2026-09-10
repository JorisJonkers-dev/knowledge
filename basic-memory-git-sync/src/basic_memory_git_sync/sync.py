"""Git commit backstop for the Basic Memory vault.

Basic Memory (AGPL-3.0) writes markdown notes into a shared git working
tree but never runs git itself, so a note written through it would otherwise
stay an uncommitted file and vanish on the next reschedule. This service
closes that gap: it watches the working tree, and when the tree has changes
it commits them with a ``memory(): …`` prefix and pushes to ``origin``.

Concurrency (AC: "two concurrent writes to one note produce a detected
conflict, not a lost edit"):

- The vault is single-writer *by construction*: it lives on a ReadWriteOnce
  PVC and the Basic Memory pod is a single replica, so only one Basic Memory
  process is ever writing at a time. This service is the only git actor.
- Before committing we pull with rebase. If a remote commit touched the same
  file we have uncommitted work in, the rebase surfaces a conflict and the
  loop stops rather than auto-resolving: an edit is never silently dropped.
  The operator (or a later retry after reconciliation) resolves and continues.
- We never force-push. A rejected push means the remote moved underneath us;
  we retain the local commit and stop, preserving both sides.

The commit author is the Basic Memory server's own identity (distinguishable
from the ingest worker's ``worker(inbox):`` and the curator's ``curator():``
commits by the ``memory():`` prefix and author).
"""

from __future__ import annotations

import shutil
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import structlog
from git import Actor, GitCommandError, Repo

from basic_memory_git_sync.settings import Settings

COMMIT_PREFIX = "memory"


@dataclass(frozen=True, slots=True)
class SyncResult:
    committed: bool
    commit_sha: str | None


class VaultBackstop(Protocol):
    def poll_once(self) -> SyncResult: ...
    def close(self) -> None: ...


class VaultGitBackstop:
    """Attaches to the shared working tree and commits+pushes changes on demand.

    ``attach()`` opens or clones the tree; ``poll_once()`` performs one
    pull → stage → commit → push cycle and returns whether anything was
    committed. Constructor params are taken explicitly for testability (a
    temp bare repo in unit tests).
    """

    def __init__(
        self,
        *,
        clone_url: str,
        vault_dir: Path,
        branch: str = "main",
        author: Actor | None = None,
        ssh_key_path: str | None = None,
        push: bool = True,
    ) -> None:
        self._clone_url = clone_url
        self._vault_dir = vault_dir
        self._branch = branch
        self._author = author or Actor("basic-memory-vault", "basicmemory@knowledge.local")
        self._ssh_key_path = ssh_key_path
        self._push = push
        self._log = structlog.get_logger(__name__)
        self._repo: Repo | None = None

    # -- lifecycle --

    def attach(self) -> None:
        """Attach to the existing checkout, or initialise + fetch on first boot.

        On a fresh PVC the directory is usually empty, so we clone. But the
        shared vault PVC can also be created non-empty-but-not-a-repo — e.g.
        when another container (or the vault-agent secrets mount) has already
        touched the mount root — in which case ``clone_from`` would fail with
        "destination path exists and is not an empty directory". We treat
        "exists, not a git repo" as a repo to initialise: ``git init`` can
        adopt an existing directory, then we add the origin and fetch the
        branch. This makes first boot robust to the shared-PVC layout without
        discarding anything already on the volume.
        """
        if self._vault_dir.exists() and (self._vault_dir / ".git").exists():
            self._repo = Repo(self._vault_dir)
            self._log.info("backstop.attached", dir=str(self._vault_dir))
            # GitPython's remote API is unreliable on a partially-initialised
            # repo (remotes["origin"] can raise even when origin is in config),
            # so drive the recovery with `git remote` / `git fetch` commands
            # directly. If origin is absent, (re)add it; then ensure the
            # tracked branch exists and fetch.
            with self._repo.git.custom_environment(**self._git_env()):
                try:
                    self._repo.git.remote("get-url", "origin")
                except GitCommandError:
                    self._log.info("backstop.registering_origin", dir=str(self._vault_dir))
                    self._repo.git.remote("add", "origin", self._clone_url)
                self._repo.git.fetch("origin")
                self._repo.git.fetch("origin", self._branch)
                # A bare Repo.init (or crash) can leave the repo on the git
                # default branch (often `master`) or on no branch (unborn
                # HEAD); adopt the branch we track so polls commit to the
                # right ref.
                try:
                    head = self._repo.git.rev_parse("--abbrev-ref", "HEAD") or ""
                except GitCommandError:
                    head = ""
                if head != self._branch:
                    self._repo.git.checkout("-B", self._branch, f"origin/{self._branch}")
                    self._log.info("backstop.branch_adopted", branch=self._branch)
            return

        self._vault_dir.parent.mkdir(parents=True, exist_ok=True)
        if self._vault_dir.exists() and not (self._vault_dir / ".git").exists():
            # Adopt the existing (non-git) directory instead of clone_from,
            # which refuses a non-empty target. init + remote + fetch mirrors
            # a clone without requiring an empty target.
            repo = Repo.init(self._vault_dir)
            repo.create_remote("origin", self._clone_url)
            with repo.git.custom_environment(**self._git_env()):
                repo.git.fetch("origin", self._branch)
                repo.git.checkout("-B", self._branch, f"origin/{self._branch}")
            self._repo = repo
            self._log.info("backstop.init_adopted", dir=str(self._vault_dir))
            return

        self._repo = Repo.clone_from(
            self._clone_url,
            self._vault_dir,
            branch=self._branch,
            env=self._git_env(),
        )
        self._log.info("backstop.cloned", dir=str(self._vault_dir))

    def close(self) -> None:
        self._repo = None

    # -- the sync cycle --

    def poll_once(self) -> SyncResult:
        """One pull → commit → push cycle. Returns whether a commit landed."""
        repo = self._require_repo()

        self._pull(repo)

        if not repo.is_dirty(untracked_files=True):
            return SyncResult(committed=False, commit_sha=None)

        # `git add -A` (not `index.add(["."])`): git's own -A never stages
        # the .git/ internals, so only real working-tree changes land in the
        # snapshot. GitPython's index.add(["."]) here was staging .git objects
        # (commits of 46 files from a one-file tree, corrupting the checkout).
        repo.git.add(A=True)
        message = self._commit_message()
        commit = repo.index.commit(message, author=self._author, committer=self._author)
        self._log.info(
            "backstop.committed",
            sha=commit.hexsha[:12],
            files=len(commit.stats.files),
            message=message,
        )

        if self._push:
            try:
                with repo.git.custom_environment(**self._git_env()):
                    repo.remotes.origin.push()
            except GitCommandError as exc:
                # The remote moved underneath us. We keep the local commit and
                # stop so nothing is dropped; a later pull will reconcile.
                self._log.error("backstop.push_refused", sha=commit.hexsha[:12], error=str(exc))
                raise

        return SyncResult(committed=True, commit_sha=commit.hexsha)

    def _pull(self, repo: Repo) -> None:
        """Pull with rebase, surfacing (never silently fixing) conflicts.

        A conflict makes ``pull`` raise a ``GitCommandError``; the change
        lands in the index/working tree and the loop stops. No ``--theirs`` /
        ``--ours`` override is applied, so an edit is never auto-dropped.
        """
        try:
            with repo.git.custom_environment(**self._git_env()):
                repo.remotes.origin.pull(rebase=True, autostash=True)
        except GitCommandError as exc:
            self._log.error("backstop.pull_conflict", error=str(exc))
            raise

    # -- internals --

    @staticmethod
    def _commit_message() -> str:
        # Static message: Basic Memory writes are batched into a single commit
        # per poll cycle, not one commit per note (we have no per-note handle,
        # only a changed working tree). The prefix keeps them filterable.
        return f"{COMMIT_PREFIX}: sync vault notes"

    def _git_env(self) -> dict[str, str]:
        env = {"GIT_TERMINAL_PROMPT": "0"}
        if self._ssh_key_path:
            env["GIT_SSH_COMMAND"] = (
                f"ssh -i {self._ssh_key_path} -o IdentitiesOnly=yes "
                f"-o StrictHostKeyChecking=accept-new"
            )
        return env

    def _require_repo(self) -> Repo:
        if self._repo is None:
            raise RuntimeError("VaultGitBackstop.attach() must be called before poll_once()")
        return self._repo


def run_forever(
    settings: Settings,
    *,
    max_iterations: int | None = None,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> None:
    """Poll loop used by the CLI entry point.

    In production the vault_dir is a git checkout on a shared RWO PVC that
    Basic Memory writes into; ``attach()`` reuses it. ``clone_url`` is only
    used to clone on first boot (and in tests, which point it at a temp bare
    repo).

    ``max_iterations`` and ``sleep_fn`` are test seams: a bounded run drives
    the real loop once and checks the commit + halt behaviour without looping
    forever, matching the estate habit of proving the loop rather than
    trusting it.
    """
    log = structlog.get_logger(__name__)
    backstop = VaultGitBackstop(
        clone_url=settings.clone_url,
        vault_dir=Path(settings.vault_dir),
        branch=settings.branch,
        author=Actor(settings.author_name, settings.author_email),
        ssh_key_path=settings.ssh_key_path,
        push=settings.push,
    )
    log.info("backstop.boot", version=settings.service_version, dir=settings.vault_dir)
    backstop.attach()
    iterations = 0
    try:
        while max_iterations is None or iterations < max_iterations:
            iterations += 1
            try:
                backstop.poll_once()
            except GitCommandError as exc:
                # A pull conflict or refused push stops the loop so the human
                # (or a retry after reconciling) resolves it; we must not
                # auto-resolve and drop an edit. Bounded wait before exiting.
                log.error("backstop.halted", error=str(exc))
                sleep_fn(10)
                break
            sleep_fn(settings.poll_seconds)
    finally:
        backstop.close()


def reset_vault_dir(path: Path) -> None:
    """Delete + recreate the directory. Test-only helper."""
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)
