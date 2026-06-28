-- Per-pass state + history for the curator orchestrator.
--
-- The orchestrator (services/knowledge-curator/src/curator/orchestrator)
-- replaces the old "every 5 minutes, do everything" CronJob with a
-- single tick that probes each registered Pass via `has_work(state)`
-- and only runs the ones with new candidates since their last
-- successful tick. State is keyed by `pass_name` and the watermark
-- is intentionally `JSONB` so each pass can pick its own shape — the
-- inbox pass tracks `{max_captured_at, last_inbox_dir_mtime}`, the
-- title-quality pass tracks `{model, patterns_hash, max_promoted_at}`,
-- the relation-enrichment pass tracks `{max_updated_at,
-- last_neighbour_model}`, etc. The orchestrator itself does not look
-- inside the JSONB; each pass parses + writes its own shape.
--
-- `kb_curator_runs` carries the most-recent state (one row per pass
-- name). `kb_curator_pass_history` is the append-only audit log:
-- every tick that actually does work emits one row with the
-- `watermark_before` and `watermark_after`, so a `SELECT pass_name,
-- COUNT(*), MAX(completed_at) FROM kb_curator_pass_history GROUP BY
-- pass_name` query answers "what has the curator done this week".
--
-- Postgres-only: JSONB is the JSON storage type, and pass-level
-- mutual exclusion uses `pg_advisory_xact_lock(hashtext(pass_name))`
-- rather than the dead-letter SELECT FOR UPDATE pattern. The curator
-- runs against Postgres exclusively; the H2-flavoured codegen path
-- never sees this migration.

CREATE TABLE kb_curator_runs (
    pass_name         TEXT PRIMARY KEY,
    last_started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_completed_at TIMESTAMPTZ,
    last_status       TEXT NOT NULL CHECK (
        last_status IN ('success', 'no_work', 'failed', 'running')
    ),
    watermark         JSONB NOT NULL DEFAULT '{}'::jsonb,
    notes_processed   INTEGER NOT NULL DEFAULT 0 CHECK (notes_processed >= 0),
    duration_seconds  NUMERIC(10, 3),
    error_summary     TEXT
);

COMMENT ON TABLE kb_curator_runs IS
    'Most-recent state per orchestrator Pass. One row per pass_name.';
COMMENT ON COLUMN kb_curator_runs.watermark IS
    'Pass-specific. Inbox: {max_captured_at}. Title-quality: '
    '{model, patterns_hash, max_promoted_at}. Etc.';
COMMENT ON COLUMN kb_curator_runs.last_status IS
    'running = orchestrator currently inside Pass.run; success / '
    'no_work / failed are terminal.';

CREATE TABLE kb_curator_pass_history (
    id                BIGSERIAL PRIMARY KEY,
    pass_name         TEXT NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ,
    status            TEXT NOT NULL CHECK (
        status IN ('success', 'no_work', 'failed')
    ),
    notes_processed   INTEGER NOT NULL DEFAULT 0 CHECK (notes_processed >= 0),
    duration_seconds  NUMERIC(10, 3),
    watermark_before  JSONB,
    watermark_after   JSONB,
    error             TEXT
);

CREATE INDEX idx_kb_curator_pass_history_pass_started
    ON kb_curator_pass_history (pass_name, started_at DESC);
CREATE INDEX idx_kb_curator_pass_history_status_started
    ON kb_curator_pass_history (status, started_at DESC)
    WHERE status IN ('failed', 'success');

COMMENT ON TABLE kb_curator_pass_history IS
    'Append-only audit log. One row per Pass execution that actually '
    'did work; `no_work` skips do not write history rows.';
