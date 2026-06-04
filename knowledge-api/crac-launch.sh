#!/bin/sh
# Production launcher for knowledge-api. Invoked by the deployment command
# *after* the Vault-injected secret env files have been sourced, so the
# process environment carries the live DB / RabbitMQ credentials.
#
# Two paths:
#   - CRaC restore: if restoration is enabled and the image actually carries
#     a baked checkpoint, restore the JVM from it (read path already hot, sub-
#     second startup). CRIU restore needs CAP_CHECKPOINT_RESTORE + CAP_SYS_PTRACE
#     on the container (set in deployment.yaml).
#   - Cold start: the byte-for-byte equivalent of the previous deployment
#     command. Used when restore is disabled, no checkpoint is baked, or a
#     restore attempt exits non-zero.
#
# KNOWN LIMITATION: the baked checkpoint freezes the JVM's checkpoint-time
# environment, so the restored Hikari pool / RabbitMQ template still hold the
# CI training connection config rather than the live Vault values sourced
# above. Until an afterRestore re-bind lands in kotlin-common, a successful
# restore will not reach the production database. The non-zero-exit fallback
# below only covers a restore that *fails*; a restore that succeeds but then
# cannot reach the DB leaves the pod failing readiness (RollingUpdate
# maxUnavailable:0 keeps the old pod serving, so there is no outage — set
# CRAC_RESTORE_ENABLED=false to revert to cold start).
set -eu

CHECKPOINT_DIR=/opt/crac/checkpoint

cold_start() {
  exec java \
    -XX:+UseZGC \
    -XX:MaxRAMPercentage=75 \
    -javaagent:/app/otel-javaagent.jar \
    -jar /app/app.jar
}

if [ "${CRAC_RESTORE_ENABLED:-false}" != "true" ]; then
  echo "CRaC: restore disabled (CRAC_RESTORE_ENABLED != true); cold start" >&2
  cold_start
fi

# CRIU writes the process image as *.img files. A directory holding only the
# .gitkeep placeholder (the default, non-baked build) is treated as no
# checkpoint.
if ! ls "$CHECKPOINT_DIR"/*.img >/dev/null 2>&1; then
  echo "CRaC: no checkpoint baked into image ($CHECKPOINT_DIR); cold start" >&2
  cold_start
fi

echo "CRaC: restoring from $CHECKPOINT_DIR" >&2
# Not exec: keep the shell alive so a failed restore can fall back. Forward
# SIGTERM/SIGINT to the JVM so k8s pod termination still drains gracefully.
java -XX:CRaCRestoreFrom="$CHECKPOINT_DIR" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null || true' TERM INT
code=0
wait "$child" || code=$?
if [ "$code" -ne 0 ]; then
  echo "CRaC: restore exited $code; falling back to cold start" >&2
  cold_start
fi
exit 0
