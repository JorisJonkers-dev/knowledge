from __future__ import annotations

from datetime import UTC, datetime

import httpx
import pytest

from knowledge_worker.hindsight_client import HindsightClient, bank_for_scope
from knowledge_worker.messages import CapturedNote


def _note(**overrides: object) -> CapturedNote:
    base: dict[str, object] = {
        "id": "01HXYZ00000000000000000000",
        "type": "lesson",
        "scope": "project:personal-stack",
        "source": "claude-code",
        "captured_at": datetime(2026, 5, 13, 12, 0, tzinfo=UTC),
        "confidence": 0.4,
        "title": "title",
        "body": "body",
        "vault_path": "personal/lesson/draft.md",
        "tags": ["k8s"],
    }
    base.update(overrides)
    return CapturedNote.model_validate(base)


@pytest.mark.parametrize(
    ("scope", "expected"),
    [
        ("project:personal-stack", "personal-stack"),
        ("personal", "personal"),
        ("project:Homelab Inventory!", "homelab-inventory"),
        ("::", "fallback"),
        ("", "fallback"),
    ],
)
def test_bank_for_scope_derives_a_slug_or_falls_back(scope: str, expected: str) -> None:
    assert bank_for_scope(scope, default_bank="fallback") == expected


def _client(handler: httpx.MockTransport | httpx.BaseTransport) -> HindsightClient:
    return HindsightClient(
        base_url="http://hindsight-api",
        default_bank="personal",
        client=httpx.Client(transport=handler, base_url="http://hindsight-api"),
    )


def test_retain_puts_to_bank_scoped_by_scope_and_keyed_by_note_id() -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"id": "mem-1"})

    result = _client(httpx.MockTransport(handler)).retain(_note())

    assert len(calls) == 1
    request = calls[0]
    assert request.method == "PUT"
    assert request.url.path == "/v1/banks/personal-stack/memories/01HXYZ00000000000000000000"
    assert result.bank == "personal-stack"
    assert result.source_id == "01HXYZ00000000000000000000"
    assert result.memory_id == "mem-1"


def test_retain_raises_on_http_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(httpx.HTTPStatusError):
        _client(httpx.MockTransport(handler)).retain(_note())


def test_delete_targets_the_same_bank_and_id() -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(204)

    _client(httpx.MockTransport(handler)).delete(_note())

    assert len(calls) == 1
    assert calls[0].method == "DELETE"
    assert calls[0].url.path == "/v1/banks/personal-stack/memories/01HXYZ00000000000000000000"


def test_delete_treats_404_as_success_not_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    # A replayed tombstone against an already-deleted memory must not raise.
    _client(httpx.MockTransport(handler)).delete(_note())


def test_delete_still_raises_on_a_real_server_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    with pytest.raises(httpx.HTTPStatusError):
        _client(httpx.MockTransport(handler)).delete(_note())
