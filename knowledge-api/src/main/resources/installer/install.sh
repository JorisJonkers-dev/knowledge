#!/usr/bin/env bash
# knowledge-system installer for Claude Code and Codex clients.
#
# Writes the local hooks + skills that pair with the knowledge-api
# MCP server. Idempotent: re-running picks up any updates the server
# ships in subsequent versions. Use `--agent claude|codex|all` to
# choose the client home to manage. Use `--dry-run` to preview the
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
readonly CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
readonly CODEX_HOOKS_DIR="${CODEX_HOME}/hooks"
readonly CODEX_SKILLS_DIR="${CODEX_HOME}/skills"
readonly CODEX_HOOKS_CONFIG="${CODEX_HOME}/hooks.json"
readonly CODEX_MANIFEST="${CODEX_HOME}/.knowledge-system-version"

DRY_RUN=0
UNINSTALL=0
AGENT=claude

usage() {
  cat <<USAGE
knowledge-system installer ${INSTALLER_VERSION}

Writes Claude Code and/or Codex hooks + skills that pair with the MCP server at
${KB_URL}.

Usage:
  curl -fsSL -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
    ${KB_URL}/install.sh | bash [-s -- [--agent claude|codex|all] [--dry-run|--uninstall]]

Options:
  --agent      Client home to manage. Defaults to "claude" for backwards compatibility.
  --dry-run     Print every change without modifying the filesystem.
  --uninstall   Remove every file this installer would write.
  --help        Show this help and exit.

Environment:
  CLAUDE_CONFIG_DIR   Override the Claude Code config root (default ~/.claude).
  CODEX_HOME          Override the Codex config root (default ~/.codex).
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --agent)
      shift
      [ "$#" -gt 0 ] || { echo "--agent requires claude, codex, or all" >&2; exit 64; }
      AGENT="$1"
      ;;
    --agent=*) AGENT="${1#--agent=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 64 ;;
  esac
  shift
done

case "${AGENT}" in
  claude)
    INSTALL_CLAUDE=1
    INSTALL_CODEX=0
    ;;
  codex)
    INSTALL_CLAUDE=0
    INSTALL_CODEX=1
    ;;
  all)
    INSTALL_CLAUDE=1
    INSTALL_CODEX=1
    ;;
  *)
    echo "--agent must be claude, codex, or all" >&2
    usage >&2
    exit 64
    ;;
esac

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

claude_managed_paths=(
  "${HOOKS_DIR}/user-prompt-submit-recall.sh"
  "${HOOKS_DIR}/pre-tool-use-edit-recall.sh"
  "${HOOKS_DIR}/pre-tool-use-git-commit-capture.sh"
  "${HOOKS_DIR}/stop-session-digest.sh"
  "${SKILLS_DIR}/topics/SKILL.md"
  "${SKILLS_DIR}/audit/SKILL.md"
  "${SKILLS_DIR}/kb-first/SKILL.md"
  "${SKILLS_DIR}/token-economy/SKILL.md"
  "${SKILLS_DIR}/agent-session-bootstrap/SKILL.md"
  "${ALLOWLIST}"
)

codex_managed_paths=(
  "${CODEX_HOOKS_DIR}/kb-user-prompt-recall.sh"
  "${CODEX_HOOKS_DIR}/kb-stop-digest.sh"
  "${CODEX_SKILLS_DIR}/topics/SKILL.md"
  "${CODEX_SKILLS_DIR}/audit/SKILL.md"
  "${CODEX_SKILLS_DIR}/kb-first/SKILL.md"
  "${CODEX_SKILLS_DIR}/token-economy/SKILL.md"
  "${CODEX_SKILLS_DIR}/agent-session-bootstrap/SKILL.md"
  "${CODEX_HOOKS_CONFIG}"
)

managed_paths=()
if [ "${INSTALL_CLAUDE}" = 1 ]; then
  managed_paths+=("${claude_managed_paths[@]}")
