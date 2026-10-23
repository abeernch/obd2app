plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abeernch.civicobd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abeernch.civicobd"
        minSdk = 26
        targetSdk = 34
        // The build pipeline passes the run number, so every APK has a higher version.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("BUILD_NUMBER") ?: "1")
    }

    // One fixed key, so a new APK installs over the old one without uninstalling.
    // This is a personal app, so the key lives in the repository.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("signing/civic-obd.keystore")
            storePassword = "civicobd"
            keyAlias = "civicobd"
            keyPassword = "civicobd"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
}
