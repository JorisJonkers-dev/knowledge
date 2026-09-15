"""Unit coverage for the job-store copy migration (fleet-infra#246).

`JobStoreCopier` itself needs two live Postgres databases to exercise
end-to-end — that's `tests/integration/test_job_store_copy.py`
(testcontainers). Here: the pure conninfo builder, and
`run_job_store_copy_migration`'s env-var wiring + dispatch, unit-tested
by monkeypatching `JobStoreCopier` the same way `test_schema.py` does for
`PostgresSchemaRunner`.
"""

from __future__ import annotations

import sys

import pytest

import knowledge_worker.job_store_copy as job_store_copy_mod
import knowledge_worker.migrate as migrate_mod
from knowledge_worker.job_store_copy import (
    JobStoreCopier,
    JobStoreCopyVerificationError,
    TableCopyResult,
    build_conninfo,
)


def test_build_conninfo_formats_all_fields() -> None:
    conninfo = build_conninfo(
        host="pg", port=5433, dbname="kb", user="kb_app", password="hunter2"
    )
    assert conninfo == "host=pg port=5433 dbname=kb user=kb_app password=hunter2"


def test_verification_error_is_a_runtime_error() -> None:
    assert issubclass(JobStoreCopyVerificationError, RuntimeError)


def test_run_job_store_copy_migration_defaults_source_to_knowledge_db(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, str] = {}

    class _FakeCopier:
        def __init__(self, source_conninfo: str, dest_conninfo: str) -> None:
            captured["source"] = source_conninfo
            captured["dest"] = dest_conninfo

        def copy_all(self) -> list[TableCopyResult]:
            return [
                TableCopyResult(
                    table="ingest_jobs", rows_copied=3, source_count=3, dest_count=3
                )
            ]

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "JobStoreCopier", _FakeCopier)
    monkeypatch.setenv("DB_HOST", "pg.example")
    monkeypatch.setenv("DB_PORT", "5432")
    monkeypatch.setenv("DB_NAME", "knowledge_platform_db")
    monkeypatch.setenv("DB_USER", "kp_user")
    monkeypatch.setenv("DB_PASSWORD", "kp_secret")

    rc = migrate_mod.run_job_store_copy_migration()

    assert rc == 0
    assert "host=pg.example" in captured["source"]
    assert "dbname=knowledge_db" in captured["source"]  # legacy db, not overridden
    assert "user=kp_user" in captured["source"]  # falls back to dest creds by default
    assert "dbname=knowledge_platform_db" in captured["dest"]
    assert "user=kp_user" in captured["dest"]


def test_run_job_store_copy_migration_honours_source_overrides(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, str] = {}

    class _FakeCopier:
        def __init__(self, source_conninfo: str, dest_conninfo: str) -> None:
            captured["source"] = source_conninfo

        def copy_all(self) -> list[TableCopyResult]:
            return []

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "JobStoreCopier", _FakeCopier)
    monkeypatch.setenv("DB_HOST", "pg.example")
    monkeypatch.setenv("DB_NAME", "knowledge_platform_db")
    monkeypatch.setenv("DB_USER", "kp_user")
    monkeypatch.setenv("DB_PASSWORD", "kp_secret")
    monkeypatch.setenv("SOURCE_DB_HOST", "old-pg.example")
    monkeypatch.setenv("SOURCE_DB_NAME", "knowledge_db")
    monkeypatch.setenv("SOURCE_DB_USER", "kb_user")
    monkeypatch.setenv("SOURCE_DB_PASSWORD", "kb_secret")

    assert migrate_mod.run_job_store_copy_migration() == 0
    assert "host=old-pg.example" in captured["source"]
    assert "dbname=knowledge_db" in captured["source"]
    assert "user=kb_user" in captured["source"]
    assert "password=kb_secret" in captured["source"]


