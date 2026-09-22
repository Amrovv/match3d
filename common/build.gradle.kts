// common: shared event types and DTOs used by both services.

dependencies {
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("com.rabbitmq:amqp-client:5.21.0")
}

// Tests tagged broker need a live RabbitMQ on localhost:5672, which CI does not
// have. Run them with ./gradlew :common:brokerTest
tasks.test {
    useJUnitPlatform {
        excludeTags("broker")
    }
}

tasks.register<Test>("brokerTest") {
    description = "Runs the tests that need a live RabbitMQ on localhost."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("broker")
    }
    outputs.upToDateWhen { false }
}
