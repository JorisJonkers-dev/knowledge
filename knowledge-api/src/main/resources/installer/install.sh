#!/usr/bin/env bash
# knowledge-system installer for Claude Code and Codex clients.
#
# Writes the local hooks + skills that pair with the knowledge-api
# MCP server. Idempotent: re-running picks up any updates the server
# ships in subsequent versions. Use `--agent claude|codex|all` and
# `--scope user|project` to choose the client homes to manage. Use
# `--dry-run` to preview the changes before they land. Use `--uninstall`
# to remove them.
#
# Released versions are pinned by the @VERSION@ token below, which
# the knowledge-api templates at request time from
# `SERVICE_VERSION` (or `unknown` for local builds).

set -euo pipefail

readonly INSTALLER_VERSION='@VERSION@'
readonly KB_URL='@KB_URL@'

DRY_RUN=0
UNINSTALL=0
AGENT=claude
SCOPE=user

usage() {
  cat <<USAGE
knowledge-system installer ${INSTALLER_VERSION}

Writes Claude Code and/or Codex hooks + skills that pair with the MCP server at
${KB_URL}.

Usage:
  curl -fsSL -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
    ${KB_URL}/install.sh | bash [-s -- [--agent claude|codex|all] [--scope user|project] [--dry-run|--uninstall]]

Options:
  --agent      Client home to manage. Defaults to "claude" for backwards compatibility.
  --scope      Install scope. "user" writes to client config homes; "project"
               writes to .claude/.codex under AGENT_KIT_PROJECT_ROOT or $PWD.
               Defaults to "user".
  --dry-run     Print every change without modifying the filesystem.
  --uninstall   Remove every file this installer would write.
  --help        Show this help and exit.

Environment:
  CLAUDE_CONFIG_DIR   Override the Claude Code config root (default ~/.claude).
  CODEX_HOME          Override the Codex config root (default ~/.codex).
  AGENT_KIT_PROJECT_ROOT
                      Project root for --scope project (default current directory).
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
    --scope)
      shift
      [ "$#" -gt 0 ] || { echo "--scope requires user or project" >&2; exit 64; }
      SCOPE="$1"
      ;;
    --scope=*) SCOPE="${1#--scope=}" ;;
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

case "${SCOPE}" in
  user|project) ;;
  *)
    echo "--scope must be user or project" >&2
    usage >&2
    exit 64
    ;;
esac

if [ "${SCOPE}" = "project" ]; then
  PROJECT_ROOT="${AGENT_KIT_PROJECT_ROOT:-$PWD}"
  [ -d "${PROJECT_ROOT}" ] || { echo "project root does not exist: ${PROJECT_ROOT}" >&2; exit 64; }
  readonly PROJECT_ROOT
  readonly CLAUDE_HOME="$(cd "${PROJECT_ROOT}" && pwd)/.claude"
  readonly CODEX_HOME="$(cd "${PROJECT_ROOT}" && pwd)/.codex"
else
  readonly CLAUDE_HOME="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
  readonly CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
fi
readonly HOOKS_DIR="${CLAUDE_HOME}/hooks"
readonly SKILLS_DIR="${CLAUDE_HOME}/skills"
readonly MANIFEST="${CLAUDE_HOME}/.knowledge-system-version"
readonly CODEX_HOOKS_DIR="${CODEX_HOME}/hooks"
readonly CODEX_SKILLS_DIR="${CODEX_HOME}/skills"
readonly CODEX_HOOKS_CONFIG="${CODEX_HOME}/hooks.json"
readonly CODEX_MANIFEST="${CODEX_HOME}/.knowledge-system-version"
readonly CODEX_ALLOWLIST="${CODEX_HOME}/.knowledge-system-allowlist"

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
  "${SKILLS_DIR}/council/SKILL.md"
  "${SKILLS_DIR}/council/council.py"
  "${SKILLS_DIR}/council/council.toml"
  "${SKILLS_DIR}/council/prompts/_baseline.md"
  "${SKILLS_DIR}/council/prompts/consolidator.md"
  "${SKILLS_DIR}/council/prompts/critic.md"
  "${SKILLS_DIR}/council/prompts/planner.md"
  "${SKILLS_DIR}/council/prompts/reviser.md"
  "${SKILLS_DIR}/council/prompts/verifier.md"
  "${SKILLS_DIR}/council/prompts/worker.md"
  "${SKILLS_DIR}/council/schemas/consolidated.schema.json"
  "${SKILLS_DIR}/council/schemas/plan.schema.json"
  "${SKILLS_DIR}/council/schemas/verdict.schema.json"
  "${ALLOWLIST}"
)

codex_managed_paths=(
  "${CODEX_HOOKS_DIR}/kb-user-prompt-recall.sh"
  "${CODEX_HOOKS_DIR}/pre-tool-use-edit-recall.sh"
  "${CODEX_HOOKS_DIR}/pre-tool-use-git-commit-capture.sh"
  "${CODEX_HOOKS_DIR}/kb-stop-digest.sh"
  "${CODEX_SKILLS_DIR}/topics/SKILL.md"
  "${CODEX_SKILLS_DIR}/audit/SKILL.md"
  "${CODEX_SKILLS_DIR}/kb-first/SKILL.md"
  "${CODEX_SKILLS_DIR}/token-economy/SKILL.md"
  "${CODEX_SKILLS_DIR}/agent-session-bootstrap/SKILL.md"
  "${CODEX_SKILLS_DIR}/council/SKILL.md"
  "${CODEX_SKILLS_DIR}/council/council.py"
  "${CODEX_SKILLS_DIR}/council/council.toml"
  "${CODEX_SKILLS_DIR}/council/prompts/_baseline.md"
  "${CODEX_SKILLS_DIR}/council/prompts/consolidator.md"
  "${CODEX_SKILLS_DIR}/council/prompts/critic.md"
  "${CODEX_SKILLS_DIR}/council/prompts/planner.md"
  "${CODEX_SKILLS_DIR}/council/prompts/reviser.md"
  "${CODEX_SKILLS_DIR}/council/prompts/verifier.md"
  "${CODEX_SKILLS_DIR}/council/prompts/worker.md"
  "${CODEX_SKILLS_DIR}/council/schemas/consolidated.schema.json"
  "${CODEX_SKILLS_DIR}/council/schemas/plan.schema.json"
  "${CODEX_SKILLS_DIR}/council/schemas/verdict.schema.json"
  "${CODEX_ALLOWLIST}"
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
  log "uninstalling knowledge-system files (${INSTALLER_VERSION}, agent=${AGENT}, scope=${SCOPE})"
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

# Stdin carries the JSON event payload. Extract the prompt from several
# possible shapes: user_prompt string, messages list, or generic prompt.
input="$(cat 2>/dev/null || true)"
prompt="$(printf '%s' "${input}" | python3 -c '
import json, sys

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

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

for key in ("user_prompt", "prompt", "input"):
    value = text(data.get(key))
    if value:
        print(value, end="")
        sys.exit(0)

messages = data.get("messages")
if isinstance(messages, list) and messages:
    print(text(messages[-1]), end="")
' 2>/dev/null || true)"

# Skip trivially-short prompts — overhead > value.
[ "${#prompt}" -lt "${KB_RECALL_MIN_PROMPT_CHARS:-40}" ] && exit 0

# Scope recall to the current repo when running inside a git checkout.
project="$(git remote get-url origin 2>/dev/null | sed -e 's#\.git$##' -e 's#.*[/:]##')"
[ -n "${project}" ] || project="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")"
scope="${KB_RECALL_SCOPE:-project:${project}}"

# Adaptive mode: short prompts rarely need semantic search.
prompt_len="${#prompt}"
if [ -n "${KB_RECALL_HOOK_MODE:-}" ]; then
  mode="${KB_RECALL_HOOK_MODE}"
elif [ "${prompt_len}" -lt 80 ]; then
  mode="fast"
else
  mode="hybrid"
fi
limit="${KB_RECALL_HOOK_LIMIT:-3}"
min_score="${KB_RECALL_MIN_SCORE:-0.004}"

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

response=$(call_recall "${prompt}" "${limit}" "${mode}" "${scope}") || response=""
if [ -z "${response}" ] && [ "${mode}" != "fast" ]; then
  response=$(call_recall "${prompt}" "${limit}" fast "${scope}") || exit 0
fi
[ -n "${response}" ] || exit 0

printf '%s' "${response}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    hits = data["result"]["structuredContent"]["hits"]
except Exception:
    sys.exit(0)
min_score = float("'"${min_score}"'")
hits = [h for h in hits if float(h.get("score", 0) or 0) >= min_score]
if not hits:
    sys.exit(0)
print("## Knowledge base — relevant prior captures")
print()
for h in hits:
    title = h.get("title", "")
    scope = h.get("scope", "")
    note_id = h.get("id", "")
    score = h.get("score", 0)
    print(f"- **{title}** (`{scope}`, score {score}) — id `{note_id}`")
    snip = (h.get("snippet") or "").replace("\n", " ").strip()
    if snip:
        print(f"  > {snip[:160]}")
' 2>/dev/null || true
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
3. Choose the right mode:
   - `fast` — short/trivial lookups or latency-sensitive (< 80 chars).
   - `hybrid` — normal work; FTS + vector + RRF.
   - `deep` — only after fast or hybrid misses something important.
4. Read only what is needed. Usually snippets are enough. If a hit
   matters, call `knowledge.relations(id, depth=1)` before fetching
   the full note.
5. Filter mentally: hits with scores below 0.01 are rarely useful —
   treat as no match and continue from repo/source inspection.
6. If the KB has no useful context, continue from repo/source
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
- Use adaptive recall mode: `fast` for prompts under 80 chars, `hybrid`
  for normal work, `deep` only after a miss or non-obvious cross-topic
  dependency.
- Keep runner MCP profiles narrow: `minimal` by default, wider profiles
  only when the task needs those extra tools.
- Do not install or enable low-fit skills just to grow the list. Skill
  metadata itself consumes prompt budget and very large skill sets can
  hide useful skills.
- Preserve prompt-cache-friendly ordering when writing durable
  instructions: stable policy first, dynamic task-specific context later.

When reporting command results, summarize only the lines needed to
support the decision. Session digests should capture only reusable
lessons above a confidence floor and should dedupe against existing KB
hits before writing.

## Tunable env vars

| Variable | Default | Effect |
| --- | --- | --- |
| `KB_RECALL_MIN_SCORE` | `0.004` | Minimum hit score injected into context; raise to tighten relevance. |
| `KB_RECALL_HOOK_LIMIT` | `3` | Max recall hits per hook invocation. |
| `KB_RECALL_HOOK_MODE` | auto | Override adaptive mode (`fast`/`hybrid`/`deep`). |
| `KB_DIGEST_MAX_CHARS` | `30000` | Transcript chars fed to the stop-digest hook; lower = cheaper. |
| `KB_DIGEST_MAX_CAPTURES` | `4` | Per-session capture cap for the stop hook. |
| `KB_AUTO_MCP_DISABLED` | `0` | Set to `1` to disable all automatic KB calls (panic switch). |
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
3. Keep runner MCP profiles narrow:
   `minimal` for routine work, and `frontend`, `cluster`, `code-intel`,
   or `full-diagnostic` only when the task needs those tools. Prefer
   `AGENT_MCP_PROFILE` for one runner and
   `AGENT_RUNTIME_DEFAULT_MCP_PROFILE` only for fleet-wide default changes.
