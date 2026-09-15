"""Minimal MCP client transport over SSE (the shape Basic Memory speaks).

Basic Memory exposes its tools over the MCP "SSE" transport: a GET on
``/mcp`` opens an event stream whose first event (``event: endpoint``)
names a session-scoped POST URL; every JSON-RPC request is POSTed there
and its response arrives asynchronously as a ``message`` event on that
same stream. This client reads the stream on a background thread and
correlates responses to requests by JSON-RPC ``id`` — no MCP SDK dependency
is pulled in for the handful of calls (`initialize` once, then repeated
`tools/call`) the worker actually needs.
"""

from __future__ import annotations

import itertools
import json
import queue
import threading
from collections.abc import Iterable, Iterator
from typing import Any, Protocol

import httpx
import structlog

_PROTOCOL_VERSION = "2024-11-05"


class McpError(RuntimeError):
    """A JSON-RPC error response, or a tool call reporting ``isError``."""


class McpNotFoundError(McpError):
    """The tool reported the target note does not exist."""


class McpTransport(Protocol):
    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]: ...


def parse_sse_events(lines: Iterable[str]) -> Iterator[tuple[str, str]]:
    """Pure SSE parser: yields ``(event, data)`` pairs from raw SSE text lines.

    Follows the SSE spec's multi-line data joining (``data:`` lines within
    one event are newline-joined) and resets on the blank line that ends an
    event. Comment lines (leading ``:``, used as keep-alives) are dropped.
    """
    event = "message"
    data_lines: list[str] = []
    for raw in lines:
        line = raw.rstrip("\n").rstrip("\r")
        if line == "":
            if data_lines:
                yield event, "\n".join(data_lines)
            event, data_lines = "message", []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event = line[len("event:") :].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:") :].lstrip(" "))
    if data_lines:
        yield event, "\n".join(data_lines)


def _content_text(result: dict[str, Any]) -> str:
    parts = result.get("content", [])
    return " ".join(p.get("text", "") for p in parts if isinstance(p, dict))


class SseMcpTransport:
    """`McpTransport` backed by a real MCP-over-SSE endpoint."""

    def __init__(self, *, base_url: str, timeout: float = 15.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        # read=None: the GET stream is held open indefinitely between
        # tool calls, only the per-request POST round-trip is bounded.
        self._client = httpx.Client(timeout=httpx.Timeout(timeout, read=None))
        self._log = structlog.get_logger(__name__)
        self._ids = itertools.count(1)
        self._pending: dict[int, queue.Queue[dict[str, Any]]] = {}
        self._endpoint_ready: queue.Queue[str] = queue.Queue(maxsize=1)
        self._post_url: str | None = None

        self._stream_ctx = self._client.stream(
            "GET", f"{self._base_url}/mcp", headers={"Accept": "text/event-stream"}
        )
        self._response = self._stream_ctx.__enter__()
        self._thread = threading.Thread(target=self._pump, daemon=True)
        self._thread.start()
        self._post_url = self._endpoint_ready.get(timeout=timeout)
        self._initialize()

    def _pump(self) -> None:
        try:
            for event, data in parse_sse_events(self._response.iter_lines()):
                if event == "endpoint":
                    url = data if data.startswith("http") else f"{self._base_url}{data}"
                    self._endpoint_ready.put(url)
                elif event == "message":
                    try:
                        msg = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    box = self._pending.get(msg.get("id"))
                    if box is not None:
                        box.put(msg)
        except httpx.HTTPError as exc:  # pragma: no cover — network failure path
            self._log.error("mcp.stream_failed", error=str(exc))

    def _request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        assert self._post_url is not None, "endpoint not ready"
        req_id = next(self._ids)
        box: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=1)
        # Register before posting: the server can only push a response to
        # the SSE stream after it has received this request, so the
        # pending entry always exists by the time that response arrives.
        self._pending[req_id] = box
        try:
            response = self._client.post(
                self._post_url,
                json={"jsonrpc": "2.0", "id": req_id, "method": method, "params": params},
            )
            response.raise_for_status()
            msg = box.get(timeout=self._timeout)
        finally:
            self._pending.pop(req_id, None)
        if "error" in msg:
            raise McpError(str(msg["error"]))
        return dict(msg.get("result", {}))

    def _initialize(self) -> None:
        self._request(
            "initialize",
            {
                "protocolVersion": _PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "knowledge-ingest-worker", "version": "1"},
            },
        )
        assert self._post_url is not None
        self._client.post(
            self._post_url, json={"jsonrpc": "2.0", "method": "notifications/initialized"}
        )

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        result = self._request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            text = _content_text(result)
            if "not found" in text.lower() or "does not exist" in text.lower():
                raise McpNotFoundError(text)
            raise McpError(text)
        return result

    def close(self) -> None:
        self._stream_ctx.__exit__(None, None, None)
        self._client.close()


__all__ = [
    "McpError",
    "McpNotFoundError",
    "McpTransport",
    "SseMcpTransport",
    "parse_sse_events",
]
