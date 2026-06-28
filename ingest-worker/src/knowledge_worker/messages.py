"""Typed envelope for the knowledge-api `IngestPublisher` payload.

Mirrors `api/.../queue/IngestPublisher.kt` —
keep the field names in lock-step. `extra` catches forward-compatible
keys (e.g. session_id metadata) so the worker accepts payloads that
add fields without a coordinated release.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class CapturedNote(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    type: str
    scope: str
    source: str
    captured_at: datetime
    session_id: str | None = None
    confidence: float
    title: str
    body: str
    vault_path: str
    tags: list[str] = Field(default_factory=list)

    @property
    def extra(self) -> dict[str, Any]:
        """Forward-compatible payload keys not modelled above."""

        # Pydantic v2 stores extras inside `model_extra`.
        return dict(self.model_extra or {})