def test_run_job_store_copy_migration_propagates_verification_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class _FailingCopier:
        def __init__(self, source_conninfo: str, dest_conninfo: str) -> None:
            pass

        def copy_all(self) -> list[TableCopyResult]:
            raise JobStoreCopyVerificationError("ingest_jobs: source has 3 rows, dest has 2")

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "JobStoreCopier", _FailingCopier)

    with pytest.raises(JobStoreCopyVerificationError):
        migrate_mod.run_job_store_copy_migration()


def test_main_dispatches_to_job_store_copy_migration(monkeypatch: pytest.MonkeyPatch) -> None:
    dispatched: list[bool] = []

    def _fake() -> int:
        dispatched.append(True)
        return 9

    monkeypatch.setattr(migrate_mod, "configure_telemetry", lambda *a, **k: None)
    monkeypatch.setattr(migrate_mod, "run_job_store_copy_migration", _fake)
    monkeypatch.setattr(sys, "argv", ["migrate.py", "--copy-job-store"])

    assert migrate_mod.main() == 9
    assert dispatched == [True]


# -- JobStoreCopier against a fake Postgres --
#
# A real cross-database copy needs two live Postgres databases (see the
# testcontainers-backed tests/integration/test_job_store_copy.py). Here,
# fake connection/cursor objects that understand just the handful of SQL
# shapes `_copy_table` issues (SELECT *, INSERT ... ON CONFLICT DO
# NOTHING, SELECT COUNT(*), the outbox setval) exercise the same control
# flow — row copying, the ON CONFLICT dedup, the sequence resync, and the
# count-mismatch raise — without a Docker dependency.


class _FakeCursor:
    def __init__(self, conn: _FakeConnection, row_factory: object | None) -> None:
        self._conn = conn
        self._as_dict = row_factory is not None
        self._result: list[object] = []

    def __enter__(self) -> _FakeCursor:
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False

    def execute(self, sql: str, params: object = None) -> None:
        sql = " ".join(sql.split())
        if sql.startswith("SELECT * FROM"):
            table = sql.split()[3]
            rows = self._conn.tables[table]
            if self._as_dict:
                self._result = [dict(r) for r in rows]
            else:
                self._result = [tuple(r.values()) for r in rows]
        elif sql.startswith("SELECT COUNT(*) FROM"):
            table = sql.split()[-1]
            self._result = [(len(self._conn.tables[table]),)]
        elif sql.startswith("SELECT setval"):
            assert isinstance(params, tuple)
            self._conn.sequences_resynced.append(params[0])
            self._result = []
        else:  # pragma: no cover — defensive; a new SQL shape must extend the fake
            raise AssertionError(f"unhandled SQL in fake cursor: {sql!r}")

    def executemany(self, sql: str, rows: list[dict[str, object]]) -> None:
        table = sql.split()[2]
        key_column = sql.split("ON CONFLICT (")[1].split(")")[0]
        existing = {r[key_column] for r in self._conn.tables[table]}
        for row in rows:
            if row[key_column] not in existing:
                self._conn.tables[table].append(dict(row))
                existing.add(row[key_column])

    def fetchall(self) -> list[object]:
        return self._result

    def fetchone(self) -> object | None:
        return self._result[0] if self._result else None


class _FakeConnection:
    def __init__(self, tables: dict[str, list[dict[str, object]]]) -> None:
        self.tables = tables
        self.sequences_resynced: list[str] = []
        self.committed = False

    def __enter__(self) -> _FakeConnection:
        return self

    def __exit__(self, *exc_info: object) -> bool:
        return False

    def cursor(self, row_factory: object | None = None) -> _FakeCursor:
        return _FakeCursor(self, row_factory)

    def commit(self) -> None:
        self.committed = True


def _job_row(job_id: str, *, checkpoint: int = -1) -> dict[str, object]:
    return {
        "job_id": job_id,
        "source_id": job_id,
        "source_type": "lesson",
        "note_id": job_id,
        "state": "in_progress",
        "attempt_count": 0,
        "max_attempts": 3,
        "last_error": None,
        "checkpoint": checkpoint,
    }


