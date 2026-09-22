// intake-service: REST endpoints for join, leave, status, party formation.

dependencies {
    implementation(project(":common"))

    implementation("io.javalin:javalin:6.3.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.rabbitmq:amqp-client:5.21.0")

    // Javalin writes and reads JSON bodies through Jackson, and records need
    // the same Instant handling the events use.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
