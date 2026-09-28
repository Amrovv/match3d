#!/usr/bin/env bash
# Brings the whole system up on minikube: the database and broker outside the
# cluster, the Secret, the manifests, and both services at the freshly built
# image tag. Idempotent: safe to run again to roll out a new build.
set -euo pipefail
cd "$(dirname "$0")/.."

# The password never lives in the repository. It comes from the environment;
# match3d is the throwaway local default, the same one docker-compose.yml uses.
DB_PASSWORD="${DB_PASSWORD:-match3d}"

echo "== starting minikube (3 nodes)"
minikube start --nodes 3

echo "== starting Postgres and RabbitMQ outside the cluster"
docker compose up -d postgres rabbitmq

echo "== building and loading images"
tag=$(scripts/minikube-images.sh)
echo "== image tag $tag"

echo "== creating the Secret"
kubectl create secret generic match3d-secret \
    --from-literal=SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    --dry-run=client -o yaml | kubectl apply -f -

echo "== applying manifests"
kubectl apply -f k8s/

echo "== setting the image tag on both deployments"
kubectl set image deployment/intake intake="match3d-intake:$tag"
kubectl set image deployment/matchmaking matchmaking="match3d-matchmaking:$tag"

echo "== waiting for both rollouts"
kubectl rollout status deployment/intake
kubectl rollout status deployment/matchmaking

echo "== up. Service URLs:"
minikube service intake --url
minikube service matchmaking --url
