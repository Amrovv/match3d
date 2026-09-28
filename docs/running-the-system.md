# Running the system

How to start Match3D, use its API, run its tests, and bring it up on Kubernetes. How it works inside is in [project-extended-description.md](project-extended-description.md), and why it is built this way is in [design-choices.md](design-choices.md).

## Contents

1. [What you need](#what-you-need)
2. [Running it with Docker Compose](#running-it-with-docker-compose)
3. [Using the API](#using-the-api)
4. [Running it on Kubernetes](#running-it-on-kubernetes)
5. [Running the services outside containers](#running-the-services-outside-containers)
6. [Tests and the benchmark](#tests-and-the-benchmark)

## What you need

| Tool | Needed for |
|---|---|
| Docker, with Compose | Running the system. On its own, this is enough. |
| JDK 21 | Building and testing outside Docker, and the end to end checks. The Gradle wrapper is committed, so no Gradle install is needed. |
| minikube and kubectl | Running it on Kubernetes. |

## Running it with Docker Compose

```sh
git clone https://github.com/Amrovv/match3d.git
cd match3d
docker compose up --build
```

That builds both service images and starts four containers: intake, matchmaking, PostgreSQL and RabbitMQ.

| Service | Address |
|---|---|
| intake | localhost:8080 |
| matchmaking | localhost:8081 |
| PostgreSQL | localhost:5432 |
| RabbitMQ | localhost:5672, dashboard at localhost:15672, as `guest` |

`docker compose down` stops everything and keeps the data. `docker compose down -v` wipes it.

The end to end check starts the stack, registers and queues ten players as a party of two and eight solos, waits until they are matched, reports a result, restarts both services and the database, and checks nothing was lost:

```sh
bash scripts/e2e-compose.sh
```

## Using the API

The database starts empty, so register players first, then queue them. Ten players at the same rating form a lobby as soon as the tenth joins. A player who was never registered is refused as unknown.

```sh
curl -X POST localhost:8081/players -H 'Content-Type: application/json' -d '{"id": "00000000-0000-0000-0000-000000000001"}'
curl -X POST localhost:8080/queue/join -H 'Content-Type: application/json' -d '{"memberIds": ["00000000-0000-0000-0000-000000000001"]}'
curl localhost:8080/queue/status/00000000-0000-0000-0000-000000000001
```

Players talk to intake. Registering players, reporting results and reading history go to matchmaking.

| Endpoint | Does |
|---|---|
| `POST :8080/queue/join` | Queues one to five player ids as a solo or a party, `{"memberIds": [...]}`. Returns the entry id. The same members joining again get their existing entry back. |
| `POST :8080/queue/leave` | Leaves by entry id, `{"entryId": "..."}`. |
| `GET :8080/queue/status/{playerId}` | Queued, with whether matchmaking is up and the estimated wait; matched, with both teams; refused, with the reason; or not queued. |
| `POST :8081/players` | Registers a player at 2500 under the caller's id, `{"id": "..."}`. |
| `GET :8081/players/{id}` | A player's current rating. |
| `GET :8081/matches/{id}` | A formed match, both teams by player id. |
| `GET :8081/players/{id}/history` | Every match a player was in, newest first. |
| `POST :8081/matches/{id}/result` | Records the winner, `{"winner": "A"}`, or tosses a coin with no body. Winners gain 100, losers lose 100. |

A queued player's status looks like this, with the wait estimated from recent matches around their rating:

```json
{"state": "QUEUED", "entryId": "...", "matchmaking": "UP", "estimatedWaitSeconds": 45}
```

Every status code each endpoint can answer is listed in the [extended description](project-extended-description.md#intake).

## Running it on Kubernetes

This runs the system on a three node minikube cluster, with intake as three copies, one per node, and matchmaking as one. PostgreSQL and RabbitMQ run outside the cluster, in Compose.

```sh
bash scripts/minikube-up.sh
```

The script starts the cluster, starts PostgreSQL and RabbitMQ, builds both images and loads them into every node, creates the database password as a Kubernetes Secret, applies the manifests in `k8s/`, and waits until every copy is ready. The password comes from `DB_PASSWORD` in your environment, or the local default `match3d`. Running the script again rolls out whatever is in your working tree.

Then, in a terminal of its own, with administrator rights:

```sh
minikube tunnel
```

That gives the services addresses on the host: intake on localhost:8080 and matchmaking on localhost:8081, the same as under Compose, so every example above works unchanged. Each intake response carries an `X-Intake-Pod` header naming the copy that served it.

Three checks run against the cluster, each with the tunnel running:

| Script | Checks |
|---|---|
| `bash scripts/e2e-minikube.sh` | The end to end flow, then restarts both services, PostgreSQL and RabbitMQ, and checks nothing was lost. |
| `bash scripts/e2e-minikube-replicas.sh` | Intake at three, two and one copies: every running copy serves players, a full flow through them loses and duplicates nobody, and one player joining through every copy at once is queued once. |
| `bash scripts/e2e-minikube-disruption.sh` | Deletes an intake copy while players are joining: no player is lost, and the cluster replaces the copy. |

A few commands for looking around:

```sh
kubectl get pods -o wide                        # every copy, and the node it runs on
kubectl delete pod <name>                       # kill a copy and watch it be replaced
kubectl scale deployment/intake --replicas=2    # run fewer copies
kubectl logs deployment/matchmaking             # the engine's log
minikube stop                                   # stop the cluster and keep it
```

## Running the services outside containers

With PostgreSQL and RabbitMQ on their usual ports, and each service in its own terminal:

```sh
docker run -d --name match3d-rabbit -p 5672:5672 -p 15672:15672 rabbitmq:4-management
docker run -d --name match3d-postgres -p 5432:5432 -e POSTGRES_PASSWORD=match3d -v match3d-pgdata:/var/lib/postgresql/data postgres:17
docker exec match3d-postgres createdb -U postgres matchmaking
docker exec match3d-postgres createdb -U postgres intake
export SPRING_DATASOURCE_PASSWORD=match3d
./gradlew :intake-service:bootRun
./gradlew :matchmaking-service:bootRun --args='--spring.profiles.active=local'
```

The password is in no source file, so each service reads it from `SPRING_DATASOURCE_PASSWORD` (on Windows PowerShell, `$env:SPRING_DATASOURCE_PASSWORD = "match3d"`). The `local` profile loads twenty players at 2500, with ids `00000000-0000-0000-0000-000000000001` to `...020`, so a lobby can be formed straight away.

## Tests and the benchmark

Each module's tests run on their own. The service tests need Docker running, since each starts a throwaway PostgreSQL of its own.

```sh
./gradlew :matchmaking-core:test
./gradlew :common:test
./gradlew :intake-service:test
./gradlew :matchmaking-service:test
```

| Suite | Tests |
|---|---|
| `matchmaking-core` | 207 |
| `intake-service` | 60 |
| `matchmaking-service` | 49 |
| `common` | 11, plus one that needs a live RabbitMQ: `./gradlew :common:brokerTest` |

Throughput under contention is measured separately, and is kept out of the normal build because a timing measurement is not a pass or fail check:

```sh
./gradlew :matchmaking-core:benchmark
```

The HTML report is written to `matchmaking-core/build/reports/tests/test/index.html`. Every number the documentation quotes comes from this command.
