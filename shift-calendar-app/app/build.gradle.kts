import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bamnat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bamnat.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // [MKT-09] Link sharing server (server/share-worker). Blank hides '링크로 공유'.
        val shareBaseUrl = (project.findProperty("bamnat.shareBaseUrl") as String?).orEmpty()
        buildConfigField("String", "SHARE_BASE_URL", "\"$shareBaseUrl\"")
    }
    // The internet permission comes only with a sharing server, see src/online.
    if ((project.findProperty("bamnat.shareBaseUrl") as String?).orEmpty().isNotBlank()) {
        sourceSets.getByName("debug").manifest.srcFile("src/online/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/online/AndroidManifest.xml")
    }

    // Fixed debug key kept in the project so every new test APK installs over the old one.
    // The keystore lives in the project folder; without it Gradle's default debug key is used.
    signingConfigs {
        if (file("debug.keystore").exists()) getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // [OPS-20] Play upload key. The key and its passwords never enter git: point
        // BAMNAT_KEYSTORE_PROPERTIES (or keystore.properties in the project root) at the file.
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric screenshot tests need the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation("com.bamnat:core")
    // [OPS-20] Google Play in-app review, asked once at a happy moment.
    implementation("com.google.android.play:review-ktx:2.0.2")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    testImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Home screen widgets [FEAT-03, REL-03].
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // [SLP-08] Sleep a watch or the phone measured, read only from Health Connect on the phone.
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // Screenshot tests: the real app rendered on the JVM, no emulator needed.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
