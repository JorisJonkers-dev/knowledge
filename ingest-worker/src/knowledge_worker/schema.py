"""Additive job-store schema for the ingestion worker.

These three tables are the durable substrate for ticket fleet-infra#246
("worker survives interruption, handles revisions and deletions,
deduplication, resumable checkpoints"). Unlike ``kb_notes`` — which
knowledge-api owns and the worker only write-backs to — the worker owns
*these* tables and runs the migration itself (see ``migrate.py``).

``ingest_jobs``
    One row per import. Carries the worker-side state machine
    (``pending``/``in_progress``/``done``/``failed``/``deadletter``), the
    attempt counter, the last error text and a resumable ``checkpoint``
    (the highest outbox ``seq`` durably applied, ``-1`` = nothing yet).

``ingest_outbox``
    The ordered batch for a job. Each record is one materialisable
    ``CapturedNote``; ``seq`` gives a gapless order and ``(job_id, seq)``
    is UNIQUE so a resumed batch cannot double-append.

``ingest_source_manifest``
    The dedup guard. ``source_id`` is the deterministic identity of an
    import, ``dedup_key`` is UNIQUE so replaying an import is a no-op
    rather than a duplicate, and ``content_hash`` distinguishes a genuine
    replay (same content, skip) from a revision (different content,
    update-in-place, never append).

Idempotent by construction: every ``CREATE TABLE`` is ``IF NOT EXISTS``
and the UNIQUE constraints make the INSERTs safe to re-run.
"""

from __future__ import annotations

import psycopg

JOB_STATES = ("pending", "in_progress", "done", "failed", "deadletter")

# NOTE: keep the CHECK-constraint state lists in DDL_SQL and JOB_STATES in
# lock-step — they must enumerate the same five states.
DDL_SQL = """
CREATE TABLE IF NOT EXISTS ingest_jobs (
    job_id          VARCHAR(256) PRIMARY KEY,
    source_id       VARCHAR(256) NOT NULL,
    source_type     VARCHAR(64)  NOT NULL,
    note_id         VARCHAR(64),
    state           VARCHAR(32)  NOT NULL DEFAULT 'pending',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    max_attempts    INTEGER      NOT NULL DEFAULT 3,
    last_error      TEXT,
    checkpoint      INTEGER      NOT NULL DEFAULT -1,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ingest_jobs_state_check
        CHECK (state IN ('pending','in_progress','done','failed','deadletter'))
);

CREATE TABLE IF NOT EXISTS ingest_outbox (
    id              BIGSERIAL PRIMARY KEY,
    job_id          VARCHAR(256) NOT NULL
                    REFERENCES ingest_jobs(job_id) ON DELETE CASCADE,
    seq             INTEGER      NOT NULL,
    state           VARCHAR(32)  NOT NULL DEFAULT 'pending',
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ingest_outbox_state_check
        CHECK (state IN ('pending','in_progress','done','failed','deadletter')),
    CONSTRAINT ingest_outbox_job_seq_uniq UNIQUE (job_id, seq)
);

CREATE TABLE IF NOT EXISTS ingest_source_manifest (
    source_id        VARCHAR(256) PRIMARY KEY,
    source_type      VARCHAR(64)  NOT NULL,
    dedup_key        VARCHAR(256) NOT NULL,
    content_hash     VARCHAR(64),
    imported_records INTEGER      NOT NULL DEFAULT 0,
    last_imported_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ingest_source_manifest_dedup_uniq UNIQUE (dedup_key)
);

CREATE INDEX IF NOT EXISTS ingest_outbox_job_idx
    ON ingest_outbox (job_id, seq);
"""


def ddl_statements() -> list[str]:
    """Split DDL_SQL into individual statements for a fake runner."""

    return [s.strip() for s in DDL_SQL.split(";") if s.strip()]


class PostgresSchemaRunner:
    """Applies the job-store DDL through a psycopg connection info string."""

    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo

    def apply(self) -> None:
        with psycopg.connect(self._conninfo) as conn, conn.cursor() as cur:
            cur.execute(DDL_SQL)
            conn.commit()