fi
if [ "${INSTALL_CODEX}" = 1 ]; then
  managed_paths+=("${codex_managed_paths[@]}")
fi

if [ "${UNINSTALL}" = 1 ]; then
  log "uninstalling knowledge-system files (${INSTALLER_VERSION}, agent=${AGENT})"
  for path in "${managed_paths[@]}"; do
    remove_file "$path"
  done
  if [ "${INSTALL_CLAUDE}" = 1 ]; then
    remove_file "${MANIFEST}"
  fi
  if [ "${INSTALL_CODEX}" = 1 ]; then
    remove_file "${CODEX_MANIFEST}"
  fi
  log "done"
  exit 0
fi

# -----------------------------------------------------------------
# Hook: UserPromptSubmit recall
# -----------------------------------------------------------------
read -r -d '' USER_PROMPT_SUBMIT_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# UserPromptSubmit hook — calls knowledge.recall with the user's
# prompt content before the agent sees it. A tiny bounded hit list is
# injected so the agent has prior captures in hand without a KB dump.
#
# Silent on failure; the KB being unreachable should not block
# typing into Claude Code.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
if [ -z "${KB_BEARER_TOKEN:-}" ]; then exit 0; fi

KB_URL="${KB_URL:-@KB_URL@}"
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac

# Stdin carries the JSON event payload; the user_prompt field has the
# raw text. Use python (always present on macOS / NixOS) to extract.
prompt="$(python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("user_prompt") or data.get("prompt") or "", end="")' 2>/dev/null || true)"

# Skip trivially-short prompts — overhead > value.
[ "${#prompt}" -lt "${KB_RECALL_MIN_PROMPT_CHARS:-40}" ] && exit 0

mode="${KB_RECALL_HOOK_MODE:-hybrid}"
limit="${KB_RECALL_HOOK_LIMIT:-3}"

recall_payload() {
  python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.recall",
    "arguments":{"query":sys.argv[1],"limit":int(sys.argv[2]),"mode":sys.argv[3]}}}))' \
    "$1" "$2" "$3"
}

call_recall() {
  payload=$(recall_payload "$1" "$2" "$3") || return 1
  curl -sS --connect-timeout 3 --max-time 5 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${payload}" \
    "${KB_MCP_URL}" 2>/dev/null
}

response=$(call_recall "${prompt}" "${limit}" "${mode}") || response=""
if [ -z "${response}" ] && [ "${mode}" != "fast" ]; then
  response=$(call_recall "${prompt}" "${limit}" fast) || exit 0
fi
[ -n "${response}" ] || exit 0

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

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${HOOKS_DIR}/user-prompt-submit-recall.sh" 0755 "${USER_PROMPT_SUBMIT_HOOK}"
fi

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

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${SKILLS_DIR}/topics/SKILL.md" 0644 "${TOPICS_SKILL}"
fi

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

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${SKILLS_DIR}/audit/SKILL.md" 0644 "${AUDIT_SKILL}"
fi

# -----------------------------------------------------------------
# Skill: kb-first
# -----------------------------------------------------------------
read -r -d '' KB_FIRST_SKILL <<'SKILL' || true
---
name: kb-first
description: Use before designing or changing behavior that may depend on prior knowledge-base captures, repo history, architecture decisions, cluster state, agent conventions, or remembered lessons. Also use near task completion to capture durable lessons or decisions without dumping large KB context.
---

# KB First

Use the KB as a small retrieval layer, not as a large context dump.

1. Distill the task into a short recall query: nouns, service names,
   file names, and the decision being made.
2. Call `knowledge.recall` with `limit <= 5`. Prefer
   `scope=project:personal-stack` for repo behavior, `topic:<slug>`
   for general framework/tool facts, or omit scope for the curated
   default.
3. Use `mode=hybrid` for normal work. Use `mode=fast` for trivial
   lookup or when latency matters more than semantic matching. Use
   `mode=deep` only when fast or hybrid misses something important.
