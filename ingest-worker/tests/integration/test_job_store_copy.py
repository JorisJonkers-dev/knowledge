"""Integration test: the job-store copy migration against real Postgres
(fleet-infra#246 placement).

Both source and destination live in the same throwaway Postgres cluster
(two databases, `src_db` and `dst_db`) — mirroring the estate's real
topology, where the old and new job stores are two databases on one
Postgres instance, not two separate servers. Proves rows genuinely move
(including the ``ingest_outbox`` FK to ``ingest_jobs`` and the sequence
resync), that verification catches a real short-copy, and that a second
run is a no-op (idempotent, so the copy Job can be Flux-forced).
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

from knowledge_worker.job_store_copy import (
    JobStoreCopier,
    JobStoreCopyVerificationError,
    build_conninfo,
)
from knowledge_worker.jobs import BatchAction, IngestionJobService, PostgresJobStore
from knowledge_worker.messages import CapturedNote
from knowledge_worker.schema import PostgresSchemaRunner

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def cluster() -> Iterator[dict[str, object]]:
    if PostgresContainer is None:  # pragma: no cover
        pytest.skip("testcontainers.postgres not installed")
    with PostgresContainer("postgres:17-alpine") as container:
        host = container.get_container_host_ip()
        port = int(container.get_exposed_port(5432))
        admin_conninfo = build_conninfo(
            host=host,
            port=port,
            dbname=container.dbname,
            user=container.username,
            password=container.password,
        )
        with psycopg.connect(admin_conninfo, autocommit=True) as conn, conn.cursor() as cur:
            cur.execute("CREATE DATABASE src_db")
            cur.execute("CREATE DATABASE dst_db")

        def _conninfo(dbname: str) -> str:
            return build_conninfo(
                host=host, port=port, dbname=dbname, user=container.username,
                password=container.password,
            )

        source_conninfo = _conninfo("src_db")
        dest_conninfo = _conninfo("dst_db")
        PostgresSchemaRunner(source_conninfo).apply()
        PostgresSchemaRunner(dest_conninfo).apply()
        yield {"source": source_conninfo, "dest": dest_conninfo}


def _note(note_id: str) -> CapturedNote:
    return CapturedNote(
        id=note_id,
        type="lesson",
        scope="personal",
        source="claude-code",
        captured_at=datetime(2026, 5, 13, 12, 0),
        confidence=0.4,
        title="title",
        body="body",
        vault_path="personal/lesson/draft.md",
        tags=[],
    )


def _conninfo_fields(conninfo: str) -> dict[str, str]:
    return dict(kv.split("=", 1) for kv in conninfo.split())


@pytest.fixture()
def source_store(cluster: dict[str, object]) -> Iterator[PostgresJobStore]:
    # PostgresJobStore takes discrete kwargs, not a conninfo string.
    fields = _conninfo_fields(str(cluster["source"]))
    store = PostgresJobStore(
        host=fields["host"],
        port=int(fields["port"]),
        database=fields["dbname"],
        user=fields["user"],
        password=fields["password"],
    )
    store.open()
    try:
        yield store
    finally:
        store.close()


def test_copy_moves_jobs_outbox_and_manifest_rows(
    cluster: dict[str, object], source_store: PostgresJobStore
) -> None:
    svc = IngestionJobService(source_store)
    notes = [_note(f"01CPY{i}0000000000000000000") for i in range(3)]
    batch = svc.begin(notes)
    assert batch.action is BatchAction.NEW
    for record in batch.pending_records():
        if record.seq <= 1:
            svc.succeed(batch, record)  # leave one pending, mid-batch

    results = JobStoreCopier(str(cluster["source"]), str(cluster["dest"])).copy_all()

    by_table = {r.table: r for r in results}
    assert by_table["ingest_jobs"].rows_copied == 1
    assert by_table["ingest_outbox"].rows_copied == 3
    assert by_table["ingest_source_manifest"].rows_copied == 1
    for result in results:
        assert result.source_count == result.dest_count

    with psycopg.connect(str(cluster["dest"])) as conn, conn.cursor() as cur:
        cur.execute("SELECT job_id, checkpoint FROM ingest_jobs")
        job_id, checkpoint = cur.fetchone()
        assert checkpoint == 1  # the resume position carried over, not reset
        cur.execute("SELECT seq FROM ingest_outbox WHERE job_id = %s ORDER BY seq", (job_id,))
        assert [r[0] for r in cur.fetchall()] == [0, 1, 2]

        # The sequence was resynced past the highest copied id. `nextval`
        # (unlike an INSERT) has no transactional row to roll back or
        # clean up, so later tests in this module see an untouched table.
        cur.execute("SELECT nextval(pg_get_serial_sequence('ingest_outbox', 'id'))")
        next_id = cur.fetchone()[0]
        assert next_id > 2  # ids 1 and 2 were copied from source


def test_copy_is_idempotent_on_a_second_run(cluster: dict[str, object]) -> None:
    # Running again after the previous test's copy must not fail or
    # duplicate rows (ON CONFLICT DO NOTHING on each table's own key).
    results = JobStoreCopier(str(cluster["source"]), str(cluster["dest"])).copy_all()
    for result in results:
        assert result.rows_copied == 0  # nothing new to copy
        assert result.source_count == result.dest_count


def test_copy_raises_verification_error_when_dest_count_disagrees(
    cluster: dict[str, object],
) -> None:
    # A row that exists only at the destination (never in source) leaves
    # dest_count > source_count after the no-op copy — the real failure
    # shape verification exists to catch, not a contrived one.
    with psycopg.connect(str(cluster["dest"])) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO ingest_source_manifest (source_id, source_type, dedup_key) "
            "VALUES ('dest-only', 'lesson', 'dest-only')"
        )
        conn.commit()

    with pytest.raises(JobStoreCopyVerificationError, match="ingest_source_manifest"):
        JobStoreCopier(str(cluster["source"]), str(cluster["dest"])).copy_all()

    # Clean up so this module's other tests (which run in file order) see
    # a consistent destination.
    with psycopg.connect(str(cluster["dest"])) as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM ingest_source_manifest WHERE source_id = 'dest-only'")
        conn.commit()
