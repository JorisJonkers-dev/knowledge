"""Job store + idempotent-claim/resume orchestration (fleet-infra#246).

The consumer today writes straight through to the vault on every
delivery with no durable record of what it already did. This module
gives the worker a durable job/outbox/manifest substrate and the
orchestration to use it:

* **Idempotent claim** — ``IngestionJobService.begin`` derives a
  deterministic ``source_id`` from a note and claims it via a UNIQUE
  ``dedup_key`` in the source manifest. Replaying the same import is a
  no-op (``REPLAY``) instead of a second write.
* **Resume-for-batch** — a job owns an ordered outbox; ``checkpoint`` is
  the highest durably-applied ``seq``. ``resume`` (or a re-``begin`` on an
  in-progress job) returns exactly the records past the checkpoint, so an
  interrupted batch re-runs with no gaps or duplicates.
* **Revisions** — same ``source_id``, different content hash updates the
  job in place and never bumps ``imported_records``.
* **Retries / deadletter** — failed records bump ``attempt_count``; when
  it reaches ``max_attempts`` the job lands in ``deadletter`` with
  ``last_error`` naming ``source_type:source_id: <error>``.

Two stores implement the same :class:`JobStore` contract:

* :class:`InMemoryJobStore` — the well-tested dev/test fake (deterministic,
  no Docker). This is what the unit suite runs against.
* :class:`PostgresJobStore` — the production store over the ``schema.py``
  tables, exercised by the testcontainers integration test.

The stores are deliberately thin CRUD; all claim/resume/revision/retry
decision logic lives in the service so it is unit-testable without Postgres.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Sequence
from dataclasses import dataclass, replace
from enum import StrEnum
from typing import Any, Protocol

import structlog
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from knowledge_worker.messages import CapturedNote

DONE_SEQ = -1  # sentinel: nothing durably applied yet


class JobState(StrEnum):
    """Worker-side state machine for a single import job."""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    DONE = "done"
    FAILED = "failed"
    DEADLETTER = "deadletter"


class BatchAction(StrEnum):
    """What ``begin``/``resume`` decided for a delivery."""

    NEW = "new"
    REPLAY = "replay"  # identical import already done -> skip, no duplicate
    REVISION = "revision"  # same source, new content -> update in place
    RESUME = "resume"  # interrupted job -> finish records past checkpoint


@dataclass(slots=True)
class Job:
    job_id: str
    source_id: str
    source_type: str
    state: JobState
    attempt_count: int
    max_attempts: int
    last_error: str | None
    checkpoint: int
    note_id: str | None = None


@dataclass(slots=True)
class OutboxRecord:
    job_id: str
    seq: int
    payload: dict[str, Any]
    state: JobState


@dataclass(slots=True)
class SourceManifest:
    source_id: str
    source_type: str
    dedup_key: str
    content_hash: str | None
    imported_records: int


class Batch:
    """A claimed import and the records still to process.

    ``records`` always holds the *pending* records in ``seq`` order (empty
    for a ``REPLAY``). Callers hand each record to ``handler.write`` and
    report the outcome via ``succeed``/``fail``.
    """

    def __init__(
        self,
        job: Job,
        action: BatchAction,
        records: Sequence[OutboxRecord],
    ) -> None:
        self.job = job
        self.action = action
        self.records = list(records)

    @property
    def is_replay(self) -> bool:
        return self.action is BatchAction.REPLAY

    def pending_records(self) -> list[OutboxRecord]:
        return list(self.records)


class JobStore(Protocol):
    """Thin CRUD surface shared by the in-memory fake and Postgres."""

    def create_job(self, job: Job) -> bool: ...
    def get_job(self, job_id: str) -> Job | None: ...
    def save_job(self, job: Job) -> None: ...
    def get_or_create_manifest(self, source_id: str, source_type: str) -> SourceManifest: ...
    def save_manifest(self, manifest: SourceManifest) -> None: ...
    def append_outbox(self, job_id: str, records: Sequence[OutboxRecord]) -> None: ...
    def outbox(self, job_id: str) -> list[OutboxRecord]: ...
    def reset_outbox(self, job_id: str) -> None: ...
    def mark_record_done(self, job_id: str, seq: int) -> None: ...


def content_hash(payloads: Sequence[dict[str, Any]]) -> str:
    """Deterministic hash of a batch of JSON payloads (revision/replay key)."""

    canonical = json.dumps(
        payloads,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def derive_source_id(notes: Sequence[CapturedNote]) -> str:
    """An import's deterministic identity is its first record's ULID."""
    return notes[0].id


class InMemoryJobStore:
    """Deterministic, dependency-free :class:`JobStore` for tests.

    Mirrors the SQL semantics of :class:`PostgresJobStore` — ``create_job``
    returns ``False`` on a duplicate ``job_id`` (like ``ON CONFLICT DO
    NOTHING``) and ``(job_id, seq)`` stays unique — so the orchestration
    tests run against the same contract without Docker.
    """

    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._outbox: dict[str, list[OutboxRecord]] = {}
        self._manifests: dict[str, SourceManifest] = {}
        self._log = structlog.get_logger(__name__)

    def create_job(self, job: Job) -> bool:
        if job.job_id in self._jobs:
            return False
        self._jobs[job.job_id] = job
        return True

    def get_job(self, job_id: str) -> Job | None:
        return self._jobs.get(job_id)

    def save_job(self, job: Job) -> None:
        self._jobs[job.job_id] = job

    def get_or_create_manifest(self, source_id: str, source_type: str) -> SourceManifest:
        manifest = self._manifests.get(source_id)
        if manifest is None:
            manifest = SourceManifest(
                source_id=source_id,
                source_type=source_type,
                dedup_key=source_id,
                content_hash=None,
                imported_records=0,
            )
            self._manifests[source_id] = manifest
        return manifest

    def save_manifest(self, manifest: SourceManifest) -> None:
        self._manifests[manifest.source_id] = manifest

    def append_outbox(self, job_id: str, records: Sequence[OutboxRecord]) -> None:
        existing = {r.seq for r in self._outbox.get(job_id, [])}
        bucket = self._outbox.setdefault(job_id, [])
        for record in records:
            if record.seq in existing:
                continue  # (job_id, seq) unique, like the SQL constraint
            bucket.append(record)
            existing.add(record.seq)

    def outbox(self, job_id: str) -> list[OutboxRecord]:
        return sorted(self._outbox.get(job_id, []), key=lambda r: r.seq)

    def reset_outbox(self, job_id: str) -> None:
        for record in self._outbox.get(job_id, []):
            record.state = JobState.PENDING

    def mark_record_done(self, job_id: str, seq: int) -> None:
        for record in self._outbox.get(job_id, []):
            if record.seq == seq:
                record.state = JobState.DONE


class PostgresJobStore:
    """:class:`JobStore` over the ``schema.py`` tables via a connection pool."""

    def __init__(
        self,
        *,
        host: str,
        port: int,
        database: str,
        user: str,
        password: str,
        min_size: int = 1,
        max_size: int = 2,
    ) -> None:
        conninfo = (
            f"host={host} port={port} dbname={database} "
            f"user={user} password={password} application_name=knowledge-ingest-worker"
        )
        self._pool = ConnectionPool(
            conninfo=conninfo,
            min_size=min_size,
            max_size=max_size,
            open=False,
        )
        self._log = structlog.get_logger(__name__)

    def open(self) -> None:
        self._pool.open(wait=True, timeout=10.0)

    def close(self) -> None:
        self._pool.close()

    def create_job(self, job: Job) -> bool:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "INSERT INTO ingest_jobs "
                "(job_id, source_id, source_type, note_id, state, "
                " attempt_count, max_attempts, last_error, checkpoint) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) "
                "ON CONFLICT (job_id) DO NOTHING",
                (
                    job.job_id,
                    job.source_id,
                    job.source_type,
                    job.note_id,
                    job.state.value,
                    job.attempt_count,
                    job.max_attempts,
                    job.last_error,
                    job.checkpoint,
                ),
            )
            conn.commit()
            return cur.rowcount == 1

    def get_job(self, job_id: str) -> Job | None:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute("SELECT * FROM ingest_jobs WHERE job_id = %s", (job_id,))
            row = cur.fetchone()
        return self._row_to_job(row) if row else None

    def save_job(self, job: Job) -> None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE ingest_jobs SET state = %s, attempt_count = %s, "
                "last_error = %s, checkpoint = %s, updated_at = NOW() "
                "WHERE job_id = %s",
                (
                    job.state.value,
                    job.attempt_count,
                    job.last_error,
                    job.checkpoint,
                    job.job_id,
                ),
            )
            conn.commit()

    def get_or_create_manifest(self, source_id: str, source_type: str) -> SourceManifest:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(
                "SELECT * FROM ingest_source_manifest WHERE source_id = %s",
                (source_id,),
            )
            row = cur.fetchone()
            if row is None:
                cur.execute(
                    "INSERT INTO ingest_source_manifest "
                    "(source_id, source_type, dedup_key, content_hash, imported_records) "
                    "VALUES (%s, %s, %s, NULL, 0) ON CONFLICT (source_id) DO NOTHING",
                    (source_id, source_type, source_id),
                )
                conn.commit()
                cur.execute(
                    "SELECT * FROM ingest_source_manifest WHERE source_id = %s",
                    (source_id,),
                )
                row = cur.fetchone()
        assert row is not None
        return self._row_to_manifest(row)

    def save_manifest(self, manifest: SourceManifest) -> None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "INSERT INTO ingest_source_manifest "
                "(source_id, source_type, dedup_key, content_hash, imported_records) "
                "VALUES (%s, %s, %s, %s, %s) "
                "ON CONFLICT (source_id) DO UPDATE SET "
                "source_type = EXCLUDED.source_type, "
                "content_hash = EXCLUDED.content_hash, "
                "imported_records = EXCLUDED.imported_records, "
                "last_imported_at = NOW()",
                (
                    manifest.source_id,
                    manifest.source_type,
                    manifest.dedup_key,
                    manifest.content_hash,
                    manifest.imported_records,
                ),
            )
            conn.commit()

    def append_outbox(self, job_id: str, records: Sequence[OutboxRecord]) -> None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            for record in records:
                cur.execute(
                    "INSERT INTO ingest_outbox (job_id, seq, state, payload) "
                    "VALUES (%s, %s, %s, %s) "
                    "ON CONFLICT (job_id, seq) DO NOTHING",
                    (job_id, record.seq, record.state.value, Jsonb(record.payload)),
                )
            conn.commit()

    def outbox(self, job_id: str) -> list[OutboxRecord]:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(
                "SELECT job_id, seq, state, payload FROM ingest_outbox "
                "WHERE job_id = %s ORDER BY seq",
                (job_id,),
            )
            rows = cur.fetchall()
        return [
            OutboxRecord(
                job_id=row["job_id"],
                seq=row["seq"],
                payload=row["payload"],
                state=JobState(row["state"]),
            )
            for row in rows
        ]

    def reset_outbox(self, job_id: str) -> None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE ingest_outbox SET state = 'pending', updated_at = NOW() "
                "WHERE job_id = %s",
                (job_id,),
            )
            conn.commit()

    def mark_record_done(self, job_id: str, seq: int) -> None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE ingest_outbox SET state = 'done', updated_at = NOW() "
                "WHERE job_id = %s AND seq = %s",
                (job_id, seq),
            )
            conn.commit()

    @staticmethod
    def _row_to_job(row: dict[str, Any]) -> Job:
        return Job(
            job_id=row["job_id"],
            source_id=row["source_id"],
            source_type=row["source_type"],
            state=JobState(row["state"]),
            attempt_count=row["attempt_count"],
            max_attempts=row["max_attempts"],
            last_error=row["last_error"],
            checkpoint=row["checkpoint"],
            note_id=row["note_id"],
        )

    @staticmethod
    def _row_to_manifest(row: dict[str, Any]) -> SourceManifest:
        return SourceManifest(
            source_id=row["source_id"],
            source_type=row["source_type"],
            dedup_key=row["dedup_key"],
            content_hash=row["content_hash"],
            imported_records=row["imported_records"],
        )


class IngestionJobService:
    """Idempotent claim + resume + dedup + retry orchestration.

    Stateless except for the injected store and ``max_attempts``; a new
    instance resuming an interrupted job just re-reads the store.
    """

    def __init__(self, store: JobStore, *, max_attempts: int = 3) -> None:
        self._store = store
        self._max_attempts = max_attempts
        self._log = structlog.get_logger(__name__)

    # -- claim / resume --

    def begin(self, notes: Sequence[CapturedNote]) -> Batch:
        """Idempotently claim an import and return the work to do.

        * Unknown source -> ``NEW`` job + outbox; ``imported_records`` +1.
        * Identical re-delivery of a DONE job -> ``REPLAY`` (no-op).
        * Same source, different content -> ``REVISION`` (update in place,
          ``imported_records`` unchanged).
        * Interrupted / failed job -> ``RESUME`` past the checkpoint.
        """
        if not notes:
            raise ValueError("begin() needs at least one note")
        source_id = derive_source_id(notes)
        source_type = notes[0].type
        payloads = [n.model_dump(mode="json") for n in notes]
        digest = content_hash(payloads)

        manifest = self._store.get_or_create_manifest(source_id, source_type)
        job = self._store.get_job(source_id)

        if job is None:
            job = Job(
                job_id=source_id,
                source_id=source_id,
                source_type=source_type,
                state=JobState.PENDING,
                attempt_count=0,
                max_attempts=self._max_attempts,
                last_error=None,
                checkpoint=DONE_SEQ,
                note_id=notes[0].id,
            )
            if not self._store.create_job(job):
                created = False
                existing = self._store.get_job(source_id)
                assert existing is not None
                job = existing
            else:
                created = True
                records = [
                    OutboxRecord(job_id=source_id, seq=i, payload=p, state=JobState.PENDING)
                    for i, p in enumerate(payloads)
                ]
                self._store.append_outbox(source_id, records)
                manifest.imported_records += 1
                manifest.content_hash = digest
                self._store.save_manifest(manifest)
                self._log.info(
                    "jobs.begin", action=BatchAction.NEW.value, source_id=source_id
                )
                return Batch(job, BatchAction.NEW, records)
            if not created:
                # raced another writer; fall through to existing-job handling
                pass

        same_content = manifest.content_hash == digest
        if job.state is JobState.DONE and same_content:
            self._log.info(
                "jobs.replay", action=BatchAction.REPLAY.value, source_id=source_id
            )
            return Batch(job, BatchAction.REPLAY, [])

        if job.state is JobState.DONE:
            action = BatchAction.REVISION
            job = replace(job, checkpoint=DONE_SEQ)
            self._store.reset_outbox(source_id)
        else:
            action = BatchAction.RESUME
            # A FAILED job is a mid-retry redelivery: preserve attempt_count
            # so the AMQP redelivery loop accumulates attempts toward the
            # deadletter. Only an explicit re-import of a *dead-lettered*
            # source starts a fresh attempt budget.
            if job.state is JobState.DEADLETTER:
                job = replace(job, attempt_count=0)

        manifest = replace(manifest, content_hash=digest)
        self._store.save_manifest(manifest)
        job = replace(job, state=JobState.IN_PROGRESS)
        self._store.save_job(job)

        pending = [
            record
            for record in self._store.outbox(source_id)
            if record.seq > job.checkpoint
        ]
        self._log.info(
            "jobs.begin", action=action.value, source_id=source_id, pending=len(pending)
        )
        return Batch(job, action, pending)

    def resume(self, source_id: str) -> Batch:
        """Recover an interrupted job from its checkpoint (no claim side-effects)."""
        job = self._store.get_job(source_id)
        if job is None:
            raise KeyError(f"no job for source_id={source_id}")
        if job.state is JobState.DONE:
            return Batch(job, BatchAction.REPLAY, [])
        pending = [
            record
            for record in self._store.outbox(source_id)
            if record.seq > job.checkpoint
        ]
        return Batch(job, BatchAction.RESUME, pending)

    # -- record outcomes --

    def succeed(self, batch: Batch, record: OutboxRecord) -> None:
        """Record one outbox item as durably applied; advance the checkpoint."""
        self._store.mark_record_done(batch.job.job_id, record.seq)
        updated = replace(batch.job, checkpoint=max(batch.job.checkpoint, record.seq))
        records = self._store.outbox(batch.job.job_id)
        if records and all(r.state is JobState.DONE for r in records):
            updated = replace(updated, state=JobState.DONE)
        else:
            updated = replace(updated, state=JobState.IN_PROGRESS)
        self._store.save_job(updated)
        batch.job = updated

    def fail(self, batch: Batch, record: OutboxRecord, error: str) -> JobState:
        """Bump the attempt counter; deadletter once attempts are exhausted.

        Returns the resulting state so callers can stop the batch.
        """
        job = batch.job
        attempt = job.attempt_count + 1
        detail = f"{job.source_type}:{job.source_id}: {error}"
        state = (
            JobState.DEADLETTER
            if attempt >= job.max_attempts
            else JobState.FAILED
        )
        job = replace(job, attempt_count=attempt, last_error=detail, state=state)
        self._store.save_job(job)
        batch.job = job
        self._log.warning(
            "jobs.failed", source_id=job.source_id, attempt=attempt, state=state.value
        )
        return state

    # -- audit --

    def count_records(self, source_id: str) -> int:
        """Number of durably-applied records for a source (replay-safe)."""
        job = self._store.get_job(source_id)
        if job is None:
            return 0
        return sum(
            1 for r in self._store.outbox(job.job_id) if r.state is JobState.DONE
        )
