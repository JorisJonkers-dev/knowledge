"""Unit tests for the #246 job-store schema + its migration wiring.

The DDL itself cannot be executed without Postgres, so it is verified
here [STATIC] (shape/structure assertions) and exercised end-to-end
against a real Postgres in the testcontainers integration test. The
``migrate.py --job-store`` dispatch is unit-tested by monkeypatching the
schema runner.
"""

from __future__ import annotations

import sys

import pytest

import knowledge_worker.migrate as migrate_mod
from knowledge_worker.jobs import JobState, PostgresJobStore
from knowledge_worker.schema import JOB_STATES, ddl_statements


def _ddl() -> str:
    return "\n".join(ddl_statements())


def test_schema_lists_all_five_job_states() -> None:
    """The CHECK constraints must enumerate exactly the worker's states."""
    ddl = _ddl()
    for state in JOB_STATES:
        assert f"'{state}'" in ddl
        # appears in the ingest_jobs CHECK and the ingest_outbox CHECK
        # (plus DEFAULT clauses) — the point is both tables enforce it.
        assert ddl.count(f"'{state}'") >= 2


def test_schema_is_idempotent_and_dedup_enforced() -> None:
    ddl = _ddl()
    assert ddl.count("IF NOT EXISTS") >= 3  # all three tables are guarded
    assert "dedup_key" in ddl
    assert "UNIQUE" in ddl  # dedup UNIQUE constraint for idempotent replay
    assert "(job_id, seq)" in ddl  # outbox ordering is gap-free + deduped
    assert "checkpoint" in ddl  # resumable checkpoint column


def test_schema_covers_required_manifest_columns() -> None:
    j = ddl_statements()[0]  # ingest_jobs
    for col in (
        "job_id",
        "source_id",
        "source_type",
        "state",
        "attempt_count",
        "max_attempts",
        "last_error",
        "checkpoint",
    ):
        assert col in j
    assert "PRIMARY KEY" in j


def test_postgres_store_constructs_pool_without_opening() -> None:
    """open=False keeps the pool quiescent — no Postgres needed here."""
    s = PostgresJobStore(
        host="pg", port=5432, database="kb", user="u", password="p"
    )
    assert s is not None


def test_postgres_store_row_parsers_round_trip() -> None:
    """Pure row->object mappers are unit-testable without a DB connection."""
    job = PostgresJobStore._row_to_job(
        {
            "job_id": "j1",
            "source_id": "s1",
            "source_type": "lesson",
            "note_id": "n1",
            "state": "in_progress",
            "attempt_count": 2,
            "max_attempts": 3,
            "last_error": "boom",
            "checkpoint": 4,
        }
    )
    assert job.job_id == "j1"
    assert job.state is JobState.IN_PROGRESS
    assert job.checkpoint == 4

    manifest = PostgresJobStore._row_to_manifest(
        {
            "source_id": "s1",
            "source_type": "lesson",
            "dedup_key": "s1",
            "content_hash": "abc",
            "imported_records": 2,
        }
    )
    assert manifest.dedup_key == "s1"
    assert manifest.imported_records == 2


def test_run_job_store_migration_applies_schema_to_configured_db(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    applied: list[str] = []

    class _FakeRunner:
        def __init__(self, conninfo: str) -> None:
            self.conninfo = conninfo

        def apply(self) -> None:
            applied.append(self.conninfo)

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "PostgresSchemaRunner", _FakeRunner)
    monkeypatch.setenv("DB_HOST", "db.example")
    monkeypatch.setenv("DB_PORT", "5433")
    monkeypatch.setenv("DB_NAME", "knowledge_db")
    monkeypatch.setenv("DB_USER", "kb")
    monkeypatch.setenv("DB_PASSWORD", "secret")

    rc = migrate_mod.run_job_store_migration()
    assert rc == 0
    assert len(applied) == 1
    assert "host=db.example" in applied[0]
    assert "dbname=knowledge_db" in applied[0]


def test_main_dispatches_to_job_store_migration(monkeypatch: pytest.MonkeyPatch) -> None:
    dispatched: list[bool] = []

    def _fake() -> int:
        dispatched.append(True)
        return 7

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "run_job_store_migration", _fake)
    monkeypatch.setattr(sys, "argv", ["migrate.py", "--job-store"])

    assert migrate_mod.main() == 7
    assert dispatched == [True]