4. Register bounded recall hooks:
   `UserPromptSubmit` with `limit=3`/`mode=hybrid`,
   `PreToolUse` edit recall deduped per session, and `Stop` transcript
   digest with a per-session capture cap.
5. Keep hooks silent on KB failure and add `KB_AUTO_MCP_DISABLED=1` as
   a panic switch.
6. Add or update memory files so future sessions know to consult and
   update the KB without user reminders.
7. Validate with dry-run hook payloads and at least one `tools/list` or
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
# Skill: council (multi-file toolkit — driver + prompts + schemas +
# default config). Installs into ${SKILLS_DIR}/council and
# ${CODEX_SKILLS_DIR}/council. council.toml is preserved on upgrade.
# -----------------------------------------------------------------
# council — generated by render-agent-kit.py from platform/agents/council/. Edit the source, not here.
read -r -d '' COUNCIL_FILE_council_py <<'COUNCIL_FILE_council_py_EOF' || true
#!/usr/bin/env python3
"""council — cross-model planning + fan-out orchestrator.

`plan` runs stages 1-4: two different model families plan a brief independently,
critique each other's plan for two rounds, then a single judge consolidates one
plan plus a parallel task DAG. `fanout` (added separately) executes that DAG.

The script is engine-agnostic — it shells out to `claude -p` and `codex exec`
and runs identically whether the host session is Claude or Codex. All state is
plain JSON/Markdown under .council/runs/<id>/ so a run is resumable and the
hand-offs between stages are structured rather than free-text.

Stdlib only. See platform/agents/council/README.md.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional, TypeVar

HERE = Path(__file__).resolve().parent
PROMPTS_DIR = HERE / "prompts"
SCHEMAS_DIR = HERE / "schemas"


def repo_root() -> Path:
    """The repository council operates on — the git toplevel of the CURRENT
    working directory (i.e. the project the agent invoked council from), NOT the
    directory the toolkit happens to live in. This is what makes a globally
    installed council orchestrate whatever project you're in."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, check=True,
        )
        return Path(out.stdout.strip())
    except Exception:
        return Path.cwd()


# HERE is where the toolkit lives (prompts, schemas, user config); REPO_ROOT is
# the project being worked on. They differ once council is installed globally.
REPO_ROOT = repo_root()
RUNS_ROOT = REPO_ROOT / ".council" / "runs"


# --------------------------------------------------------------------------
# engines
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class Engine:
    """A model behind one of the two CLIs."""
    cli: str      # "claude" | "codex"
    model: str    # alias (claude) or model id (codex)

    @property
    def label(self) -> str:
        return f"{self.cli}:{self.model}"


# Model tiers are driven by an intensity preset plus optional per-role overrides
# (council.toml + CLI flags). A preset bundles the dials that scale with effort;
# the expensive-tier role models stay constant across presets. Expensive models
# plan / critique / judge (errors propagate there); cheap models do the fan-out.
BASE_ROLES = {
    "planner_a": "claude:opus",
    "planner_b": "codex:gpt-5.5",
    "consolidator": "claude:opus",
    "verifier": "claude:sonnet",
}
PRESETS = {
    "quick":    {"rounds": 1, "codex_effort": "low",   "worker": "claude:haiku",  "max_workers": 4},
    "standard": {"rounds": 2, "codex_effort": "high",  "worker": "claude:haiku",  "max_workers": 6},
    "thorough": {"rounds": 3, "codex_effort": "high",  "worker": "claude:sonnet", "max_workers": 6},
    "max":      {"rounds": 3, "codex_effort": "xhigh", "worker": "claude:sonnet", "max_workers": 8},
}
DEFAULT_INTENSITY = "standard"
ROLE_KEYS = ("planner_a", "planner_b", "consolidator", "worker", "verifier")
INT_KEYS = ("rounds", "max_workers")
CODEX_EFFORTS = ("low", "medium", "high", "xhigh")
CONFIG_KEYS = ("intensity",) + ROLE_KEYS + ("codex_effort",) + INT_KEYS
# User-global config lives next to the toolkit (council.toml beside the script,
# i.e. the committed default in-repo, or ~/.claude/skills/council/ when
# installed). A per-project ./.council.toml in the target repo overrides it.
USER_CONFIG_PATH = HERE / "council.toml"
PROJECT_CONFIG_PATH = REPO_ROOT / ".council.toml"

# codex reasoning effort; resolved per-run from config and set at command entry.
CODEX_REASONING = os.environ.get("COUNCIL_CODEX_REASONING", "high")
PLAN_TIMEOUT_S = int(os.environ.get("COUNCIL_PLAN_TIMEOUT_S", "1200"))

# fan-out tier
WORKER_PERMISSION_MODE = "acceptEdits"   # auto-accept edits; no command approval
WORKER_TIMEOUT_S = int(os.environ.get("COUNCIL_WORKER_TIMEOUT_S", "1800"))
VERIFY_TIMEOUT_S = int(os.environ.get("COUNCIL_VERIFY_TIMEOUT_S", "600"))
WT_ROOT = Path(tempfile.gettempdir()) / "council-worktrees"


@dataclass
class EngineResult:
    label: str
    text: str
    cost_usd: Optional[float] = None
    raw: Optional[dict] = None


def child_env() -> dict:
    """Environment for sub-invocations: silence the KB hooks so council's own
    prompts never get recalled into or digested out to the knowledge base."""
    env = dict(os.environ)
    env["KB_AUTO_MCP_DISABLED"] = "1"
    return env


def run_claude(prompt: str, model: str, *, cwd: Optional[Path] = None,
               permission_mode: str = "plan",
               timeout: int = PLAN_TIMEOUT_S) -> EngineResult:
    # "plan" = read-only repo access, no edits: correct for the planning tier.
    cmd = ["claude", "-p", "--model", model, "--output-format", "json",
           "--permission-mode", permission_mode]
    proc = subprocess.run(
        cmd, input=prompt, capture_output=True, text=True,
        cwd=str(cwd or REPO_ROOT), env=child_env(), timeout=timeout,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"claude:{model} exited {proc.returncode}: "
                           f"{proc.stderr.strip()[:500]}")
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"claude:{model} non-JSON output: {exc}: "
                           f"{proc.stdout[:300]}") from exc
    return EngineResult(
        label=f"claude:{model}",
        text=str(data.get("result", "")),
        cost_usd=data.get("total_cost_usd"),
        raw=data,
    )


def run_codex(prompt: str, model: str, *, cwd: Optional[Path] = None,
              timeout: int = PLAN_TIMEOUT_S) -> EngineResult:
    # `-o` writes only the final assistant message to a file; stdout is noisy
    # (banner + hook wrappers) so we read the message back from the file.
    last = Path(
        subprocess.run(["mktemp"], capture_output=True, text=True, check=True)
        .stdout.strip()
    )
    cmd = [
        "codex", "exec", "-m", model,
        "-c", f"model_reasoning_effort={CODEX_REASONING}",
        "-s", "read-only", "--skip-git-repo-check",
        "-o", str(last), prompt,
    ]
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True,
            cwd=str(cwd or REPO_ROOT), env=child_env(), timeout=timeout,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"codex:{model} exited {proc.returncode}: "
                               f"{proc.stderr.strip()[:500]}")
        text = last.read_text().strip()
    finally:
        last.unlink(missing_ok=True)
    return EngineResult(label=f"codex:{model}", text=text)


def run_engine(engine: Engine, prompt: str, *, cwd: Optional[Path] = None,
               timeout: int = PLAN_TIMEOUT_S, retries: int = 1) -> EngineResult:
    def once() -> EngineResult:
        if engine.cli == "claude":
            return run_claude(prompt, engine.model, cwd=cwd, timeout=timeout)
        if engine.cli == "codex":
            return run_codex(prompt, engine.model, cwd=cwd, timeout=timeout)
        raise ValueError(f"unknown cli: {engine.cli}")

    last: Exception = RuntimeError("no attempt")
    for attempt in range(retries + 1):
        try:
            return once()
        except (RuntimeError, ValueError) as exc:
            last = exc
            if attempt < retries:
                log(f"{engine.label} attempt {attempt + 1} failed ({exc}); retrying")
                time.sleep(3)
    raise last


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

T = TypeVar("T")


