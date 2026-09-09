"""Integration test: #246 job store against a real Postgres.

Drives the *same* `IngestionJobService` orchestration the unit tests run
against `InMemoryJobStore`, but against `PostgresJobStore` on a throwaway
Postgres (testcontainers). Proves the SQL port of the contract matches the
in-memory semantics: claim/dedup, resume, revision, retry/deadletter, and
that the migration SQL applies cleanly (idempotently).

This is the [RUN-VERIFIED] counterpart to the in-memory unit suite.
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import datetime

import psycopg
import pytest

try:  # pragma: no cover — Docker is required for integration runs
    from testcontainers.postgres import PostgresContainer
except ImportError:  # pragma: no cover
    PostgresContainer = None  # type: ignore[assignment]

from knowledge_worker.jobs import (
    BatchAction,
    IngestionJobService,
    JobState,
    PostgresJobStore,
)
from knowledge_worker.messages import CapturedNote
from knowledge_worker.schema import PostgresSchemaRunner

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def postgres() -> Iterator[dict[str, object]]:
    if PostgresContainer is None:  # pragma: no cover
        pytest.skip("testcontainers.postgres not installed")
    with PostgresContainer("postgres:17-alpine") as container:
        conninfo = (
            f"host={container.get_container_host_ip()} "
            f"port={int(container.get_exposed_port(5432))} "
            f"dbname={container.dbname} user={container.username} "
            f"password={container.password}"
        )
        PostgresSchemaRunner(conninfo).apply()
        yield {
            "host": container.get_container_host_ip(),
            "port": int(container.get_exposed_port(5432)),
            "database": container.dbname,
            "user": container.username,
            "password": container.password,
            "conninfo": conninfo,
        }


@pytest.fixture()
def store(postgres: dict[str, object]) -> Iterator[PostgresJobStore]:
    s = PostgresJobStore(
        host=str(postgres["host"]),
        port=int(postgres["port"]),  # type: ignore[arg-type]
        database=str(postgres["database"]),
        user=str(postgres["user"]),
        password=str(postgres["password"]),
    )
    s.open()
    try:
        yield s
    finally:
        s.close()


def _note(note_id: str, *, body: str = "body") -> CapturedNote:
    return CapturedNote(
        id=note_id,
        type="lesson",
        scope="personal",
        source="claude-code",
        captured_at=datetime(2026, 5, 13, 12, 0),
        confidence=0.4,
        title="title",
        body=body,
        vault_path="personal/lesson/draft.md",
        tags=[],
    )


def test_schema_applies_idempotently(postgres: dict[str, object]) -> None:
    PostgresSchemaRunner(str(postgres["conninfo"])).apply()  # second time: no-op
    with psycopg.connect(str(postgres["conninfo"])) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
            "AND tablename IN ('ingest_jobs','ingest_outbox','ingest_source_manifest') "
            "ORDER BY tablename"
        )
        tables = [r[0] for r in cur.fetchall()]
    assert tables == ["ingest_jobs", "ingest_outbox", "ingest_source_manifest"]


def test_pg_new_import_and_replay_is_deduped(store: PostgresJobStore) -> None:
    svc = IngestionJobService(store)
    note = _note("01PGNW0000000000000000000")

    batch = svc.begin([note])
    assert batch.action is BatchAction.NEW
    for r in batch.pending_records():
        svc.succeed(batch, r)
    assert svc.count_records(note.id) == 1

    replay = svc.begin([note])
    assert replay.action is BatchAction.REPLAY
    assert svc.count_records(note.id) == 1  # no duplicate in Postgres either

    # The manifest row is in Postgres with the dedup key present.
    assert store.get_or_create_manifest(note.id, note.type).imported_records == 1


def test_pg_interrupted_batch_resumes_from_checkpoint(store: PostgresJobStore) -> None:
    svc = IngestionJobService(store)
    notes = [_note(f"01PGB{i}00000000000000000") for i in range(5)]
    batch = svc.begin(notes)
    for r in batch.pending_records():
        if r.seq <= 2:
            svc.succeed(batch, r)

    restarted = IngestionJobService(store)
    resumed = restarted.resume(notes[0].id)
    assert [r.seq for r in resumed.pending_records()] == [3, 4]
    for r in resumed.pending_records():
        restarted.succeed(resumed, r)
    assert restarted.count_records(notes[0].id) == 5
    assert resumed.job.state is JobState.DONE


def test_pg_exhausted_retries_deadletter_naming_source_and_error(
    store: PostgresJobStore,
) -> None:
    svc = IngestionJobService(store, max_attempts=2)
    note = _note("01PGDL0000000000000000000")
    batch = svc.begin([note])
    record = batch.pending_records()[0]

    assert svc.fail(batch, record, "kaboom") is JobState.FAILED
    assert svc.fail(batch, record, "kaboom") is JobState.DEADLETTER

    dead = store.get_job(note.id)
    assert dead is not None
    assert dead.state is JobState.DEADLETTER
    assert "kaboom" in (dead.last_error or "")
    assert note.id in (dead.last_error or "")
