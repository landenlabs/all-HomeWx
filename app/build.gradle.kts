import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
}

// Firebase config is per-developer and gitignored; without it the Firebase plugins would fail the
// build, so they are only applied when google-services.json is present (Telemetry no-ops otherwise).
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
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
        versionCode = 60420
        versionName = "6.04.20"

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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.squareup.picasso:picasso:2.71828")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // WxData (see wxdata-integration-notes.md) - a local .aar carries no dependency metadata of
    // its own, so every one of its own dependencies must be declared here explicitly too, even
    // the ones it marks "implementation" rather than "api".
    // 2.26.0904: picks up wxdata's WxCurrentConditions updates + dependency bumps on top of the
    // 2.26.0903b race-condition hotfix - see max-auto-android-wxdata commit history for details.
    implementation(files("libs/WxData-debug-2.26.0904.aar"))
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.greenrobot:eventbus:3.3.1")

    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    testImplementation("junit:junit:4.13.2")
}
