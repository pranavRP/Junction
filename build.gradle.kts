plugins {
    application
}

repositories {
    mavenCentral()
}

// R-10: Java 21. Toolchain pinned so the build is identical regardless of the
// JDK on PATH.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("io.netty:netty-all:4.1.115.Final")
    implementation("org.yaml:snakeyaml:2.5")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.junction.Junction"
}

// R-44: the benchmark is a command, not a paragraph. `./gradlew bench` reproduces
// MEA-006 and MEA-007; it is excluded from `test` because a benchmark that runs on
// every build is one nobody can trust.
tasks.register<Test>("bench") {
    useJUnitPlatform()
    systemProperty("junction.bench", "true")
    filter { includeTestsMatching("*Bench") }
    maxHeapSize = "512m"
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnitPlatform()
    // R-6: Netty leak detection at PARANOID in tests. A leak found here blocks merge.
    systemProperty("io.netty.leakDetection.level", "PARANOID")
    // The Phase 1 gate streams 1 GB through this JVM. Capping the heap well below
    // that makes the streaming claim structural: buffering the body would OOM,
    // not merely exceed a threshold.
    maxHeapSize = "256m"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
