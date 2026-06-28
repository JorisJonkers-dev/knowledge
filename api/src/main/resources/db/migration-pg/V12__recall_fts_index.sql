-- Runtime-only Postgres index for the lexical leg of knowledge.recall.
--
-- The codegen-safe migration tree deliberately avoids GIN and
-- Postgres-specific expression indexes so jOOQ's H2-backed DDL parser
-- can still generate tables. Runtime Flyway also loads migration-pg,
-- where the production recall path gets the index shape it needs.
--
-- Keep this expression byte-for-byte aligned with RecallRepository:
-- to_tsvector('english', coalesce(title, '') || ' ' || coalesce(body, ''))
CREATE INDEX IF NOT EXISTS kb_notes_fts_english_idx
    ON kb_notes
    USING GIN (to_tsvector('english', coalesce(title, '') || ' ' || coalesce(body, '')));
