pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bamnat"

include(":app")
include(":database")
// [SHFT-08][REL-09] The Wear OS app (tile, complication, today·week·month), same package as the phone app.
include(":wear")
// [PERF-03] 324-5: start-up timings and the Baseline Profile on a real phone (benchmark/README.md). Built only on request.
include(":benchmark")
// Pure Kotlin logic (patterns, hours, alarms), unit tested on the JVM.
includeBuild("core")
