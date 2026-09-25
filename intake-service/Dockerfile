# Build from the repository root: docker build -f intake-service/Dockerfile .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common/build.gradle.kts common/
COPY matchmaking-core/build.gradle.kts matchmaking-core/
COPY intake-service/build.gradle.kts intake-service/
COPY matchmaking-service/build.gradle.kts matchmaking-service/
COPY common/src common/src
COPY intake-service/src intake-service/src

# Gradle's download cache is kept by Docker between builds, outside the image.
RUN --mount=type=cache,target=/root/.gradle ./gradlew :intake-service:bootJar --no-daemon

# A JRE with a shell, for debugging inside a running container.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/intake-service/build/libs/intake-service-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
