from __future__ import annotations

import pytest

from knowledge_worker.mcp_transport import (
    McpError,
    McpNotFoundError,
    SseMcpTransport,
    parse_sse_events,
)


def test_parse_sse_events_yields_event_data_pairs() -> None:
    lines = [
        "event: endpoint\n",
        "data: /messages/session-1\n",
        "\n",
        "event: message\n",
        'data: {"jsonrpc": "2.0"}\n',
        "\n",
    ]
    events = list(parse_sse_events(lines))
    assert events == [
        ("endpoint", "/messages/session-1"),
        ("message", '{"jsonrpc": "2.0"}'),
    ]


def test_parse_sse_events_joins_multi_line_data_with_newlines() -> None:
    lines = ["data: line1\n", "data: line2\n", "\n"]
    assert list(parse_sse_events(lines)) == [("message", "line1\nline2")]


def test_parse_sse_events_drops_comment_lines() -> None:
    lines = [": keep-alive\n", "data: real\n", "\n"]
    assert list(parse_sse_events(lines)) == [("message", "real")]


def test_parse_sse_events_defaults_to_message_when_no_event_line() -> None:
    assert list(parse_sse_events(["data: x\n", "\n"])) == [("message", "x")]


def test_parse_sse_events_flushes_a_trailing_event_with_no_final_blank_line() -> None:
    assert list(parse_sse_events(["data: tail\n"])) == [("message", "tail")]


def test_call_tool_raises_mcp_not_found_error_on_not_found_text(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    transport = object.__new__(SseMcpTransport)

    def fake_request(method: str, params: dict[str, object]) -> dict[str, object]:
        return {"isError": True, "content": [{"type": "text", "text": "Note not found: x"}]}

    monkeypatch.setattr(transport, "_request", fake_request)
    with pytest.raises(McpNotFoundError):
        transport.call_tool("edit_note", {})


def test_call_tool_raises_plain_mcp_error_for_other_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    transport = object.__new__(SseMcpTransport)

    def fake_request(method: str, params: dict[str, object]) -> dict[str, object]:
        return {"isError": True, "content": [{"type": "text", "text": "internal server error"}]}

    monkeypatch.setattr(transport, "_request", fake_request)
    with pytest.raises(McpError) as exc_info:
        transport.call_tool("edit_note", {})
    assert not isinstance(exc_info.value, McpNotFoundError)


def test_call_tool_returns_result_on_success(monkeypatch: pytest.MonkeyPatch) -> None:
    transport = object.__new__(SseMcpTransport)

    def fake_request(method: str, params: dict[str, object]) -> dict[str, object]:
        assert method == "tools/call"
        assert params == {"name": "write_note", "arguments": {"title": "t"}}
        return {"content": [{"type": "text", "text": "ok"}]}

    monkeypatch.setattr(transport, "_request", fake_request)
    result = transport.call_tool("write_note", {"title": "t"})
    assert result["content"][0]["text"] == "ok"
