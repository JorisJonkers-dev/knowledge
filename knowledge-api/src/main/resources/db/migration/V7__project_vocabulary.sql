-- Closed project vocabulary, mirroring kb_topics. Replaces the
-- previous free-form `project:<github-repo-name>` shape, which let
-- the classifier hallucinate scopes like
-- `project:my-kubernetes-observability-stack`,
-- `project:github-actions`, `project:home-direct`,
-- `project:esa-blueshell/website`, `project:esa-blueshell.website`,
-- `project:personal-stack-2` — none of which are real repos.
--
-- The curator side reads this table on boot to build the same
-- ProjectVocabulary slug+alias machinery TopicVocabulary already
-- has. Notes whose classifier-emitted `project:<slug>` doesn't
-- match any active row land in `_inbox/_needs-review/` with reason
-- `unknown-project-slug:<emitted>` — same posture topic violations
-- already get.
--
-- Schema constraints inherited from V1/V2:
--   - jOOQ DDLDatabase against H2 must parse this. Stay inside the
--     SQL subset that worked for V1.
--   - Single-action ALTER TABLE / CREATE statements only; H2's
--     simulator chokes on multi-action ALTERs.
--
-- Semantics:
--   - `slug` is the GitHub `org/repo` path, lowercase
--     (e.g. `extratoast/personal-stack`). Vault folders use this
--     verbatim (`projects/<org>/<repo>/<type>/...`), so every
--     repo from the same org groups under one folder.
--   - `github_org` is redundant with the slug prefix but kept for
--     fast filtering / future per-org policy.
--   - `is_active` mirrors kb_topics — soft-delete keeps historical
--     `project:<slug>` references intact when a project is retired.
CREATE TABLE kb_projects (
    slug         VARCHAR(160) PRIMARY KEY,
    description  TEXT NOT NULL DEFAULT '',
    github_org   VARCHAR(128),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(128) NOT NULL DEFAULT 'seed',
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX kb_projects_is_active_idx ON kb_projects (is_active);

-- Aliases work the same way topic aliases do. Used to catch
-- classifier hallucinations like `personal-stack-2` (the IDE
-- working-copy folder name, not the repo) or `esa-blueshell-website`
-- (the org/repo mashed together).
CREATE TABLE kb_project_aliases (
    alias_lower  VARCHAR(160) PRIMARY KEY,
    slug         VARCHAR(160) NOT NULL
);

CREATE INDEX kb_project_aliases_slug_idx ON kb_project_aliases (slug);
