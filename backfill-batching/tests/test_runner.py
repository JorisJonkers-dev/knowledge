"""Proofs for the three backfill invariants (fleet-infra#249).

1. Interrupt + restart produces **no duplicate records** (count + a boundary
   spot-check that the record at the crash boundary is written exactly once).
2. Per-batch success/failure **reconciles to the source count**.
3. The **selection rule is recorded** per batch (audit trail).
Plus the cost-known-before-run protocol (dry-run writes nothing; LLM pass is
refused without an allowance).
"""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import (
    EXPECTED_RETAINED,
    EXPECTED_SELECTED,
    TOTAL_RECORDS,
    iter_sink_ids,
)

from backfill_batching.checkpoint import (
    BatchOutcome,
    Checkpoint,
    ReconciliationError,
    SelectionRecord,
)
from backfill_batching.cost import CostPolicyError, estimate_cost
from backfill_batching.manifest import CorpusPlan, plan_corpus
from backfill_batching.runner import (
    BackfillInterrupted,
    Runner,
    VerboseSink,
    default_selection_policy,
)

BATCH_SIZE = 3


def _plan(corpus: Path) -> CorpusPlan:
    return plan_corpus(corpus, batch_records=BATCH_SIZE)


def _sink_ids(written: list[object]) -> list[str]:
    return iter_sink_ids(written)


# ---------------------------------------------------------------------------
# 1. Interrupt + restart produces no duplicate records
# ---------------------------------------------------------------------------


def test_interrupt_restart_no_duplicate_records(
    corpus: Path, checkpoint_path: Path
) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()

    # Run 1: crash after 8 writes (mid-batch; batches of 3 => lands inside a
    # later batch, well past the first committed batch boundary).
    first = Runner(
        plan=plan,
        checkpoint_path=checkpoint_path,
        sink=sink,
        crash_after_writes=8,
    )
    with pytest.raises(BackfillInterrupted):
        first.run()
    wrote_before_crash = list(sink.written)

    # Run 2: fresh process, same durable checkpoint + sink, resumes.
    second = Runner(plan=plan, checkpoint_path=checkpoint_path, sink=sink)
    report = second.run()

    all_ids = _sink_ids(sink.written)
    unique_ids = list(dict.fromkeys(all_ids))  # order-preserving dedup
    assert unique_ids == all_ids, "sink received a duplicate record"
    assert len(unique_ids) == len(EXPECTED_SELECTED), (
        "union of written records must equal the selected set once"
    )
    assert set(unique_ids) == EXPECTED_SELECTED

    # Boundary spot-check: the record that was being written at the crash
    # point appears exactly once.
    if wrote_before_crash:
        boundary_id = wrote_before_crash[-1].source_id
        assert all_ids.count(boundary_id) == 1

    # Totals still reconcile to the source count.
    assert report.reconcile() == TOTAL_RECORDS


def test_restart_committed_batch_is_not_reprocessed(
    corpus: Path, checkpoint_path: Path
) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()

    first = Runner(
        plan=plan, checkpoint_path=checkpoint_path, sink=sink, crash_after_writes=4
    )
    with pytest.raises(BackfillInterrupted):
        first.run()

    completed_after_crash = Checkpoint.load(checkpoint_path)
    assert completed_after_crash is not None
    assert completed_after_crash.next_batch >= 1  # at least one batch committed

    second = Runner(plan=plan, checkpoint_path=checkpoint_path, sink=sink)
    second.run()

    # The first committed batch contributes exactly its records once.
    ids = _sink_ids(sink.written)
    assert len(ids) == len(set(ids))


# ---------------------------------------------------------------------------
# 2. Per-batch success/failure reconciles to the source count
# ---------------------------------------------------------------------------


def test_reconcile_to_source_count(corpus: Path, checkpoint_path: Path) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()
    report = Runner(plan=plan, checkpoint_path=checkpoint_path, sink=sink).run()

    assert report.reconcile() == TOTAL_RECORDS
    assert report.selected_total == len(EXPECTED_SELECTED)
    assert report.retained_total == len(EXPECTED_RETAINED)
    assert report.duplicates_total == 0
    assert report.failed_total == 0
    assert report.completed_batches == report.total_batches

    ckpt = Checkpoint.load(checkpoint_path)
    assert ckpt is not None
    for outcome in ckpt.outcomes.values():
        assert outcome.is_balanced(), f"batch {outcome.index} unbalanced"


def test_batch_internal_reconcile_violation_raises() -> None:
    bad = BatchOutcome(index=0, source_lines=5, selected=1, retained=1, duplicates=2, failed=0)
    assert bad.is_balanced() is False
    with pytest.raises(ReconciliationError):
        # commit_batch raises for unbalanced outcome
        ckpt = Checkpoint(path=Path("unused.json"))
        ckpt.commit_batch(bad, _dummy_selection(0))


def _dummy_selection(index: int) -> SelectionRecord:
    return SelectionRecord(batch_index=index, rule_name="x", selected_ids=[], retained_ids=[])


