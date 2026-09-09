"""Frontmatter → Basic Memory mapping: native syntax preserved, stable ids."""

from __future__ import annotations

from note_import.frontmatter import (
    content_hash,
    extract_frontmatter,
    map_note,
    stable_source_id,
)
from note_import.model import SourceFile

RAW = (
    "---\n"
    "id: 01KVV0CMBXMMXTAX55T87X0315\n"
    "type: lesson\n"
    "scope: project:personal-stack\n"
    "source: claude-code:auto-capture\n"
    "captured_at: 2026-06-23T19:47:49.757263+00:00\n"
    "confidence: 0.85\n"
    "tags: [claude-code, oauth, agents-login]\n"
    "---\n"
    "\n"
    "# Claude Code apiKeyHelper\n"
    "\n"
    "Body text with [[]] wikilinks.\n"
)


def _source(text: str = RAW, rel: str = "topics/claude-code/notes.md") -> SourceFile:
    return SourceFile(relative_path=rel, bytes=text.encode("utf-8"))


def test_extract_preserves_native_frontmatter() -> None:
    parsed, raw = extract_frontmatter(RAW)
    assert parsed["id"] == "01KVV0CMBXMMXTAX55T87X0315"
    assert parsed["type"] == "lesson"
    assert parsed["scope"] == "project:personal-stack"
    assert parsed["tags"] == ["claude-code", "oauth", "agents-login"]
    # Native YAML block text survives verbatim (syntax preserved).
    assert "source: claude-code:auto-capture" in raw
    assert raw.startswith("id: 01KVV0CMBXMMXTAX55T87X0315")


def test_extract_no_frontmatter() -> None:
    parsed, raw = extract_frontmatter("# Just a heading\n")
    assert parsed == {}
    assert raw == ""


def test_map_preserves_upstream_fields_and_adds_housekeeping() -> None:
    note = map_note(_source())
    assert note.source_id == "01KVV0CMBXMMXTAX55T87X0315"  # stable upstream id
    assert note.type == "lesson"
    assert note.tags == ("agents-login", "claude-code", "oauth")  # sorted
    assert note.title == "Claude Code apiKeyHelper"
    # Upstream-native frontmatter is preserved untouched.
    assert note.frontmatter["scope"] == "project:personal-stack"
    assert note.frontmatter["source"] == "claude-code:auto-capture"
    assert "source: claude-code:auto-capture" in note.frontmatter_raw
    # Basic Memory mapping carries native fields + housekeeping.
    assert note.mapped["source_id"] == note.source_id
    assert note.mapped["content_hash"] == note.content_hash
    assert note.mapped["type"] == "lesson"


def test_map_no_type_defaults_to_note() -> None:
    src = _source("---\nid: abc\n---\n\n# T\n")
    assert map_note(src).type == "note"


def test_stable_source_id_fallback_is_deterministic() -> None:
    hash_ = content_hash(b"x")
    a = stable_source_id({}, hash_, "topics/a.md")
    b = stable_source_id({}, hash_, "topics/a.md")
    assert a == b
    assert a.startswith("nid_")  # marked synthetic, never an upstream claim


def test_content_hash_deterministic() -> None:
    assert content_hash(b"body") == content_hash(b"body")
    assert content_hash(b"body") != content_hash(b"BODY")
