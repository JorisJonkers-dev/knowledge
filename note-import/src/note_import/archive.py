"""Content-addressed store layout for archived original bytes.

Requirement: archive original bytes to a content-addressed layout (paths by
content hash) *without* a live Garage. This module implements the layout
against the local filesystem — a plain directory tree on a disk, NFS/S3 mount,
or anywhere a real Garage object store can later be swapped in. The
:class:`ContentAddressedArchive` class is the single seam; a future Garage
adapter implements the same two operations (:meth:`put` / :meth:`path_for`).

Layout::

    <root>/objects/ab/cd/<full-sha256>.md

The first two hex chars shard by prefix, the next two by the second prefix
(64^4 fanout at the top, enough for a ~few-thousand-note vault), and the
filename is the full SHA-256 digest of the stored bytes. Writing is
idempotent: putting identical bytes returns the same path and does not
duplicate the object (content-addressed + idempotent store, like Garage's
``PutOptions`` overwrite semantics).
"""

from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

from note_import.frontmatter import content_hash

#: Shard length (hex chars) for the first and second path levels.
_SHARD = 2


class ContentAddressedArchive:
    """Local filesystem content-addressed archive. Thread-safe enough for
    sequential single-process import (the intended mode)."""

    def __init__(self, root: Path) -> None:
        self._root = Path(root).expanduser()

    # -- lifecycle -- #

    def open(self) -> None:
        """Create the object-store directory if missing. Idempotent."""
        self._root.mkdir(parents=True, exist_ok=True)

    def close(self) -> None:
        """No-op for the filesystem backend; kept for drop-in symmetry."""

    # -- content-addressed operations -- #

    def put(self, data: bytes) -> str:
        """Store ``data`` and return its content-addressed relative path.

        The object path is fully determined by the bytes, so storing the
        same bytes twice returns the same path and writes nothing extra.
        """
        digest = content_hash(data)
        rel = self._object_rel(digest)
        target = self._root / rel
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            # Write temp in the same directory then atomically move, so a
            # crash mid-write never leaves a partial object at the final
            # content-addressed path.
            fd, tmp = tempfile.mkstemp(dir=str(target.parent))
            try:
                with os.fdopen(fd, "wb") as fh:
                    fh.write(data)
                os.replace(tmp, target)
            except BaseException:
                Path(tmp).unlink(missing_ok=True)
                raise
        return rel

    def contains(self, data: bytes) -> bool:
        """True when the objects for ``data`` are already archived."""
        digest = content_hash(data)
        return (self._root / self._object_rel(digest)).exists()

    def read(self, rel: str) -> bytes:
        """Read an object by its content-addressed relative path."""
        return (self._root / rel).read_bytes()

    def path_for(self, digest: str) -> str:
        """Content-addressed relative path for a known SHA-256 digest."""
        return self._object_rel(digest)

    # -- test helpers -- #

    def wipe(self) -> None:
        """Remove the archive root. Test-only helper."""
        if self._root.exists():
            shutil.rmtree(self._root)
        self._root.mkdir(parents=True, exist_ok=True)

    def _object_rel(self, digest: str) -> str:
        """``objects/ab/cd/<digest>.md`` from a hex digest."""
        if len(digest) < 2 * _SHARD * 2:
            raise ValueError(f"digest too short for layout: {digest!r}")
        d0 = digest[0:_SHARD]
        d1 = digest[_SHARD : 2 * _SHARD]
        return f"objects/{d0}/{d1}/{digest}.md"


__all__ = ["ContentAddressedArchive"]