4. Read only what is needed. Usually snippets are enough. If a hit
   matters, call `knowledge.relations(id, depth=1)` before fetching
   the full note.
5. If the KB has no useful context, continue from repo/source
   inspection and say the KB had no relevant hits.

Capture at the end only when the information is durable and reusable:
implementation pitfalls, verified behavior, operational runbooks,
architecture/process choices, or ambiguity that needs operator judgment.
Keep captures compact. Do not capture secrets, raw logs, full diffs, or
entire transcripts.

Never run broad `scope=all` recall as a first step. Use it only after
targeted recall fails and the task genuinely needs cross-scope context.
SKILL

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${SKILLS_DIR}/kb-first/SKILL.md" 0644 "${KB_FIRST_SKILL}"
fi

# -----------------------------------------------------------------
# Skill: token-economy
# -----------------------------------------------------------------
read -r -d '' TOKEN_ECONOMY_SKILL <<'SKILL' || true
---
name: token-economy
description: Use when the user asks to reduce token usage, agent cost, context bloat, prompt-caching misses, RAG/LightRAG behavior, memory policies, or durable instructions. Also use when installing many skills or designing automatic KB recall so retrieval stays bounded.
---

# Token Economy

- Keep stable instructions in `CLAUDE.md` or skills; keep volatile facts
  in the KB and retrieve them on demand.
- Prefer progressive disclosure: list/search first, open small file
  ranges next, fetch full files or notes only when needed.
- Keep recall bounded: default to `limit=3` for hook-injected context and
  `limit <= 5` for manual task setup.
- Use hybrid retrieval for normal KB recall. Escalate to deep retrieval
  only after a miss, ambiguity, or non-obvious cross-topic dependency.
- Do not install or enable low-fit skills just to grow the list. Skill
  metadata itself consumes prompt budget and very large skill sets can
  hide useful skills.
- Preserve prompt-cache-friendly ordering when writing durable
  instructions: stable policy first, dynamic task-specific context later.

When reporting command results, summarize only the lines needed to
support the decision. Session digests should capture only reusable
lessons above a confidence floor and should dedupe against existing KB
hits before writing.
SKILL

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${SKILLS_DIR}/token-economy/SKILL.md" 0644 "${TOKEN_ECONOMY_SKILL}"
fi

# -----------------------------------------------------------------
# Skill: agent-session-bootstrap
# -----------------------------------------------------------------
read -r -d '' AGENT_SESSION_BOOTSTRAP_SKILL <<'SKILL' || true
---
name: agent-session-bootstrap
description: Use when configuring Claude Code or Codex sessions, hooks, skills, MCP servers, durable instructions, agent runners, or future-session defaults. Ensures KB recall/capture and token-efficient behavior are installed without relying on user reminders.
---

# Agent Session Bootstrap

Checklist:

1. Locate the active user and project config layers:
   `~/.claude/settings.json`, `~/.claude/CLAUDE.md`, project
   `CLAUDE.md`, project `.claude/settings.json`, project `.claude/hooks`,
   `~/.claude/skills`, Codex `~/.codex/config.toml`, `~/.codex/hooks.json`,
   repo `AGENTS.md`, and `.agents/skills`.
2. Ensure the `knowledge` MCP server is configured and uses
   `KB_BEARER_TOKEN` rather than an inline secret where possible.
3. Register bounded recall hooks:
   `UserPromptSubmit` with `limit=3`/`mode=hybrid`,
   `PreToolUse` edit recall deduped per session, and `Stop` transcript
   digest with a per-session capture cap.
4. Keep hooks silent on KB failure and add `KB_AUTO_MCP_DISABLED=1` as
   a panic switch.
5. Add or update memory files so future sessions know to consult and
   update the KB without user reminders.
6. Validate with dry-run hook payloads and at least one `tools/list` or
   `knowledge.recall` MCP call.

