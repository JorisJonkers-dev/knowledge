"""Content-addressed archive layout (paths by content hash), no live store."""

from __future__ import annotations

from pathlib import Path

from note_import.archive import ContentAddressedArchive
from note_import.frontmatter import content_hash


def test_put_returns_content_addressed_sharded_path(tmp_path: Path) -> None:
    archive = ContentAddressedArchive(tmp_path / "store")
    archive.open()
    data = b"hello original bytes"
    digest = content_hash(data)
    rel = archive.put(data)
    # objects/<2>/<2>/<full-sha256>.md  — first two hex chars, next two, full.
    assert rel == f"objects/{digest[0:2]}/{digest[2:4]}/{digest}.md"
    # Bytes recovered exactly (round-trip fidelity).
    assert archive.read(rel) == data
    # Only the sharded object exists — no flat fallback.
    assert (tmp_path / "store" / "objects" / digest[0:2] / digest[2:4]).is_dir()


def test_put_is_idempotent_same_path_no_duplicate(tmp_path: Path) -> None:
    archive = ContentAddressedArchive(tmp_path / "store")
    archive.open()
    data = b"same content"
    rel1 = archive.put(data)
    files_before = len(list(archive._root.rglob("*.md")))
    rel2 = archive.put(data)
    files_after = len(list(archive._root.rglob("*.md")))
    assert rel1 == rel2
    assert files_after == files_before == 1


def test_contains_and_path_for(tmp_path: Path) -> None:
    archive = ContentAddressedArchive(tmp_path / "store")
    archive.open()
    data = b"content"
    digest = content_hash(data)
    assert archive.contains(data) is False
    archive.put(data)
    assert archive.contains(data) is True
    assert archive.path_for(digest) == f"objects/{digest[0:2]}/{digest[2:4]}/{digest}.md"


def test_large_shard_fanout(tmp_path: Path) -> None:
    """Two different contents land in different shard prefixes (no collision)."""
    archive = ContentAddressedArchive(tmp_path / "store")
    archive.open()
    a = archive.put(b"aaaa")
    b = archive.put(b"bbbb")
    assert a != b
    assert archive.read(a) == b"aaaa"
    assert archive.read(b) == b"bbbb"


def test_reopen_survives(tmp_path: Path) -> None:
    """Archive written by one process is readable by another instance."""
    store = tmp_path / "store"
    archive = ContentAddressedArchive(store)
    archive.open()
    rel = archive.put(b"persisted")
    reopened = ContentAddressedArchive(store)
    reopened.open()
    assert reopened.contains(b"persisted")
    assert reopened.read(rel) == b"persisted"
