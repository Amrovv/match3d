#!/usr/bin/env bash
# End to end check against the minikube cluster: run the flow, restart the
# service pods and the external Postgres and RabbitMQ, then check the flow's
# data survived. The mirror of scripts/e2e-compose.sh, but the services are
# pods restarted with kubectl instead of Compose containers.
#
# Preconditions (scripts/minikube-up.sh leaves both in place):
#   - the cluster is up with both deployments rolled out
#   - `minikube tunnel` is running in another terminal, so the LoadBalancer
#     services answer on localhost:8080 (intake) and :8081 (matchmaking)
# Exits nonzero on any failure.
set -euo pipefail
cd "$(dirname "$0")/.."

INTAKE="${INTAKE_URL:-http://localhost:8080}"
MATCHMAKING="${MATCHMAKING_URL:-http://localhost:8081}"
NOBODY=00000000-0000-0000-0000-000000000000

# Ready once matchmaking answers 404 for an unknown player and intake answers
# status. The same readiness the Compose wrapper waits on.
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

echo "== waiting for the cluster to answer"
wait_ready

echo "== flow"
./gradlew :e2e-tests:e2eTest -Pintake="$INTAKE" -Pmatchmaking="$MATCHMAKING"

echo "== restarting the service pods (kubectl) and the external stores (compose)"
kubectl rollout restart deployment/intake deployment/matchmaking
kubectl rollout status deployment/intake
kubectl rollout status deployment/matchmaking
docker compose restart postgres rabbitmq
wait_ready

echo "== after restart"
./gradlew :e2e-tests:e2eTest -PafterRestart -Pintake="$INTAKE" -Pmatchmaking="$MATCHMAKING"

echo "== end to end check passed"