Every Codex project skill, hook, or durable instruction must have an
equivalent Claude implementation in the same branch. Treat Codex-only
`.agents`/`.codex` files as incomplete until `.claude`/`CLAUDE.md`/
installer parity exists.

Do not put bearer tokens, secrets, or full transcripts into committed
files.
SKILL

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${SKILLS_DIR}/agent-session-bootstrap/SKILL.md" 0644 "${AGENT_SESSION_BOOTSTRAP_SKILL}"
fi

# -----------------------------------------------------------------
# Path allowlist (gitignore-style). Hooks below skip any tool input
# whose target matches a pattern here. Defaults exclude paths that
# typically carry secrets so an Edit on `.env` does not exfiltrate
# the path to the KB recall query.
# -----------------------------------------------------------------
if [ "${INSTALL_CLAUDE}" = 1 ] && [ ! -e "${ALLOWLIST}" ]; then
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
elif [ "${INSTALL_CLAUDE}" = 1 ]; then
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
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac
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

# Scope to the current repo and query by filename + parent + path; this
# keeps automatic edit recall focused while still giving FTS enough terms.
project="$(git remote get-url origin 2>/dev/null | sed -e 's#\.git$##' -e 's#.*[/:]##')"
[ -n "${project}" ] || project="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")"
scope="${KB_RECALL_SCOPE:-project:${project}}"

basename=$(basename "${file_path}")
parent=$(basename "$(dirname "${file_path}")")
query="${basename} ${parent} ${file_path}"
mode="${KB_RECALL_HOOK_MODE:-hybrid}"
limit="${KB_RECALL_EDIT_LIMIT:-2}"

recall_payload() {
  python3 -c 'import json,sys
args = {"query": sys.argv[1], "limit": int(sys.argv[2]), "mode": sys.argv[3]}
if sys.argv[4]:
    args["scope"] = sys.argv[4]
print(json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"knowledge.recall","arguments":args}}))' \
    "$1" "$2" "$3" "$4"
}

call_recall() {
  payload=$(recall_payload "$1" "$2" "$3" "$4") || return 1
  curl -sS --connect-timeout 3 --max-time 5 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${payload}" \
    "${KB_MCP_URL}" 2>/dev/null
}

response=$(call_recall "${query}" "${limit}" "${mode}" "${scope}") || response=""
if [ -z "${response}" ] && [ "${mode}" != "fast" ]; then
  response=$(call_recall "${query}" "${limit}" fast "${scope}") || exit 0
fi
[ -n "${response}" ] || exit 0

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

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${HOOKS_DIR}/pre-tool-use-edit-recall.sh" 0755 "${PRE_TOOL_USE_EDIT_HOOK}"
fi

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
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac

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
  "${KB_MCP_URL}" >/dev/null 2>&1 || true
HOOK

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${HOOKS_DIR}/pre-tool-use-git-commit-capture.sh" 0755 "${PRE_TOOL_USE_GIT_COMMIT_HOOK}"
fi

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
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac
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

# Per-session token bucket: max 5 auto-captures per session by default.
session_dir="${STATE_DIR}/sessions/${session}"
mkdir -p "${session_dir}"
remaining_file="${session_dir}/digest-budget"
if [ -e "${remaining_file}" ]; then
  remaining=$(cat "${remaining_file}")
else
  remaining="${KB_DIGEST_MAX_CAPTURES:-5}"
fi

# Load the transcript text. Claude Code stores it as JSONL; the
# digest tool tolerates raw text, so a stringify-then-send is fine.
if [ -z "${transcript_path}" ] || [ ! -r "${transcript_path}" ]; then
  echo "$(date -u +%FT%TZ) stop-digest skip: no transcript" >>"${LOG}" 2>/dev/null
  exit 0
