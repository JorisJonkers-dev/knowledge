"""Git-backed knowledge-vault writer.

Each captured note becomes one markdown file under
``<vault>/notes/<scope>/<type>/<ulid>.md`` with full frontmatter
(provenance fields the agents need for cross-referencing). The
worker commits with a `worker(<scope>): …` message — distinguishable
from human Obsidian commits which use the `vault(<host>): …` prefix
the Obsidian Git plugin renders — and pushes to ``origin``.

The clone lives in a stable per-pod directory (``/var/lib/
knowledge-vault`` under k8s, configurable for tests). The worker
opens it once at boot, pulls before the first write, then commits
per delivery. Concurrent writers on the same clone are not
supported — the worker is a single replica today; if it ever scales
out the broker round-robins deliveries across replicas but the
clone needs to move to a per-replica path.
"""

from __future__ import annotations

import os
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import structlog
from git import Actor, GitCommandError, Repo

from knowledge_worker.messages import CapturedNote


@dataclass(frozen=True, slots=True)
class VaultWriteResult:
    relative_path: str
    commit_sha: str


class VaultWriter(Protocol):
    def write(self, note: CapturedNote) -> VaultWriteResult: ...


_DEFAULT_AUTHOR = Actor("knowledge-ingest-worker", "worker@knowledge.local")


class VaultGitWriter:
    """`VaultWriter` backed by a real git working tree.

    Constructor parameters are taken explicitly so unit tests can wire
    against a temporary bare repo without env-var plumbing. Production
    builds the writer via `from_settings(settings)` which reads the
    same env layout as the rest of the worker.
    """

    def __init__(
        self,
        *,
        clone_url: str,
        clone_dir: Path,
        branch: str = "main",
        author: Actor | None = None,
        ssh_key_path: str | None = None,
        push: bool = True,
    ) -> None:
        self._clone_url = clone_url
        self._clone_dir = clone_dir
        self._branch = branch
        self._author = author or _DEFAULT_AUTHOR
        self._ssh_key_path = ssh_key_path
        self._push = push
        self._log = structlog.get_logger(__name__)
        self._repo: Repo | None = None

    # -- lifecycle --

    def open(self) -> None:
        """Clone (first boot) or attach (restart). Idempotent."""

        if self._clone_dir.exists() and (self._clone_dir / ".git").exists():
            self._repo = Repo(self._clone_dir)
            self._log.info("vault.attached", clone=str(self._clone_dir))
        else:
            self._clone_dir.parent.mkdir(parents=True, exist_ok=True)
            # NOT a shallow clone: GitPython resolves parent objects via
            # `git cat-file --batch` when committing on top of HEAD, and
            # a `depth=1` store crashes that with a misleading
            # `BrokenPipeError` because cat-file exits early when an
            # ancestor object is missing. The knowledge-vault is small
            # markdown anyway; a full clone is cheap.
            self._repo = Repo.clone_from(
                self._clone_url,
                self._clone_dir,
                branch=self._branch,
                env=self._git_env(),
            )
            self._log.info("vault.cloned", clone=str(self._clone_dir))

    def pull(self) -> None:
        repo = self._require_repo()
        with repo.git.custom_environment(**self._git_env()):
            repo.remotes.origin.pull(rebase=True, autostash=True)

    # -- write path --

    def write(self, note: CapturedNote) -> VaultWriteResult:
        repo = self._require_repo()
        target = self._clone_dir / self._relative_path(note)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(self._render_markdown(note), encoding="utf-8")

        rel = target.relative_to(self._clone_dir).as_posix()
        repo.index.add([rel])
        message = self._commit_message(note)
        commit = repo.index.commit(message, author=self._author, committer=self._author)

        if self._push:
            try:
                with repo.git.custom_environment(**self._git_env()):
                    repo.remotes.origin.push()
            except GitCommandError as exc:
                # Push failures are recoverable — the next `pull` +
                # subsequent write retries the push. Log loudly so the
                # operator notices if the remote is genuinely broken.
                self._log.error("vault.push_failed", rel=rel, error=str(exc))
                raise

        sha = commit.hexsha
        self._log.info("vault.written", rel=rel, sha=sha[:12], scope=note.scope)
        return VaultWriteResult(relative_path=rel, commit_sha=sha)

    # -- internals --

    @staticmethod
    def _relative_path(note: CapturedNote) -> str:
        """Render to ``notes/<scope>/<type>/<id>.md``.

        Slashes in ``scope`` (e.g. ``project:personal-stack``) become
        hyphens to keep the directory shape flat. The ULID is the
        filename so future supersedes-chains can locate the note by id
        regardless of title edits.
        """

        scope = _slug(note.scope)
        return f"notes/{scope}/{note.type}/{note.id}.md"

    def _render_markdown(self, note: CapturedNote) -> str:
        # YAML frontmatter — keep one key per line, no nesting, so
        # diffs stay legible. Body is the raw captured text.
        lines = [
            "---",
            f"id: {note.id}",
            f"type: {note.type}",
            f"scope: {note.scope}",
            f"source: {note.source}",
            f"captured_at: {note.captured_at.isoformat()}",
        ]
        if note.session_id:
            lines.append(f"session_id: {note.session_id}")
        lines.append(f"confidence: {note.confidence}")
        if note.tags:
            tags = ", ".join(sorted(note.tags))
            lines.append(f"tags: [{tags}]")
        lines += [
            "---",
            "",
            f"# {note.title}",
            "",
            note.body,
            "",
        ]
        return "\n".join(lines)

    @staticmethod
    def _commit_message(note: CapturedNote) -> str:
        # `worker(<scope>): <type> <id>` — distinguishable from the
        # human `vault(<host>): …` commits the Obsidian Git plugin
        # writes. `git log --grep '^worker('` filters worker commits
        # for backfill or audit.
        return f"worker({note.scope}): {note.type} {note.id}"

    def _git_env(self) -> dict[str, str]:
        env = dict(os.environ)
        if self._ssh_key_path:
            env["GIT_SSH_COMMAND"] = (
                f"ssh -i {self._ssh_key_path} -o IdentitiesOnly=yes "
                f"-o StrictHostKeyChecking=accept-new"
            )
        return env

    def _require_repo(self) -> Repo:
        if self._repo is None:
            raise RuntimeError("VaultGitWriter.open() must be called before write()")
        return self._repo


def _slug(scope: str) -> str:
    """Filesystem-safe slug for a scope string.

    Keeps alphanumerics, `_`, `-`, and `.`. Everything else becomes
    a `-` so `project:personal-stack-2` round-trips to
    `project-personal-stack-2` instead of creating a directory tree.
    """

    return re.sub(r"[^A-Za-z0-9_.\-]+", "-", scope).strip("-")


def reset_clone_dir(path: Path) -> None:
    """Delete + recreate the clone directory. Test-only helper."""

    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)
