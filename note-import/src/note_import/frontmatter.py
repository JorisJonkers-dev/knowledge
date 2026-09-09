"""Map a source file's YAML frontmatter into the Basic Memory note format.

Three distinct things are preserved, never conflated:

- ``frontmatter_raw`` — the exact YAML block text as it appeared upstream
  (native syntax untouched).
- ``frontmatter`` — the upstream-native key/value mapping as parsed.
- ``mapped`` — the canonical Basic Memory note object derived from it: the
  upstream fields pass through verbatim (``id``/``type``/``scope``/
  ``source``/``captured_at``/``confidence``/``tags``/…) plus the housekeeping
  fields the import pipeline adds (``source_id``, ``content_hash``,
  ``source_path``, ``provenance_claimed``).

Title resolution prefers frontmatter ``title``, then the first ``# `` body
heading, then the file stem. Nothing here guesses at provenance — fields
absent upstream stay absent.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

import yaml

from note_import.model import NoteMetadata, SourceFile

_TITLE_HEADING_RE = re.compile(r"^\s*#\s+(.+?)\s*$", re.MULTILINE)
_FRONTMATTER_DELIM = "---"

# Upstream-native keys that the SCHEMA.md contract treats as first-class in
# this vault. Passed through verbatim into the Basic Memory mapping.
_NATIVE_KEYS = (
    "id",
    "type",
    "scope",
    "source",
    "captured_at",
    "session_id",
    "confidence",
    "supersedes",
    "tags",
    "review_attempts",
)


def content_hash(data: bytes) -> str:
    """SHA-256 hex digest of the original byte content."""
    return hashlib.sha256(data).hexdigest()


def extract_frontmatter(text: str) -> tuple[dict[str, object], str]:
    """Split ``text`` into (parsed frontmatter, raw YAML block text).

    A file that does not start with a ``---`` delimiter yields ``({}, "")``.
    YAML that fails to parse yields ``({}, raw_block)`` — the raw text is
    still preserved so the import never silently drops content.
    """

    if not text.startswith(_FRONTMATTER_DELIM):
        return {}, ""
    lines = text.splitlines()
    # Find the closing delimiter after the opening one on line 1.
    end = -1
    for i in range(1, len(lines)):
        if lines[i].strip() == _FRONTMATTER_DELIM:
            end = i
            break
    if end < 0:
        # Opening ``---`` with no closing delimiter: not valid frontmatter.
        return {}, ""
    block = "\n".join(lines[1:end])
    try:
        parsed = yaml.safe_load(block) or {}
        if not isinstance(parsed, dict):
            return {}, block
    except yaml.YAMLError:
        return {}, block
    return {str(k): v for k, v in parsed.items()}, block


def _derive_raw_title(frontmatter: dict[str, object], body: str, stem: str) -> str:
    """Title resolution order: frontmatter -> first heading -> file stem."""
    t = frontmatter.get("title")
    if isinstance(t, str) and t.strip():
        return t.strip()
    m = _TITLE_HEADING_RE.search(body)
    if m:
        return m.group(1).strip()
    return stem


def _normalize_tags(raw: object) -> tuple[str, ...]:
    """Accept ``[a, b]`` (list) or ``a, b`` (comma string) forms."""
    if raw is None:
        return ()
    if isinstance(raw, list):
        vals = [str(x) for x in raw]
    elif isinstance(raw, str):
        vals = [part.strip() for part in raw.split(",") if part.strip()]
    else:
        vals = [str(raw)]
    return tuple(sorted(dict.fromkeys(v for v in vals if v)))


def _hex16(seed: str) -> str:
    """Stable 16-hex-char id from a seed, without random provenance."""
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()[:16]


def stable_source_id(
    frontmatter: dict[str, object], hash_: str, relative_path: str
) -> str:
    """Stable source id: upstream ``id`` when present, else hash-derived.

    The fallback is deterministic (same file -> same id across runs) and
    never claims a provenance it does not have — it is a hash of the content
    and path, not a fabricated upstream id.
    """
    native = frontmatter.get("id")
    if isinstance(native, str) and native.strip():
        return native.strip()
    seed = hash_ + ":" + relative_path
    return f"nid_{_hex16(seed)}"


def map_note(
    source: SourceFile,
    *,
    git_commit: str = "",
    git_lineage: tuple[str, ...] = (),
) -> NoteMetadata:
    """Build a normalized :class:`NoteMetadata` from a :class:`SourceFile`.

    Git lineage is injected by the caller (walk/lineage resolver) so the
    mapper stays pure and trivially testable.
    """

    text = source.bytes.decode("utf-8", errors="replace")
    frontmatter, raw = extract_frontmatter(text)

    # Body is everything after the frontmatter block.
    if raw and text.startswith(_FRONTMATTER_DELIM):
        body = _body_after_frontmatter(text, raw)
    else:
        body = text

    hash_ = content_hash(source.bytes)
    sid = stable_source_id(frontmatter, hash_, source.relative_path)
    stem = Path(source.relative_path).stem
    title = _derive_raw_title(frontmatter, body, stem)
    note_type = str(frontmatter.get("type") or "note").strip() or "note"

    tags = _normalize_tags(frontmatter.get("tags"))
    mapped: dict[str, object] = {k: frontmatter[k] for k in _NATIVE_KEYS if k in frontmatter}
    mapped.update(
        {
            "source_id": sid,
            "content_hash": hash_,
            "title": title,
            "tags": list(tags),
            "source_path": source.relative_path,
        }
    )

    return NoteMetadata(
        source_id=sid,
        content_hash=hash_,
        relative_path=source.relative_path,
        directory=source.directory,
        title=title,
        type=note_type,
        tags=tags,
        frontmatter=frontmatter,
        frontmatter_raw=raw,
        mapped=mapped,
        git_commit=git_commit,
        git_lineage=git_lineage,
        source_path=source.relative_path,
        provenance_claimed=False,  # set by the classifier, never here
    )


def _body_after_frontmatter(text: str, raw: str) -> str:
    """Return the markdown body after a parsed frontmatter ``---...---``."""

    marker = _FRONTMATTER_DELIM + "\n"
    rest = text[len(marker) :]
    closing = rest.find("\n" + _FRONTMATTER_DELIM)
    if closing < 0:
        # Malformed (no closing dedent); fall back to everything.
        return text
    return rest[closing + len(_FRONTMATTER_DELIM) + 1 :].lstrip("\n")


__all__ = [
    "content_hash",
    "extract_frontmatter",
    "map_note",
    "stable_source_id",
]
