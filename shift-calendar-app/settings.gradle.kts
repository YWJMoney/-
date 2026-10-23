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
// Pure Kotlin logic (patterns, hours, alarms), unit tested on the JVM.
includeBuild("core")
