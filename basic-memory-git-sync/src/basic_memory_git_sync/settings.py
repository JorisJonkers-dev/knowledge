"""Settings for the Basic Memory git backstop.

Kept boring, matching the ingest-worker: no .env files, no profiles.
The deployment manifest sets the env vars; tests construct the object
directly.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Settings:
    clone_url: str
    vault_dir: str
    branch: str
    ssh_key_path: str
    author_name: str
    author_email: str
    poll_seconds: int
    push: bool
    log_level: str
    service_version: str

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        return cls(
            # Only used to clone on first boot; in production the vault_dir
            # is already a checkout on the shared PVC.
            clone_url=e.get(
                "VAULT_CLONE_URL", "git@github.com:JorisJonkers-dev/knowledge-vault.git"
            ),
            # The working tree the Basic Memory server and this backstop
            # share. Basic Memory writes markdown here; this service runs
            # git on the same tree and pushes those writes to origin.
            vault_dir=e.get("VAULT_DIR", "/var/lib/knowledge-vault"),
            branch=e.get("VAULT_BRANCH", "main"),
            ssh_key_path=e.get("VAULT_SSH_KEY_PATH", "/etc/git-secrets/id_ed25519"),
            author_name=e.get("VAULT_AUTHOR_NAME", "basic-memory-vault"),
            author_email=e.get("VAULT_AUTHOR_EMAIL", "basicmemory@knowledge.local"),
            poll_seconds=int(e.get("POLL_SECONDS", "10")),
            # Off in local/dev; the manifest sets it true in production.
            push=e.get("PUSH", "false").lower() in ("1", "true", "yes"),
            log_level=e.get("LOG_LEVEL", "INFO"),
            service_version=e.get("SERVICE_VERSION", "unknown"),
        )
