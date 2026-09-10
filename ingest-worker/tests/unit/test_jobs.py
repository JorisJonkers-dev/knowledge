"""Unit tests for #246 job-store orchestration.

Run against :class:`InMemoryJobStore` — a faithful, dependency-free port
of the Postgres store — so the claim/resume/revision/retry decision logic
is exercised deterministically without Docker. The same SQL store is
exercised against a real Postgres in ``tests/integration/test_job_store.py``.

Scenarios proved here (fleet-infra#246):
1. replay produces no duplicate records (count check)
2. an interrupted batch resumes from its checkpoint with no gaps/duplicates
3. a revised source updates in place rather than appending
4. exhausted retries land in a deadletter state naming source + error
"""

from __future__ import annotations

from datetime import datetime

from knowledge_worker.jobs import (
    BatchAction,
    IngestionJobService,
    InMemoryJobStore,
    JobState,
)
from knowledge_worker.messages import CapturedNote


def _note(
    note_id: str,
    *,
    title: str = "title",
    body: str = "body",
    source: str = "claude-code",
) -> CapturedNote:
    return CapturedNote(
        id=note_id,
        type="lesson",
        scope="personal",
        source=source,
        captured_at=datetime(2026, 5, 13, 12, 0),
        confidence=0.4,
        title=title,
        body=body,
        vault_path="personal/lesson/draft.md",
        tags=[],
    )


def test_new_import_creates_job_records_and_bumps_manifest() -> None:
    store = InMemoryJobStore()
    svc = IngestionJobService(store)
    note = _note("01NEW000000000000000000000")

    batch = svc.begin([note])

    assert batch.action is BatchAction.NEW
    assert [r.seq for r in batch.pending_records()] == [0]
    assert store.get_job(note.id) is not None
    assert store.get_or_create_manifest(note.id, note.type).imported_records == 1


def test_replay_produces_no_duplicate_records() -> None:
    """A redelivered identical import is skipped — record count stays 1."""
    store = InMemoryJobStore()
    svc = IngestionJobService(store)
    note = _note("01REPLAY00000000000000000")

    first = svc.begin([note])
    for r in first.pending_records():
        svc.succeed(first, r)
    assert svc.count_records(note.id) == 1

    # Re-delivery of the *same* payload (the consumer ACKs came after the
    # vault write, so the broker re-queues it exactly once) must not append.
    replay = svc.begin([note])
    assert replay.action is BatchAction.REPLAY
    assert replay.pending_records() == []

    assert svc.count_records(note.id) == 1  # still exactly one durable record
    assert len(store.outbox(note.id)) == 1


def test_interrupted_batch_resumes_from_checkpoint_without_gaps_or_duplicates() -> None:
    store = InMemoryJobStore()
    notes = [_note(f"01BATCH{i}000000000000000") for i in range(5)]
    svc = IngestionJobService(store)

    batch = svc.begin(notes)
    applied: list[int] = []
    # Simulate an interruption: only the first three records are durably
    # applied (checkpoint should advance to 2) before the process dies.
    for r in batch.pending_records():
        if r.seq <= 2:
            svc.succeed(batch, r)
            applied.append(r.seq)
    assert batch.job.state is JobState.IN_PROGRESS
    assert batch.job.checkpoint == 2
    assert svc.count_records(notes[0].id) == 3

    # A fresh writer (as after a restart) recovers only what's left.
    restarted = IngestionJobService(store)
    resumed = restarted.resume(notes[0].id)
    assert resumed.action is BatchAction.RESUME
    assert batch.action is BatchAction.NEW  # begin() decided NEW, not resume
    pending = [r.seq for r in resumed.pending_records()]
    assert pending == [3, 4]  # no gap, and 0..2 are not re-done

    for r in resumed.pending_records():
        restarted.succeed(resumed, r)
        applied.append(r.seq)

    assert restarted.count_records(notes[0].id) == 5
    assert sorted(applied) == [0, 1, 2, 3, 4]
    assert len(applied) == len(set(applied))  # each seq applied exactly once
    assert resumed.job.state is JobState.DONE


