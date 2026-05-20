-- Cognee-style usage stats on `kb_notes`. Every recall hit bumps the
-- counter + last-touched timestamp so the reranker (and future stale-
-- note detection) has a "what does the operator actually use" signal
-- to layer on top of pure semantic similarity.
--
-- Codegen-safe: plain B-tree on a non-pg-specific type. Lives in the
-- default migration tree (not `db/migration-pg/`) so jOOQ's
-- DDLDatabase picks the columns up and the generated metamodel can
-- carry them — `recall_count` is the kind of field future jOOQ DSL
-- queries (e.g. "least-recalled within scope") will want.

ALTER TABLE kb_notes
    ADD COLUMN recall_count     INTEGER NOT NULL DEFAULT 0 CHECK (recall_count >= 0),
    ADD COLUMN last_recalled_at TIMESTAMP;

-- Sort by least-recalled-first for stale-note detection.
CREATE INDEX kb_notes_last_recalled_at_idx ON kb_notes (last_recalled_at NULLS FIRST);
