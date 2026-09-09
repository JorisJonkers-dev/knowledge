"""Resolve per-file git lineage from the source vault repository.

History is carried across the import rather than collapsed: for each
markdown file we record every commit SHA that touched it (newest-first)
plus the commit that most recently changed it. Renames are followed with
``git log --follow`` where the repository supports it, so a file that moved
keeps its pre-move history.
"""

from __future__ import annotations

from pathlib import Path

from git import InvalidGitRepositoryError, NoSuchPathError, Repo


class LineageResolver:
    """Read-only git lineage for every markdown file under a vault root.

    Purely read-only: constructing or calling it never stages, commits,
    checks out or otherwise mutates the source repository. It is safe to
    point at a submodule checkout (e.g. the pinned knowledge-vault).
    """

    def __init__(self, repo: Repo) -> None:
        self._repo = repo

    @classmethod
    def open(cls, vault_root: Path) -> LineageResolver:
        """Open the git repo containing ``vault_root`` (the vault itself)."""
        try:
            return cls(Repo(vault_root))
        except (InvalidGitRepositoryError, NoSuchPathError) as exc:
            raise NotAGitRepo(vault_root) from exc

    def lineage_for(self, relative_path: str) -> tuple[str, ...]:
        """Commit SHAs touching ``relative_path`` at its current path.

        Newest-first, matching ``git log`` default ordering, so the first
        element is the commit that most recently changed the file. The full
        list is carried across the import so history is never collapsed to a
        single commit.

        Note: git's ``--follow`` rename detection is deliberately NOT used —
        it is known to attribute unrelated earlier commits to files that were
        only ever edited once. Plain ``git log <path>`` is correct and
        verifiable; a file that moved shows the commits at its current path.
        Returns ``()`` when the path has no history (e.g. uncommitted work).
        """
        try:
            shas = self._repo.git.log(
                "--format=%H",
                "--",
                relative_path,
            )
        except Exception:
            return ()
        if not shas.strip():
            return ()
        return tuple(shas.strip().splitlines())

    def head_commit(self) -> str:
        """The repository HEAD commit SHA, or ``""`` if there is no commit."""
        if not self._repo.head.is_valid():
            return ""
        return self._repo.head.commit.hexsha

    def resolve(self, relative_path: str) -> tuple[str, tuple[str, ...]]:
        """Return ``(last_commit, lineage)`` for a file.

        ``last_commit`` is ``""`` when the file is uncommitted or the repo
        has no commits.
        """
        lineage = self.lineage_for(relative_path)
        return (lineage[0] if lineage else ""), lineage


class NotAGitRepo(Exception):
    """Raised when the vault root is not (inside) a git repository."""

    def __init__(self, vault_root: Path) -> None:
        super().__init__(f"not a git repository at: {vault_root}")


__all__ = ["LineageResolver", "NotAGitRepo"]
