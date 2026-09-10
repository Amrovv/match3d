// matchmaking-core: pure domain and algorithm module, no framework, no network.

// Benchmarks are excluded from the normal build. They take minutes, they report
// numbers rather than asserting them, and a timing measurement is not a
// regression gate. Run them with ./gradlew :matchmaking-core:benchmark
tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

tasks.register<Test>("benchmark") {
    description = "Runs the matching engine benchmarks and prints their results."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