def parallel(thunks: list[Callable[[], T]]) -> list[T]:
    """Run thunks concurrently, return results in order. Raises if any raises."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(thunks)) as ex:
        futs = [ex.submit(t) for t in thunks]
        return [f.result() for f in futs]


def render(template: str, **values: str) -> str:
    """Replace {{key}} tokens. Double braces avoid clashing with JSON braces."""
    out = template
    for key, val in values.items():
        out = out.replace("{{" + key + "}}", val)
    return out


def load_prompt(name: str) -> str:
    return (PROMPTS_DIR / f"{name}.md").read_text()


# Durable rules every agent must follow regardless of role (no attribution,
# match conventions, stay in scope, validate against real code). Injected into
# every prompt via the {{baseline}} token. Loaded once at import.
BASELINE_PROMPT = load_prompt("_baseline")


def load_schema_text(name: str) -> str:
    return json.dumps(json.loads((SCHEMAS_DIR / f"{name}.schema.json").read_text()),
                      indent=2)


def extract_json(text: str) -> dict:
    """Pull a JSON object out of a model reply, tolerating ```json fences and
    surrounding prose. Tries a clean parse first (so backticks or braces inside
    string values survive), then a string-aware scan from the first brace."""
    stripped = text.strip()
    try:
        obj = json.loads(stripped)
        if isinstance(obj, dict):
            return obj
    except json.JSONDecodeError:
        pass
    start = stripped.find("{")
    if start == -1:
        raise ValueError(f"no JSON object found in reply: {text[:200]}")
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(stripped)):
        ch = stripped[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return json.loads(stripped[start:i + 1])
    raise ValueError(f"unbalanced JSON in reply: {text[:200]}")


def plan_waves(tasks: list[dict]) -> list[list[str]]:
    """Topologically group task ids into parallel waves (Kahn's algorithm).
    Raises on unknown dependency or cycle. Pure — covered by --self-test."""
    ids = {t["id"] for t in tasks}
    deps = {t["id"]: list(t.get("depends_on", [])) for t in tasks}
    for tid, ds in deps.items():
        for d in ds:
            if d not in ids:
                raise ValueError(f"task {tid!r} depends on unknown task {d!r}")
    remaining = dict(deps)
    done: set[str] = set()
    waves: list[list[str]] = []
    while remaining:
        ready = sorted(t for t, ds in remaining.items()
                       if all(d in done for d in ds))
        if not ready:
            raise ValueError(f"dependency cycle among tasks: "
                             f"{sorted(remaining)}")
        waves.append(ready)
        for t in ready:
            done.add(t)
            del remaining[t]
    return waves


# --------------------------------------------------------------------------
# run directory / state
# --------------------------------------------------------------------------

@dataclass
class Run:
    path: Path
    costs: list[tuple[str, float]] = field(default_factory=list)

    @classmethod
    def create(cls, brief: str, slug: Optional[str]) -> "Run":
        stamp = time.strftime("%Y%m%d-%H%M%S")
        slug = slug or _slugify(brief.splitlines()[0] if brief.strip() else "run")
        path = RUNS_ROOT / f"{stamp}-{slug}"
        path.mkdir(parents=True, exist_ok=True)
        return cls(path)

    @classmethod
    def open(cls, path: Path) -> "Run":
        if not path.exists():
            raise SystemExit(f"run dir not found: {path}")
        return cls(path)

    def write_text(self, name: str, text: str) -> None:
        (self.path / name).write_text(text)

    def write_json(self, name: str, obj: object) -> None:
        (self.path / name).write_text(json.dumps(obj, indent=2))

    def read_json(self, name: str) -> dict:
        return json.loads((self.path / name).read_text())

    def has(self, name: str) -> bool:
        return (self.path / name).exists()

    def record(self, res: EngineResult) -> EngineResult:
        if res.cost_usd is not None:
            self.costs.append((res.label, res.cost_usd))
        return res

    def set_state(self, **kw: object) -> None:
        state = {}
        if self.has("state.json"):
            state = self.read_json("state.json")
        state.update(kw)
        self.write_json("state.json", state)


def _slugify(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return (s[:48] or "run")


# --------------------------------------------------------------------------
# stages 1-4
# --------------------------------------------------------------------------

def stage_plan(run: Run, brief: str, a: Engine, b: Engine) -> tuple[dict, dict]:
    if run.has("planA.v1.json") and run.has("planB.v1.json"):
        log("stage 1: dual plans already present, skipping")
        return run.read_json("planA.v1.json"), run.read_json("planB.v1.json")
    log(f"stage 1: independent plans  {a.label} ║ {b.label}")
    schema = load_schema_text("plan")
    tmpl = load_prompt("planner")

    def mk(engine: Engine) -> Callable[[], EngineResult]:
        prompt = render(tmpl, engine_label=engine.label, brief=brief,
                        repo_root=str(REPO_ROOT), schema=schema,
                        baseline=BASELINE_PROMPT)
        return lambda: run.record(run_engine(engine, prompt))

    res_a, res_b = parallel([mk(a), mk(b)])
    plan_a, plan_b = extract_json(res_a.text), extract_json(res_b.text)
    run.write_json("planA.v1.json", plan_a)
    run.write_json("planB.v1.json", plan_b)
    return plan_a, plan_b


def stage_critique_round(run: Run, brief: str, a: Engine, b: Engine,
                         plan_a: dict, plan_b: dict, rnd: int) -> tuple[dict, dict]:
    out_a, out_b = f"planA.v{rnd + 1}.json", f"planB.v{rnd + 1}.json"
    if run.has(out_a) and run.has(out_b):
        log(f"stage 2: critique round {rnd} already present, skipping")
        return run.read_json(out_a), run.read_json(out_b)
    log(f"stage 2: cross-critique round {rnd}  ({a.label} ⇄ {b.label})")
    critic_tmpl = load_prompt("critic")
    schema = load_schema_text("plan")

    # Cross: each model critiques the OTHER's plan.
    def crit(critic: Engine, plan: dict) -> Callable[[], EngineResult]:
        prompt = render(critic_tmpl, engine_label=critic.label, brief=brief,
                        repo_root=str(REPO_ROOT),
                        plan=json.dumps(plan, indent=2),
                        baseline=BASELINE_PROMPT)
        return lambda: run.record(run_engine(critic, prompt))

    crit_of_a, crit_of_b = parallel([crit(b, plan_a), crit(a, plan_b)])
    run.write_text(f"critique-of-A.r{rnd}.md", crit_of_a.text)
    run.write_text(f"critique-of-B.r{rnd}.md", crit_of_b.text)

    # Each author revises its own plan using the critique it received.
    rev_tmpl = load_prompt("reviser")

    def rev(author: Engine, plan: dict, critique: str) -> Callable[[], EngineResult]:
        prompt = render(rev_tmpl, engine_label=author.label, brief=brief,
                        repo_root=str(REPO_ROOT), plan=json.dumps(plan, indent=2),
                        critique=critique, schema=schema,
                        baseline=BASELINE_PROMPT)
        return lambda: run.record(run_engine(author, prompt))

    rev_a, rev_b = parallel([rev(a, plan_a, crit_of_a.text),
                             rev(b, plan_b, crit_of_b.text)])
    next_a, next_b = extract_json(rev_a.text), extract_json(rev_b.text)
    run.write_json(out_a, next_a)
    run.write_json(out_b, next_b)
    return next_a, next_b


def stage_consolidate(run: Run, brief: str, plan_a: dict, plan_b: dict,
                      rounds: int, consolidator: Engine) -> dict:
    if run.has("tasks.json") and run.has("consolidated_plan.md"):
        log("stage 4: consolidation already present, skipping")
        return run.read_json("tasks.json")
    log(f"stage 4: consolidation  ({consolidator.label})")
    history_parts = []
    for r in range(1, rounds + 1):
        for side in ("A", "B"):
            name = f"critique-of-{side}.r{r}.md"
            if run.has(name):
                history_parts.append(f"## Round {r} — critique of plan {side}\n"
                                     + (run.path / name).read_text())
    prompt = render(
        load_prompt("consolidator"), brief=brief, repo_root=str(REPO_ROOT),
        plan_a=json.dumps(plan_a, indent=2), plan_b=json.dumps(plan_b, indent=2),
        history="\n\n".join(history_parts) or "(no critiques recorded)",
        schema=load_schema_text("consolidated"),
        baseline=BASELINE_PROMPT,
    )
    res = run.record(run_engine(consolidator, prompt))
    obj = extract_json(res.text)
    tasks = obj.get("tasks", [])
    validate_tasks(tasks)
    run.write_text("consolidated_plan.md", obj.get("consolidated_plan_markdown", ""))
    run.write_json("tasks.json", tasks)
    return tasks


def validate_tasks(tasks: list[dict]) -> None:
    """Structural + DAG validation (we don't ship a full JSON-Schema validator;
    this checks the fields fan-out actually relies on)."""
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("consolidator returned no tasks")
    required = {"id", "objective", "depends_on", "paths", "model", "verify"}
    seen: set[str] = set()
    for t in tasks:
        missing = required - t.keys()
        if missing:
            raise ValueError(f"task {t.get('id', '?')} missing fields: {sorted(missing)}")
        if t["id"] in seen:
            raise ValueError(f"duplicate task id: {t['id']}")
        seen.add(t["id"])
        if not str(t.get("verify", "")).strip():
            log(f"warning: task {t['id']} has no verify command — its result "
                "is unchecked except by the adversarial verifier")
    plan_waves(tasks)  # raises on cycle / unknown dep


# --------------------------------------------------------------------------
# stages 5-6: fan-out + verify + reconcile
# --------------------------------------------------------------------------

def git(*args: str, cwd: Optional[Path] = None, check: bool = True,
        timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd or REPO_ROOT),
                          capture_output=True, text=True, check=check,
                          timeout=timeout)


def parallel_bounded(thunks: list[Callable[[], T]], cap: int) -> list[T]:
    """Run thunks with at most `cap` concurrent, preserving order. Thunks must
    not raise (wrap failures into return values)."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, cap)) as ex:
        return list(ex.map(lambda t: t(), thunks))


def run_verify(cmd: str, cwd: Path) -> tuple[Optional[int], str]:
    if not cmd.strip():
        return None, "(no verify command)"
    try:
        proc = subprocess.run(["bash", "-lc", cmd], cwd=str(cwd),
                              capture_output=True, text=True, env=child_env(),
                              timeout=VERIFY_TIMEOUT_S)
        return proc.returncode, (proc.stdout + proc.stderr)[-8000:]
    except subprocess.TimeoutExpired:
        return 124, f"(verify timed out after {VERIFY_TIMEOUT_S}s)"


def run_verifier(run: Run, task: dict, diff: str, verify_cmd: str,
                 verify_rc: Optional[int], verify_out: str,
                 verifier: Engine) -> Optional[dict]:
    prompt = render(
        load_prompt("verifier"), objective=task.get("objective", ""),
        output_format=task.get("output_format", ""),
        paths="\n".join(f"- {p}" for p in task.get("paths", [])) or "(none)",
        diff=diff[:16000] or "(no changes)", verify_cmd=verify_cmd or "(none)",
        verify_rc=str(verify_rc), verify_output=verify_out[:6000] or "(none)",
        schema=load_schema_text("verdict"),
        baseline=BASELINE_PROMPT,
    )
    try:
        res = run.record(run_engine(verifier, prompt, retries=0))
        return extract_json(res.text)
    except Exception as exc:  # verifier is advisory; never fail the run on it
        log(f"verifier for {task['id']} errored: {exc}")
        return None


def run_worker(run: Run, task: dict, base_ref: str, run_name: str,
               worker: Engine, verifier: Engine) -> dict:
    tid = task["id"]
    paths = list(task.get("paths", []))
    branch = f"council/{run_name}/{tid}"
    wt = WT_ROOT / run_name / tid
    result: dict = {"task_id": tid, "title": task.get("title", tid),
                    "model": worker.label, "suggested_model": task.get("model"),
                    "branch": branch, "worktree": str(wt), "committed": False}
    try:
        wt.parent.mkdir(parents=True, exist_ok=True)
        git("worktree", "remove", "--force", str(wt), check=False)
        git("branch", "-D", branch, check=False)
        git("worktree", "add", "--force", "-b", branch, str(wt), base_ref)

        if paths:
            prompt = render(
                load_prompt("worker"), title=task.get("title", tid),
                objective=task["objective"],
                paths="\n".join(f"- {p}" for p in paths),
                boundaries=task.get("boundaries", ""),
                output_format=task.get("output_format", ""), cwd=str(wt),
                baseline=BASELINE_PROMPT)
            res = run.record(run_claude(prompt, worker.model, cwd=wt,
                                        permission_mode=WORKER_PERMISSION_MODE,
                                        timeout=WORKER_TIMEOUT_S))
            result["summary"] = res.text[-2000:]
        else:
            result["summary"] = "(verify-only task: no files to edit)"

        git("add", "-A", cwd=wt)
        dirty = git("status", "--porcelain", cwd=wt).stdout.strip()
        if dirty:
            git("-c", "user.name=council", "-c", "user.email=council@local",
                "commit", "-q", "-m", f"council: {tid}", cwd=wt)
            result["committed"] = True
            result["files_changed"] = git(
                "diff", "--name-only", f"{base_ref}..HEAD", cwd=wt
            ).stdout.split()
            diff = git("diff", f"{base_ref}..HEAD", cwd=wt, timeout=120).stdout
        else:
            result["files_changed"] = []
            diff = ""

        out_of_bounds = [f for f in result["files_changed"] if f not in paths]
        result["out_of_bounds"] = out_of_bounds

        verify_cmd = task.get("verify", "")
        rc, out = run_verify(verify_cmd, wt)
        result["verify_rc"] = rc
        result["verify_output"] = out[-4000:]

        verdict = run_verifier(run, task, diff, verify_cmd, rc, out, verifier)
        result["verdict"] = verdict

        if out_of_bounds:
            result["status"] = "out-of-bounds"
        elif paths and not result["committed"]:
            result["status"] = "no-op"
        elif rc not in (None, 0):
            result["status"] = "verify-failed"
        elif verdict is not None and not verdict.get("satisfied", True):
            result["status"] = "rejected"
        else:
            result["status"] = "ok"
    except Exception as exc:
        result["status"] = "error"
        result["error"] = str(exc)[:500]
    finally:
        wdir = run.path / "workers" / tid
        wdir.mkdir(parents=True, exist_ok=True)
        (wdir / "result.json").write_text(json.dumps(result, indent=2))
    return result


def cmd_fanout(args: argparse.Namespace) -> int:
    run = Run.open(Path(args.run))
    if not run.has("tasks.json"):
        raise SystemExit(f"no tasks.json in {run.path}; run `plan` first")
    tasks = run.read_json("tasks.json")
    by_id = {t["id"]: t for t in tasks}
    waves = plan_waves(tasks)

    cfg = resolve_config({"intensity": args.intensity, "worker": args.worker,
                          "verifier": args.verifier, "codex_effort": args.codex_effort,
                          "max_workers": args.max_workers})
    global CODEX_REASONING
    CODEX_REASONING = cfg["codex_effort"]
    worker = parse_engine_value(cfg["worker"])
    verifier = parse_engine_value(cfg["verifier"])
    if worker.cli != "claude":
        raise SystemExit(f"workers must be claude:<model>, got {cfg['worker']!r} "
                         "(codex workers are not yet supported)")
    cores = max(1, (os.cpu_count() or 3) - 2)
    cap = min(cfg["max_workers"], cores)

    if args.estimate:
        print(f"council fanout — {len(tasks)} tasks in {len(waves)} wave(s); "
              f"intensity {cfg['intensity']}; worker {worker.label}; "
              f"verifier {verifier.label}; concurrency {cap}")
        for i, wave in enumerate(waves, 1):
            print(f"  wave {i}: {', '.join(wave)}")
        print("Each task spawns one worker + one verifier. Worktrees are "
              "isolated; nothing is merged into your branch — results land on an "
              "integration branch for review.")
        return 0

    run_name = run.path.name
    base = git("rev-parse", "HEAD").stdout.strip()
    integ_branch = f"council/{run_name}/integration"
    integ_wt = WT_ROOT / run_name / "_integration"
    integ_wt.parent.mkdir(parents=True, exist_ok=True)
    git("worktree", "remove", "--force", str(integ_wt), check=False)
    git("branch", "-D", integ_branch, check=False)
    git("worktree", "add", "--force", "-b", integ_branch, str(integ_wt), base)
    log(f"fanout: {len(tasks)} task(s) in {len(waves)} wave(s); base {base[:8]}; "
        f"integration branch {integ_branch}; concurrency {cap}")

    results: dict[str, dict] = {}
    for wi, wave in enumerate(waves, 1):
        wave_base = git("rev-parse", "HEAD", cwd=integ_wt).stdout.strip()
        log(f"wave {wi}/{len(waves)}: {wave}  (base {wave_base[:8]})")
        thunks = [(lambda t=t: run_worker(run, by_id[t], wave_base, run_name,
                                           worker, verifier))
                  for t in wave]
        for tid, res in zip(wave, parallel_bounded(thunks, cap)):
            results[tid] = res
            log(f"  [{tid}] {res['status']}"
                + (f" ({len(res.get('files_changed', []))} files)"
                   if res.get("committed") else ""))
        # reconcile this wave into the integration branch, in order
        for tid in wave:
            res = results[tid]
            if not res.get("committed"):
                res["merge"] = "nothing-to-merge"
                continue
            m = git("merge", "--no-ff", "-m", f"council merge {tid}",
                    f"council/{run_name}/{tid}", cwd=integ_wt, check=False)
            if m.returncode != 0:
                git("merge", "--abort", cwd=integ_wt, check=False)
                res["merge"] = "conflict"
                log(f"  [{tid}] merge CONFLICT — left out of integration")
            else:
                res["merge"] = "ok"

    if not args.keep_worktrees:
        for tid in by_id:
            git("worktree", "remove", "--force", str(WT_ROOT / run_name / tid),
                check=False)

    report = build_report(run, integ_branch, str(integ_wt), waves, results, tasks)
    run.write_json("report.json", report)
    run.write_text("report.md", render_report_md(report))
    run.set_state(stage="fanned-out", integration_branch=integ_branch)
    s = report["summary"]
    log(f"done: {s['ok']}/{s['total']} ok, {s['failed']} failed, "
        f"{s['merged']} merged into {integ_branch}")
    print(integ_branch)  # stdout: integration branch for the host to surface
    return 0


def build_report(run: Run, integ_branch: str, integ_wt: str,
                 waves: list[list[str]], results: dict[str, dict],
                 task_defs: list[dict]) -> dict:
    task_map = {t["id"]: t for t in task_defs}
    no_verify = sorted(tid for tid in results
                       if not str(task_map.get(tid, {}).get("verify", "")).strip())
    rows = []
    ok = failed = merged = 0
    for tid, r in results.items():
        good = r.get("status") == "ok" and r.get("merge") in ("ok", None)
        ok += 1 if r.get("status") == "ok" else 0
        failed += 0 if r.get("status") == "ok" else 1
        merged += 1 if r.get("merge") == "ok" else 0
        rows.append({
            "task_id": tid, "status": r.get("status"),
            "merge": r.get("merge"), "model": r.get("model"),
            "files_changed": r.get("files_changed", []),
            "verify_rc": r.get("verify_rc"),
            "verifier_satisfied": (r.get("verdict") or {}).get("satisfied"),
            "out_of_bounds": r.get("out_of_bounds", []),
            "branch": r.get("branch"), "good": good,
        })
    return {
        "run": run.path.name, "integration_branch": integ_branch,
        "integration_worktree": integ_wt, "waves": waves, "tasks": rows,
        "no_verify": no_verify,
        "summary": {"total": len(results), "ok": ok, "failed": failed,
                    "merged": merged},
    }


def render_report_md(report: dict) -> str:
    s = report["summary"]
    lines = [f"# council fan-out report — {report['run']}", "",
             f"- integration branch: `{report['integration_branch']}`",
             f"- worktree: `{report['integration_worktree']}`",
             f"- result: **{s['ok']}/{s['total']} ok**, {s['failed']} failed, "
             f"{s['merged']} merged", "", "## Tasks", "",
             "| task | status | merge | model | files | verify | verifier |",
             "|---|---|---|---|---|---|---|"]
    for t in report["tasks"]:
        lines.append(
            f"| {t['task_id']} | {t['status']} | {t['merge']} | {t['model']} "
            f"| {len(t['files_changed'])} | "
            f"{'-' if t['verify_rc'] is None else t['verify_rc']} "
            f"| {t['verifier_satisfied']} |")
    failures = [t for t in report["tasks"] if not t["good"]]
    if failures:
        lines += ["", "## Needs attention", ""]
        for t in failures:
            note = t["status"]
            if t["merge"] == "conflict":
                note += " + merge conflict"
            if t["out_of_bounds"]:
                note += f" (touched out-of-bounds: {t['out_of_bounds']})"
            lines.append(f"- `{t['task_id']}`: {note}")
    no_verify = report.get("no_verify", [])
    if no_verify:
        lines += ["", "## Tasks with no verify command", "",
                  "These ran without an automated check — only the adversarial "
                  "verifier reviewed them:", ""]
        lines += [f"- `{tid}`" for tid in no_verify]
    lines += ["", f"Review: `git -C {report['integration_worktree']} log --oneline`"
              f" or `git checkout {report['integration_branch']}`."]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------

def log(msg: str) -> None:
    print(f"[council] {msg}", file=sys.stderr, flush=True)


def read_brief(arg: str) -> str:
    if arg == "-":
        return sys.stdin.read()
    p = Path(arg)
    if not p.exists():
        raise SystemExit(f"brief file not found: {arg}")
    return p.read_text()


def cmd_plan(args: argparse.Namespace) -> int:
    cfg = resolve_config({"intensity": args.intensity, "planner_a": args.planner_a,
                          "planner_b": args.planner_b, "consolidator": args.consolidator,
                          "rounds": args.rounds, "codex_effort": args.codex_effort})
    global CODEX_REASONING
    CODEX_REASONING = cfg["codex_effort"]
    a = parse_engine_value(cfg["planner_a"])
    b = parse_engine_value(cfg["planner_b"])
    consolidator = parse_engine_value(cfg["consolidator"])
    rounds = cfg["rounds"]

    if args.estimate:
        calls = 2 + rounds * 4 + 1
        print(f"council plan — intensity {cfg['intensity']}, "
              f"estimated model calls: {calls}")
        print(f"  stage 1 dual plans      : 2  ({a.label}, {b.label})")
        print(f"  stage 2 critique+revise : {rounds * 4}  ({rounds} rounds x "
              f"[2 critiques + 2 revisions])")
        print(f"  stage 4 consolidation   : 1  ({consolidator.label})")
        print(f"  codex reasoning effort  : {cfg['codex_effort']}")
        print("These are expensive-tier calls; fan-out (cheap workers) is "
              "separate. Multi-agent runs ~15x the tokens of a single chat — "
              "use council only for large, decomposable work.")
        return 0

    brief = read_brief(args.brief)
    run = Run.open(Path(args.run)) if args.run else Run.create(brief, args.slug)
    run.write_text("brief.md", brief)
    run.set_state(stage="plan", intensity=cfg["intensity"], rounds=rounds,
                  planner_a=a.label, planner_b=b.label)
    log(f"run dir: {run.path}")
    log(f"intensity {cfg['intensity']}: {a.label} ║ {b.label}, {rounds} round(s), "
        f"codex effort {cfg['codex_effort']}")

    plan_a, plan_b = stage_plan(run, brief, a, b)
    for rnd in range(1, rounds + 1):
        plan_a, plan_b = stage_critique_round(run, brief, a, b, plan_a, plan_b, rnd)
    tasks = stage_consolidate(run, brief, plan_a, plan_b, rounds, consolidator)
    run.set_state(stage="planned", task_count=len(tasks))

    waves = plan_waves(tasks)
    total = sum(c for _, c in run.costs)
    log(f"done: {len(tasks)} tasks in {len(waves)} wave(s); "
        f"recorded claude cost ${total:.2f} (codex cost not reported by CLI)")
    print(str(run.path))  # stdout: the run dir, for the host to pick up
    return 0


def parse_engine_value(spec: str) -> Engine:
    """spec form: "cli:model" e.g. "claude:opus" or "codex:gpt-5.5"."""
    cli, _, model = spec.partition(":")
    if cli not in ("claude", "codex") or not model:
        raise SystemExit(f"engine must be claude:<model> or codex:<model>, "
                         f"got {spec!r}")
    return Engine(cli, model)


# --------------------------------------------------------------------------
# config: intensity presets + per-role overrides (council.toml)
# --------------------------------------------------------------------------

def load_config_at(path: Path) -> dict:
    if not path.exists():
        return {}
    with path.open("rb") as fh:
        raw = tomllib.load(fh)
    return {k: v for k, v in raw.items() if k in CONFIG_KEYS}


def merge_config(file_cfg: dict, cli_overrides: dict) -> dict:
    """Resolve final settings: intensity preset < council.toml < CLI flags.
    Pure (no IO) so it is covered by --self-test."""
    intensity = (cli_overrides.get("intensity") or file_cfg.get("intensity")
                 or DEFAULT_INTENSITY)
    if intensity not in PRESETS:
        raise ValueError(f"unknown intensity {intensity!r}; choose from "
                         f"{', '.join(PRESETS)}")
    resolved = dict(BASE_ROLES)
    resolved.update(PRESETS[intensity])
    for src in (file_cfg, cli_overrides):
        for key, val in src.items():
            if key == "intensity" or val is None or key not in CONFIG_KEYS:
                continue
            resolved[key] = val
    resolved["intensity"] = intensity
    return resolved


def resolve_config(cli_overrides: dict) -> dict:
    # precedence: preset < user council.toml < project .council.toml < CLI
    file_cfg = {**load_config_at(USER_CONFIG_PATH),
                **load_config_at(PROJECT_CONFIG_PATH)}
    return merge_config(file_cfg, cli_overrides)


def coerce_config_value(key: str, raw: str):
    """Validate + type a `config set` value. Raises ValueError on bad input
    (callers convert to a clean CLI exit)."""
    if key not in CONFIG_KEYS:
        raise ValueError(f"unknown key {key!r}; choose from {', '.join(CONFIG_KEYS)}")
    if key == "intensity":
        if raw not in PRESETS:
            raise ValueError(f"intensity must be one of {', '.join(PRESETS)}")
        return raw
    if key in INT_KEYS:
        try:
            return int(raw)
        except ValueError:
            raise ValueError(f"{key} must be an integer, got {raw!r}")
    if key == "codex_effort":
        if raw not in CODEX_EFFORTS:
            raise ValueError(f"codex_effort must be one of {', '.join(CODEX_EFFORTS)}")
        return raw
    if key in ROLE_KEYS:
        if ":" not in raw or raw.split(":", 1)[0] not in ("claude", "codex"):
            raise ValueError(f"{key} must be claude:<model> or codex:<model>, "
                             f"got {raw!r}")
        if key == "worker" and not raw.startswith("claude:"):
            raise ValueError("worker must be claude:<model> (codex workers "
                             "are not yet supported)")
        return raw
    return raw


def save_config_at(path: Path, cfg: dict) -> None:
    lines = ["# council configuration. CLI flags override these per run;",
             "# `council config set <key> <value>` edits this file. Keys not",
             "# listed follow the chosen intensity preset (quick|standard|"
             "thorough|max).", ""]
    for key in CONFIG_KEYS:
        if key in cfg and cfg[key] is not None:
            val = cfg[key]
            if isinstance(val, bool):  # not expected, but keep TOML valid
                lines.append(f"{key} = {str(val).lower()}")
            elif isinstance(val, int):
                lines.append(f"{key} = {val}")
            else:
                lines.append(f'{key} = "{val}"')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


def cmd_config(args: argparse.Namespace) -> int:
    target = PROJECT_CONFIG_PATH if getattr(args, "project", False) else USER_CONFIG_PATH
    action = args.action
    if action == "path":
        print(f"user:    {USER_CONFIG_PATH}")
        print(f"project: {PROJECT_CONFIG_PATH}")
        return 0
    if action == "show":
        user_cfg = load_config_at(USER_CONFIG_PATH)
        proj_cfg = load_config_at(PROJECT_CONFIG_PATH)
        resolved = resolve_config({})
        print(f"user config:    {USER_CONFIG_PATH}"
              f"{'' if USER_CONFIG_PATH.exists() else '  (none)'}")
        print(f"project config: {PROJECT_CONFIG_PATH}"
              f"{'' if PROJECT_CONFIG_PATH.exists() else '  (none)'}")
        print(f"intensity: {resolved['intensity']}")
        for key in CONFIG_KEYS[1:]:
            src = (" (project)" if key in proj_cfg
                   else " (user)" if key in user_cfg else "")
            print(f"  {key} = {resolved[key]}{src}")
        return 0
    if action == "get":
        if not args.key or args.key not in CONFIG_KEYS:
            raise SystemExit(f"config get requires a known key "
                             f"({', '.join(CONFIG_KEYS)})")
        print(resolve_config({})[args.key])
        return 0
    if action == "set":
        if not args.key or args.value is None:
            raise SystemExit("config set requires <key> <value>")
        file_cfg = load_config_at(target)
        try:
            file_cfg[args.key] = coerce_config_value(args.key, args.value)
        except ValueError as exc:
            raise SystemExit(str(exc))
        save_config_at(target, file_cfg)
        print(f"set {args.key} = {file_cfg[args.key]!r} in {target}")
        return 0
    if action == "unset":
        if not args.key:
            raise SystemExit("config unset requires a key")
        file_cfg = load_config_at(target)
        if args.key in file_cfg:
            del file_cfg[args.key]
            save_config_at(target, file_cfg)
            print(f"unset {args.key} in {target}")
        else:
            print(f"{args.key} not set in {target}")
        return 0
    raise SystemExit(f"unknown config action {action!r}")


def cmd_self_test(_args: argparse.Namespace) -> int:
    failures: list[str] = []

    def check(name: str, cond: bool) -> None:
        if not cond:
            failures.append(name)
        print(f"  {'ok  ' if cond else 'FAIL'} {name}")

    # plan_waves
    tasks = [
        {"id": "a", "depends_on": []},
        {"id": "b", "depends_on": ["a"]},
        {"id": "c", "depends_on": ["a"]},
        {"id": "d", "depends_on": ["b", "c"]},
    ]
    check("plan_waves groups by dependency",
          plan_waves(tasks) == [["a"], ["b", "c"], ["d"]])
    check("plan_waves rejects cycle", _raises(
        lambda: plan_waves([{"id": "x", "depends_on": ["y"]},
                            {"id": "y", "depends_on": ["x"]}])))
    check("plan_waves rejects unknown dep", _raises(
        lambda: plan_waves([{"id": "x", "depends_on": ["nope"]}])))

    # extract_json
    check("extract_json plain", extract_json('{"a": 1}') == {"a": 1})
    check("extract_json fenced",
          extract_json('text\n```json\n{"a": [1,2]}\n```\nmore') == {"a": [1, 2]})
    check("extract_json with braces in string",
          extract_json('{"k": "a{b}c"}') == {"k": "a{b}c"})
    check("extract_json keeps code fences inside string value",
          extract_json('{"md": "see ```bash\\nx\\n``` end", "n": 1}')
          == {"md": "see ```bash\nx\n``` end", "n": 1})
    check("extract_json outer fence with inner fences",
          extract_json('```json\n{"md": "a ```inner``` b"}\n```')
          == {"md": "a ```inner``` b"})
    check("extract_json none raises", _raises(lambda: extract_json("no json here")))

    # render
    check("render replaces tokens",
          render("hi {{name}} {{name}}", name="x") == "hi x x")
    check("render leaves JSON braces",
          render('{"x": {{v}}}', v="1") == '{"x": 1}')

    # validate_tasks
    check("validate_tasks rejects empty", _raises(lambda: validate_tasks([])))
    check("validate_tasks rejects missing fields",
          _raises(lambda: validate_tasks([{"id": "a"}])))

    # baseline rules + verify checks
    check("_baseline loadable and non-empty", bool(BASELINE_PROMPT.strip()))
    global log
    captured: list[str] = []
    orig_log = log
    log = lambda m: captured.append(m)  # noqa: E731
    try:
        validate_tasks([{"id": "t1", "objective": "o", "depends_on": [],
                         "paths": [], "model": "haiku", "verify": "  "}])
    finally:
        log = orig_log
    check("validate_tasks warns on empty verify",
          any("no verify" in m for m in captured))

    # merge_config (intensity presets + precedence)
    std = merge_config({}, {})
    check("default intensity is standard",
          std["intensity"] == "standard" and std["rounds"] == 2
          and std["worker"] == "claude:haiku" and std["codex_effort"] == "high")
    thorough = merge_config({"intensity": "thorough"}, {})
    check("thorough preset bumps rounds + worker",
          thorough["rounds"] == 3 and thorough["worker"] == "claude:sonnet")
    check("cli overrides preset",
          merge_config({"intensity": "quick"}, {"rounds": 5})["rounds"] == 5)
    check("file overrides preset, cli overrides file",
          merge_config({"worker": "claude:sonnet"},
                       {"worker": "claude:opus"})["worker"] == "claude:opus")
    check("file intensity used when no cli",
          merge_config({"intensity": "max"}, {})["codex_effort"] == "xhigh")
    check("merge_config rejects bad intensity",
          _raises(lambda: merge_config({}, {"intensity": "nope"})))
    check("coerce rejects unknown key",
          _raises(lambda: coerce_config_value("bogus", "x")))
    check("coerce rejects codex worker",
          _raises(lambda: coerce_config_value("worker", "codex:gpt-5.5")))
    check("coerce types ints", coerce_config_value("rounds", "3") == 3)

    print(f"\n{'PASS' if not failures else 'FAIL: ' + ', '.join(failures)}")
    return 1 if failures else 0


def _raises(fn: Callable[[], object]) -> bool:
    try:
        fn()
        return False
    except Exception:
        return True


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="council", description=__doc__)
    p.add_argument("--self-test", action="store_true",
                   help="run pure-function checks (no model calls) and exit")
    sub = p.add_subparsers(dest="command")

    pl = sub.add_parser("plan", help="stages 1-4: dual plans, critique, consolidate")
    pl.add_argument("--brief", help="brief file path, or - for stdin")
    pl.add_argument("--run", help="existing run dir to resume")
    pl.add_argument("--slug", help="slug for the run dir name")
    pl.add_argument("--intensity", choices=list(PRESETS),
                    help="preset (overrides council.toml for this run)")
    pl.add_argument("--rounds", type=int, default=None, help="override critique rounds")
    pl.add_argument("--planner-a", default=None, help="override, form cli:model")
    pl.add_argument("--planner-b", default=None, help="override, form cli:model")
    pl.add_argument("--consolidator", default=None, help="override, form cli:model")
    pl.add_argument("--codex-effort", default=None, choices=list(CODEX_EFFORTS),
                    help="override codex reasoning effort")
    pl.add_argument("--estimate", action="store_true",
                    help="print planned call count and exit without spending")
    pl.set_defaults(func=cmd_plan)

    fo = sub.add_parser("fanout", help="stages 5-6: execute the task DAG, "
                                       "verify, reconcile onto a branch")
    fo.add_argument("--run", required=True, help="run dir with a tasks.json")
    fo.add_argument("--intensity", choices=list(PRESETS),
                    help="preset (overrides council.toml for this run)")
    fo.add_argument("--max-workers", type=int, default=None,
                    help="override max concurrent workers (clamped to cores-2)")
    fo.add_argument("--worker", default=None,
                    help="override worker model, form claude:model")
    fo.add_argument("--verifier", default=None, help="override verifier, form cli:model")
    fo.add_argument("--codex-effort", default=None, choices=list(CODEX_EFFORTS),
                    help="override codex reasoning effort")
    fo.add_argument("--keep-worktrees", action="store_true",
                    help="keep per-task worktrees for inspection")
    fo.add_argument("--estimate", action="store_true",
                    help="print the wave/worker plan and exit without spending")
    fo.set_defaults(func=cmd_fanout)

    cf = sub.add_parser("config", help="show or change model/intensity config "
                                       "(council.toml)")
    cf.add_argument("action", choices=["show", "get", "set", "unset", "path"])
    cf.add_argument("key", nargs="?", help="config key (see `config show`)")
    cf.add_argument("value", nargs="?", help="value (for `set`)")
    cf.add_argument("--project", action="store_true",
                    help="target the per-project .council.toml instead of the "
                         "user-global config (for set/unset)")
    cf.set_defaults(func=cmd_config)
    return p


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return cmd_self_test(args)
    if not getattr(args, "command", None):
        build_parser().print_help()
        return 2
    if args.command == "plan" and not args.estimate and not args.brief:
        raise SystemExit("plan requires --brief (or --brief -)")
    try:
        return args.func(args)
    except ValueError as exc:  # e.g. bad intensity in council.toml
        print(f"council: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
COUNCIL_FILE_council_py_EOF
read -r -d '' COUNCIL_FILE_council_toml <<'COUNCIL_FILE_council_toml_EOF' || true
# council configuration. CLI flags override these per run;
# `council config set <key> <value>` edits this file. Keys not
# listed follow the chosen intensity preset (quick|standard|thorough|max).

intensity = "standard"
planner_a = "claude:opus"
planner_b = "codex:gpt-5.5"
consolidator = "claude:opus"
verifier = "claude:sonnet"
COUNCIL_FILE_council_toml_EOF
read -r -d '' COUNCIL_FILE_prompts__baseline_md <<'COUNCIL_FILE_prompts__baseline_md_EOF' || true
# Baseline rules (apply to every task, every agent)

These rules are non-negotiable and override any conflicting habit:

- **No attribution.** Never add `Co-Authored-By` trailers, "Generated with"
  footers, or any AI / assistant / agent / model name to commit messages, PR
  bodies, code, comments, or generated files. The work is authored solely by the
  human driver.
- **Match the surrounding code.** Follow each file's existing style, naming, and
  idioms. Do not reformat or refactor code unrelated to the objective.
- **Stay minimal and in scope.** Make only the changes the objective requires —
  no tangential cleanup, no "while we're here" edits, no backwards-compat shims
  when a clean change is possible.
- **Comments explain WHY, not WHAT,** and only when the reason is non-obvious.
  No multi-paragraph docstrings.
- **Validate against the real codebase.** Never invent file paths, APIs,
  commands, or config; if you reference something, it must exist.
COUNCIL_FILE_prompts__baseline_md_EOF
read -r -d '' COUNCIL_FILE_prompts_consolidator_md <<'COUNCIL_FILE_prompts_consolidator_md_EOF' || true
You are the consolidator — a strong, impartial judge. Two independent plans
(from different model families) have each been critiqued and revised twice.
SYNTHESISE them into ONE superior plan by grafting the strongest elements of
each. Do not merely pick one and discard the other; the best ideas are often
split across both.

# Task brief
{{brief}}

# Plan A (final)
{{plan_a}}

# Plan B (final)
{{plan_b}}

# Critique history (rounds 1-2, both directions)
{{history}}

# Repository
Ground every task in the real codebase at {{repo_root}}. Do not invent paths.

# Your job
Produce (1) a clear consolidated plan in Markdown, and (2) a task DAG for
parallel execution. Each task must:
- be independently executable by a cheap worker agent given only its own fields,
- touch a NON-OVERLAPPING set of files from every task it does not depend on
  (overlapping files across parallel tasks cause merge conflicts — partition the
  work so this never happens),
- declare its dependencies explicitly in depends_on (task ids),
- carry a `verify` that is a SINGLE shell command run verbatim via `bash -lc`
  and exits 0 only on success. It must be pure shell — no prose, no backticks,
  no markdown, no parenthetical asides. Chain steps with `&&`. Right:
  `python3 foo.py --version && python3 -m pytest -q`. Wrong:
  `run the script (expect "ok") and check it passes`.
- be tagged with a difficulty and a worker model (haiku for trivial/moderate,
  sonnet for hard).

Keep the task count proportional to the work: a handful for a focused change,
more only when the work genuinely decomposes. Sequential, tightly-coupled work
should be a single task with a clear ordering, not forced into false parallelism.

{{baseline}}

# Output
Return ONLY a JSON object — no prose, no code fences — matching this schema:

{{schema}}
COUNCIL_FILE_prompts_consolidator_md_EOF
read -r -d '' COUNCIL_FILE_prompts_critic_md <<'COUNCIL_FILE_prompts_critic_md_EOF' || true
You are {{engine_label}}, an ADVERSARIAL plan reviewer. The plan below was
written by a DIFFERENT model for the brief below. Default stance: the plan is
guilty until proven innocent. If you cannot find concrete weaknesses, you are
not looking hard enough. Do NOT compliment, do NOT rubber-stamp, do NOT restate
the plan back.

# Task brief
{{brief}}

# Plan under review
{{plan}}

# Repository
You may read files in {{repo_root}} to check the plan's claims against the real
code. Catch invented paths and wrong assumptions here.

# Your job
List specific, actionable weaknesses:
- wrong or invented file paths, APIs, commands, or config
- missing steps and unhandled edge cases
- hidden dependencies between tasks the plan claims are parallel (these cause
  merge conflicts during fan-out — flag every one)
- underestimated or missing risks
- incorrect assumptions about how the codebase actually works
- concrete better alternatives

Prioritise issues that would make the plan FAIL or produce conflicts during
parallel execution.

{{baseline}}

# Output
Return a concise Markdown critique: a bulleted list of concrete problems, each
with WHY it matters and a suggested fix. End with one line: `VERDICT:` followed
by the single most important thing to change.
COUNCIL_FILE_prompts_critic_md_EOF
read -r -d '' COUNCIL_FILE_prompts_planner_md <<'COUNCIL_FILE_prompts_planner_md_EOF' || true
You are {{engine_label}}, an expert software architect producing an INDEPENDENT
plan. Another model is planning the same brief in parallel; do not coordinate —
bring your own best thinking.

# Task brief
{{brief}}

# Repository
You are running inside the target git repository at {{repo_root}}. Read whatever
files you need to ground the plan in the real codebase. Validate every
assumption against the actual code — do not invent file paths, APIs, commands,
or config. If you reference a file, it must exist.

# Your job
Produce the best plan to accomplish the brief. Decompose the work so that as
much as possible can run in PARALLEL across independent worker agents, each
touching a NON-OVERLAPPING set of files (parallel workers that edit the same
file will collide). Be concrete: name real files and real commands.

{{baseline}}

# Output
Return ONLY a JSON object — no prose, no code fences — matching this schema:

{{schema}}

Field guidance:
- summary: one paragraph stating what will be built and the end state.
- approach: the strategy and why it beats the obvious alternative.
- steps: ordered high-level steps.
- risks: concrete risks, unknowns, and failure modes.
- parallelizable_tasks: candidate independent units, each as
  "objective — the files/paths it touches".
- open_questions: anything genuinely ambiguous in the brief (empty if none).
COUNCIL_FILE_prompts_planner_md_EOF
read -r -d '' COUNCIL_FILE_prompts_reviser_md <<'COUNCIL_FILE_prompts_reviser_md_EOF' || true
You are {{engine_label}}. You wrote the plan below. A reviewer from a DIFFERENT
model has critiqued it. Revise your plan to address every valid point —
incorporate the fixes, fill the gaps, and sharpen the parallel decomposition and
file boundaries so independent workers will not collide. If a critique point is
wrong, you may reject it, but only with a concrete, specific reason; silence is
not allowed.

# Task brief
{{brief}}

# Your current plan
{{plan}}

# Critique to address
{{critique}}

# Repository
Re-check claims against the real code at {{repo_root}} as needed.

{{baseline}}

# Output
Return ONLY the revised JSON object — no prose, no code fences — matching this
schema:

{{schema}}
COUNCIL_FILE_prompts_reviser_md_EOF
read -r -d '' COUNCIL_FILE_prompts_verifier_md <<'COUNCIL_FILE_prompts_verifier_md_EOF' || true
You are an ADVERSARIAL verifier. A worker claims to have completed the task
below. Your job is to decide whether the diff ACTUALLY accomplishes the
objective — not whether it looks plausible. Assume it is wrong until the diff
proves otherwise.

# Task objective
{{objective}}

## Definition of done
{{output_format}}

## Files the worker was allowed to touch
{{paths}}

# The worker's diff
```diff
{{diff}}
```

# Result of the task's own verify command (`{{verify_cmd}}`)
exit code: {{verify_rc}}
output:
{{verify_output}}

# Your job
Check, concretely:
- Does the diff actually achieve the objective and the definition of done?
- Did the worker stay within the allowed files? (changes outside them are a fail)
- Did the verify command actually pass, and does its output prove the objective
  (not just exit 0 for an unrelated reason)?
- Any obvious bug, omission, or regression introduced by the diff?

{{baseline}}

# Output
Return ONLY a JSON object — no prose, no code fences — matching this schema:

{{schema}}
COUNCIL_FILE_prompts_verifier_md_EOF
read -r -d '' COUNCIL_FILE_prompts_worker_md <<'COUNCIL_FILE_prompts_worker_md_EOF' || true
You are a worker agent executing ONE task from a larger plan. Other workers are
handling other tasks in parallel; stay strictly inside your boundaries so the
parallel work does not collide.

# Task
{{title}}

## Objective
{{objective}}

## Files you may touch (and ONLY these)
{{paths}}

## Boundaries
{{boundaries}}

## Expected output / definition of done
{{output_format}}

# Repository
You are in a dedicated git worktree at {{cwd}} — your own isolated copy of the
repository. Edit files here to accomplish the objective. Read whatever you need
for context, but only WRITE within the files listed above.

# Rules
- Do the task fully. Make the edits; do not just describe them.
- Do NOT run `git` (no add/commit/branch/push) — the orchestrator commits your
  worktree for you.
- Do NOT touch files outside your listed paths.
- Match the surrounding code's style and conventions.
- Keep the change minimal and focused on the objective; no tangential cleanup.

{{baseline}}

# Final message
End with a short plain-text summary: what you changed, in which files, and any
caveat the orchestrator should know. This summary is read by the orchestrator,
not a human — be terse and factual.
COUNCIL_FILE_prompts_worker_md_EOF
read -r -d '' COUNCIL_FILE_schemas_consolidated_schema_json <<'COUNCIL_FILE_schemas_consolidated_schema_json_EOF' || true
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "council-consolidated",
  "type": "object",
  "additionalProperties": false,
  "required": ["consolidated_plan_markdown", "tasks"],
  "properties": {
    "consolidated_plan_markdown": { "type": "string" },
    "tasks": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "id",
          "title",
          "objective",
          "output_format",
          "paths",
          "depends_on",
          "difficulty",
          "model",
          "verify",
          "boundaries"
        ],
        "properties": {
          "id": { "type": "string" },
          "title": { "type": "string" },
          "objective": { "type": "string" },
          "output_format": { "type": "string" },
          "paths": { "type": "array", "items": { "type": "string" } },
          "depends_on": { "type": "array", "items": { "type": "string" } },
          "difficulty": { "type": "string", "enum": ["trivial", "moderate", "hard"] },
          "model": { "type": "string", "enum": ["haiku", "sonnet", "opus"] },
          "verify": { "type": "string" },
          "boundaries": { "type": "string" }
        }
      }
    }
  }
}
COUNCIL_FILE_schemas_consolidated_schema_json_EOF
read -r -d '' COUNCIL_FILE_schemas_plan_schema_json <<'COUNCIL_FILE_schemas_plan_schema_json_EOF' || true
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "council-plan",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "summary",
    "approach",
    "steps",
    "risks",
    "parallelizable_tasks",
    "open_questions"
  ],
  "properties": {
    "summary": { "type": "string" },
    "approach": { "type": "string" },
    "steps": { "type": "array", "items": { "type": "string" } },
    "risks": { "type": "array", "items": { "type": "string" } },
    "parallelizable_tasks": { "type": "array", "items": { "type": "string" } },
    "open_questions": { "type": "array", "items": { "type": "string" } }
  }
}
COUNCIL_FILE_schemas_plan_schema_json_EOF
read -r -d '' COUNCIL_FILE_schemas_verdict_schema_json <<'COUNCIL_FILE_schemas_verdict_schema_json_EOF' || true
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "council-verdict",
  "type": "object",
  "additionalProperties": false,
  "required": ["satisfied", "reasons", "issues"],
  "properties": {
    "satisfied": { "type": "boolean" },
    "reasons": { "type": "string" },
    "issues": { "type": "array", "items": { "type": "string" } }
  }
}
COUNCIL_FILE_schemas_verdict_schema_json_EOF
read -r -d '' COUNCIL_SKILL_claude <<'COUNCIL_SKILL_claude_EOF' || true
---
name: council
description: Orchestrate a large, decomposable problem across two model families — Claude and Codex plan it independently, cross-critique for two rounds, a judge consolidates one plan plus a parallel task DAG, then cheap workers fan out execution in isolated worktrees. Use for big parallelizable work; NOT for small or tightly-coupled/sequential changes (use a normal session for those).
---

# council

A cross-model orchestrator for problems too big for one pass. The driver is
`~/.claude/skills/council/council.py` (stdlib-only; shells out to `claude -p` and
`codex exec`); inside the personal-stack repo it also lives at
`platform/agents/council/council.py`. Your job around it is the human-facing
part: clarify the brief and present the two checkpoints.

**Run it from the project you want council to work on** — it targets the
repository of the current working directory, so a globally installed council
orchestrates whatever repo you're in. It installs into `~/.claude/skills/council`
(and `~/.codex/skills/council`) via the agent-kit installer
(`curl …/install.sh | bash`); re-run that to upgrade.

## When to use / when not

- **Use it** when the work is large, decomposes into independent parallel
  pieces, and is worth the spend (multi-agent runs roughly 15x the tokens of a
  single chat).
- **Do not use it** for small changes, or for tightly-coupled / sequential work
  where every step depends on the last — multi-agent *hurts* there. Just do
  those in a normal session.

## The loop

1. **Clarify (you → user).** Read the request. If scope, constraints,
   definition-of-done, or the repo area are ambiguous, ask 2-3 targeted
   questions with `AskUserQuestion`. Skip if already clear. Write the result to
   a `brief.md`.
2. **Plan (council.py).** Run the `plan` phase — two independent plans, two
   cross-critique rounds, one consolidation:
   ```bash
   python3 ~/.claude/skills/council/council.py plan --brief brief.md
   ```
   It prints the run dir. (`--estimate` first if you want the call count.)
3. **Checkpoint 1 (you → user).** Show the user `consolidated_plan.md` and the
   `tasks.json` DAG from the run dir. Let them approve or edit before any fan-out
   spend. This is the cheap gate before the expensive wave.
4. **Fan out (council.py).** On approval:
   ```bash
   python3 ~/.claude/skills/council/council.py fanout --run <run-dir>
   ```
   Cheap workers execute the DAG in isolated worktrees; results land on a
   `council/<run>/integration` branch. Your working branch is untouched.
5. **Checkpoint 2 (you → user).** Present `report.md`: what merged, what failed,
   the integration branch to review. Collect feedback; re-loop into `plan` or
   re-run specific tasks as needed.

## Controlling models & intensity

Council is driven by an **intensity preset** plus optional **per-role
overrides**, stored in `platform/agents/council/council.toml`. Manage it
conversationally — when the user says "use thorough intensity" or "switch the
planners to sonnet", translate to a flag or a `config` command:

| preset | rounds | codex effort | workers | max workers |
|---|---|---|---|---|
| `quick` | 1 | low | haiku | 4 |
| `standard` (default) | 2 | high | haiku | 6 |
| `thorough` | 3 | high | sonnet | 6 |
| `max` | 3 | xhigh | sonnet | 8 |

```bash
# one-off for this run:
python3 ~/.claude/skills/council/council.py plan --brief brief.md --intensity thorough
python3 ~/.claude/skills/council/council.py fanout --run <dir> --worker claude:sonnet

