from __future__ import annotations

from knowledge_worker.migrate import _new_path, _parse_frontmatter, _title_from_body


def test_parse_frontmatter_extracts_flat_keys() -> None:
    body = (
        "---\n"
        "id: 01HXYZ00000000000000000000\n"
        "type: lesson\n"
        "scope: personal\n"
        "captured_at: 2026-05-13T12:00:00+00:00\n"
        "---\n"
        "\n"
        "# Title\n"
        "\n"
        "body\n"
    )
    meta = _parse_frontmatter(body)
    assert meta["id"] == "01HXYZ00000000000000000000"
    assert meta["type"] == "lesson"
    assert meta["scope"] == "personal"
    assert meta["captured_at"] == "2026-05-13T12:00:00+00:00"


def test_parse_frontmatter_returns_empty_when_marker_missing() -> None:
    assert _parse_frontmatter("just a body, no frontmatter") == {}


def test_title_from_body_picks_the_first_h1() -> None:
    body = "---\n---\n\n# Real title\n\nbody\n"
    assert _title_from_body(body) == "Real title"
    assert _title_from_body("no header") == ""


def test_new_path_uses_captured_at_plus_title_slug_plus_id_prefix() -> None:
    body = (
        "---\n"
        "id: 01HXYZ00000000000000000000\n"
        "captured_at: 2026-05-13T12:00:00+00:00\n"
        "---\n"
        "\n"
        "# Render scripts must run\n"
    )
    meta = _parse_frontmatter(body)
    rel = _new_path(meta, body)
    assert rel == "_inbox/2026-05-13/120000-render-scripts-must-run--01HXYZ00.md"


def test_new_path_falls_back_to_id_when_title_missing() -> None:
    body = (
        "---\n"
        "id: 01ABCDEF12345678901234567\n"
        "captured_at: 2026-05-13T12:00:00+00:00\n"
        "---\n"
        "\n"
        "no header here\n"
    )
    meta = _parse_frontmatter(body)
    rel = _new_path(meta, body)
    assert rel.endswith("--01ABCDEF.md")
    assert "01abcdef" in rel  # slug fallback uses lowercased id prefix
