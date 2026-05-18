# knowledge-api

Spring/Kotlin MCP server backing the centralized knowledge base.
Exposes an HTTP Streamable JSON-RPC MCP endpoint at
`https://kb.jorisjonkers.dev/mcp`. Capture tools (`capture_lesson`,
`capture_decision`, `ingest_note`) mint a ULID, insert a row in
`kb_notes`, and publish a job to the `knowledge` RabbitMQ topic
exchange for the Python ingest worker. Read tools (`recall`,
`list_recent`, `get_note`, `find_conflicts`) query the same tables
via jOOQ — today Postgres FTS, a pgvector ANN leg lands in a
follow-up.

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

### Dotfiles installed by Phase 6

These live under `~/.claude/` on the workstation, NOT in this repo
(they are user-scope and survive across every Claude Code session,
not just personal-stack-2).

- `~/.claude/skills/recall/SKILL.md` — wraps the `recall` MCP tool;
  Claude Code surfaces it proactively when relevant queries come up.
- `~/.claude/skills/lesson/SKILL.md` — wraps `capture_lesson`.
- `~/.claude/skills/decision/SKILL.md` — wraps `capture_decision`.
- `~/.claude/hooks/session-start-recall.sh` — prepends recent
  project-scoped notes to every new session's preamble.
- `~/.claude/hooks/stop-digest.sh` — async-uploads modified
  auto-memory files (`~/.claude/projects/<encoded>/memory/*.md`)
  via `ingest_note` so future sessions in other projects can recall
  them.
- `~/.claude/settings.json` — `hooks.SessionStart` + `hooks.Stop`
  entries pointing at the two scripts.

### Sanity check

```bash
# Hooks can reach the MCP:
curl -sS -m 3 \
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
