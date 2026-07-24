plugins {
    // Lets the Java toolchain auto-provision JDK 21 on machines that only have an
    // older JDK (this dev box has 17). Docker build already ships 21, so this is
    // purely a convenience for local `gradle` runs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "junction"
