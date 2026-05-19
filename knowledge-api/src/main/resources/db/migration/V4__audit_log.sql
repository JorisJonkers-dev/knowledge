-- Shared audit log for retroactive curator + admin operations.
--
-- Three callers, all of them mutate existing rows the operator
-- needs to be able to inspect after the fact:
--
--   - The curator's relation resolver (Plan C, already shipped via
--     structured logs only). Each substituted / dropped edge will
--     gain a row here in a follow-up that flips the resolver from
--     `log.info` to `audit_repository.record(...)`.
--   - The title renormaliser (Plan A). Every `kb_renormalise_titles`
--     pass writes one row per renamed note with the before / after
--     title in `before_json` / `after_json`.
--   - The retroactive reclassifier (Plan B). Every scope change and
--     every tag-set change driven by a vocabulary update lands here.
--
-- The schema is intentionally narrow — `actor` / `action` /
-- `target_*` are free-text rather than enums so a new caller can
-- ship without a Flyway migration. The shape of `before_json` /
-- `after_json` is left to the caller per `action`.
--
-- Schema constraints inherited from V1 + V2:
--   - jOOQ DDLDatabase against H2 must parse this — no JSONB, no
--     TEXT[]. `before_json` / `after_json` are plain TEXT carrying
--     JSON the application parses.
--   - All identifiers carry the `kb_` prefix.

CREATE TABLE kb_audit (
    -- ULID minted by the application on write. Sort lex by time so
    -- `ORDER BY id DESC` doubles as recency without an extra column.
    id           VARCHAR(64) PRIMARY KEY,
    -- Who triggered the change. `mcp:<token-name>` for MCP-initiated
    -- mutations; `kb-curator-resolver`, `kb-renormalise-titles`,
    -- `kb-reclassifier` for the curator's own passes; `seed` for
    -- bootstrap migrations.
    actor        VARCHAR(128) NOT NULL,
    -- What happened. Examples: `resolve_relation`, `drop_relation`,
    -- `rename_title`, `reclassify_scope`, `rename_tag`,
    -- `merge_topic`, `add_topic`, `update_topic`.
    action       VARCHAR(64) NOT NULL,
    -- The kb_notes / kb_topics / kb_relations row the action
    -- touched. Nullable for actions that are corpus-wide rather
    -- than row-targeted (e.g. a vocabulary-merge audit row could
    -- reference the topic slug as `target_id` + `target_kind=topic`).
    target_id    VARCHAR(64),
    target_kind  VARCHAR(32),
    before_json  TEXT,
    after_json   TEXT,
    at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX kb_audit_at_idx     ON kb_audit (at);
CREATE INDEX kb_audit_actor_idx  ON kb_audit (actor);
CREATE INDEX kb_audit_action_idx ON kb_audit (action);
CREATE INDEX kb_audit_target_idx ON kb_audit (target_id);
