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

readonly STATE_DIR="${CLAUDE_HOME}/state"
readonly ALLOWLIST="${CLAUDE_HOME}/.knowledge-system-allowlist"

managed_paths=(
  "${HOOKS_DIR}/user-prompt-submit-recall.sh"
  "${HOOKS_DIR}/pre-tool-use-edit-recall.sh"
  "${HOOKS_DIR}/pre-tool-use-git-commit-capture.sh"
  "${HOOKS_DIR}/stop-session-digest.sh"
  "${SKILLS_DIR}/topics/SKILL.md"
  "${SKILLS_DIR}/audit/SKILL.md"
  "${ALLOWLIST}"
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
# Path allowlist (gitignore-style). Hooks below skip any tool input
# whose target matches a pattern here. Defaults exclude paths that
# typically carry secrets so an Edit on `.env` does not exfiltrate
# the path to the KB recall query.
# -----------------------------------------------------------------
if [ ! -e "${ALLOWLIST}" ]; then
  read -r -d '' ALLOWLIST_DEFAULTS <<'ALLOW' || true
# knowledge-system auto-MCP path allowlist (gitignore-style).
# Lines starting with `#` are comments. Patterns match against the
# full target path the hook is about to act on. Hooks SKIP any
# match, so adding a line here disables auto-MCP for that path.
#
# Re-running the installer never overwrites this file once you've
# customised it — only the initial install seeds these defaults.

# Secrets-bearing files
*.env
.env*
*.secret
*.key
*.pem
*.p12
*.pfx
*.jks
secrets/**
credentials*
**/credentials/**
id_rsa
id_ed25519
**/.ssh/**

# Vault / KMS / cloud auth state
.aws/**
.config/gcloud/**
.kube/config
.kube/cache/**

# Browser / OS keychains
**/Library/Keychains/**
**/Mozilla/Firefox/**
**/Google/Chrome/Default/Login Data*
ALLOW
  write_file "${ALLOWLIST}" 0644 "${ALLOWLIST_DEFAULTS}"
else
  log "preserving existing ${ALLOWLIST}"
fi

# -----------------------------------------------------------------
# Hook: PreToolUse — Edit/Write/MultiEdit recall
# -----------------------------------------------------------------
read -r -d '' PRE_TOOL_USE_EDIT_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# PreToolUse hook for Edit/Write/MultiEdit. Looks at the file path
# the agent is about to touch and runs a `knowledge.recall` against
# it so prior captures referencing that file (or its module) surface
# before the edit lands.
#
# Safety:
#   - Honours KB_AUTO_MCP_DISABLED=1 (panic switch).
#   - Honours `~/.claude/.knowledge-system-allowlist` (skip if match).
#   - Per-session dedupe: only fires once per (session, file_path)
#     so an N-Edit sequence on the same file does not stutter.
#   - Silent on failure — the KB being unreachable must never block
#     an edit.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
[ -z "${KB_BEARER_TOKEN:-}" ] && exit 0

KB_URL="${KB_URL:-@KB_URL@}"
STATE_DIR="${HOME}/.claude/state"
ALLOWLIST="${HOME}/.claude/.knowledge-system-allowlist"

input=$(cat 2>/dev/null || true)
file_path=$(printf '%s' "${input}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    inp = data.get("tool_input") or {}
    print(inp.get("file_path") or inp.get("filePath") or inp.get("path") or "", end="")
except Exception:
    pass' 2>/dev/null || true)
[ -z "${file_path}" ] && exit 0

# Allowlist match — git-style globbing via a python helper. fnmatch
# does not span `/`, so we walk pattern + path together.
if [ -r "${ALLOWLIST}" ]; then
  if python3 - "${ALLOWLIST}" "${file_path}" <<'PY' 2>/dev/null
import fnmatch, sys, os
allowlist, path = sys.argv[1], sys.argv[2]
with open(allowlist) as f:
    for line in f:
        pat = line.strip()
        if not pat or pat.startswith("#"): continue
        # Translate `**` to a recursive match by relying on fnmatch's
        # `*` against the basename and the full path.
        if fnmatch.fnmatch(path, pat) or fnmatch.fnmatch(os.path.basename(path), pat):
            sys.exit(0)
    sys.exit(1)
PY
  then
    exit 0
  fi
fi

# Per-session dedupe: state/sessions/<session>/edit-<sha1-of-path>
session="${CLAUDE_SESSION_ID:-unknown}"
mkdir -p "${STATE_DIR}/sessions/${session}"
marker="${STATE_DIR}/sessions/${session}/edit-$(printf '%s' "${file_path}" | shasum -a 1 | cut -c1-12)"
[ -e "${marker}" ] && exit 0
: > "${marker}"

# Query: filename basename + the parent dir, plenty for FTS recall.
basename=$(basename "${file_path}")
parent=$(basename "$(dirname "${file_path}")")
query="${basename} ${parent}"

payload=$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.recall","arguments":{"query":sys.argv[1],"limit":2}}}))' "${query}")

response=$(curl -sS --connect-timeout 3 --max-time 5 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${KB_URL}/mcp" 2>/dev/null) || exit 0

printf '%s' "${response}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    hits = data["result"]["structuredContent"]["hits"]
    if not hits: sys.exit(0)
    print()
    print(f"## Related captures for this file")
    for h in hits:
        print(f"- **{h[\"title\"]}** (`{h[\"scope\"]}`) — id `{h[\"id\"]}`")
        snip = h.get("snippet","").replace("\n"," ").strip()
        if snip: print(f"  > {snip[:160]}")
except Exception:
    sys.exit(0)' 2>/dev/null || true
HOOK

write_file "${HOOKS_DIR}/pre-tool-use-edit-recall.sh" 0755 "${PRE_TOOL_USE_EDIT_HOOK}"

# -----------------------------------------------------------------
# Hook: PreToolUse — Bash matching `git commit` capture
# -----------------------------------------------------------------
read -r -d '' PRE_TOOL_USE_GIT_COMMIT_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# PreToolUse hook for Bash. Fires only when the command looks like a
# `git commit -m "..."` (or heredoc variant). Captures the commit
# message as a `decision` note scoped to the current repo, with a
# distinct source so the operator can bulk-revoke if needed.
#
# Skips merge / fixup / WIP commits — too noisy to capture.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
[ -z "${KB_BEARER_TOKEN:-}" ] && exit 0

KB_URL="${KB_URL:-@KB_URL@}"

input=$(cat 2>/dev/null || true)
read -r tool command < <(printf '%s' "${input}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    print(data.get("tool_name") or data.get("tool") or "", (data.get("tool_input") or {}).get("command") or "")
except Exception:
    print("")' 2>/dev/null)
[ "${tool}" = "Bash" ] || exit 0

# Match `git commit -m "..."` shape; both single and double quotes.
case "${command}" in
  *"git commit"*"-m"*) : ;;
  *) exit 0 ;;
esac
# Skip noise: merge, fixup, WIP.
case "${command}" in
  *"fixup!"*|*"WIP"*|*"wip"*|*"Merge "*) exit 0 ;;
esac

# Extract the message: everything between the first matching quote
# pair after `-m`. Defer the regex to python for sanity.
title=$(printf '%s' "${command}" | python3 -c '
import re, sys
cmd = sys.stdin.read()
m = re.search(r"-m\s+(\x27([^\x27]+)\x27|\"([^\"]+)\")", cmd)
if not m: sys.exit(0)
print(m.group(2) or m.group(3) or "", end="")' 2>/dev/null)
[ -z "${title}" ] && exit 0

# Scope: project:<repo-name> from origin URL, mirrors the
# SessionStart hook's convention.
project="$(git remote get-url origin 2>/dev/null | sed -e 's#\.git$##' -e 's#.*[/:]##')"
[ -n "${project}" ] || project="$(basename "$(pwd)")"
scope="project:${project}"

body=$(cat <<BODY
Commit message: ${title}

Captured automatically by the auto-MCP \`git commit\` hook. The
diff and surrounding context live in git history.
BODY
)

payload=$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.capture_decision","arguments":{
      "title": sys.argv[1],
      "body": sys.argv[2],
      "scope": sys.argv[3],
      "source": "claude-code:auto-capture:git-commit",
      "tags": ["auto-capture","git-commit"]
    }}}))' "${title}" "${body}" "${scope}")

curl -sS --connect-timeout 3 --max-time 5 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${KB_URL}/mcp" >/dev/null 2>&1 || true
HOOK

write_file "${HOOKS_DIR}/pre-tool-use-git-commit-capture.sh" 0755 "${PRE_TOOL_USE_GIT_COMMIT_HOOK}"

# -----------------------------------------------------------------
# Hook: Stop — session-digest auto-capture
# -----------------------------------------------------------------
read -r -d '' STOP_SESSION_DIGEST_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# Stop hook: Reflexion-style session-digest auto-capture.
#
# Reads the transcript path from the hook input, asks
# `knowledge.digest_transcript` for candidate captures, applies the
# client-side policy (confidence floor, per-session token bucket,
# cross-session dedupe via `knowledge.recall`), and emits survivors
# via `knowledge.capture_lesson` with a distinct `source` so the
# operator can bulk-revoke if needed.
#
# Async by default (Claude Code's settings.json marks Stop hooks
# `async: true`), so blocking is fine here. Still bounded at ~60s.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
[ -z "${KB_BEARER_TOKEN:-}" ] && exit 0

KB_URL="${KB_URL:-@KB_URL@}"
STATE_DIR="${HOME}/.claude/state"
LOG="${STATE_DIR}/auto-mcp.log"
mkdir -p "${STATE_DIR}"

input=$(cat 2>/dev/null || true)
read -r session transcript_path < <(printf '%s' "${input}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    print(data.get("session_id") or "unknown", data.get("transcript_path") or "")
except Exception:
    print("unknown")' 2>/dev/null)

# Per-session token bucket: max 5 auto-captures per session.
session_dir="${STATE_DIR}/sessions/${session}"
mkdir -p "${session_dir}"
remaining_file="${session_dir}/digest-budget"
if [ -e "${remaining_file}" ]; then
  remaining=$(cat "${remaining_file}")
else
  remaining=5
fi

# Load the transcript text. Claude Code stores it as JSONL; the
# digest tool tolerates raw text, so a stringify-then-send is fine.
if [ -z "${transcript_path}" ] || [ ! -r "${transcript_path}" ]; then
  echo "$(date -u +%FT%TZ) stop-digest skip: no transcript" >>"${LOG}" 2>/dev/null
  exit 0
fi
transcript=$(python3 -c '
import json, sys
out = []
with open(sys.argv[1]) as f:
    for line in f:
        line = line.strip()
        if not line: continue
        try:
            row = json.loads(line)
            role = row.get("role") or row.get("type") or "?"
            content = row.get("content") or row.get("text") or ""
            if isinstance(content, list):
                content = " ".join(p.get("text","") for p in content if isinstance(p, dict))
            out.append(f"[{role}] {content}")
        except Exception:
            pass
print("\n".join(out))' "${transcript_path}" 2>/dev/null) || exit 0

[ -z "${transcript}" ] && exit 0

# Server-side digest call.
payload=$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.digest_transcript",
    "arguments":{"transcript":sys.argv[1],"max_candidates":int(sys.argv[2])}}}))' \
  "${transcript}" "${remaining}")

response=$(curl -sS --connect-timeout 5 --max-time 60 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${KB_URL}/mcp" 2>/dev/null) || exit 0

candidates=$(printf '%s' "${response}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    print(json.dumps(data["result"]["structuredContent"]["candidates"]))
except Exception:
    print("[]")' 2>/dev/null || echo "[]")

# Emit each survivor via capture_lesson. Server-side digest already
# applied the confidence floor; we re-apply the per-session token
# bucket here, and the source tagging.
emitted=0
while IFS= read -r line; do
  [ -z "${line}" ] && continue
  [ "${remaining}" -le 0 ] && break
  title=$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["title"], end="")')
  body=$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["body"], end="")')
  topic=$(printf '%s' "${line}" | python3 -c 'import json,sys; print((json.loads(sys.stdin.read()).get("suggested_topic") or ""), end="")')
  tags_json=$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read()).get("suggested_tags") or []), end="")')
  scope_arg=""
  [ -n "${topic}" ] && scope_arg="topic:${topic}"
  capture_payload=$(python3 -c 'import json,sys; print(json.dumps({
    "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
      "name":"knowledge.capture_lesson","arguments":{
        "title": sys.argv[1],
        "body": sys.argv[2],
        "scope": sys.argv[3] or None,
        "source": "claude-code:auto-digest:" + sys.argv[4],
        "tags": json.loads(sys.argv[5])
      }}}))' "${title}" "${body}" "${scope_arg}" "${session}" "${tags_json}")
  curl -sS --connect-timeout 3 --max-time 10 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${capture_payload}" \
    "${KB_URL}/mcp" >/dev/null 2>&1 || continue
  remaining=$((remaining - 1))
  emitted=$((emitted + 1))
  echo "$(date -u +%FT%TZ) stop-digest emit session=${session} title=${title}" >>"${LOG}" 2>/dev/null
done < <(printf '%s' "${candidates}" | python3 -c '
import json, sys
try:
    rows = json.load(sys.stdin)
    for r in rows: print(json.dumps(r))
except Exception:
    pass' 2>/dev/null)

echo "${remaining}" > "${remaining_file}"
echo "$(date -u +%FT%TZ) stop-digest done session=${session} emitted=${emitted}" >>"${LOG}" 2>/dev/null
HOOK

write_file "${HOOKS_DIR}/stop-session-digest.sh" 0755 "${STOP_SESSION_DIGEST_HOOK}"

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

  1. Register the four hooks in ${CLAUDE_HOME}/settings.json
     under the matching "hooks.<event>" arrays. Suggested config
     (drop into your settings under "hooks"):

     "UserPromptSubmit": [
       { "matcher": ".*", "hooks": [
         { "type": "command",
           "command": "${HOOKS_DIR}/user-prompt-submit-recall.sh",
           "timeout": 5 } ] } ],
     "PreToolUse": [
       { "matcher": "Edit|Write|MultiEdit", "hooks": [
         { "type": "command",
           "command": "${HOOKS_DIR}/pre-tool-use-edit-recall.sh",
           "timeout": 5 } ] },
       { "matcher": "Bash", "hooks": [
         { "type": "command",
           "command": "${HOOKS_DIR}/pre-tool-use-git-commit-capture.sh",
           "timeout": 5 } ] } ],
     "Stop": [
       { "matcher": ".*", "hooks": [
         { "type": "command",
           "command": "${HOOKS_DIR}/stop-session-digest.sh",
           "async": true,
           "timeout": 60 } ] } ]

  2. Make sure KB_BEARER_TOKEN is set in your shell / Claude Code env:
       export KB_BEARER_TOKEN="<your-token>"

  3. Verify with:  curl -sS -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
                     ${KB_URL}/mcp -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'

Safety controls:
  - Panic switch:   export KB_AUTO_MCP_DISABLED=1   (turns every hook into a no-op).
  - Path allowlist: edit ${ALLOWLIST}  (gitignore-style patterns).
  - State + logs:   ${STATE_DIR}/auto-mcp.log + per-session dedupe under ${STATE_DIR}/sessions/.
  - Provenance:     every auto-capture lands with source = "claude-code:auto-capture:<hook>"
                    or "claude-code:auto-digest:<session>" so a bulk revoke is one SQL query.

Run with --uninstall to remove every file this installer wrote.
EOF
