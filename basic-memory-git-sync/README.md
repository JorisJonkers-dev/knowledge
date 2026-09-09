# basic-memory-git-sync

Git commit backstop for the [Basic Memory](https://github.com/basicmachines-co/basic-memory)
vault.

Basic Memory (AGPL-3.0) writes markdown notes into a shared git working tree
but never runs git itself. Left alone, a note written through Basic Memory
stays an uncommitted file and is lost on the next reschedule. This service
closes that gap: it watches the working tree, and when the tree has changes
it commits them with a `memory():` prefix and pushes to `origin`.

## Why it is a separate service

Basic Memory has no git integration (no git library in its deps, no git
execution in its source), and its image is the AGPL-3.0 project's own. Rather
than patch a vendor image, the backstop runs as a sibling container in the
same pod, sharing the vault volume. The Basic Memory server is the *only*
writer; this process is the *only* git actor.

## Concurrency (never a lost edit)

- Single-writer by construction: the vault lives on a ReadWriteOnce PVC and
  the pod is a single replica, so one Basic Memory process writes at a time.
- Before committing, the backstop pulls with rebase. If a remote commit
  touched a file that has uncommitted local work, the rebase surfaces a
  conflict and the loop **stops** (raising) rather than auto-resolving. No
  `--theirs`/`--ours` override is applied: an edit is never silently dropped.
- It never force-pushes. A refused push means the remote moved underneath us;
  the local commit is retained and the loop stops for a human to reconcile.

This is exactly what the acceptance criterion "two concurrent writes to one
note produce a detected conflict, not a lost edit" asks for, verified by unit
tests (`tests/unit/test_sync.py::test_conflicting_remote_change_surfaces_never_auto_resolves`).

## Local development

```bash
cd basic-memory-git-sync
uv sync            # install runtime + dev deps
uv run pytest      # unit tests
uv run ruff check .
uv run mypy
```

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `VAULT_CLONE_URL` | `git@github.com:…/knowledge-vault.git` | Only used to clone on first boot |
| `VAULT_DIR` | `/var/lib/knowledge-vault` | Shared working tree (the PVC Basic Memory also writes) |
| `VAULT_BRANCH` | `main` | |
| `VAULT_SSH_KEY_PATH` | `/etc/git-secrets/id_ed25519` | Deploy key for push |
| `VAULT_AUTHOR_NAME` / `VAULT_AUTHOR_EMAIL` | `basic-memory-vault` / `basicmemory@knowledge.local` | |
| `POLL_SECONDS` | `10` | Cycle interval |
| `PUSH` | `false` | Set `true` in production |
| `LOG_LEVEL` | `INFO` | |
| `SERVICE_VERSION` | `unknown` | |

## AGPL obligation boundary

Basic Memory is AGPL-3.0-or-later. Running its server, unchanged, as a network
service triggers the AGPL source-availability obligation for that server. In
this estate, **only the Basic Memory image is the AGPL component**: the
`basic-memory-git-sync` backstop is first-party MIT-licensed code that does not
derive from Basic Memory and does not modify it. Its interface to Basic Memory
is the shared markdown vault and the AGPL service's own unmodified behaviour —
no part of the server is copied, modified, or linked. The AGPL obligation is
met by the upstream project's published source; this estate runs a pinned,
unmodified image and does not distribute a modified derivative. The source for
this backstop lives in this repository, so no proprietary layer is introduced
between the AGPL server and its users. If the Basic Memory image is ever
patched/embedded (rather than consumed unmodified), that work must sink into
the upstream project to stay on the right side of the license.

## Deployment

Wired into the estate as a sibling container to Basic Memory in
`fleet-infra/cluster/flux/apps/knowledge-platform/basic-memory/` (see the PR
that deploys Basic Memory). Published as
`ghcr.io/jorisjonkers-dev/knowledge/basic-memory-git-sync` from this repo's
tag-publish flow.