-- pgvector ANN leg on top of the V1 schema. Lives in
-- `db/migration-pg/` rather than `db/migration/` because jOOQ's
-- codegen runs the whole migration tree through DDLDatabase, which
-- cannot parse `vector(N)` or `USING hnsw`. The application loads
-- BOTH locations via `spring.flyway.locations` so prod gets the
-- column; codegen only ever sees `db/migration/` and stays happy.
--
-- The vector column is NULL on every existing row until the curator
-- backfill (separate PR) populates it. Recall code MUST treat NULL
-- embeddings as "not in the vector leg" rather than 0/0 NaN.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE kb_notes
    ADD COLUMN embedding        vector(1024),
    ADD COLUMN embedding_model  VARCHAR(64),
    ADD COLUMN embedded_at      TIMESTAMP;

-- HNSW index for cosine similarity. m / ef_construction stay at
-- pgvector defaults (16 / 64) — sane for ~10² – 10⁴ notes. Raise
-- if recall quality plateaus before the corpus grows past that.
CREATE INDEX kb_notes_embedding_hnsw_idx
    ON kb_notes
    USING hnsw (embedding vector_cosine_ops);

CREATE INDEX kb_notes_embedded_at_idx
    ON kb_notes (embedded_at);