def test_revision_updates_in_place_rather_than_appending() -> None:
    store = InMemoryJobStore()
    svc = IngestionJobService(store)
    note = _note("01REV000000000000000000000", body="v1")

    batch = svc.begin([note])
    for r in batch.pending_records():
        svc.succeed(batch, r)
    assert store.get_or_create_manifest(note.id, note.type).imported_records == 1

    # Same source_id (id), different content -> a revision, not a new import.
    revised = _note("01REV000000000000000000000", body="v2")
    batch2 = svc.begin([revised])
    assert batch2.action is BatchAction.REVISION
    for r in batch2.pending_records():
        svc.succeed(batch2, r)

    manifest = store.get_or_create_manifest(note.id, note.type)
    assert manifest.imported_records == 1  # updated, not appended/counted
    assert svc.count_records(note.id) == 1
    assert len(store.outbox(note.id)) == 1  # still a single outbox row


def test_exhausted_retries_land_in_deadletter_naming_source_and_error() -> None:
    store = InMemoryJobStore()
    svc = IngestionJobService(store, max_attempts=2)
    note = _note("01DEADL000000000000000000")

    batch = svc.begin([note])
    record = batch.pending_records()[0]

    first = svc.fail(batch, record, "kaboom")
    assert first is JobState.FAILED
    assert batch.job.attempt_count == 1

    second = svc.fail(batch, record, "kaboom")
    assert second is JobState.DEADLETTER

    dead = store.get_job(note.id)
    assert dead is not None
    assert dead.state is JobState.DEADLETTER
    assert dead.last_error is not None
    assert note.type in dead.last_error  # names the source type
    assert note.id in dead.last_error  # names the source id
    assert "kaboom" in dead.last_error  # names the error


def test_retryable_failure_preserves_attempt_count_on_redelivery() -> None:
    """A FAILED job re-begun by an AMQP redelivery keeps its attempt budget."""
    store = InMemoryJobStore()
    svc = IngestionJobService(store, max_attempts=3)
    note = _note("01RETRY000000000000000000")

    batch = svc.begin([note])
    record = batch.pending_records()[0]
    svc.fail(batch, record, "boom")  # attempt 1 -> FAILED

    redelivered = svc.begin([note])  # broker redelivers the same payload
    assert redelivered.action is BatchAction.RESUME
    assert redelivered.pending_records() != []
    assert redelivered.job.attempt_count == 1  # not reset on retry

    state = svc.fail(redelivered, redelivered.pending_records()[0], "boom")
    assert state is JobState.FAILED  # attempt 2 of 3
    assert redelivered.job.attempt_count == 2


def test_deadletter_resets_attempt_budget_on_external_reimport() -> None:
    store = InMemoryJobStore()
    svc = IngestionJobService(store, max_attempts=1)
    note = _note("01RESET000000000000000000")

    batch = svc.begin([note])
    svc.fail(batch, batch.pending_records()[0], "nope")
    dead = store.get_job(note.id)
    assert dead is not None and dead.state is JobState.DEADLETTER

    # A fresh import attempt of the dead-lettered source starts over.
    again = svc.begin([note])
    assert again.job.attempt_count == 0
    assert again.action is BatchAction.RESUME


def test_begin_with_no_notes_raises() -> None:
    svc = IngestionJobService(InMemoryJobStore())
    try:
        svc.begin([])
        raise AssertionError("expected ValueError")
    except ValueError:
        pass


def test_resume_unknown_source_raises() -> None:
    svc = IngestionJobService(InMemoryJobStore())
    try:
        svc.resume("01UNKNOWN00000000000000")
        raise AssertionError("expected KeyError")
    except KeyError:
        pass


def test_resume_of_done_job_is_replay() -> None:
    store = InMemoryJobStore()
    svc = IngestionJobService(store)
    note = _note("01DONE0000000000000000000")
    batch = svc.begin([note])
    for r in batch.pending_records():
        svc.succeed(batch, r)
    assert svc.resume(note.id).action is BatchAction.REPLAY


def test_inmemory_store_create_job_is_deduped() -> None:
    store = InMemoryJobStore()
    from knowledge_worker.jobs import Job

    job = Job(
        job_id="same",
        source_id="same",
        source_type="lesson",
        state=JobState.PENDING,
        attempt_count=0,
        max_attempts=3,
        last_error=None,
        checkpoint=-1,
    )
    assert store.create_job(job) is True
    assert store.create_job(job) is False  # (job_id) unique, like SQL PK


def test_content_hash_is_deterministic_and_content_sensitive() -> None:
    from knowledge_worker.jobs import content_hash

    p1 = _note("01HASH0000000000000000000").model_dump(mode="json")
    p2 = _note("01HASH0000000000000000000").model_dump(mode="json")
    p3 = _note("01HASH0000000000000000000", body="changed").model_dump(mode="json")
    assert content_hash([p1]) == content_hash([p2])
    assert content_hash([p1]) != content_hash([p3])
