plugins {
    application
}

repositories {
    mavenCentral()
}

// R-10: Java 21. Toolchain pinned so the build is identical regardless of the
// JDK on PATH. Phase 0 only needs Netty; nothing else yet.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("io.netty:netty-all:4.1.115.Final")
}

application {
    // Default main; the two spike entry points (proxy + chaos backend) are
    // selected at runtime via `-cp` + $MAIN in the container. Deleted in Phase 1.
    mainClass = "io.junction.spike.JunctionProxy"
}
