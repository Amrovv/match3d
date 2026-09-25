#!/usr/bin/env bash
# End to end check against docker compose: start the stack, run the flow,
# restart the services and Postgres, then check the flow's data survived.
# Leaves the stack running. Exits nonzero on any failure.
set -euo pipefail
cd "$(dirname "$0")/.."

NOBODY=00000000-0000-0000-0000-000000000000

# Ready once matchmaking answers 404 for an unknown player and intake answers status.
wait_ready() {
    for _ in $(seq 1 60); do
        mm=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8081/players/$NOBODY" || true)
        in=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/queue/status/$NOBODY" || true)
        if [ "$mm" = 404 ] && [ "$in" = 200 ]; then return 0; fi
        sleep 2
    done
    echo "services not ready after 120 s" >&2
    return 1
}

echo "== starting the stack"
docker compose up -d --build
wait_ready

echo "== flow"
./gradlew :e2e-tests:e2eTest

echo "== restarting intake, matchmaking and postgres"
docker compose restart intake matchmaking postgres
wait_ready

echo "== after restart"
./gradlew :e2e-tests:e2eTest -PafterRestart

echo "== end to end check passed"
