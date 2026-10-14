// Pure Kotlin module with the scheduling, hour-calculation and alarm logic.
// It has no Android dependency so it can be unit tested on any JVM:
//   ./gradlew -p core test
plugins {
    kotlin("jvm") version "2.1.21"
}

group = "com.shiftmate"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
