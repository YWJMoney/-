import java.util.Properties

// [SHFT-08][REL-09] The Wear OS app: a tile, a watch-face complication and a small app (오늘·앞으로 7일·이번 달)
// showing the days the phone app sends through the Wearable Data Layer. No internet permission.
// It has the phone app's package name and must be signed with the same key, or the Data Layer will not pair them.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bamnat.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bamnat.app"
        // Wear OS 3 and later.
        minSdk = 30
        targetSdk = 36
        // The watch build's own number: the phone app's versionCode (gradle.properties) + 100000, so the two never clash on Play.
        versionCode = (project.property("bamnat.versionCode") as String).toInt() + 100_000
        versionName = project.property("bamnat.versionName") as String
    }

    signingConfigs {
        if (rootProject.file("app/debug.keystore").exists()) getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The same Play upload key as the phone app (see app/build.gradle.kts); never in git.
        val keyProps = (System.getenv("BAMNAT_KEYSTORE_PROPERTIES")?.let(::File) ?: rootProject.file("keystore.properties"))
            .takeIf { it.exists() }
            ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
        if (keyProps != null) create("upload") {
            storeFile = File(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // [OPS-24] Lint fails the build on what would crash an older phone or a missing permission; what was there
    // when this was turned on (1.23) is in lint-baseline.xml, so only new problems fail.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        error += listOf("NewApi", "InlinedApi", "MissingPermission", "WrongConstant")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The day codec (WatchDays) is shared with the phone app.
    implementation("com.bamnat:core")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
