#!/usr/bin/env bash
#
# Local replica of the "Execute E2E Tests" GitHub workflow (.github/workflows/run-e2e-tests.yml).
# Builds the runtime images, spins up a KinD cluster, deploys the MVD resources via Helm/kubectl
# and runs the E2E test suite.
#
# Prerequisites (the workflow installs these via actions; install them yourself locally):
#   - docker, helm, kubectl, kind, and a JDK (the gradlew wrapper is used as-is)
#
# Usage:
#   ./run-e2e-tests.sh            # full run
#   KEEP_CLUSTER=1 ./run-e2e-tests.sh   # don't delete the KinD cluster on exit
#
set -euo pipefail

CLUSTER_NAME="mvd"
GATEWAY_API_VERSION="v1.5.1"
PORT_FORWARD_PID=""

K="kubectl --kubeconfig ~/.kube/${CLUSTER_NAME}.config"

# Run everything from the repository root (directory of this script).
cd "$(dirname "$0")"

log() { echo -e "\n\033[1;34m==> $*\033[0m"; }

cleanup() {
  # stop the background port-forward if it is still running
  if [[ -n "${PORT_FORWARD_PID}" ]] && kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    kill "${PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  if [[ "${KEEP_CLUSTER:-0}" == "1" ]]; then
    log "KEEP_CLUSTER=1 set — leaving KinD cluster '${CLUSTER_NAME}' running"
  else
    log "Destroy the KinD cluster"
    kind delete cluster -n "${CLUSTER_NAME}" || true
  fi
}
trap cleanup EXIT

# Verify required tools are present.
for tool in docker helm kubectl kind; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "ERROR: required tool '${tool}' not found on PATH" >&2
    exit 1
  fi
done

log "Build runtime images"
./gradlew dockerize

log "Create k8s KinD cluster '${CLUSTER_NAME}'"
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  echo "Cluster '${CLUSTER_NAME}' already exists — reusing it"
else
  kind create cluster --name "${CLUSTER_NAME}" --kubeconfig ~/.kube/${CLUSTER_NAME}.config
fi

log "Load runtime images into KinD"
kind load docker-image -n "${CLUSTER_NAME}" \
  ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/controlplane:latest \
  ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/dataplane:latest \
  ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/identity-hub:latest \
  ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/issuerservice:latest

log "Update image pull policy (Always -> Never)"
grep -rl "imagePullPolicy: Always" k8s | xargs sed -i "s/imagePullPolicy: Always/imagePullPolicy: Never/g" || true

log "Install Traefik Gateway controller"
helm repo add traefik https://traefik.github.io/charts
helm repo update
helm upgrade --install --namespace traefik traefik traefik/traefik --create-namespace -f values.yaml --kubeconfig ~/.kube/${CLUSTER_NAME}.config

# Wait for traefik to be ready
log "Waiting for Traefik to be ready"
eval $K rollout status deployment/traefik -n traefik --timeout=600s

# install Gateway API CRDs
log "Install Gateway API CRDs"
eval $K apply --server-side --force-conflicts \
  -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/standard-install.yaml"

# forward port 80 -> 8080
log "Create port-forward"
eval $K -n traefik port-forward svc/traefik 8080:80 &
PORT_FORWARD_PID=$!

sleep 5 # to be safe

log "Deploy MVD common resources"
eval $K apply -k k8s/common
eval $K wait -A \
  --for=condition=ready pod \
  --selector=type=edc-infra \
  --timeout=600s || { eval $K get pods -A; exit 1; }

log "Deploy MVD issuer resources"
eval $K apply -k k8s/issuer
eval $K wait -A \
  --selector=type=edc-job \
  --for=condition=complete job --all \
  --timeout=600s || { eval $K get pods -A; exit 1; }

log "Deploy MVD consumer resources"
eval $K apply -k k8s/consumer
eval $K wait -A \
  --selector=type=edc-job \
  --for=condition=complete job --all \
  --timeout=600s || { eval $K get pods -A; exit 1; }

log "Deploy MVD provider resources"
eval $K apply -k k8s/provider
eval $K wait -A \
  --selector=type=edc-job \
  --for=condition=complete job --all \
  --timeout=600s || { eval $K get pods -A; exit 1; }

log "Run E2E Test"
./gradlew -DincludeTags="EndToEndTest" test -DverboseTest=true

log "E2E tests completed successfully"
