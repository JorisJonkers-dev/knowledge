# syntax=docker/dockerfile:1.7

# Python 3.12 slim — matches pyproject's requires-python; bookworm is
# stable and small enough.
FROM python:3.12-slim-bookworm AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
# git + openssh-client are needed by the vault-clone + commit step
# the worker runs against the knowledge-vault repo. Without ssh on
# PATH, `git clone git@github.com:…` fails with `ssh: not found` —
# python:3.12-slim ships only the git CLI; ssh is a separate package.
# Ship both now so the runtime image doesn't change shape between
# commits and Keel's image-digest comparison stays meaningful.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        git \
        openssh-client \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install uv as the package manager. uv handles both the lockfile-
# enforced install (`uv sync --frozen`) and the local lint/test
# flow, so we keep the install path consistent between dev + image.
COPY --from=ghcr.io/astral-sh/uv:0.4.30 /uv /usr/local/bin/uv

# -- build stage -----------------------------------------------------
FROM base AS build
WORKDIR /app
# Copy pyproject + lockfile first so layer caching survives source
# edits that don't touch dependencies.
COPY services/knowledge-ingest-worker/pyproject.toml /app/pyproject.toml
COPY services/knowledge-ingest-worker/uv.lock /app/uv.lock
COPY services/knowledge-ingest-worker/src /app/src
# `uv sync` resolves against the lockfile and materialises a project
# venv. `--no-dev` drops the test / lint deps (ruff, pytest,
# testcontainers, mypy, …) from the runtime image. `--frozen`
# refuses to update the lockfile on the build host — CI catches a
# stale lock instead of silently regenerating it.
#
# `--no-dev` is a `uv sync` flag, NOT a `uv pip install` flag — the
# earlier incarnation of this Dockerfile used `uv pip install . --no-dev`
# which failed with `unexpected argument '--no-dev' found` at the
# first push to main.
ENV UV_PROJECT_ENVIRONMENT=/app/.venv
RUN uv sync --frozen --no-dev --no-cache --python 3.12

# -- runtime ---------------------------------------------------------
FROM base
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA} \
    PATH=/app/.venv/bin:$PATH \
    PYTHONPATH=/app/src
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY services/knowledge-ingest-worker/src /app/src

# Run non-root with a stable uid so Vault Agent and any future
# knowledge-vault PVC ownership stay aligned. uid 1000 is the
# convention used elsewhere in the cluster (alpine/git, linuxserver
# image PUID, etc.).
RUN groupadd --system --gid 1000 worker \
    && useradd --system --uid 1000 --gid 1000 --home /home/worker worker \
    && mkdir -p /home/worker && chown -R worker:worker /home/worker /app
USER worker

# `opentelemetry-instrument` auto-wires the OTLP exporter + pika
# instrumentation around the consumer.
ENTRYPOINT ["opentelemetry-instrument", "python", "-m", "knowledge_worker"]
