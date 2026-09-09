"""Shared fixtures: a temporary git-backed vault with structure + history."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from git import Repo


def _write(repo: Repo, root: Path, rel: str, content: str) -> str:
    """Write a file, stage + commit it, and return the commit SHA."""
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    repo.index.add([rel])
    commit = repo.index.commit(f"update {rel}")
    return commit.hexsha


@pytest.fixture()
def vault(tmp_path: Path) -> Iterator[tuple[Path, Repo]]:
    """A git-backed vault with real structure and multi-commit history.

    Layout mirrors the knowledge-vault's top-level categories:

    - ``personal/notes.md``  (edited twice -> 2 commits of history)
    - ``work/notes.md``      (edited twice -> 2 commits)
    - ``projects/acme/notes.md``
    - ``topics/python/notes.md``
    - ``agents/_shared/notes.md``
    - ``public/notes.md``
    - ``_inbox/2026-06-18/capture.md``
    - ``_index/topics.md``
    - an empty root stub ``stub.md`` (0 bytes -> legacy-derived evidence)
    """
    root = tmp_path / "vault"
    root.mkdir()
    repo = Repo.init(root, initial_branch="main")
    repo.config_writer().set_value("user", "email", "test@example").release()
    repo.config_writer().set_value("user", "name", "tester").release()

    fm = (
        "---\n"
        "id: 01KVV0CMBXMMXTAX55T87X0315\n"
        "type: lesson\n"
        "scope: personal\n"
        "source: claude-code\n"
        "captured_at: 2026-06-23T19:47:49.757263+00:00\n"
        "confidence: 0.85\n"
        "tags: [claude-code, oauth]\n"
        "---\n"
        "\n"
        "# Personal note\n"
        "\n"
        "Body text.\n"
    )
    _write(repo, root, "personal/notes.md", fm)
    _write(repo, root, "work/notes.md", fm.replace("Personal", "Work"))
    _write(repo, root, "projects/acme/notes.md", fm.replace("Personal", "Project"))
    _write(repo, root, "topics/python/notes.md", fm.replace("Personal", "Python"))
    _write(repo, root, "agents/_shared/notes.md", fm.replace("Personal", "Agent"))
    _write(repo, root, "public/notes.md", fm.replace("Personal", "Public"))
    _write(repo, root, "_inbox/2026-06-18/capture.md", fm.replace("Personal", "Capture"))
    _write(repo, root, "_index/topics.md", fm.replace("Personal", "Index"))
    # Empty stub — valid file with no original content (legacy evidence gone).
    _write(repo, root, "stub.md", "")

    # Second commit touching two files so "history preservation" is real.
    # NOTE: the work retouch must actually change the title — retouched still
    # says "Personal note", so `.replace("Work",...)` would be a no-op and the
    # file would become byte-identical to personal/notes.md, collapsing the
    # fixture to 8 distinct notes instead of 9 (and wrongly failing the
    # count-reconciliation test).
    retouched = fm.replace("Body text.", "Retouched body.")
    _write(repo, root, "personal/notes.md", retouched)
    _write(repo, root, "work/notes.md", retouched.replace("Personal", "Work"))

    yield root, repo


NOTE_SAMPLE = """---
id: 01KVV0CMBXMMXTAX55T87X0315
type: lesson
scope: project:personal-stack
source: claude-code:auto-capture
captured_at: 2026-06-23T19:47:49.757263+00:00
confidence: 0.85
tags: [claude-code, oauth, agents-login]
---

# Claude Code apiKeyHelper is for API keys

Body.
"""

EMPTY_STUB = ""
