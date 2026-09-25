// e2e-tests: drives a running system from outside, over HTTP only.

dependencies {
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

// These need a running system, which a normal build does not have.
tasks.test {
    enabled = false
}

// ./gradlew :e2e-tests:e2eTest, then after a restart the same with -PafterRestart.
// -Pintake=<url> and -Pmatchmaking=<url> point it at a system other than localhost.
tasks.register<Test>("e2eTest") {
    description = "Runs the end to end checks against a running system."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    systemProperty("e2e.intake", providers.gradleProperty("intake").getOrElse("http://localhost:8080"))
    systemProperty("e2e.matchmaking", providers.gradleProperty("matchmaking").getOrElse("http://localhost:8081"))
    systemProperty("e2e.phase", if (providers.gradleProperty("afterRestart").isPresent) "after-restart" else "flow")
    systemProperty("e2e.record", layout.buildDirectory.file("e2e/last-run.properties").get().asFile.path)

    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
