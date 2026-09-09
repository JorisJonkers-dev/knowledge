"""Resumable batch runner.

Drives a :class:`CorpusPlan` batch-by-batch into a sink (Hindsight),
committing each batch to a durable :class:`Checkpoint` only after the batch
balances. A crash (simulated via :class:`BackfillInterrupted`, or a real
KeyboardInterrupt) that happens *mid-batch* leaves ``next_batch`` pointing at
that batch, so a restart re-processes it; the watermark is updated per-write
and fsynced, so already-written records are recognised as duplicates on the
restart and are not re-written. That is what makes restart converge to no
duplicate knowledge.

The selection rule is pure and injected; the runner never decides what is
"relevant" — it records whatever rule was chosen per batch so the audit trail
shows what went into Hindsight vs stayed in the corpus.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from backfill_batching.checkpoint import (
    BatchOutcome,
    Checkpoint,
    SelectionRecord,
)
from backfill_batching.cost import CostEstimate, enforce_allowance, estimate_cost
from backfill_batching.manifest import (
    SELECTABLE_CLAIM_TYPES,
    Batch,
    CorpusPlan,
    Record,
)


class BackfillInterrupted(RuntimeError):
    """Raised to simulate a crash mid-run. Test-only; not used in production."""


class Sink(Protocol):
    def write(self, record: Record) -> None: ...


PolicyFn = Callable[[Record], bool]


def default_selection_policy(rec: Record) -> bool:
    """[DESIGN] selection rule: select a record if it is a relevant experience.

    Sends to Hindsight only records whose claim type is a durable,
    recall-worthy decision/outcome/preference. Everything else stays in the
    corpus (is retained, never dropped).
    """
    return rec.claim_type in SELECTABLE_CLAIM_TYPES


@dataclass
class RunReport:
    plan: CorpusPlan
    cost: CostEstimate
    completed_batches: int
    total_batches: int
    selected_total: int = 0
    retained_total: int = 0
    duplicates_total: int = 0
    failed_total: int = 0
    dry_run: bool = False
    interrupted: bool = False

    def reconcile(self) -> int:
        """Sum of the four tallies; must equal plan.total_records."""
        return (
            self.selected_total
            + self.retained_total
            + self.duplicates_total
            + self.failed_total
        )


@dataclass
class VerboseSink:
    """In-memory sink that records what it wrote, for tests / dry-run."""

    written: list[Record] = field(default_factory=list)

    def write(self, record: Record) -> None:
        self.written.append(record)


@dataclass
class Runner:
    """Resumable batch runner over a corpus plan."""

    plan: CorpusPlan
    checkpoint_path: Path
    sink: Sink
    policy: PolicyFn = default_selection_policy
    policy_name: str = "default_selection_policy"
    llm_pass_enabled: bool = False
    llm_allowance_usd: float | None = None
    model_id: str = "openrouter/pareto-code"
    dry_run: bool = False
    run_id: str | None = None
    # Test seam: raise after this many total writes to simulate a crash.
    crash_after_writes: int | None = None

    def estimate(self) -> CostEstimate:
        return estimate_cost(
            self.plan.total_records,
            llm_pass_enabled=self.llm_pass_enabled,
            model_id=self.model_id,
        )

    def run(self) -> RunReport:
        cost = self.estimate()
        enforce_allowance(cost, self.llm_allowance_usd)

        report = RunReport(
            plan=self.plan,
            cost=cost,
            completed_batches=0,
            total_batches=self.plan.total_batches,
            dry_run=self.dry_run,
        )

        ckpt = self._load_or_init()
        if self.dry_run:
            return report

        total_writes = 0
        for batch in self.plan.batches:
            if batch.index < ckpt.next_batch:
                continue  # already committed; resume past it
            outcome, selection, writes_this_batch, crashed = self._run_batch(
                batch, ckpt, total_writes
            )
            total_writes += writes_this_batch
            if crashed:
                report.interrupted = True
                raise BackfillInterrupted("crash injected mid-batch")
            ckpt.commit_batch(outcome, selection)
            ckpt.save()
            report.completed_batches += 1

        report.selected_total = sum(o.selected for o in ckpt.outcomes.values())
        report.retained_total = sum(o.retained for o in ckpt.outcomes.values())
        report.duplicates_total = sum(o.duplicates for o in ckpt.outcomes.values())
        report.failed_total = sum(o.failed for o in ckpt.outcomes.values())
        return report

    def _load_or_init(self) -> Checkpoint:
        ckpt = Checkpoint.load(self.checkpoint_path)
        if ckpt is None:
            ckpt = Checkpoint(path=self.checkpoint_path, run_id=self.run_id or str(uuid.uuid4()))
        if ckpt.corpus_fingerprint and ckpt.corpus_fingerprint != self.plan.fingerprint:
            raise RuntimeError(
                "corpus changed since checkpoint; resolve #261 or reset checkpoint"
            )
        ckpt.corpus_fingerprint = self.plan.fingerprint
        return ckpt

    def _run_batch(
        self,
        batch: Batch,
        ckpt: Checkpoint,
        total_writes_so_far: int,
    ) -> tuple[BatchOutcome, SelectionRecord, int, bool]:
        outcome = BatchOutcome(
            index=batch.index,
            source_lines=len(batch.records),
            selected=0,
            retained=0,
            duplicates=0,
            failed=0,
        )
        selected_ids: list[str] = []
        retained_ids: list[str] = []
        writes_this_batch = 0
        for rec in batch.records:
            if ckpt.is_duplicate(rec):
                outcome.duplicates += 1
                continue
            if self.policy(rec):
                try:
                    self.sink.write(rec)
                except Exception:
                    outcome.failed += 1
                else:
                    outcome.selected += 1
                    selected_ids.append(rec.source_id)
                    writes_this_batch += 1
                    # Watermark is durable *per write*: a crash mid-batch
                    # cannot re-write this record on restart.
                    ckpt.observe(rec)
                    ckpt.save()
                    if (
                        self.crash_after_writes is not None
                        and total_writes_so_far + writes_this_batch
                        >= self.crash_after_writes
                    ):
                        return (
                            outcome,
                            self._selection(batch, selected_ids, retained_ids),
                            writes_this_batch,
                            True,
                        )
            else:
                outcome.retained += 1
                retained_ids.append(rec.source_id)
        return (
            outcome,
            self._selection(batch, selected_ids, retained_ids),
            writes_this_batch,
            False,
        )

    def _selection(
        self, batch: Batch, selected_ids: list[str], retained_ids: list[str]
    ) -> SelectionRecord:
        return SelectionRecord(
            batch_index=batch.index,
            rule_name=self.policy_name,
            selected_ids=selected_ids,
            retained_ids=retained_ids,
        )
