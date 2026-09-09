"""Walk a source vault tree, preserving directory structure.

The knowledge-vault keeps curated notes under top-level categories —
``personal``, ``work``, ``projects``, ``topics``, ``agents``, ``public``,
``_inbox``, ``_index`` and friends. The walker yields one
:class:`~note_import.model.SourceFile` per ``.md`` file with its
vault-relative path intact and never flattens the tree. Hidden paths
(``.git``, ``.obsidian``) and non-markdown files are skipped.
"""

from __future__ import annotations

from pathlib import Path

from note_import.model import SourceFile

# Relative segments that are never treated as notes regardless of content.
_SKIPPED_NAMES = frozenset(
    {
        ".git",
        ".obsidian",
        ".trash",
        "node_modules",
        ".venv",
        "__pycache__",
    }
)


def _skip_relative(rel_parts: tuple[str, ...]) -> bool:
    """True when any relative path segment is hidden or a tool directory."""
    return any(part in _SKIPPED_NAMES or part.startswith(".") for part in rel_parts)


def walk_vault(vault_root: Path) -> list[SourceFile]:
    """Recursively collect every ``.md`` file under ``vault_root``.

    Returns files sorted by ``relative_path`` for deterministic ordering.
    Raises :class:`FileNotFoundError` if ``vault_root`` does not exist.
    """

    root = Path(vault_root).expanduser()
    if not root.exists() or not root.is_dir():
        raise FileNotFoundError(f"vault root is not an existing directory: {root}")

    found: list[SourceFile] = []
    for entry in sorted(root.rglob("*.md")):
        if not entry.is_file():
            continue
        rel = entry.relative_to(root).as_posix()
        rel_parts = entry.relative_to(root).parts
        # Skip anything whose path passes through a hidden / tool directory
        # (e.g. a stray note inside `.obsidian/`).
        if _skip_relative(rel_parts):
            continue
        found.append(SourceFile(relative_path=rel, bytes=entry.read_bytes()))
    return found
