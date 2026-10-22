// Pure Kotlin module with the scheduling, hour-calculation and alarm logic.
// It has no Android dependency so it can be unit tested on any JVM:
//   ./gradlew -p core test
plugins {
    kotlin("jvm") version "2.1.21"
}

group = "com.bamnat"
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

// [REMOTE-01] The remote data files (remote/v1/*.json at the top of the repository, published by GitHub
// Pages) ship inside the app as resources, so the app and the site start from the same file.
val bundleRemoteFiles = tasks.register<Sync>("bundleRemoteFiles") {
    from(projectDir.resolve("../../remote/v1")) {
        include("*.json")
        into("com/bamnat/core/remote/v1")
    }
    into(layout.buildDirectory.dir("generated/remote"))
}
sourceSets.main {
    resources.srcDir(bundleRemoteFiles)
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