def test_failed_records_reconcile_too(corpus: Path, checkpoint_path: Path) -> None:
    plan = _plan(corpus)

    class FlakySink:
        def __init__(self) -> None:
            self.written: list[object] = []
            self.fail_on = {"p2/b-3"}

        def write(self, record: object) -> None:
            if record.source_id in self.fail_on:
                raise RuntimeError("transient")
            self.written.append(record)

    sink = FlakySink()
    report = Runner(plan=plan, checkpoint_path=checkpoint_path, sink=sink).run()

    assert report.failed_total == 1
    assert report.reconcile() == TOTAL_RECORDS


# ---------------------------------------------------------------------------
# 3. Selection rule is recorded
# ---------------------------------------------------------------------------


def test_selection_rule_recorded(corpus: Path, checkpoint_path: Path) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()
    Runner(
        plan=plan,
        checkpoint_path=checkpoint_path,
        sink=sink,
        policy_name="selected_claim_types",
    ).run()

    ckpt = Checkpoint.load(checkpoint_path)
    assert ckpt is not None
    assert ckpt.selection_records, "selection rule must be recorded"
    # The union of selected+retained ids across batches covers every source.
    all_ids = {
        sid
        for s in ckpt.selection_records
        for sid in s.selected_ids + s.retained_ids
    }
    assert all_ids == EXPECTED_SELECTED | EXPECTED_RETAINED
    assert all(s.rule_name == "selected_claim_types" for s in ckpt.selection_records)


def test_dry_run_records_no_selection_and_writes_nothing(
    corpus: Path, checkpoint_path: Path
) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()
    report = Runner(
        plan=plan, checkpoint_path=checkpoint_path, sink=sink, dry_run=True
    ).run()
    assert report.dry_run is True
    assert sink.written == []
    assert report.completed_batches == 0
    # Cost projection is available before anything is written.
    assert report.cost.total_usd == 0.0  # embed-only self-hosted
    assert report.cost.llm_pass_enabled is False


# ---------------------------------------------------------------------------
# Cost-known-before-run protocol (#240 estimate)
# ---------------------------------------------------------------------------


def test_embed_only_cost_is_zero(corpus: Path) -> None:
    plan = _plan(corpus)
    est = estimate_cost(plan.total_records, llm_pass_enabled=False)
    assert est.embed_cost_usd == 0.0
    assert est.extract_cost_usd == 0.0
    assert est.total_usd == 0.0


def test_llm_pass_refused_without_allowance(corpus: Path, checkpoint_path: Path) -> None:
    plan = _plan(corpus)
    runner = Runner(
        plan=plan,
        checkpoint_path=checkpoint_path,
        sink=VerboseSink(),
        llm_pass_enabled=True,
    )
    with pytest.raises(CostPolicyError):
        runner.run()


def test_llm_pass_refused_when_over_allowance(
    corpus: Path, checkpoint_path: Path
) -> None:
    plan = _plan(corpus)
    runner = Runner(
        plan=plan,
        checkpoint_path=checkpoint_path,
        sink=VerboseSink(),
        llm_pass_enabled=True,
        llm_allowance_usd=0.0001,
    )
    with pytest.raises(CostPolicyError):
        runner.run()


def test_llm_pass_allowed_with_adequate_allowance(
    corpus: Path, checkpoint_path: Path
) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()
    runner = Runner(
        plan=plan,
        checkpoint_path=checkpoint_path,
        sink=sink,
        llm_pass_enabled=True,
        llm_allowance_usd=100.0,
    )
    report = runner.run()
    assert report.cost.llm_pass_enabled is True
    assert sink.written  # ran, with allowance


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------


def test_plan_stable_and_deterministic(corpus: Path) -> None:
    a = plan_corpus(corpus, batch_records=BATCH_SIZE)
    b = plan_corpus(corpus, batch_records=BATCH_SIZE)
    assert a.fingerprint == b.fingerprint
    assert a.total_records == TOTAL_RECORDS
    assert a.total_batches > 0


def test_corpus_change_refuses_resume(corpus: Path, checkpoint_path: Path) -> None:
    plan = _plan(corpus)
    sink = VerboseSink()
    Runner(plan=plan, checkpoint_path=checkpoint_path, sink=sink).run()

    # Mutate the corpus -> fingerprint differs -> resume refuses.
    (corpus / "proj-one" / "extra.jsonl").write_text(
        '{"source_id":"new","claim_type":"explicit_user_decision","content":"x"}\n'
    )
    mutated = _plan(corpus)
    with pytest.raises(RuntimeError):
        Runner(plan=mutated, checkpoint_path=checkpoint_path, sink=VerboseSink()).run()


def test_default_policy_selects_expected(corpus: Path) -> None:
    plan = _plan(corpus)
    for batch in plan.batches:
        for rec in batch.records:
            expected = rec.source_id in EXPECTED_SELECTED
            assert default_selection_policy(rec) is expected