fi
transcript=$(python3 -c '
import json, os, sys
out = []
max_chars = int(os.environ.get("KB_DIGEST_MAX_CHARS", "60000"))
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
joined = "\n".join(out)
print(joined[-max_chars:])' "${transcript_path}" 2>/dev/null) || exit 0

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
  "${KB_MCP_URL}" 2>/dev/null) || exit 0

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
  [ -n "${title}" ] && [ -n "${body}" ] || continue

  dedupe_payload=$(python3 -c 'import json,sys; print(json.dumps({
    "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
      "name":"knowledge.recall","arguments":{
        "query": sys.argv[1], "limit": 1, "mode": "hybrid"}}}))' "${title} ${body}")
  duplicate_count=$(curl -sS --connect-timeout 3 --max-time 5 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${dedupe_payload}" \
    "${KB_MCP_URL}" 2>/dev/null | python3 -c '
import json, sys
try:
    hits = json.load(sys.stdin)["result"]["structuredContent"]["hits"]
    print(1 if hits and float(hits[0].get("score", 0)) >= float("'${KB_DIGEST_DEDUPE_SCORE:-0.86}'") else 0)
except Exception:
    print(0)
' 2>/dev/null || echo 0)
  [ "${duplicate_count}" = 1 ] && continue

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
    "${KB_MCP_URL}" >/dev/null 2>&1 || continue
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

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  write_file "${HOOKS_DIR}/stop-session-digest.sh" 0755 "${STOP_SESSION_DIGEST_HOOK}"
fi

# -----------------------------------------------------------------
# Codex project hook mirror
# -----------------------------------------------------------------
read -r -d '' CODEX_STOP_DIGEST_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# Codex Stop hook: summarize reusable lessons from the transcript and
# capture a capped set into the KB. Silent on failure.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
[ -z "${KB_BEARER_TOKEN:-}" ] && exit 0

KB_URL="${KB_URL:-@KB_URL@}"
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac

CODEX_STATE="${CODEX_HOME:-${HOME}/.codex}"
STATE_DIR="${CODEX_STATE}/state"
LOG="${STATE_DIR}/auto-kb.log"
mkdir -p "${STATE_DIR}"

input="$(cat 2>/dev/null || true)"
read -r session transcript_path < <(printf '%s' "${input}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("unknown")
    sys.exit(0)
session = data.get("session_id") or data.get("conversation_id") or data.get("thread_id") or "unknown"
path = data.get("transcript_path") or data.get("transcriptPath") or data.get("log_path") or ""
print(session, path)
' 2>/dev/null)

[ -n "${transcript_path:-}" ] && [ -r "${transcript_path}" ] || exit 0

session_dir="${STATE_DIR}/sessions/${session}"
mkdir -p "${session_dir}"
remaining_file="${session_dir}/digest-budget"
if [ -r "${remaining_file}" ]; then
  remaining="$(cat "${remaining_file}")"
else
  remaining="${KB_DIGEST_MAX_CAPTURES:-4}"
fi
[ "${remaining}" -gt 0 ] 2>/dev/null || exit 0

transcript="$(python3 - "${transcript_path}" "${KB_DIGEST_MAX_CHARS:-60000}" <<'PY' 2>/dev/null
import json, sys
path, max_chars = sys.argv[1], int(sys.argv[2])
rows = []

def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return " ".join(text(v) for v in value)
    if isinstance(value, dict):
        if isinstance(value.get("text"), str):
            return value["text"]
        if "content" in value:
            return text(value["content"])
    return ""

with open(path, errors="ignore") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except Exception:
            continue
        role = row.get("role") or row.get("type") or row.get("source") or "?"
        content = text(row.get("content") or row.get("text") or row.get("message") or row)
        if content:
            rows.append(f"[{role}] {content}")
out = "\n".join(rows)
print(out[-max_chars:])
PY
)" || exit 0

[ -n "${transcript}" ] || exit 0

payload="$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.digest_transcript",
    "arguments":{"transcript":sys.argv[1],"max_candidates":int(sys.argv[2])}}}))' \
  "${transcript}" "${remaining}")"

