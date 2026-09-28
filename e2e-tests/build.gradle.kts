// e2e-tests: drives a running system from outside, over HTTP only.

dependencies {
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.18.2"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

// These need a running system, which a normal build does not have.
tasks.test {
    enabled = false
}

fun Test.pointAtSystem() {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    systemProperty("e2e.intake", providers.gradleProperty("intake").getOrElse("http://localhost:8080"))
    systemProperty("e2e.matchmaking", providers.gradleProperty("matchmaking").getOrElse("http://localhost:8081"))

    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ./gradlew :e2e-tests:e2eTest, then after a restart the same with -PafterRestart.
// -Pintake=<url> and -Pmatchmaking=<url> point it at a system other than localhost.
tasks.register<Test>("e2eTest") {
    description = "Runs the end to end checks against a running system."
    group = "verification"
    pointAtSystem()
    systemProperty("e2e.phase", if (providers.gradleProperty("afterRestart").isPresent) "after-restart" else "flow")
    systemProperty("e2e.record", layout.buildDirectory.file("e2e/last-run.properties").get().asFile.path)
}

// ./gradlew :e2e-tests:replicaTest -Pcopies=<n>: intake as n copies behind the
// Service. scripts/e2e-minikube-replicas.sh scales intake and runs this per count.
tasks.register<Test>("replicaTest") {
    description = "Checks intake running as several copies behind one Service."
    group = "verification"
    pointAtSystem()
    systemProperty("e2e.phase", "replicas")
    systemProperty("e2e.copies", providers.gradleProperty("copies").getOrElse("3"))
}

// ./gradlew :e2e-tests:disruptionTest: drives a flow while
// scripts/e2e-minikube-disruption.sh kills an intake pod under it. The marker
// file lets the script kill only once the load is under way.
tasks.register<Test>("disruptionTest") {
    description = "Checks the system stays up when an intake pod is killed under load."
    group = "verification"
    pointAtSystem()
    systemProperty("e2e.phase", providers.gradleProperty("phase").getOrElse("crash"))
    systemProperty("e2e.started", layout.buildDirectory.file("e2e/started").get().asFile.path)
}
