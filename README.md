<a id="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![CI][ci-shield]][ci-url]
[![MIT License][license-shield]][license-url]

<br />
<div align="center">
  <h3 align="center">Match3D</h3>

  <p align="center">
    Match3D is a concurrent, skill based matchmaking engine that decides how much match quality is worth trading for a shorter queue. Split across two services that share nothing but a message queue.
    <br />
    <a href="docs/project-extended-description.md"><strong>How it works, in detail</strong></a>
    &middot;
    <a href="docs/design-choices.md">Design choices</a>
    &middot;
    <a href="https://github.com/Amrovv/match3d/issues">Report a bug</a>
  </p>
</div>

<details>
  <summary>Contents</summary>
  <ol>
    <li><a href="#about-the-project">About the project</a>
      <ul>
        <li><a href="#project-status">Project status</a></li>
        <li><a href="#documentation">Documentation</a></li>
        <li><a href="#built-with">Built with</a></li>
      </ul>
    </li>
    <li><a href="#architecture">Architecture</a></li>
    <li><a href="#getting-started">Getting started</a></li>
    <li><a href="#deployment">Deployment</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#limitations">Limitations</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About the project

Match3D accepts players into a skill based queue and matches them into balanced ten player lobbies. The queue itself is not the point of focus, what the queue has to reconcile is. A player wants a lobby full of players at a similar rating to their own, but at the same time they want reasonable queue times. Those two aspects pull against one another, and the engine must decide and optimise how much match quality to trade for how much waiting.

The engine is a standalone module with no web framework and no network code, so it can be exercised and reasoned about on its own. Two services sit around it, coordinating only over a message queue.

### Project status

The core engine is built and tested: the skill index, the fairness heap and its widening window, the matching pass itself, and the concurrency fix that lets several workers run it at once, covered by 136 tests. Everything around it is still a skeleton, so no service runs and nothing is persisted or containerised yet. See the <a href="#roadmap">roadmap</a> for what is done and what is not.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Documentation

Two longer documents sit in `docs/`, and this README is the summary of both.

| Document | Holds |
|---|---|
| [Extended description](docs/project-extended-description.md) | How each part of the system actually works, in the order it was built, including the API surface and the cost of every operation. |
| [Design choices](docs/design-choices.md) | Every major design decision taken, explaining the choice, the trade offs, and the alternatives considered. |

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built with

* [![Java][java-shield]][java-url]
* [![Gradle][gradle-shield]][gradle-url]
* [![JUnit5][junit-shield]][junit-url]
* [![GitHub Actions][actions-shield]][actions-url]

Planned, not yet in the build: RabbitMQ, PostgreSQL, Docker, Kubernetes, AWS.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Architecture

```mermaid
flowchart TD
    player(["Player"])

    player -->|"REST: join, leave, status, party"| intake["intake-service"]
    intake -->|"PlayerQueued, PlayerLeft"| queue[["RabbitMQ"]]
    queue --> service["matchmaking-service"]

    subgraph core["matchmaking-core"]
        direction LR
        index["SkillIndex<br/>range query by rating"]
        heap["FairnessHeap<br/>window widens with wait time"]
        matcher["MatchMaker<br/>forms a lobby, removes<br/>members from both structures"]
        index --> matcher
        heap --> matcher
    end

    service --> core
    service --> db[("PostgreSQL<br/>match history, ratings,<br/>queue time analytics")]
    service -->|"REST: match by id, player history"| player

    classDef pending stroke-dasharray: 5 5;
    class intake,queue,service,db pending;
```

Dashed means not built yet.

| Module | Holds | Status |
|---|---|---|
| `matchmaking-core` | Domain model, skill index, fairness heap, widening function, matching algorithm | Built |
| `common` | Shared event types and DTOs used by both services | Skeleton only |
| `intake-service` | REST endpoints for join, leave, status, party formation | Skeleton only |
| `matchmaking-service` | Consumes queue events, runs the engine, persists, exposes query endpoints | Skeleton only |

