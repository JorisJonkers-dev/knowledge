# syntax=docker/dockerfile:1.7

# Python 3.12 slim — matches pyproject's requires-python; bookworm is
# stable and small enough.
FROM python:3.12-slim-bookworm AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
# git is needed once Phase 5-2's vault writer lands; ship it now so
# the runtime image doesn't change shape between PRs and Keel's
# image-digest comparison stays meaningful.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install uv as the package manager. Phase 5-1 only needs `pip` but
# uv is what the rest of the plan references and what dependabot will
# eventually update — same install path for the whole service.
COPY --from=ghcr.io/astral-sh/uv:0.4.30 /uv /usr/local/bin/uv

# -- build stage -----------------------------------------------------
FROM base AS build
WORKDIR /app
# Copy the project skeleton first so layer caching survives source
# edits that don't touch pyproject.toml.
COPY services/knowledge-ingest-worker/pyproject.toml /app/pyproject.toml
COPY services/knowledge-ingest-worker/src /app/src
# Resolve + install into a self-contained virtualenv. `--no-dev`
# omits the test deps (ruff, pytest, testcontainers, …) from the
# runtime image.
RUN uv venv /app/.venv --python python3.12 \
    && uv pip install --python /app/.venv/bin/python --no-cache --no-dev .

# -- runtime ---------------------------------------------------------
FROM base
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA} \
    PATH=/app/.venv/bin:$PATH \
    PYTHONPATH=/app/src
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY services/knowledge-ingest-worker/src /app/src

# Run non-root with a stable uid so Vault Agent and the knowledge-vault
# PVC ownership stay aligned. uid 1000 matches the convention used in
# obsidian (PUID=1000) and the alpine/git initContainer's chown target.
RUN groupadd --system --gid 1000 worker \
    && useradd --system --uid 1000 --gid 1000 --home /home/worker worker \
    && mkdir -p /home/worker && chown -R worker:worker /home/worker /app
USER worker

# `opentelemetry-instrument` auto-wires the OTLP exporter + pika
# instrumentation around the consumer.
ENTRYPOINT ["opentelemetry-instrument", "python", "-m", "knowledge_worker"]
