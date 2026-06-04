# knowledge-api

Spring/Kotlin MCP server backing the centralized knowledge base.
Exposes an HTTP Streamable JSON-RPC MCP endpoint at
`https://kb.jorisjonkers.dev/mcp`. Capture tools (`capture_lesson`,
`capture_decision`, `ingest_note`) mint a ULID, insert a row in
`kb_notes`, and publish a job to the `knowledge` RabbitMQ topic
exchange for the Python ingest worker. Read tools (`recall`,
`list_recent`, `get_note`, `find_conflicts`) query the same tables
via jOOQ. Recall supports `fast` Postgres FTS, `hybrid` FTS +
pgvector RRF, and `deep` hybrid + optional LightRAG graph context +
reranker. LightRAG stays in its own vector/graph store; deep recall
fuses graph context at the hit level only, and the graph leg is an
explicit opt-in via `KB_RECALL_GRAPH_ENABLED=true`.

## Claude Code integration — workstation setup

The Traefik forward-auth chain bypasses the SSO redirect for any
request whose `Authorization` header matches a per-device bearer
token stored in Vault under `secret/data/knowledge-system/mcp-bearer`.
This is the contract Claude Code uses to talk to the MCP server
without holding an SSO cookie.

### One-time bootstrap

1. **Fetch this workstation's bearer token from Vault.** The
   forward-auth chain blocks `vault login` over the public host,
   so a port-forward is required (see CLAUDE.md "Forward-auth
   blocks CLI tools"):

   ```bash
   kube-personal
   kubectl port-forward -n data-system svc/vault 8200:8200 &
   VAULT_ADDR=http://127.0.0.1:8200 vault kv get \
     -field=workstation secret/knowledge-system/mcp-bearer
   ```

   If the `workstation` field does not yet exist:

   ```bash
   VAULT_ADDR=http://127.0.0.1:8200 vault kv patch \
     -mount=secret knowledge-system/mcp-bearer \
     workstation="$(openssl rand -hex 32)"
   ```

   …then re-read. VSO refreshes the in-cluster
   `knowledge-api-mcp-tokens` Secret on its next poll and the
   `rolloutRestartTargets` annotation bounces knowledge-api so the
   new token allow-list is loaded.

2. **Register the MCP server at user scope.** This writes the
   bearer header literally into `~/.claude.json` under
   `mcpServers.knowledge`.

   Put the positional args (`name`, then `url`) BEFORE `--header`:
   `--header` is variadic (`<header...>`), so if it appears first
   the parser slurps the name + url as additional header values
   and you get `error: missing required argument 'name'`.

   ```bash
   KB_TOKEN="<paste from step 1>"
   claude mcp add knowledge https://kb.jorisjonkers.dev/mcp \
     --transport http \
     --scope user \
     --header "Authorization: Bearer ${KB_TOKEN}"
   ```

   Confirm:

   ```bash
   claude mcp list
   ```

3. **Export the same token to the shell** so the SessionStart +
   Stop hooks can curl the MCP directly. Add to `~/.zshrc`:
   ```bash
   export KB_BEARER_TOKEN='<same token>'
   ```
   The token is repeated in two places — one for the MCP transport
   in `~/.claude.json`, one for the hooks. Rotation means editing
   both.

### Dotfiles installed by the bootstrap

The bootstrap writes user-scope copies under `~/.claude/` on the
workstation so they survive across every Claude Code session, not just
this checkout. Project-scoped mirrors can also live in this repo for
agents that load repository skills directly.

Claude/Codex parity is mandatory for project agent features. The
checked-in `.codex/hooks.json` + `.codex/hooks/*` prompt-recall and
stop-digest hooks are mirrored by `.claude/settings.json` +
`.claude/hooks/*`; future agent skills or hooks should keep both sides
aligned.

- `~/.claude/skills/topics/SKILL.md` — inspect the closed topic/tag
  vocabulary before scoping captures.
- `~/.claude/skills/audit/SKILL.md` — audit inbox backlog, duplicate
  tags, and thin/duplicate topics.
- `~/.claude/skills/kb-first/SKILL.md` — consult and update the KB with
  bounded recall/capture.
- `~/.claude/skills/token-economy/SKILL.md` — keep hooks, skills, and
  output summaries token-efficient.
- `~/.claude/skills/agent-session-bootstrap/SKILL.md` — set up future
  Claude/Codex sessions with KB recall/capture defaults.
- `~/.claude/hooks/user-prompt-submit-recall.sh` — injects up to 3
  `knowledge.recall(mode=hybrid)` snippets for non-trivial prompts.
- `~/.claude/hooks/pre-tool-use-edit-recall.sh` — runs a deduped
  path/module recall before edits and skips secrets-bearing paths.
- `~/.claude/hooks/pre-tool-use-git-commit-capture.sh` — captures
  non-WIP commit messages as project-scoped decisions.
- `~/.claude/hooks/stop-session-digest.sh` — sends a capped transcript
  tail to `knowledge.digest_transcript` and captures at most 5 reusable
  lessons per session by default.
- `~/.claude/settings.json` — hook registrations pointing at those
  scripts. The installer prints the JSON because it does not rewrite
  existing settings automatically.

Cost controls: `KB_RECALL_HOOK_LIMIT`, `KB_RECALL_HOOK_MODE`,
`KB_RECALL_MIN_PROMPT_CHARS`, `KB_DIGEST_MAX_CHARS`, and
`KB_DIGEST_MAX_CAPTURES` tune hook behavior.
`KB_DIGEST_DEDUPE_SCORE` sets the auto-capture duplicate cutoff.
`KB_AUTO_MCP_DISABLED=1` turns every hook into a no-op.

### Sanity check

```bash
# Hooks can reach the MCP. DNS for kb.jorisjonkers.dev can take a
# few seconds on a cold cache, so use a generous connect-timeout:
curl -sS --connect-timeout 8 --max-time 10 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' \
  https://kb.jorisjonkers.dev/mcp | jq '.result.tools | map(.name)'

# Claude Code can see the server:
claude mcp list
```

A fresh Claude Code session in any repo should print the
"Knowledge base — recent notes for project:&lt;repo&gt;" preamble
(or nothing, if that scope has no notes yet).

### Rotation

Tokens live in Vault. Rotate:

```bash
VAULT_ADDR=http://127.0.0.1:8200 vault kv patch \
  -mount=secret knowledge-system/mcp-bearer \
  workstation="$(openssl rand -hex 32)"
```

VSO refreshes the in-cluster Secret + knowledge-api restarts on the
next reconcile. On the workstation, update `~/.claude.json` (via
`claude mcp remove knowledge && claude mcp add …` with the new
token) and `~/.zshrc`, then `source ~/.zshrc`.
