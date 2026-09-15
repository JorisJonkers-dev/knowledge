"""HTTP client for Hindsight's retain API (fleet-infra#246 placement).

Hindsight owns long-term recall. The worker retains each captured note into
a bank scoped to the note's project/domain, keyed by the note's own stable
id as `source_id` — an upsert-by-id PUT so a replay or a revision updates
the same memory in place rather than appending a duplicate, and a delete by
that same id is exactly the "an old queued job must not resurrect a
deleted source" guarantee: deleting once removes the record no matter how
many times a stale job replays afterward.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

import httpx
import structlog

from knowledge_worker.messages import CapturedNote

_SCOPE_PREFIX_RE = re.compile(r"^[a-z]+:")
_NON_SLUG_RE = re.compile(r"[^a-z0-9_-]+")


def bank_for_scope(scope: str, *, default_bank: str) -> str:
    """Derive a Hindsight bank name from a capture's scope.

    `project:personal-stack` -> `personal-stack`; a bare scope with no
    `<kind>:` prefix (e.g. `personal`) is used as-is. A scope that slugs to
    nothing (empty, or all punctuation) falls back to `default_bank` so a
    malformed scope never produces an invalid bank path segment.
    """
    stripped = _SCOPE_PREFIX_RE.sub("", scope)
    slug = _NON_SLUG_RE.sub("-", stripped.lower()).strip("-")
    return slug or default_bank


@dataclass(frozen=True, slots=True)
class HindsightWriteResult:
    bank: str
    source_id: str
    memory_id: str


class HindsightClient:
    """Retains/deletes memories via Hindsight's HTTP API."""

    def __init__(
        self,
        *,
        base_url: str,
        default_bank: str,
        api_key: str | None = None,
        client: httpx.Client | None = None,
        timeout: float = 10.0,
    ) -> None:
        self._default_bank = default_bank
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"), headers=headers, timeout=timeout
        )
        self._log = structlog.get_logger(__name__)

    def retain(self, note: CapturedNote) -> HindsightWriteResult:
        bank = bank_for_scope(note.scope, default_bank=self._default_bank)
        body: dict[str, Any] = {
            "source_id": note.id,
            "content": note.body,
            "metadata": {
                "title": note.title,
                "type": note.type,
                "source": note.source,
                "tags": list(note.tags),
                "captured_at": note.captured_at.isoformat(),
            },
        }
        response = self._client.put(f"/v1/banks/{bank}/memories/{note.id}", json=body)
        response.raise_for_status()
        payload: dict[str, Any] = response.json() if response.content else {}
        memory_id = str(payload.get("id", note.id))
        self._log.info("hindsight.retained", bank=bank, source_id=note.id, memory_id=memory_id)
        return HindsightWriteResult(bank=bank, source_id=note.id, memory_id=memory_id)

    def delete(self, note: CapturedNote) -> None:
        bank = bank_for_scope(note.scope, default_bank=self._default_bank)
        response = self._client.delete(f"/v1/banks/{bank}/memories/{note.id}")
        # A delete on an already-absent memory is the success case for a
        # replayed tombstone, not an error — don't raise on 404.
        if response.status_code != 404:
            response.raise_for_status()
        self._log.info("hindsight.deleted", bank=bank, source_id=note.id)

    def close(self) -> None:
        self._client.close()


__all__ = ["HindsightClient", "HindsightWriteResult", "bank_for_scope"]
