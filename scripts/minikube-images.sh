#!/usr/bin/env bash
# Builds both service images on this machine and copies them into every
# minikube node. Prints the tag: the short commit SHA, plus a timestamp when
# the working tree has uncommitted changes, so every build is a new tag.
#
# Each image is loaded twice: under the unique tag, which the setup script
# sets on the Deployments to force a rollout, and under :dev, the tag the
# manifests name. The :dev alias means `kubectl apply -f k8s/` alone always
# finds an image present, rather than a placeholder that was never loaded.
set -euo pipefail
cd "$(dirname "$0")/.."

tag=$(git rev-parse --short HEAD)
if [ -n "$(git status --porcelain)" ]; then
    tag="$tag-changes-$(date +%s)"
fi

for service in intake matchmaking; do
    image="match3d-$service:$tag"
    echo "== building $image" >&2
    docker build -q -t "$image" -f "$service-service/Dockerfile" . >&2
    docker tag "$image" "match3d-$service:dev" >&2
    echo "== loading $image and :dev into minikube" >&2
    minikube image load "$image" >&2
    minikube image load "match3d-$service:dev" >&2
done

echo "$tag"
