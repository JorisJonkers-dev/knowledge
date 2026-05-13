#!/bin/sh
# Same shim as the other services: compose OTEL_RESOURCE_ATTRIBUTES from the
# image-baked SERVICE_VERSION (Dockerfile ARG GIT_SHA → ENV) plus whatever the
# k8s manifest provides via DEPLOYMENT_ENVIRONMENT. K8s env interpolation can't
# reach values that are only ENV-baked into the image, so this small wrapper
# closes the gap before exec-ing the JVM.
set -eu

attrs="service.version=${SERVICE_VERSION:-unknown}"
if [ -n "${DEPLOYMENT_ENVIRONMENT:-}" ]; then
  attrs="${attrs},deployment.environment=${DEPLOYMENT_ENVIRONMENT}"
fi

if [ -n "${OTEL_RESOURCE_ATTRIBUTES:-}" ]; then
  export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES},${attrs}"
else
  export OTEL_RESOURCE_ATTRIBUTES="${attrs}"
fi

exec java "$@"
