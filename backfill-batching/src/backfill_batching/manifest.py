"""Corpus discovery and planning.

Walks a tree of ``.jsonl`` transcript files, assigns each record a stable
id and a content hash, and groups them into capped batches. Pure — no
writes, no Hindsight calls. The real corpus is unreachable (in the
#261-diverged PVC / archive), so this operates on whatever tree it is
pointed at; locally we point it at a synthetic fixture.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

JsonRecord = dict[str, object]

# Contract-level stable identifier and claim classification keys.
SOURCE_ID_FIELD = "source_id"
CLAIM_TYPE_FIELD = "claim_type"
CONTENT_FIELD = "content"

# Claim types the [DESIGN] selection rule sends to Hindsight as relevant
# experiences. Any other claim type is retained in the corpus, not loaded.
SELECTABLE_CLAIM_TYPES = frozenset(
    {
        "explicit_user_decision",
        "explicit_user_preference",
        "observed_tool_outcome",
        "synthesized_summary",
    }
)


@dataclass(frozen=True)
class Record:
    """One parsed .jsonl line with its derived stable id and content hash."""

    source_id: str
    content_hash: str
    file_rel: str
    line_no: int
    claim_type: str
    text: str

    def as_record(self) -> JsonRecord:
        return {
            SOURCE_ID_FIELD: self.source_id,
            CLAIM_TYPE_FIELD: self.claim_type,
            CONTENT_FIELD: self.text,
            "file": self.file_rel,
            "line": self.line_no,
        }


@dataclass
class Batch:
    """A contiguous page of records — the unit of work and reconciliation."""

    index: int
    records: list[Record]


@dataclass
class CorpusPlan:
    """Ordered, immutable plan over a corpus."""

    batches: list[Batch]
    total_records: int
    fingerprint: str

    @property
    def total_batches(self) -> int:
        return len(self.batches)


def _stable_id(record: JsonRecord, digest: str) -> str:
    raw = record.get(SOURCE_ID_FIELD)
    if isinstance(raw, str) and raw:
        return raw
    # No explicit source id: derive one from the content hash so replay is
    # still deterministic (a [DESIGN] fallback, never a crypto guarantee).
    return f"derived:{digest[:16]}"


def _content_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def parse_line(raw: bytes, file_rel: str, line_no: int) -> Record | None:
    """Parse one .jsonl line into a Record, or None for blank/invalid lines."""
    stripped = raw.rstrip(b"\r\n")
    if not stripped:
        return None
    try:
        obj = json.loads(stripped)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if not isinstance(obj, dict):
        return None
    text_val = obj.get(CONTENT_FIELD)
    text = text_val if isinstance(text_val, str) else json.dumps(obj, sort_keys=True)
    digest = _content_hash(text)
    claim = obj.get(CLAIM_TYPE_FIELD, "agent_suggestion")
    claim_type = claim if isinstance(claim, str) else "agent_suggestion"
    return Record(
        source_id=_stable_id(obj, digest),
        content_hash=digest,
        file_rel=file_rel,
        line_no=line_no,
        claim_type=claim_type,
        text=text,
    )


def _jsonl_files(root: Path) -> list[Path]:
    """All ``*.jsonl`` files under root, stable-sorted by relative path."""
    if not root.exists():
        return []
    return sorted(
        (p for p in root.rglob("*.jsonl") if p.is_file()),
        key=lambda p: p.relative_to(root).as_posix(),
    )


def plan_corpus(root: Path, batch_records: int = 10) -> CorpusPlan:
    """Build an ordered plan over a corpus without mutating anything.

    Records are enumerated in file order across stable-sorted files, so the
    plan is deterministic for a given tree (a [DESIGN] requirement for
    resume-and-dedup). Empty files contribute nothing.
    """
    if batch_records < 1:
        raise ValueError("batch_records must be >= 1")
    files = _jsonl_files(root)
    ordered: list[Record] = []
    for fpath in files:
        rel = fpath.relative_to(root).as_posix()
        try:
            with fpath.open("rb") as fh:
                for i, raw in enumerate(fh, start=1):
                    rec = parse_line(raw, rel, i)
                    if rec is not None:
                        ordered.append(rec)
        except OSError:
            # Unreadable file -> the record list simply omits it; the
            # fingerprint below still changes, and reconcile at the source
            # count is the caller's ground truth. Keep planning total.
            continue
    fingerprint = hashlib.sha256(
        "\n".join(f"{r.file_rel}:{r.line_no}:{r.content_hash}" for r in ordered).encode()
    ).hexdigest()

    batches: list[Batch] = []
    for start in range(0, len(ordered), batch_records):
        page = ordered[start : start + batch_records]
        batches.append(Batch(index=len(batches), records=page))
    return CorpusPlan(batches=batches, total_records=len(ordered), fingerprint=fingerprint)
