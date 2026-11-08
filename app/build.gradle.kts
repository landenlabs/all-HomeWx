import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
}

// Firebase config is per-developer and gitignored; without it the Firebase plugins would fail the
// build, so they are only applied when google-services.json is present (Telemetry no-ops otherwise).
// plugins {} can't be conditional, so they go through pluginManager; the versions come from the
// root build's `apply false` declarations.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    pluginManager.apply("com.google.gms.google-services")
    pluginManager.apply("com.google.firebase.crashlytics")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.landenlabs.homewx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.landenlabs.all_homewx"
        minSdk = 28 // wxdata's AAR declares minSdk 28 (see wxdata-integration-notes.md)
        targetSdk = 37
        versionCode = 61110
        versionName = "6.11.10"

        // GOVEE_API_KEY / WYZE_USER / WYZE_PWD / SUN_API_KEY (the WxData key) are per build type (see buildTypes): debug seeds them
        // from local.properties, release ships empty and the user enters them in Settings.

        // Google Nest (SDM) thermostat credentials; see NestApiClient.
        for (key in listOf("NEST_PROJECT_ID", "NEST_CLIENT_ID", "NEST_CLIENT_SECRET", "NEST_REFRESH_TOKEN")) {
            buildConfigField("String", key, "\"${localProperties.getProperty(key) ?: ""}\"")
        }

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        // Default (first-run) credentials for the Credentials & Feeds dialogs in Settings; see
        // CredentialStore. Only debug builds read local.properties - a release bundle must never
        // carry anyone's personal keys.
        debug {
            for (key in listOf("GOVEE_API_KEY", "WYZE_USER", "WYZE_PWD", "SUN_API_KEY")) {
                val value = (localProperties.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
                buildConfigField("String", key, "\"$value\"")
            }
        }
        release {
            for (key in listOf("GOVEE_API_KEY", "WYZE_USER", "WYZE_PWD", "SUN_API_KEY")) {
                buildConfigField("String", key, "\"\"")
            }
            // R8 shrink + obfuscate + optimize (Play requires obfuscation). Keep rules for anything
            // reflective live in proguard-rules.pro; wxdata/Firebase/OkHttp ship consumer rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // "standard" (default) ships Open-Meteo only: no wxdata AAR, no WxData key entry, no WxData
    // weather source. "wxdata" adds the AAR and its Settings options. Code that touches the AAR
    // lives in src/wxdata; src/standard has a no-op WxDataSupport with the same API.
    flavorDimensions += "weather"
    productFlavors {
        create("standard") {
            dimension = "weather"
            isDefault = true
            buildConfigField("boolean", "HAS_WXDATA", "false")
        }
        create("wxdata") {
            dimension = "weather"
            buildConfigField("boolean", "HAS_WXDATA", "true")
        }
    }

    testOptions {
        // OkHttp touches android.util.Log on the JVM; return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Include the version in output file names: HomeWx-6.10.31-standard-release.apk / .aab.
// archivesName covers the APK; the bundle is renamed after the bundle task since its name
// isn't consistently derived from archivesName across AGP versions.
val versionedName = "HomeWx-${android.defaultConfig.versionName}"
base.archivesName = versionedName

tasks.configureEach {
    val match = Regex("bundle(Standard|Wxdata)(Debug|Release)").matchEntire(name) ?: return@configureEach
    val flavor = match.groupValues[1].lowercase()
    val buildType = match.groupValues[2]
    doLast {
        val dir = layout.buildDirectory.dir("outputs/bundle/$flavor$buildType").get().asFile
        val target = File(dir, "$versionedName-$flavor-${buildType.lowercase()}.aab")
        dir.listFiles { f -> f.extension == "aab" && f.name != target.name }?.forEach { old ->
            target.delete()
            old.renameTo(target)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // WxData (see wxdata-integration-notes.md) - a local .aar carries no dependency metadata of
    // its own, so every one of its own dependencies must be declared here explicitly too, even
    // the ones it marks "implementation" rather than "api".
    // 3.26.1031: initialize() now takes a host-built WxDataConfig (no provisioning, no baked-in
    // URLs); Retrofit/RxJava/GreenRobot were dropped in favour of Gson + CompletableFuture, so only
    // okhttp (above), logging-interceptor and gson are still needed. See
    // max-auto-android-wxdata/technotes/initialize.md.
    // Only the "wxdata" flavor carries the AAR and its dependencies.
    "wxdataImplementation"(files("libs/wxdata-3.26.1031.aar"))
    "wxdataImplementation"("com.squareup.okhttp3:logging-interceptor:5.5.0")
    "wxdataImplementation"("com.google.code.gson:gson:2.14.0")

    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in JVM unit tests; use the real implementation
    // (needed by NestApiClientLiveTest).
    testImplementation("org.json:json:20250517")
}
