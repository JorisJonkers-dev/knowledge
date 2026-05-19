-- Dynamic topic vocabulary. Replaces the closed list previously
-- baked into `knowledge-curator-topics` ConfigMap with a Postgres-
-- backed table the curator + knowledge-api + MCP discovery tools
-- can all read, and admin-gated MCP tools can write.
--
-- The ConfigMap stays around as the emergency reseed source — the
-- next migration (V3) seeds the table from its current contents
-- and any drift afterwards is handled here.
--
-- Schema constraints inherited from V1:
--   - jOOQ DDLDatabase against H2 must parse this. Stay inside the
--     SQL subset that worked for V1 — no JSONB, no TEXT[].
--   - All identifiers use the same `kb_` prefix as kb_notes /
--     kb_note_tags / kb_relations.
--
-- Semantics:
--   - `is_active` instead of physical delete so historical notes
--     scoped `topic:<slug>` keep their reference even when a topic
--     is retired. Reads filter `WHERE is_active`.
--   - `created_by` records the MCP token name that minted the row
--     (`mcp:<token>` or `seed` for the V3 import). Lets the audit
--     report from the follow-up "kb_audit" PR attribute every
--     vocabulary change to a caller.

CREATE TABLE kb_topics (
    slug         VARCHAR(64) PRIMARY KEY,
    description  TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(128) NOT NULL DEFAULT 'seed',
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX kb_topics_is_active_idx ON kb_topics (is_active);

-- Aliases live in a join table so a lookup by alias is a primary-key
-- hit. Lowercased on insert (enforced in code) so the matcher in
-- TopicVocabulary is case-insensitive without a runtime UPPER() on
-- every query.
CREATE TABLE kb_topic_aliases (
    alias_lower  VARCHAR(128) PRIMARY KEY,
    slug         VARCHAR(64) NOT NULL
);

CREATE INDEX kb_topic_aliases_slug_idx ON kb_topic_aliases (slug);
