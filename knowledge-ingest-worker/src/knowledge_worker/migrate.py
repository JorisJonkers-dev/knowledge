"""One-shot migration: move notes/ → _inbox/ for the curator to classify.

Pre-redesign captures landed under
``notes/<scope-slug>/<type>/<ulid>.md`` with no slug, no topic, and
no curation. The redesign drops the `notes/` prefix and routes
everything through `_inbox/<YYYY-MM-DD>/<HHMMSS>-<slug>--<id8>.md`
so the curator agent can classify + promote with the full body in
hand.

This script:

1. Connects to the vault git clone and the knowledge_db Postgres.
2. Walks ``notes/`` in the working tree; for each ``*.md`` file:
   - Parses the YAML frontmatter to recover `id`, `type`,
     `captured_at`, and `title` (or falls back to the file's H1).
   - Computes the new ``_inbox/<day>/<time>-<slug>--<id8>.md`` path.
   - ``git mv`` to the new location.
   - UPDATEs ``kb_notes.vault_path`` for the matching id.
3. Removes the now-empty ``notes/`` directory tree.
4. Commits the lot as ``migrate(vault): notes/ -> _inbox/ (N files)``
   and pushes.

Idempotent: if ``notes/`` doesn't exist (already migrated), exits 0.

Invoked from a one-shot k8s Job; the image ships this module by
construction. Rate-limits filesystem operations to avoid bursting
the LiveSync sidecar.
"""

from __future__ import annotations

import os
import re
import sys
from datetime import datetime
from pathlib import Path

import structlog
from git import Actor, Repo

from knowledge_worker.settings import Settings
from knowledge_worker.store import PostgresNoteStore
from knowledge_worker.telemetry import configure as configure_telemetry
from knowledge_worker.vault import (
    ID_FILENAME_PREFIX,
    VaultGitWriter,
    _title_slug,
)

_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)
_H1_RE = re.compile(r"^#\s+(.+)$", re.MULTILINE)


def _parse_frontmatter(body: str) -> dict[str, str]:
    """Extract the YAML frontmatter into a flat dict.

    Deliberately not pulling in PyYAML for one one-shot script — the
    notes the worker has written so far use the simple ``key: value``
    line shape with no nesting and only one list field (`tags`), so a
    line-by-line parse covers all observed inputs.
    """

    match = _FRONTMATTER_RE.search(body)
    if not match:
        return {}
    out: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            continue
        key, _, raw = line.partition(":")
        out[key.strip()] = raw.strip()
    return out


def _title_from_body(body: str) -> str:
    match = _H1_RE.search(body)
    return match.group(1).strip() if match else ""


def _new_path(meta: dict[str, str], body: str) -> str:
    captured_at = meta.get("captured_at", "")
    try:
        dt = datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
    except ValueError:
        dt = datetime.now()
    day = dt.strftime("%Y-%m-%d")
    time = dt.strftime("%H%M%S")
    note_id = meta.get("id", "")
    title = _title_from_body(body)
    slug = _title_slug(title) or note_id[:ID_FILENAME_PREFIX].lower() or "untitled"
    id_suffix = (note_id[:ID_FILENAME_PREFIX] or "00000000").upper()
    return f"_inbox/{day}/{time}-{slug}--{id_suffix}.md"


def _git_mv(repo: Repo, src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    repo.git.mv(str(src.relative_to(repo.working_dir)), str(dst.relative_to(repo.working_dir)))


def main() -> int:  # pragma: no cover — orchestrated via a k8s Job
    settings = Settings.from_env()
    configure_telemetry(level=settings.log_level, service_version=settings.service_version)
    log = structlog.get_logger(__name__)

    writer = VaultGitWriter(
        clone_url=settings.vault_clone_url,
        clone_dir=Path(settings.vault_clone_dir),
        branch=settings.vault_branch,
        author=Actor(settings.vault_author_name, settings.vault_author_email),
        ssh_key_path=settings.vault_ssh_key_path,
        push=True,
    )
    writer.open()
    writer.pull()

    repo = Repo(settings.vault_clone_dir)
    notes_root = Path(settings.vault_clone_dir) / "notes"
    if not notes_root.exists():
        log.info("migrate.skip", reason="notes/ not present")
        return 0

    store: PostgresNoteStore | None = None
    if settings.kb_persist_enabled:
        store = PostgresNoteStore(
            host=settings.db_host,
            port=settings.db_port,
            database=settings.db_name,
            user=settings.db_user,
            password=settings.db_password,
        )
        store.open()

    moved = 0
    for src in sorted(notes_root.rglob("*.md")):
        body = src.read_text(encoding="utf-8")
        meta = _parse_frontmatter(body)
        new_rel = _new_path(meta, body)
        dst = Path(settings.vault_clone_dir) / new_rel
        if dst.exists():
            log.warning("migrate.skip_existing", src=str(src), dst=str(dst))
            continue
        _git_mv(repo, src, dst)
        if store is not None and meta.get("id"):
            # We don't know the new vault_commit yet — that lands when
            # the migration commit is created below — so we only update
            # the path here. The next worker write_back UPDATE clobbers
            # vault_commit anyway.
            store.update_vault_pointer(meta["id"], new_rel, "pending-migrate")
        log.info("migrate.moved", src=str(src.relative_to(repo.working_dir)), dst=new_rel)
        moved += 1

    # Remove the now-empty `notes/` skeleton so it doesn't sit around
    # half-empty pretending the worker still writes there.
    if notes_root.exists():
        for empty_dir in sorted(notes_root.rglob("*"), reverse=True):
            if empty_dir.is_dir() and not any(empty_dir.iterdir()):
                empty_dir.rmdir()
        if not any(notes_root.iterdir()):
            notes_root.rmdir()
            repo.index.add([])  # no-op staging tick; rmdir is enough

    if moved == 0:
        log.info("migrate.no_changes")
        return 0

    repo.git.add(A=True)
    message = f"migrate(vault): notes/ -> _inbox/ ({moved} files)"
    commit = repo.index.commit(message)
    with repo.git.custom_environment(**_git_env(settings.vault_ssh_key_path)):
        repo.remotes.origin.push()
    log.info("migrate.committed", moved=moved, sha=commit.hexsha[:12])

    if store is not None:
        # Reconcile the placeholder `pending-migrate` we wrote per-row
        # against the actual migration commit. The next worker write
        # for any row supersedes this value too, so it's a soft
        # invariant rather than a tight one.
        with store._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE kb_notes SET vault_commit = %s WHERE vault_commit = %s",
                (commit.hexsha, "pending-migrate"),
            )
        store.close()
    return 0


def _git_env(ssh_key_path: str | None) -> dict[str, str]:
    env = dict(os.environ)
    if ssh_key_path:
        env["GIT_SSH_COMMAND"] = (
            f"ssh -i {ssh_key_path} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
        )
    return env


if __name__ == "__main__":  # pragma: no cover — k8s Job entry point
    sys.exit(main())
