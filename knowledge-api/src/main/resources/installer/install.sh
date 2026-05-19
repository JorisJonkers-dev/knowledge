#!/usr/bin/env bash
# knowledge-system installer for the Claude Code client.
#
# Writes the local hooks + skills that pair with the knowledge-api
# MCP server. Idempotent: re-running picks up any updates the server
# ships in subsequent versions. Use `--dry-run` to preview the
# changes before they land. Use `--uninstall` to remove them.
#
# Released versions are pinned by the @VERSION@ token below, which
# the knowledge-api templates at request time from
# `SERVICE_VERSION` (or `unknown` for local builds).

set -euo pipefail

readonly INSTALLER_VERSION='@VERSION@'
readonly KB_URL='@KB_URL@'
readonly CLAUDE_HOME="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
readonly HOOKS_DIR="${CLAUDE_HOME}/hooks"
readonly SKILLS_DIR="${CLAUDE_HOME}/skills"
readonly MANIFEST="${CLAUDE_HOME}/.knowledge-system-version"

DRY_RUN=0
UNINSTALL=0

usage() {
  cat <<USAGE
knowledge-system installer ${INSTALLER_VERSION}

Writes Claude Code hooks + skills that pair with the MCP server at
${KB_URL}.

Usage:
  curl -fsSL -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
    ${KB_URL}/install.sh | bash [-s -- [--dry-run|--uninstall]]

Options:
  --dry-run     Print every change without modifying the filesystem.
  --uninstall   Remove every file this installer would write.
  --help        Show this help and exit.

Environment:
  CLAUDE_CONFIG_DIR   Override the Claude Code config root (default ~/.claude).
USAGE
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown option: $arg" >&2; usage >&2; exit 64 ;;
  esac
done

log() { printf 'knowledge-system installer: %s\n' "$*"; }

write_file() {
  local path="$1"
  local mode="$2"
  local content="$3"
  if [ "${DRY_RUN}" = 1 ]; then
    log "would write ${path} (mode ${mode}, $(printf '%s' "$content" | wc -c | tr -d ' ') bytes)"
    return
  fi
  mkdir -p "$(dirname "$path")"
  printf '%s' "$content" > "$path"
  chmod "$mode" "$path"
  log "wrote ${path}"
}

remove_file() {
  local path="$1"
  if [ ! -e "$path" ]; then return; fi
  if [ "${DRY_RUN}" = 1 ]; then
    log "would remove ${path}"
    return
  fi
  rm -f "$path"
  log "removed ${path}"
}

managed_paths=(
  "${HOOKS_DIR}/user-prompt-submit-recall.sh"
  "${SKILLS_DIR}/topics/SKILL.md"
  "${SKILLS_DIR}/audit/SKILL.md"
)

if [ "${UNINSTALL}" = 1 ]; then
  log "uninstalling knowledge-system files (${INSTALLER_VERSION})"
  for path in "${managed_paths[@]}"; do
    remove_file "$path"
  done
  remove_file "${MANIFEST}"
  log "done"
  exit 0
fi

# -----------------------------------------------------------------
# Hook: UserPromptSubmit recall
# -----------------------------------------------------------------
read -r -d '' USER_PROMPT_SUBMIT_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# UserPromptSubmit hook — calls knowledge.recall with the user's
# prompt content before the agent sees it. Top-3 hits are injected
# as a <context> block so the agent has prior captures in hand.
#
# Silent on failure; the KB being unreachable should not block
# typing into Claude Code.

set -u

if [ -z "${KB_BEARER_TOKEN:-}" ]; then exit 0; fi

KB_URL="${KB_URL:-@KB_URL@}"

# Stdin carries the JSON event payload; the user_prompt field has the
# raw text. Use python (always present on macOS / NixOS) to extract.
prompt="$(python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("user_prompt") or data.get("prompt") or "", end="")' 2>/dev/null || true)"

# Skip trivially-short prompts — overhead > value.
[ "${#prompt}" -lt 20 ] && exit 0

payload=$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.recall","arguments":{"query":sys.argv[1],"limit":3}}}))' "${prompt}")

response=$(curl -sS --connect-timeout 3 --max-time 5 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${KB_URL}" 2>/dev/null) || exit 0

hits=$(printf '%s' "${response}" | python3 -c 'import json,sys
try:
    data = json.load(sys.stdin)
    hits = data["result"]["structuredContent"]["hits"]
    if not hits: sys.exit(0)
    print("## Knowledge base — relevant prior captures")
    print()
    for h in hits:
        print(f"- **{h[\"title\"]}** (`{h[\"scope\"]}`, score {h[\"score\"]:.2f}) — id `{h[\"id\"]}`")
        snip = h.get("snippet","").replace("\n"," ").strip()
        if snip: print(f"  > {snip[:220]}")
except Exception:
    sys.exit(0)' 2>/dev/null) || exit 0

if [ -n "${hits}" ]; then
  printf '%s\n' "${hits}"
fi
HOOK

write_file "${HOOKS_DIR}/user-prompt-submit-recall.sh" 0755 "${USER_PROMPT_SUBMIT_HOOK}"

# -----------------------------------------------------------------
# Skill: topics
# -----------------------------------------------------------------
read -r -d '' TOPICS_SKILL <<'SKILL' || true
---
name: topics
description: Inspect the knowledge-base topic vocabulary before capturing or recalling. Use proactively when about to assign a scope or pick a tag — the closed-vocabulary slugs change over time and an incorrect slug routes captures to _inbox/_needs-review/.
---

# Topics + tags discovery

Three MCP tools surface what the knowledge base already knows:

- `knowledge.list_topics` — every topic slug in use, with note count
  + last-captured-at. Sort by note_count desc by default. Use before
  picking a `topic:<slug>` scope so a new capture lands on the
  existing vocabulary instead of forking a near-duplicate.
- `knowledge.topic_stats(slug)` — per-topic aggregate: count,
  capture window, type breakdown, top tags. Use to decide whether a
  topic is well-populated enough to capture into or whether to merge
  it with a more active neighbour.
- `knowledge.list_tags(scope?)` — tag frequency, optional scope
  filter. Use before tagging a new capture so the spelling matches
  existing tags (`kotlin` vs `Kotlin` vs `kt`).

When in doubt about which slug to use, prefer the one with the
highest note_count among plausible candidates. If none fit, capture
without scope — the curator's classifier will assign one against
the closed vocabulary.
SKILL

write_file "${SKILLS_DIR}/topics/SKILL.md" 0644 "${TOPICS_SKILL}"

# -----------------------------------------------------------------
# Skill: audit
# -----------------------------------------------------------------
read -r -d '' AUDIT_SKILL <<'SKILL' || true
---
name: audit
description: Audit the knowledge base for drift — pending inbox notes, near-duplicate tags, near-duplicate topic slugs. Use periodically (weekly is plenty) or when capture quality feels off.
---

# Knowledge-base audit

Three checks, each a single MCP call:

1. `knowledge.list_inbox(limit=20)` — notes the worker captured but
   the curator hasn't classified yet. A persistent backlog signals
   that Ollama is wedged or the classifier is rejecting too much.
2. `knowledge.list_tags(limit=100)` — scan for near-duplicate
   spellings (`kotlin` / `Kotlin` / `kt`, `ci` / `CI` / `ci-cd`).
   Propose `knowledge.rename_tag(from, to)` for the cleanups, but
   don't run them — those are admin-only mutations.
3. `knowledge.list_topics(limit=100)` — flag topics with note_count
   of 1 or 2 (thin) and pairs of slugs that look like duplicates.
   Propose `knowledge.merge_topics(from_slug, into_slug)` for the
   candidates.

Report findings in three short sections. Don't mutate anything —
the operator runs the proposed merges / renames manually after
review.
SKILL

write_file "${SKILLS_DIR}/audit/SKILL.md" 0644 "${AUDIT_SKILL}"

# -----------------------------------------------------------------
# Manifest
# -----------------------------------------------------------------
if [ "${DRY_RUN}" != 1 ]; then
  cat > "${MANIFEST}" <<MANIFEST
# Managed by the knowledge-system installer (${KB_URL}/install.sh).
# Re-run that command to update. Use --uninstall to remove every
# file listed below.
version=${INSTALLER_VERSION}
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
managed:
$(printf '  - %s\n' "${managed_paths[@]}")
MANIFEST
  log "wrote ${MANIFEST}"
fi

cat <<EOF
knowledge-system installer complete (${INSTALLER_VERSION}).

Next steps:
  1. Register the new hook in ${CLAUDE_HOME}/settings.json under
     the "hooks.UserPromptSubmit" array:
     {
       "matcher": ".*",
       "hooks": [
         { "type": "command",
           "command": "${HOOKS_DIR}/user-prompt-submit-recall.sh",
           "timeout": 5 }
       ]
     }
  2. Make sure KB_BEARER_TOKEN is set in your shell / Claude Code env:
       export KB_BEARER_TOKEN="<your-token>"
  3. Verify with:  curl -sS -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
                     ${KB_URL}/mcp -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'

Run with --uninstall to remove every file this installer wrote.
EOF
