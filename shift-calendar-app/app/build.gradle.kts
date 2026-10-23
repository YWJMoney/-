import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// [MKT-09] Link sharing server (server/share-worker). Blank hides '링크로 공유'.
val shareBaseUrl = (project.findProperty("bamnat.shareBaseUrl") as String?).orEmpty().trim()
// [COMM-04] Company rules server (server/company-worker). Blank hides 회사 찾기 and sending setups.
val companyBaseUrl = (project.findProperty("bamnat.companyBaseUrl") as String?).orEmpty().trim()
// [REMOTE-02] Signed data files on GitHub Pages; fetched only when the build has the internet permission.
val remoteBaseUrl = (project.findProperty("bamnat.remoteBaseUrl") as String?).orEmpty().trim()
// [MON-07] AdMob ids, public values the owner gives. Blank: no ads in a release build (a debug build
// uses Google's test units). The app id falls back to Google's sample id so the SDK starts without one.
fun adProp(name: String) = (project.findProperty(name) as String?).orEmpty().trim()
val admobAppId = adProp("bamnat.admobAppId").ifBlank { "ca-app-pub-3940256099942544~3347511713" }
val adUnitsGiven = adProp("bamnat.admobBanner").isNotBlank() || adProp("bamnat.admobRewarded").isNotBlank()

android {
    namespace = "com.bamnat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bamnat.app"
        minSdk = 26
        targetSdk = 36
        // [REL-09] Set once in gradle.properties (bamnat.versionCode / bamnat.versionName); the watch build follows it.
        versionCode = (project.property("bamnat.versionCode") as String).toInt()
        versionName = project.property("bamnat.versionName") as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SHARE_BASE_URL", "\"$shareBaseUrl\"")
        buildConfigField("String", "COMPANY_BASE_URL", "\"$companyBaseUrl\"")
        buildConfigField("String", "REMOTE_BASE_URL", "\"${remoteBaseUrl.takeIf { shareBaseUrl.isNotBlank() || companyBaseUrl.isNotBlank() }.orEmpty()}\"")
        buildConfigField("String", "ADMOB_BANNER", "\"${adProp("bamnat.admobBanner")}\"")
        buildConfigField("String", "ADMOB_REWARDED", "\"${adProp("bamnat.admobRewarded")}\"")
        manifestPlaceholders["admobAppId"] = admobAppId
        // [SHR-02] The share server's host, for its /s/ links to open in the app (src/onlineShare).
        manifestPlaceholders["shareHost"] = runCatching { URI(shareBaseUrl).host }.getOrNull() ?: "share.invalid"
    }
    // The internet permission comes only with a server, see src/online; with the share server,
    // tapped schedule links open in the app too (src/onlineShare).
    val onlineManifest = when {
        shareBaseUrl.isNotBlank() -> "src/onlineShare/AndroidManifest.xml"
        companyBaseUrl.isNotBlank() -> "src/online/AndroidManifest.xml"
        else -> null
    }
    if (onlineManifest != null) {
        sourceSets.getByName("debug").manifest.srcFile(onlineManifest)
        sourceSets.getByName("release").manifest.srcFile(onlineManifest)
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
        // About 700 Robolectric tests and 400 screenshots in one worker outgrow Gradle's 512 MB test heap.
        unitTests.all { it.maxHeapSize = "2g" }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// [MON-07] A release build without ad unit ids shows no ads, so it leaves out the advertising ID
// permissions the ads SDK brings (src/noAdId): the test builds need no 광고 ID declaration in Play Console.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        if (!adUnitsGiven) variant.sources.manifests.addStaticManifestFile("src/noAdId/AndroidManifest.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


dependencies {
    implementation("com.bamnat:core")
    // [OPS-20] Google Play in-app review, asked once at a happy moment.
    implementation("com.google.android.play:review-ktx:2.0.2")
    // [PAYS-05][PAYX-04] Payslip and rule photos read on the phone; the Korean model is bundled, nothing is downloaded or sent.
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // [DAYW-07] On-device geofencing for 출퇴근 자동 기록 (off by default); no location leaves the phone.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // [SHFT-08] The Wear OS watch gets the days through the Wearable Data Layer, phone to watch.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // [MON-03] Play billing 9.1.0 (the Java API, so the Kotlin library stays as it is).
    implementation("com.android.billingclient:billing:9.1.0")
    // [MON-07] AdMob and Google's consent form (UMP); nothing shows before the 정식 출시 switch.
    // 25.3.0 and later are built with Kotlin 2.3, which this project's Kotlin 2.1 cannot read: raise both together.
    implementation("com.google.android.gms:play-services-ads:25.2.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    testImplementation(composeBom)
    testImplementation("androidx.window:window-testing:1.3.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    // [DAILY-09] The fold's hinge and posture, for the half-open (Flex) calendar.
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // [PERF-01] Installs src/main/baseline-prof.txt on phones that got the APK outside Play, so the start is compiled ahead too.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Home screen widgets [FEAT-03, REL-03].
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // [PERF-02] Room and its generated code live in :database.
    implementation(project(":database"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // [SLP-08] Sleep a watch or the phone measured, read only from Health Connect on the phone.
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // Screenshot tests: the real app rendered on the JVM, no emulator needed.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
