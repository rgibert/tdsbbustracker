plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun versionCodeFor(versionName: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(versionName)
        ?: throw GradleException(
            "appVersionName \"$versionName\" must start with major.minor.patch",
        )
    val (major, minor, patch) = match.destructured
    return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
}

val appVersionName = (
    providers.gradleProperty("appVersionName").orNull
        ?: providers.environmentVariable("APP_VERSION_NAME").orNull
        ?: "1.0.0"
).trim().removePrefix("v")

val appVersionCode = (
    providers.gradleProperty("appVersionCode").orNull
        ?: providers.environmentVariable("APP_VERSION_CODE").orNull
)
    ?.toIntOrNull()
    ?: versionCodeFor(appVersionName)

val releaseKeystorePath = providers.environmentVariable("BUS_TRACKER_KEYSTORE_FILE").orNull

android {
    namespace = "ca.bustracker.tstg"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.bustracker.tstg"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Release signing is optional. CI supplies these from repository
        // secrets; without them the release APK is unsigned.
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("BUS_TRACKER_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("BUS_TRACKER_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("BUS_TRACKER_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // Keep shrinker off for a personal sideload build: fewer surprises,
            // and the app is tiny anyway.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    // OpenStreetMap map view for the live bus map (no API key needed).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real org.json for JVM unit tests (the Android stub throws "not mocked").
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
