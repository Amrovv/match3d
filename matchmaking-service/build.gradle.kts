// matchmaking-service: consumes queue events, runs matchmaking-core, persists to Postgres.

dependencies {
    implementation(project(":common"))
    implementation(project(":matchmaking-core"))
}
