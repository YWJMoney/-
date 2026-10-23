// [PERF-02] The Room database on its own: the tables, DAOs, migrations and their schemas. Room's code
// generator (ksp) runs only here, so a change to the screens does not run it again.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bamnat.app.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api("com.bamnat:core")
    api("androidx.room:room-runtime:2.7.1")
    api("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
}