`matchmaking-core` deliberately has no framework dependency. It is testable without starting a service, and matching decisions are made there rather than scattered across the two services, which is what makes this one system rather than two services stapled together.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting started

### Prerequisites

* JDK 21. The Gradle toolchain is pinned to 21, so another JDK on the path is fine as long as 21 is resolvable.
* No local Gradle install needed, the wrapper is committed.

### Installation

```sh
git clone https://github.com/Amrovv/match3d.git
cd match3d
./gradlew build
```

### Usage

There is nothing to run yet, no service has a main method. The engine is exercised through its test suite:

```sh
./gradlew :matchmaking-core:test
```

The HTML report lands in `matchmaking-core/build/reports/tests/test/index.html`.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Deployment

Not yet implemented.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Roadmap

- [x] Milestone 0, repo, CI, and the branch to pull request workflow
- [x] Milestone 1, core engine, skill index, fairness heap, and matching algorithm
- [ ] Milestone 2, concurrency, the race reproduced and fixed
- [ ] Milestone 3, `MatchmakingStrategy` with skill based and party aware implementations
- [ ] Milestone 4, split into two services over RabbitMQ, REST API
- [ ] Milestone 5, PostgreSQL persistence and Elo style rating updates
- [ ] Milestone 6, Docker images and Compose for local development
- [ ] Milestone 7, Kubernetes manifests, proven against minikube
- [ ] Milestone 8, deployment to AWS, k3s on EC2 with RDS, and the delivery step in CI

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Limitations

* Matching is greedy. It can miss a valid lobby that exists elsewhere in the queue.
* Every commit serialises through one lock, so the engine scales by making passes cheap rather than by running more of them. Sharding the queue by rating band is the recorded next step.
* No performance measurement exists. Every stated cost is derived from the structures, not observed.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing

Solo project, run on a professional workflow: feature branches, Conventional Commits, pull requests, and CI on GitHub.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## License

Distributed under the MIT License. See [`LICENSE.txt`](LICENSE.txt) for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

Adam Wasiak, adamwasiak55@gmail.com

Project link: [https://github.com/Amrovv/match3d](https://github.com/Amrovv/match3d)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

* [Best README Template](https://github.com/othneildrew/Best-README-Template), which this README is structured from.
* [The art of writing meaningful Git commit messages](https://thoughtbot.com/blog/5-useful-tips-for-a-better-commit-message), thoughtbot, which the commit style follows.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

[contributors-shield]: https://img.shields.io/github/contributors/Amrovv/match3d.svg?style=for-the-badge
[contributors-url]: https://github.com/Amrovv/match3d/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Amrovv/match3d.svg?style=for-the-badge
[forks-url]: https://github.com/Amrovv/match3d/network/members
[stars-shield]: https://img.shields.io/github/stars/Amrovv/match3d.svg?style=for-the-badge
[stars-url]: https://github.com/Amrovv/match3d/stargazers
[issues-shield]: https://img.shields.io/github/issues/Amrovv/match3d.svg?style=for-the-badge
[issues-url]: https://github.com/Amrovv/match3d/issues
[license-shield]: https://img.shields.io/github/license/Amrovv/match3d.svg?style=for-the-badge
[license-url]: https://github.com/Amrovv/match3d/blob/main/LICENSE.txt
[ci-shield]: https://img.shields.io/github/actions/workflow/status/Amrovv/match3d/ci.yml?style=for-the-badge&label=CI
[ci-url]: https://github.com/Amrovv/match3d/actions/workflows/ci.yml
[java-shield]: https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white
[java-url]: https://openjdk.org/projects/jdk/21/
[gradle-shield]: https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white
[gradle-url]: https://gradle.org/
[junit-shield]: https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white
[junit-url]: https://junit.org/junit5/
[actions-shield]: https://img.shields.io/badge/GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white
[actions-url]: https://github.com/features/actions
