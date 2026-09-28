#!/usr/bin/env bash
# Shows Kubernetes keeping the system running while an intake pod is killed
# under load. DisruptionTest drives a full flow and touches a marker once its
# load is under way; this script waits for that marker, then deletes a pod, so
# the kill always lands on a live flow rather than racing the test's start-up.
# Retries carry the requests that met the dying pod, no player is lost, and
# Kubernetes replaces the pod on its own. A planned maintenance shutdown is
# gentler than an abrupt kill, so it is covered by the same proof.
#
# Preconditions (scripts/minikube-up.sh leaves both in place):
#   - the cluster is up and `minikube tunnel` is running in another terminal
# Leaves intake at three copies. Exits nonzero on any failure.
set -euo pipefail
cd "$(dirname "$0")/.."

INTAKE="${INTAKE_URL:-http://localhost:8080}"
MATCHMAKING="${MATCHMAKING_URL:-http://localhost:8081}"
NOBODY=00000000-0000-0000-0000-000000000000

STARTED=e2e-tests/build/e2e/started

wait_ready() {
    for _ in $(seq 1 60); do
        mm=$(curl -s -o /dev/null -w '%{http_code}' "$MATCHMAKING/players/$NOBODY" || true)
        in=$(curl -s -o /dev/null -w '%{http_code}' "$INTAKE/queue/status/$NOBODY" || true)
        if [ "$mm" = 404 ] && [ "$in" = 200 ]; then return 0; fi
        sleep 2
    done
    echo "services not reachable after 120 s; is 'minikube tunnel' running?" >&2
    return 1
}

# Wait until the test signals its load is under way.
wait_started() {
    for _ in $(seq 1 60); do
        if [ -f "$STARTED" ]; then return 0; fi
        sleep 1
    done
    echo "the load never started within 60 s" >&2
    return 1
}

wait_ready

echo "== killed pod under load: expecting retries to succeed and no player lost"
rm -f "$STARTED"
./gradlew :e2e-tests:disruptionTest -Pphase=crash -Pintake="$INTAKE" -Pmatchmaking="$MATCHMAKING" &
load=$!
wait_started
pod=$(kubectl get pods -l app=intake -o name | head -1)
echo "== deleting $pod"
kubectl delete "$pod"
wait "$load"

echo "== intake heals back to three copies"
kubectl rollout status deployment/intake --timeout=150s

echo "== disruption check passed"
