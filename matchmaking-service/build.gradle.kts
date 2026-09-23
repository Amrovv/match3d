// matchmaking-service: consumes queue events, runs matchmaking-core, persists to Postgres.

plugins {
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

// Start it with ./gradlew :matchmaking-service:bootRun

dependencies {
    implementation(project(":common"))
    implementation(project(":matchmaking-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
