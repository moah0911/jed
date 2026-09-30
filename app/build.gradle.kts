plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.jed.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jed.app"
        minSdk = 29
        targetSdk = 36
        // Static versions for F-Droid / side-load distribution.
        // No Play Console, no upload key, no remote version plumbing.
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures.compose = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core:1.15.0")
    // Storage Access Framework helpers for user-attached note folders.
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
}