response="$(curl -sS --connect-timeout 5 --max-time 60 \
  -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${payload}" \
  "${KB_MCP_URL}" 2>/dev/null)" || exit 0

candidates="$(printf '%s' "${response}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    print(json.dumps(data["result"]["structuredContent"]["candidates"]))
except Exception:
    print("[]")
' 2>/dev/null || echo "[]")"

project="$(git remote get-url origin 2>/dev/null | sed -e 's#\.git$##' -e 's#.*[/:]##')"
[ -n "${project}" ] || project="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")"
fallback_scope="project:${project}"
emitted=0

while IFS= read -r line; do
  [ -n "${line}" ] || continue
  [ "${remaining}" -gt 0 ] 2>/dev/null || break

  title="$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("title",""), end="")')"
  body="$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("body",""), end="")')"
  topic="$(printf '%s' "${line}" | python3 -c 'import json,sys; print((json.loads(sys.stdin.read()).get("suggested_topic") or ""), end="")')"
  tags_json="$(printf '%s' "${line}" | python3 -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read()).get("suggested_tags") or []), end="")')"
  [ -n "${title}" ] && [ -n "${body}" ] || continue

  dedupe_payload="$(python3 -c 'import json,sys; print(json.dumps({
    "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
      "name":"knowledge.recall","arguments":{
        "query": sys.argv[1], "limit": 1, "mode": "hybrid"}}}))' "${title} ${body}")"
  duplicate_count="$(curl -sS --connect-timeout 3 --max-time 5 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${dedupe_payload}" \
    "${KB_MCP_URL}" 2>/dev/null | python3 -c '
import json, sys
try:
    hits = json.load(sys.stdin)["result"]["structuredContent"]["hits"]
    print(1 if hits and float(hits[0].get("score", 0)) >= float("'${KB_DIGEST_DEDUPE_SCORE:-0.86}'") else 0)
except Exception:
    print(0)
' 2>/dev/null || echo 0)"
  [ "${duplicate_count}" = 1 ] && continue

  scope="${fallback_scope}"
  [ -n "${topic}" ] && scope="topic:${topic}"
  capture_payload="$(python3 -c 'import json,sys; print(json.dumps({
    "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
      "name":"knowledge.capture_lesson","arguments":{
        "title": sys.argv[1],
        "body": sys.argv[2],
        "scope": sys.argv[3],
        "source": "codex:auto-digest:" + sys.argv[4],
        "session_id": sys.argv[4],
        "tags": json.loads(sys.argv[5])}}}))' \
    "${title}" "${body}" "${scope}" "${session}" "${tags_json}")"
  curl -sS --connect-timeout 3 --max-time 10 \
    -H "Authorization: Bearer ${KB_BEARER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${capture_payload}" \
    "${KB_MCP_URL}" >/dev/null 2>&1 || continue
  remaining=$((remaining - 1))
  emitted=$((emitted + 1))
done < <(printf '%s' "${candidates}" | python3 -c '
import json, sys
try:
    for row in json.load(sys.stdin):
        print(json.dumps(row))
except Exception:
    pass
' 2>/dev/null)

echo "${remaining}" > "${remaining_file}"
echo "$(date -u +%FT%TZ) codex-stop-digest session=${session} emitted=${emitted}" >>"${LOG}" 2>/dev/null
HOOK

read -r -d '' CODEX_HOOKS_JSON <<HOOKS || true
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "${CODEX_HOOKS_DIR}/kb-user-prompt-recall.sh",
            "timeout": 5,
            "statusMessage": "Loading KB context"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "${CODEX_HOOKS_DIR}/kb-stop-digest.sh",
            "timeout": 60,
            "statusMessage": "Capturing KB lessons"
          }
        ]
      }
    ]
  }
}
HOOKS

