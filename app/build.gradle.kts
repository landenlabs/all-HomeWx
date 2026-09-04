import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.dlang.homewx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dlang.homewx"
        minSdk = 28 // wxdata's AAR declares minSdk 28 (see wxdata-integration-notes.md)
        targetSdk = 37
        versionCode = 60420
        versionName = "6.04.20"

        val goveeApiKey = localProperties.getProperty("GOVEE_API_KEY") ?: ""
        buildConfigField("String", "GOVEE_API_KEY", "\"$goveeApiKey\"")

        val sunApiKey = localProperties.getProperty("SUN_API_KEY") ?: ""
        buildConfigField("String", "SUN_API_KEY", "\"$sunApiKey\"")

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        release {
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
    // 2.26.0903b: same-day hotfix for two races in WxCurrentFetcher/WxDailyFetcher/
    // WxHourlyFetcher/WxAlmanacDailyFetcher introduced by the 2.26.0903 Retrofit/RxJava removal -
    // see max-auto-android-wxdata commit history for details.
    implementation(files("libs/WxData-debug-2.26.0903b.aar"))
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.greenrobot:eventbus:3.3.1")
}
