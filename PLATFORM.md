# knowledge — platform integration guide

## Quick start: local validate

```bash
./platform/render-local.sh
```

Review `out/scorecard.md` for any failures before committing.

## Full deployment flow

1. **Local validate** — `./platform/render-local.sh`; all scorecard fields must pass (or be `not_applicable`).
2. **Tag release** — merge to `main`; release-please opens a release PR; merging it pushes a `v*.*.*` tag.
3. **Automated publish** — `publish.yml` runs on the tag: builds both images → locks image digests →
   publishes the deploy artifact to GHCR → opens a registry PR in homelab-deploy.
4. **Renovate pin PR** — Renovate opens a PR in homelab-deploy updating the registry entry digest.
5. **Deploy preview** — on any PR touching `platform/`, `deploy-preview.yml` posts a scorecard comment.
6. **Merge → gates** — homelab-deploy runs Compose Gate, Leak Scan, Stack Integration Gate, Pipeline Complete.
7. **Auto-deploy** — on merge to homelab-deploy main, `publish-deploy-branch.yml` pushes to `platform/production`.

## Stateful workload notes

`knowledge-ingest-worker` is declared `stateful: true` with `migrationPolicy.strategy: pre-deploy-job`.
The pre-deploy job (`knowledge-vault-migrate-2026-05-18`) must complete before ingest-worker pods start.
The registry entry in homelab-deploy carries explicit `pvcDecisions` and `migrationJobDecisions` for the
`knowledge-vault-clone` PVC and the migration job — these must not be removed without owner approval.

See `knowledge-onboarding-plan.md` in the migration workspace for the full state-move-plan.

## Files

| File | Purpose |
|------|---------|
| `platform/deployment.yml` | Deployment contract (v2): namespace, `platform.layer`, workloads, health, routes, rollback policy |
| `platform/images.lock.json` | Digest-pinned image references (filled by `publish.yml`; stub in repo) |
| `.github/workflows/publish.yml` | Tag-triggered dual-image publish and registry PR |
| `.github/workflows/release.yml` | release-please with mandatory App token |

## Readiness scorecard (SC-11)

Every render evaluates these fields; `fail` blocks deployment, `not_applicable`
means the check does not apply to this service:

| Field | not_applicable when |
|---|---|
| `schema_pinned` | never |
| `context_pinned` | never |
| `no_latest_images` | never |
| `health_declared` | never |
| `route_owner_authmode_declared` | no workload declares routes |
| `rollback_retention_acknowledged` | never |
| `no_raw_secrets` | never |
| `stateful_policy_declared` | all workloads are stateless |
| `raw_manifests_guarded` | no workload enables rawManifests |
| `npm_signatures_verified` | never |

## Reference

- Full platform design: [DEPLOY-PLATFORM-PLAN.md](https://github.com/JorisJonkers-dev/platform-blueprints/blob/main/DEPLOY-PLATFORM-PLAN.md)
- Schema docs: `deploy-config-schema --help`
- Composition authority: [homelab-deploy](https://github.com/JorisJonkers-dev/homelab-deploy)
- State-move plan: `homelab-deploy/knowledge-state-adopt.yml`