if [ "${INSTALL_CODEX}" = 1 ]; then
  write_file "${CODEX_HOOKS_DIR}/kb-user-prompt-recall.sh" 0755 "${USER_PROMPT_SUBMIT_HOOK}"
  write_file "${CODEX_HOOKS_DIR}/kb-stop-digest.sh" 0755 "${CODEX_STOP_DIGEST_HOOK}"
  write_file "${CODEX_SKILLS_DIR}/topics/SKILL.md" 0644 "${TOPICS_SKILL}"
  write_file "${CODEX_SKILLS_DIR}/audit/SKILL.md" 0644 "${AUDIT_SKILL}"
  write_file "${CODEX_SKILLS_DIR}/kb-first/SKILL.md" 0644 "${KB_FIRST_SKILL}"
  write_file "${CODEX_SKILLS_DIR}/token-economy/SKILL.md" 0644 "${TOKEN_ECONOMY_SKILL}"
  write_file "${CODEX_SKILLS_DIR}/agent-session-bootstrap/SKILL.md" 0644 "${AGENT_SESSION_BOOTSTRAP_SKILL}"
  write_file "${CODEX_HOOKS_CONFIG}" 0644 "${CODEX_HOOKS_JSON}"
fi

# -----------------------------------------------------------------
# Manifest
# -----------------------------------------------------------------
if [ "${DRY_RUN}" != 1 ] && [ "${INSTALL_CLAUDE}" = 1 ]; then
  cat > "${MANIFEST}" <<MANIFEST
# Managed by the knowledge-system installer (${KB_URL}/install.sh).
# Re-run that command to update. Use --uninstall to remove every
# file listed below.
version=${INSTALLER_VERSION}
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
managed:
$(printf '  - %s\n' "${claude_managed_paths[@]}")
MANIFEST
  log "wrote ${MANIFEST}"
fi

if [ "${DRY_RUN}" != 1 ] && [ "${INSTALL_CODEX}" = 1 ]; then
  cat > "${CODEX_MANIFEST}" <<MANIFEST
# Managed by the knowledge-system installer (${KB_URL}/install.sh).
# Re-run that command to update. Use --agent codex --uninstall to remove every
# Codex file listed below.
version=${INSTALLER_VERSION}
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
agent=codex
managed:
$(printf '  - %s\n' "${codex_managed_paths[@]}")
MANIFEST
  log "wrote ${CODEX_MANIFEST}"
fi

cat <<EOF
knowledge-system installer complete (${INSTALLER_VERSION}, agent=${AGENT}).
EOF

if [ "${INSTALL_CLAUDE}" = 1 ]; then
  cat <<EOF

Claude next steps:

  1. Register the four hooks in ${CLAUDE_HOME}/settings.json under the
     matching "hooks.<event>" arrays. Suggested config:

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

  2. Make sure KB_BEARER_TOKEN is set in the Claude Code environment:
       export KB_BEARER_TOKEN="<your-token>"
EOF
fi

if [ "${INSTALL_CODEX}" = 1 ]; then
  cat <<EOF

Codex next steps:

  1. ${CODEX_HOOKS_CONFIG} has been written with UserPromptSubmit and Stop hooks.
  2. Make sure KB_BEARER_TOKEN is set in the Codex environment:
       export KB_BEARER_TOKEN="<your-token>"
EOF
fi

cat <<EOF

Verify with:  curl -sS -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
                     ${KB_URL}/mcp -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'

Safety controls:
  - Panic switch:   export KB_AUTO_MCP_DISABLED=1   (turns every hook into a no-op).
  - Path allowlist: edit ${ALLOWLIST}  (gitignore-style patterns).
  - State + logs:   ${STATE_DIR}/auto-mcp.log + per-session dedupe under ${STATE_DIR}/sessions/.
  - Provenance:     every auto-capture lands with source = "claude-code:auto-capture:<hook>"
                    or "claude-code:auto-digest:<session>" so a bulk revoke is one SQL query.

Run with --agent ${AGENT} --uninstall to remove every selected file this installer wrote.
EOF
