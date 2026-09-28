#!/usr/bin/env bash
# Proves intake runs as several copies behind one Service. Scales intake to
# three, then two, then one, and runs the replica check against each: the
# Service reaches every live copy, and a full flow through them loses and
# duplicates no player. Scaling down (not deleting a pod) leaves a known,
# stable count while the checks run; the self-healing story is 7.4's.
#
# Preconditions (scripts/minikube-up.sh leaves both in place):
#   - the cluster is up and `minikube tunnel` is running in another terminal,
#     so intake answers on localhost:8080 and matchmaking on :8081
# Leaves intake back at three copies. Exits nonzero on any failure.
set -euo pipefail
cd "$(dirname "$0")/.."

INTAKE="${INTAKE_URL:-http://localhost:8080}"
MATCHMAKING="${MATCHMAKING_URL:-http://localhost:8081}"
NOBODY=00000000-0000-0000-0000-000000000000

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

# Scale intake to $1 copies, wait for the rollout to settle, run the check.
check_at() {
    copies=$1
    echo "== scaling intake to $copies copies"
    kubectl scale deployment/intake --replicas="$copies"
    kubectl rollout status deployment/intake
    wait_ready
    echo "== checking with $copies copies"
    ./gradlew :e2e-tests:replicaTest -Pcopies="$copies" -Pintake="$INTAKE" -Pmatchmaking="$MATCHMAKING"
}

check_at 3
check_at 2
check_at 1

echo "== scaling intake back to three copies"
kubectl scale deployment/intake --replicas=3
kubectl rollout status deployment/intake

echo "== replica check passed"
