# Build from the repository root: docker build -f matchmaking-service/Dockerfile .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common/build.gradle.kts common/
COPY matchmaking-core/build.gradle.kts matchmaking-core/
COPY intake-service/build.gradle.kts intake-service/
COPY matchmaking-service/build.gradle.kts matchmaking-service/
COPY common/src common/src
COPY matchmaking-core/src matchmaking-core/src
COPY matchmaking-service/src matchmaking-service/src

# Gradle's download cache is kept by Docker between builds, outside the image.
RUN --mount=type=cache,target=/root/.gradle ./gradlew :matchmaking-service:bootJar --no-daemon

# A JRE with a shell, for debugging inside a running container.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/matchmaking-service/build/libs/matchmaking-service-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
