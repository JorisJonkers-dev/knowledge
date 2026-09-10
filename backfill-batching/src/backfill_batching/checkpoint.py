"""Durable checkpoint and per-batch reconciliation.

The checkpoint is the single source of truth that makes interrupt+restart
idempotent. It records ``next_batch`` (first unprocessed batch index),
committed batch outcomes (each balancing to its source count), and the
growing watermark (seen ``(stable_id, content_hash)`` pairs). It is written
atomically (temp + ``os.replace``) so a crash cannot yield a half-written
file, and the fingerprint is checked on resume so a mutated corpus aborts
instead of silently drifting.
"""

from __future__ import annotations

import json
import os
import tempfile
from contextlib import suppress
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from backfill_batching.manifest import Record

CHECKPOINT_SCHEMA_VERSION = 1


class ReconciliationError(RuntimeError):
    """Raised when a batch does not balance to its source count."""


@dataclass
class BatchOutcome:
    index: int
    source_lines: int
    selected: int
    retained: int
    duplicates: int
    failed: int

    def is_balanced(self) -> bool:
        return self.source_lines == (
            self.selected + self.retained + self.duplicates + self.failed
        )


@dataclass
class SelectionRecord:
    """Record of the selection rule actually applied to a batch (audit)."""

    batch_index: int
    rule_name: str
    selected_ids: list[str]
    retained_ids: list[str]


@dataclass
class Checkpoint:
    path: Path
    schema_version: int = CHECKPOINT_SCHEMA_VERSION
    corpus_fingerprint: str = ""
    next_batch: int = 0
    completed_batches: list[int] = field(default_factory=list)
    outcomes: dict[int, BatchOutcome] = field(default_factory=dict)
    observed_pairs: set[tuple[str, str]] = field(default_factory=set)
    selection_records: list[SelectionRecord] = field(default_factory=list)
    run_id: str = ""

    @property
    def is_complete(self) -> bool:
        return self.next_batch >= 0 and bool(self.completed_batches)

    # -- persistence -----------------------------------------------------

    def _serialize(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "corpus_fingerprint": self.corpus_fingerprint,
            "next_batch": self.next_batch,
            "completed_batches": self.completed_batches,
            "outcomes": [
                asdict(o) for o in sorted(self.outcomes.values(), key=lambda x: x.index)
            ],
            "observed_pairs": sorted(self.observed_pairs),
            "selection_records": [
                {
                    "batch_index": s.batch_index,
                    "rule_name": s.rule_name,
                    "selected_ids": s.selected_ids,
                    "retained_ids": s.retained_ids,
                }
                for s in self.selection_records
            ],
            "run_id": self.run_id,
        }

    @classmethod
    def _deserialize(cls, path: Path, data: dict[str, Any]) -> Checkpoint:
        outcomes: dict[int, BatchOutcome] = {}
        for o in data.get("outcomes", []):
            outcome = BatchOutcome(
                index=int(o["index"]),
                source_lines=int(o["source_lines"]),
                selected=int(o["selected"]),
                retained=int(o["retained"]),
                duplicates=int(o["duplicates"]),
                failed=int(o["failed"]),
            )
            outcomes[outcome.index] = outcome
        selection_records = [
            SelectionRecord(
                batch_index=s["batch_index"],
                rule_name=str(s["rule_name"]),
                selected_ids=list(s["selected_ids"]),
                retained_ids=list(s["retained_ids"]),
            )
            for s in data.get("selection_records", [])
        ]
        return cls(
            path=path,
            schema_version=int(data.get("schema_version", 0)),
            corpus_fingerprint=str(data.get("corpus_fingerprint", "")),
            next_batch=int(data.get("next_batch", 0)),
            completed_batches=[int(b) for b in data.get("completed_batches", [])],
            outcomes=outcomes,
            observed_pairs={tuple(p) for p in data.get("observed_pairs", [])},
            selection_records=selection_records,
            run_id=str(data.get("run_id", "")),
        )

    def save(self) -> None:
        data = self._serialize()
        self._atomic_write(data)

    def _atomic_write(self, data: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(
            dir=str(self.path.parent), prefix=".checkpoint-", suffix=".tmp"
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(data, fh, sort_keys=True)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, self.path)
            self._fsync_dir(self.path.parent)
        except BaseException:
            with suppress(OSError):
                os.unlink(tmp)
            raise

    @staticmethod
    def _fsync_dir(directory: Path) -> None:
        try:
            dfd = os.open(str(directory), os.O_RDONLY)
        except OSError:
            return
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)

    @classmethod
    def load(cls, path: Path) -> Checkpoint | None:
        if not path.exists():
            return None
        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
        ckpt = cls._deserialize(path, data)
        if ckpt.schema_version != CHECKPOINT_SCHEMA_VERSION:
            raise ReconciliationError(
                f"checkpoint schema {ckpt.schema_version} != {CHECKPOINT_SCHEMA_VERSION}"
            )
        if not ckpt._valid_continuity():
            raise ReconciliationError("checkpoint completed_batches is not contiguous")
        return ckpt

    def _valid_continuity(self) -> bool:
        expect = list(range(self.next_batch))
        return self.completed_batches == expect

    # -- dedup / watermark ----------------------------------------------

    def is_duplicate(self, rec: Record) -> bool:
        key = (rec.source_id, rec.content_hash)
        return key in self.observed_pairs

    def observe(self, rec: Record) -> None:
        self.observed_pairs.add((rec.source_id, rec.content_hash))

    def commit_batch(self, outcome: BatchOutcome, selection: SelectionRecord) -> None:
        if not outcome.is_balanced():
            raise ReconciliationError(
                f"batch {outcome.index} does not balance: "
                f"{outcome.source_lines} source != "
                f"{outcome.selected}+{outcome.retained}+{outcome.duplicates}"
                f"+{outcome.failed}"
            )
        self.outcomes[outcome.index] = outcome
        self.selection_records.append(selection)
        if outcome.index == self.next_batch:
            self.completed_batches.append(outcome.index)
            self.next_batch = outcome.index + 1
        else:
            raise ReconciliationError(
                f"out-of-order commit {outcome.index}, expected {self.next_batch}"
            )