# persist defaults (edits council.toml):
python3 ~/.claude/skills/council/council.py config show
python3 ~/.claude/skills/council/council.py config set intensity thorough
python3 ~/.claude/skills/council/council.py config set planner_b codex:gpt-5.5
python3 ~/.claude/skills/council/council.py config unset worker
```

Per-role override flags: `--intensity`, `--rounds`, `--planner-a`, `--planner-b`,
`--consolidator`, `--worker`, `--verifier`, `--codex-effort`, `--max-workers`.
Precedence: intensity preset < `council.toml` < CLI flag. Workers must be
`claude:<model>` (codex workers aren't supported yet).

## Notes

- Both `claude` and `codex` CLIs must be authenticated.
- Runs are resumable: stages are idempotent on their output files; re-run with
  `--run <dir>` to continue.
- Full design and rationale: `docs/private/council-orchestrator-design.md`.
COUNCIL_SKILL_claude_EOF
read -r -d '' COUNCIL_SKILL_codex <<'COUNCIL_SKILL_codex_EOF' || true
---
name: council
description: Orchestrate a large, decomposable problem across two model families — Codex and Claude plan it independently, cross-critique for two rounds, a judge consolidates one plan plus a parallel task DAG, then cheap workers fan out execution in isolated worktrees. Use for big parallelizable work, not for small or tightly-coupled sequential changes.
---

# council

A cross-model orchestrator for problems too big for one pass. The driver is
`~/.codex/skills/council/council.py` (stdlib-only; shells out to `codex exec`
and `claude -p`); inside the personal-stack repo it also lives at
`platform/agents/council/council.py`. Around it, you handle the human-facing
part: clarify the brief and present the two checkpoints.

Run it from the project you want council to work on — it targets the repository
of the current working directory. It installs into `~/.codex/skills/council`
(and `~/.claude/skills/council`) via the agent-kit installer
(`curl …/install.sh | bash`); re-run that to upgrade.

Use it for large work that decomposes into independent parallel pieces and is
worth the spend (multi-agent uses roughly 15x the tokens of a single chat). Do
not use it for small or tightly-coupled, sequential work — handle those in a
normal session.

Loop:

1. Clarify. If scope, constraints, definition of done, or repo area are
   ambiguous, ask the user 2-3 targeted questions; otherwise skip. Write the
   result to `brief.md`.
2. Plan:
   ```bash
   python3 ~/.codex/skills/council/council.py plan --brief brief.md
   ```
   Two independent plans, two cross-critique rounds, one consolidation. Prints
   the run dir. Use `--estimate` for the call count first.
3. Checkpoint 1: show the user `consolidated_plan.md` and the `tasks.json` DAG
   from the run dir. Get approval or edits before any fan-out spend.
4. Fan out, on approval:
   ```bash
   python3 ~/.codex/skills/council/council.py fanout --run <run-dir>
   ```
   Cheap workers execute the DAG in isolated worktrees; results land on a
   `council/<run>/integration` branch. The working branch is untouched.
5. Checkpoint 2: present `report.md` — what merged, what failed, the integration
   branch to review. Collect feedback; re-loop into `plan` or re-run tasks.

## Controlling models & intensity

Council is driven by an intensity preset plus optional per-role overrides in
`platform/agents/council/council.toml`. Manage it conversationally — translate
"use thorough intensity" or "switch planners to sonnet" into a flag or a
`config` command.

| preset | rounds | codex effort | workers | max workers |
|---|---|---|---|---|
| quick | 1 | low | haiku | 4 |
| standard (default) | 2 | high | haiku | 6 |
| thorough | 3 | high | sonnet | 6 |
| max | 3 | xhigh | sonnet | 8 |

```bash
# one-off:
python3 ~/.codex/skills/council/council.py plan --brief brief.md --intensity thorough
python3 ~/.codex/skills/council/council.py fanout --run <dir> --worker claude:sonnet
# persist (edits council.toml):
python3 ~/.codex/skills/council/council.py config show
python3 ~/.codex/skills/council/council.py config set intensity thorough
python3 ~/.codex/skills/council/council.py config set planner_b codex:gpt-5.5
```

Override flags: `--intensity`, `--rounds`, `--planner-a`, `--planner-b`,
`--consolidator`, `--worker`, `--verifier`, `--codex-effort`, `--max-workers`.
Precedence: preset < council.toml < CLI flag. Workers must be `claude:<model>`.

Notes:

- Both `codex` and `claude` CLIs must be authenticated.
- Runs are resumable: stages are idempotent on their output files; re-run with
  `--run <dir>`.
- Full design: `docs/private/council-orchestrator-design.md`.
COUNCIL_SKILL_codex_EOF
install_council() {
  local dir="$1" skill="$2"
  write_file "${dir}/council/council.py" 0755 "${COUNCIL_FILE_council_py}"
  write_file "${dir}/council/prompts/_baseline.md" 0644 "${COUNCIL_FILE_prompts__baseline_md}"
  write_file "${dir}/council/prompts/consolidator.md" 0644 "${COUNCIL_FILE_prompts_consolidator_md}"
  write_file "${dir}/council/prompts/critic.md" 0644 "${COUNCIL_FILE_prompts_critic_md}"
  write_file "${dir}/council/prompts/planner.md" 0644 "${COUNCIL_FILE_prompts_planner_md}"
  write_file "${dir}/council/prompts/reviser.md" 0644 "${COUNCIL_FILE_prompts_reviser_md}"
  write_file "${dir}/council/prompts/verifier.md" 0644 "${COUNCIL_FILE_prompts_verifier_md}"
  write_file "${dir}/council/prompts/worker.md" 0644 "${COUNCIL_FILE_prompts_worker_md}"
  write_file "${dir}/council/schemas/consolidated.schema.json" 0644 "${COUNCIL_FILE_schemas_consolidated_schema_json}"
  write_file "${dir}/council/schemas/plan.schema.json" 0644 "${COUNCIL_FILE_schemas_plan_schema_json}"
  write_file "${dir}/council/schemas/verdict.schema.json" 0644 "${COUNCIL_FILE_schemas_verdict_schema_json}"
  write_file "${dir}/council/SKILL.md" 0644 "$skill"
  if [ ! -e "${dir}/council/council.toml" ]; then
    write_file "${dir}/council/council.toml" 0644 "${COUNCIL_FILE_council_toml}"
  else
    log "preserving existing ${dir}/council/council.toml"
  fi
}
if [ "${INSTALL_CLAUDE}" = 1 ]; then install_council "${SKILLS_DIR}" "${COUNCIL_SKILL_claude}"; fi
if [ "${INSTALL_CODEX}" = 1 ]; then install_council "${CODEX_SKILLS_DIR}" "${COUNCIL_SKILL_codex}"; fi

# -----------------------------------------------------------------
# Path allowlist (gitignore-style). Hooks below skip any tool input
# whose target matches a pattern here. Defaults exclude paths that
# typically carry secrets so an Edit on `.env` does not exfiltrate
# the path to the KB recall query.
# -----------------------------------------------------------------
if [ "${INSTALL_CLAUDE}" = 1 ] || [ "${INSTALL_CODEX}" = 1 ]; then
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
fi

if [ "${INSTALL_CLAUDE}" = 1 ] && [ ! -e "${ALLOWLIST}" ]; then
  write_file "${ALLOWLIST}" 0644 "${ALLOWLIST_DEFAULTS}"
elif [ "${INSTALL_CLAUDE}" = 1 ]; then
  log "preserving existing ${ALLOWLIST}"
fi

if [ "${INSTALL_CODEX}" = 1 ] && [ ! -e "${CODEX_ALLOWLIST}" ]; then
  write_file "${CODEX_ALLOWLIST}" 0644 "${ALLOWLIST_DEFAULTS}"
elif [ "${INSTALL_CODEX}" = 1 ]; then
  log "preserving existing ${CODEX_ALLOWLIST}"
fi

# -----------------------------------------------------------------
# Hook: PreToolUse — Edit/Write/MultiEdit/apply_patch recall
# -----------------------------------------------------------------
read -r -d '' PRE_TOOL_USE_EDIT_HOOK <<'HOOK' || true
#!/usr/bin/env bash
# PreToolUse hook for Edit/Write/MultiEdit/apply_patch. Looks at the file path
# the agent is about to touch and runs a `knowledge.recall` against
# it so prior captures referencing that file (or its module) surface
# before the edit lands.
#
# Safety:
#   - Honours KB_AUTO_MCP_DISABLED=1 (panic switch).
#   - Honours the client allowlist (skip if match).
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
CLIENT_HOME="${KB_AUTO_MCP_HOME:-${CLAUDE_CONFIG_DIR:-$HOME/.claude}}"
STATE_DIR="${KB_AUTO_MCP_STATE_DIR:-${CLIENT_HOME}/state}"
ALLOWLIST="${KB_AUTO_MCP_ALLOWLIST:-${CLIENT_HOME}/.knowledge-system-allowlist}"

input=$(cat 2>/dev/null || true)
file_path=$(printf '%s' "${input}" | python3 -c '
import json, re, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

inputs = []
for key in ("tool_input", "input", "arguments", "params"):
    value = data.get(key)
    if isinstance(value, dict):
        inputs.append(value)
tool = data.get("tool")
if isinstance(tool, dict):
    for key in ("input", "arguments", "params"):
        value = tool.get(key)
        if isinstance(value, dict):
            inputs.append(value)

for source in inputs + [data]:
    for key in ("file_path", "filePath", "path", "target_file", "targetFile"):
        value = source.get(key) if isinstance(source, dict) else None
        if isinstance(value, str) and value.strip():
            print(value.strip(), end="")
            sys.exit(0)

patch_parts = []
for source in inputs + [data]:
    if not isinstance(source, dict):
        continue
    for key in ("patch", "content", "body", "diff", "input"):
        value = source.get(key)
        if isinstance(value, str):
            patch_parts.append(value)

for line in "\n".join(patch_parts).splitlines():
    match = re.match(r"^\*\*\* (?:Update|Add|Delete) File: (.+)$", line)
    if match:
        print(match.group(1).strip(), end="")
        sys.exit(0)
' 2>/dev/null || true)
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
session="${CLAUDE_SESSION_ID:-${CODEX_THREAD_ID:-${CODEX_SESSION_ID:-unknown}}}"
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
        title = h.get("title", "")
        scope = h.get("scope", "")
        note_id = h.get("id", "")
        print(f"- **{title}** (`{scope}`) — id `{note_id}`")
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
parsed=$(printf '%s' "${input}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

tool = data.get("tool_name") or data.get("name") or ""
raw_tool = data.get("tool")
if isinstance(raw_tool, str):
    tool = tool or raw_tool
elif isinstance(raw_tool, dict):
    tool = tool or raw_tool.get("name") or ""

inputs = []
for key in ("tool_input", "input", "arguments", "params"):
    value = data.get(key)
    if isinstance(value, dict):
        inputs.append(value)
if isinstance(raw_tool, dict):
    for key in ("input", "arguments", "params"):
        value = raw_tool.get(key)
        if isinstance(value, dict):
            inputs.append(value)

command = ""
for source in inputs + [data]:
    if not isinstance(source, dict):
        continue
    for key in ("command", "cmd", "script", "shell_command", "shellCommand"):
        value = source.get(key)
        if isinstance(value, str) and value.strip():
            command = value.strip()
            break
    if command:
        break

print(f"{tool}\x1f{command}", end="")' 2>/dev/null || true)
tool="${parsed%%$'\x1f'*}"
command="${parsed#*$'\x1f'}"
[ "${tool}" = "${parsed}" ] && command=""
if [ -n "${tool}" ] && [ "${tool}" != "Bash" ] && [ "${tool}" != "bash" ]; then
  exit 0
fi

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
[ -n "${project}" ] || project="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)")"
scope="project:${project}"

body=$(cat <<BODY
Commit message: ${title}

Captured automatically by ${KB_AUTO_MCP_CLIENT_NAME:-the Claude PreToolUse} \`git commit\` hook. The
diff and surrounding context live in git history.
BODY
)

source="${KB_AUTO_MCP_SOURCE:-claude-code:auto-capture:git-commit}"
payload=$(python3 -c 'import json,sys; print(json.dumps({
  "jsonrpc":"2.0","id":1,"method":"tools/call","params":{
    "name":"knowledge.capture_decision","arguments":{
      "title": sys.argv[1],
      "body": sys.argv[2],
      "scope": sys.argv[3],
      "source": sys.argv[4],
      "tags": ["auto-capture","git-commit"]
    }}}))' "${title}" "${body}" "${scope}" "${source}")

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
# Claude Stop hook: summarize reusable lessons from the transcript and
# capture a capped set into the KB. Silent on failure.

set -u

[ "${KB_AUTO_MCP_DISABLED:-0}" = 1 ] && exit 0
[ -z "${KB_BEARER_TOKEN:-}" ] && exit 0

KB_URL="${KB_URL:-@KB_URL@}"
case "${KB_URL}" in
  */mcp) KB_MCP_URL="${KB_URL}" ;;
  *) KB_MCP_URL="${KB_URL%/}/mcp" ;;
esac

CLAUDE_STATE="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"
STATE_DIR="${CLAUDE_STATE}/state"
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

transcript="$(python3 - "${transcript_path}" "${KB_DIGEST_MAX_CHARS:-30000}" <<'PY' 2>/dev/null
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
        "source": "claude-code:auto-digest:" + sys.argv[4],
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
echo "$(date -u +%FT%TZ) claude-stop-digest session=${session} emitted=${emitted}" >>"${LOG}" 2>/dev/null
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

transcript="$(python3 - "${transcript_path}" "${KB_DIGEST_MAX_CHARS:-30000}" <<'PY' 2>/dev/null
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
    "PreToolUse": [
      {
        "matcher": "Edit|Write|apply_patch",
        "hooks": [
          {
            "type": "command",
            "command": "env KB_AUTO_MCP_HOME=${CODEX_HOME} ${CODEX_HOOKS_DIR}/pre-tool-use-edit-recall.sh",
            "timeout": 5,
            "statusMessage": "Loading file KB context"
          }
        ]
      },
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "env KB_AUTO_MCP_HOME=${CODEX_HOME} KB_AUTO_MCP_SOURCE=codex:auto-capture:git-commit KB_AUTO_MCP_CLIENT_NAME=Codex ${CODEX_HOOKS_DIR}/pre-tool-use-git-commit-capture.sh",
            "timeout": 5,
            "statusMessage": "Capturing commit decision"
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
  write_file "${CODEX_HOOKS_DIR}/pre-tool-use-edit-recall.sh" 0755 "${PRE_TOOL_USE_EDIT_HOOK}"
  write_file "${CODEX_HOOKS_DIR}/pre-tool-use-git-commit-capture.sh" 0755 "${PRE_TOOL_USE_GIT_COMMIT_HOOK}"
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
scope=${SCOPE}
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
scope=${SCOPE}
managed:
$(printf '  - %s\n' "${codex_managed_paths[@]}")
MANIFEST
  log "wrote ${CODEX_MANIFEST}"
fi

cat <<EOF
knowledge-system installer complete (${INSTALLER_VERSION}, agent=${AGENT}, scope=${SCOPE}).
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

  1. ${CODEX_HOOKS_CONFIG} has been written with UserPromptSubmit, PreToolUse,
     and Stop hooks.
  2. Make sure KB_BEARER_TOKEN is set in the Codex environment:
       export KB_BEARER_TOKEN="<your-token>"
EOF
fi

cat <<EOF

Verify with:  curl -sS -H "Authorization: Bearer \$KB_BEARER_TOKEN" \\
                     ${KB_URL}/mcp -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'

Safety controls:
  - Panic switch:   export KB_AUTO_MCP_DISABLED=1   (turns every hook into a no-op).
EOF
if [ "${INSTALL_CLAUDE}" = 1 ]; then
  cat <<EOF
  - Claude allowlist: edit ${ALLOWLIST}  (gitignore-style patterns).
  - Claude state:     ${STATE_DIR}/auto-mcp.log + per-session dedupe under ${STATE_DIR}/sessions/.
EOF
fi
if [ "${INSTALL_CODEX}" = 1 ]; then
  cat <<EOF
  - Codex allowlist:  edit ${CODEX_ALLOWLIST}  (gitignore-style patterns).
  - Codex state:      ${CODEX_HOME}/state/auto-mcp.log + per-session dedupe under ${CODEX_HOME}/state/sessions/.
EOF
fi
cat <<EOF
  - Provenance:     every auto-capture lands with source = "<agent>:auto-capture:<hook>"
                    or "claude-code:auto-digest:<session>" so a bulk revoke is one SQL query.

Run with --agent ${AGENT} --scope ${SCOPE} --uninstall to remove every selected file this installer wrote.
EOF
