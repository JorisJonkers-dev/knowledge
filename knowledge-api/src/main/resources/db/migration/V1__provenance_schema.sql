-- Provenance schema for the centralized knowledge base, owned by
-- knowledge-api. Stores the canonical notes (lessons, decisions, etc.)
-- plus the relationships between them. The Python ingest worker (later
-- PR) creates separate LightRAG tables (chunks, entities, kv_store)
-- alongside these; the two schemas don't overlap.
--
-- Schema choices deliberately stay inside the SQL subset that jOOQ's
-- DDLDatabase code generator can interpret on top of H2:
--   - No TEXT[]                  → tags live in a join table (kb_note_tags).
--   - No JSONB                   → kb_relations.props is TEXT with a
--                                  JSON-encoded payload, parsed in code.
--   - No `USING GIN`             → plain B-tree indexes on tag/predicate.
-- All identifiers are ULIDs minted by knowledge-api on capture — sort
-- lexicographically by time, so `ORDER BY id DESC` doubles as a recency
-- index without an extra column.

CREATE TABLE kb_notes (
    id              VARCHAR(64) PRIMARY KEY,
    -- One of: lesson, decision, note, fact. Kept as VARCHAR to avoid
    -- Flyway-managed enum migrations every time a new type lands.
    type            VARCHAR(32) NOT NULL,
    -- First-class access boundary. Examples: personal, work, public,
    -- agent:<name>, project:<repo>. Cross-scope queries are opt-in
    -- per tool call.
    scope           VARCHAR(128) NOT NULL,
    -- Provenance. Examples: claude-code, codex, manual, url:<...>.
    source          VARCHAR(256) NOT NULL,
    captured_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    -- Set by the SessionStart hook when present; lets `recall` link a
    -- note back to its originating chat.
    session_id      VARCHAR(128),
    -- 0.0 - 1.0. Worker starts incoming notes at 0.4, raises to 0.8
    -- on consolidation, manual edits bump to 0.95.
    confidence      REAL NOT NULL DEFAULT 0.4 CHECK (confidence BETWEEN 0 AND 1),
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    -- Where the canonical markdown lives in the knowledge-vault repo.
    vault_path      TEXT NOT NULL,
    -- The commit sha that holds the current canonical body. Null until
    -- the first commit completes.
    vault_commit    VARCHAR(64),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX kb_notes_scope_idx       ON kb_notes (scope);
CREATE INDEX kb_notes_type_idx        ON kb_notes (type);
CREATE INDEX kb_notes_captured_at_idx ON kb_notes (captured_at);

-- Many-to-many tag join table. Indexed both ways so `notes by tag` and
-- `tags of note` are point lookups.
CREATE TABLE kb_note_tags (
    note_id  VARCHAR(64) NOT NULL,
    tag      VARCHAR(64) NOT NULL,
    PRIMARY KEY (note_id, tag)
);

CREATE INDEX kb_note_tags_tag_idx ON kb_note_tags (tag);

-- Conflict resolution + graph edges. predicate values include:
--   supersedes, derived_from, contradicts, mentions, see_also.
-- Free-form keeps the door open for the ingest worker to introduce
-- richer relations without schema migrations; the MCP `find_conflicts`
-- tool only cares about supersedes + contradicts today.
CREATE TABLE kb_relations (
    subject_id  VARCHAR(64) NOT NULL,
    predicate   VARCHAR(64) NOT NULL,
    object_id   VARCHAR(64) NOT NULL,
    -- JSON-encoded extra properties (e.g. confidence_delta on
    -- contradicts, evidence span on derived_from). TEXT instead of
    -- JSONB so jOOQ's DDL simulator can ingest it; the application
    -- parses on read/write.
    props       TEXT NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (subject_id, predicate, object_id)
);

CREATE INDEX kb_relations_object_idx    ON kb_relations (object_id);
CREATE INDEX kb_relations_predicate_idx ON kb_relations (predicate);
