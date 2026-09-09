# syntax=docker/dockerfile:1.7

# Sidecar that commits + pushes the notes Basic Memory writes into the shared
# knowledge-vault working tree. Shares the vault volume with the Basic Memory
# container in the same pod; this is the only git actor on that tree.
FROM python:3.12-slim-bookworm AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
# git + openssh-client are needed to pull/commit/push the vault over SSH.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        git \
        openssh-client \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.4.30 /uv /usr/local/bin/uv

FROM base AS build
WORKDIR /app
COPY basic-memory-git-sync/pyproject.toml /app/pyproject.toml
COPY basic-memory-git-sync/uv.lock /app/uv.lock
COPY basic-memory-git-sync/src /app/src
ENV UV_PROJECT_ENVIRONMENT=/app/.venv
RUN uv sync --frozen --no-dev --no-cache --python 3.12

FROM base
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA} \
    PATH=/app/.venv/bin:$PATH \
    PYTHONPATH=/app/src
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY basic-memory-git-sync/src /app/src

# Non-root with a stable uid (1000) so the shared vault PVC ownership stays
# aligned with the Basic Memory container. (`sync` is a reserved system
# account on Debian, so this uses a distinct, project-specific name.)
RUN groupadd --system --gid 1000 bmsy \
    && useradd --system --uid 1000 --gid 1000 --home /home/bmsy bmsy \
    && mkdir -p /home/bmsy && chown -R bmsy:bmsy /home/bmsy /app
USER bmsy

ENTRYPOINT ["python", "-m", "basic_memory_git_sync"]