def _outbox_row(row_id: int, job_id: str, seq: int) -> dict[str, object]:
    return {"id": row_id, "job_id": job_id, "seq": seq, "state": "pending", "payload": {}}


def _manifest_row(source_id: str) -> dict[str, object]:
    return {
        "source_id": source_id,
        "source_type": "lesson",
        "dedup_key": source_id,
        "content_hash": "abc",
        "imported_records": 1,
    }


def _patch_connections(
    monkeypatch: pytest.MonkeyPatch, source: _FakeConnection, dest: _FakeConnection
) -> None:
    by_conninfo = {"source-conninfo": source, "dest-conninfo": dest}
    monkeypatch.setattr(
        job_store_copy_mod.psycopg, "connect", lambda conninfo: by_conninfo[conninfo]
    )


def test_copier_copies_rows_and_resyncs_the_outbox_sequence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _FakeConnection(
        {
            "ingest_jobs": [_job_row("j1", checkpoint=1)],
            "ingest_outbox": [_outbox_row(1, "j1", 0), _outbox_row(2, "j1", 1)],
            "ingest_source_manifest": [_manifest_row("j1")],
        }
    )
    dest = _FakeConnection({"ingest_jobs": [], "ingest_outbox": [], "ingest_source_manifest": []})
    _patch_connections(monkeypatch, source, dest)

    results = JobStoreCopier("source-conninfo", "dest-conninfo").copy_all()

    assert {r.table: r.rows_copied for r in results} == {
        "ingest_jobs": 1,
        "ingest_outbox": 2,
        "ingest_source_manifest": 1,
    }
    assert dest.tables["ingest_jobs"] == source.tables["ingest_jobs"]

    # `payload` (JSONB) is wrapped in `Jsonb(...)` on the way to Postgres —
    # compare every column except that one against the source.
    def _without_payload(rows: list[dict[str, object]]) -> list[dict[str, object]]:
        return [{k: v for k, v in r.items() if k != "payload"} for r in rows]

    assert _without_payload(dest.tables["ingest_outbox"]) == _without_payload(
        source.tables["ingest_outbox"]
    )
    assert dest.sequences_resynced == ["ingest_outbox"]  # the only table with a sequence
    assert dest.committed is True


def test_copier_skips_rows_already_present_at_the_destination(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _FakeConnection({"ingest_jobs": [_job_row("j1"), _job_row("j2")]})
    dest = _FakeConnection({"ingest_jobs": [_job_row("j1")]})  # j1 already copied
    _patch_connections(monkeypatch, source, dest)

    result = JobStoreCopier._copy_table(
        JobStoreCopier("source-conninfo", "dest-conninfo"), source, dest, "ingest_jobs", "job_id"
    )

    assert result.rows_copied == 1  # only j2 is new; j1 was dropped by ON CONFLICT
    assert [r["job_id"] for r in dest.tables["ingest_jobs"]] == ["j1", "j2"]


def test_copier_raises_verification_error_on_a_count_mismatch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _FakeConnection({"ingest_jobs": [_job_row("j1")]})
    dest = _FakeConnection({"ingest_jobs": [_job_row("j1"), _job_row("stray")]})
    _patch_connections(monkeypatch, source, dest)

    with pytest.raises(JobStoreCopyVerificationError, match="ingest_jobs"):
        JobStoreCopier._copy_table(
            JobStoreCopier("source-conninfo", "dest-conninfo"),
            source,
            dest,
            "ingest_jobs",
            "job_id",
        )


def test_copier_handles_an_empty_source_table(monkeypatch: pytest.MonkeyPatch) -> None:
    source = _FakeConnection({"ingest_source_manifest": []})
    dest = _FakeConnection({"ingest_source_manifest": []})
    _patch_connections(monkeypatch, source, dest)

    result = JobStoreCopier._copy_table(
        JobStoreCopier("source-conninfo", "dest-conninfo"),
        source,
        dest,
        "ingest_source_manifest",
        "source_id",
    )
    assert result == TableCopyResult(
        table="ingest_source_manifest", rows_copied=0, source_count=0, dest_count=0
    )
